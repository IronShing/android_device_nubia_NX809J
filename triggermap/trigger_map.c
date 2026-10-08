// trigger_map — NX809J shoulder-trigger -> on-screen touch mapper.
//
// The two capacitive shoulder triggers are Awinic SAR sensors that emit plain
// key events on their own evdev nodes:
//     nubia_tgk_aw_sar0_ch0  -> KEY_F7   (RIGHT trigger)
//     nubia_tgk_aw_sar1_ch0  -> KEY_F8   (LEFT  trigger)
// On stock RedMagicOS a Game Space service maps these to screen touches. That
// service does not exist here, so by themselves the triggers only fire F7/F8,
// which games ignore. Userspace remappers (input tap / sendevent) work but suffer
// the InputFlinger mixed-device latency + touch-drop problems.
//
// This daemon is the native equivalent of the stock mapper. It has two engines:
//
// MERGED (default, persist.sys.rm.trig_merge != 0) -- ported from Austin Young's
//   Redmagic-Trigger-Bridge 0.3.x (github.com/austineyoung2000/Redmagic-Trigger-Bridge,
//   GPL-3.0, used with the author's agreement). While a game is in front the daemon
//   EVIOCGRABs the real panel AND the two SAR nodes and republishes everything on
//   ONE uinput touchscreen: the panel's MT slots are forwarded 1:1 (same slot
//   numbers, same tracking ids, same coordinates) and the two triggers live in two
//   extra slots above the panel's. InputFlinger therefore sees a single device
//   with up to N+2 pointers, so a trigger press while a finger is on the screen is
//   a real second pointer of the SAME MotionEvent stream -- which is what games
//   need (the previous separate-device design produced two independent streams;
//   many engines only track the device of the first pointer, so the trigger was
//   ignored or cancelled the finger). Austin found and fixed that; this is his
//   engine with our property/rc/sepolicy plumbing around it.
//
// LEGACY (persist.sys.rm.trig_merge=0) -- the original design: a second
//   standalone uinput touchscreen that only ever carries the two trigger
//   pointers, nothing grabbed. Kept as a fallback.
//
// SELinux: like dt2w_uewake this runs as a clean system_ext coredomain doing only
// evdev reads/grabs (/dev/input/*, platform-labelled input_device) + /dev/uinput.
// It never touches any vendor-labelled node: SAR arming is done by
// redmagic_hw_arm.rc, haptics by the app.
//
// Config (all live, re-read on every trigger press / every 250 ms so RedMagic
// Control edits apply without a restart):
//   persist.sys.rm.trigger_left   "true"/"false"  master enable, LEFT  (existing)
//   persist.sys.rm.trigger_right  "true"/"false"  master enable, RIGHT (existing)
//   persist.sys.rm.trig_l_x / _l_y   LEFT  touch target, per-mille (0..1000 of
//   persist.sys.rm.trig_r_x / _r_y   RIGHT touch target, the panel W/H). Normalized
//                                 so RedMagic Control (display-pixel space) and this
//                                 daemon (touch-ABS space) need not share a resolution.
//   ..._land                      the same four for landscape (portrait set is the
//                                 fallback); sys.rm.trig_rot (0/1) picks the set.
//   persist.sys.rm.trig_map       "true"/"false"  master "map to touch" switch;
//                                 when false the triggers keep firing F7/F8 only
//                                 (init stops this daemon on 0).
//   persist.sys.rm.trig_merge     "1" (default) merged engine, "0" legacy engine.
//   persist.sys.power_mode_perf   "1" while a GameSpace game is in front (set by
//                                 GameSpace). The merged engine only grabs the
//                                 panel while this is 1 -- outside games the real
//                                 panel is untouched and the SARs are stopped by
//                                 the rc anyway.
// Coordinate space is the physical panel's, discovered at runtime from the real
// touchscreen's ABS_MT_POSITION_X/Y range, so this is panel-agnostic.

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <dirent.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <sys/ioctl.h>
#include <sys/system_properties.h>

#define LOG_TAG "trigger_map"
#include <log/log.h>

#define TRIG_INPUT_PORT "trigger_map"
#define REAL_TS_NAME    "synaptics_tcm_touch"
#define SAR_RIGHT_NAME  "nubia_tgk_aw_sar0_ch0"   // KEY_F7
#define SAR_LEFT_NAME   "nubia_tgk_aw_sar1_ch0"   // KEY_F8

// Trigger indices. In LEGACY mode these are the MT slots on the standalone
// device; in MERGED mode the trigger slot is physical_slot_count + index.
#define SLOT_LEFT   0
#define SLOT_RIGHT  1
#define TRIG_SLOTS  2
#define MAX_PHYS_SLOTS 32

// Poll timeout: how often the merged engine re-evaluates "game in front".
#define GATE_POLL_MS 250
#define MERGE_MAX_FAILS 3

// Deactivation debounce. GameSpace bounces persist.sys.power_mode_perf -- observed
// live (10-05, kingdom's log) doing 0->1->0 within 0.3-0.7 s while a game stayed in
// front. Tearing the grabbing uinput device down+up on each bounce makes InputFlinger
// re-enumerate (EventHub "Removing device trigger_map due to epoll hang-up") and drops
// the triggers for the rebuild window. So once "game in front" drops we hold the engine
// up for this long; a flap back within the window cancels the teardown. A real game
// exit (perf stays 0, the ~25 s swings in the same log) still releases promptly.
#define DEACTIVATE_GRACE_MS 2000

static int g_max_x = 1215;   // sensible RM692H5 fallback until probed
static int g_max_y = 2687;
static volatile sig_atomic_t g_stop = 0;

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void on_term(int sig) { (void)sig; g_stop = 1; }

static int prop_bool(const char *name, int def) {
    char v[PROP_VALUE_MAX];
    if (__system_property_get(name, v) <= 0) return def;
    return (strcmp(v, "true") == 0 || strcmp(v, "1") == 0);
}
static int prop_int(const char *name, int def) {
    char v[PROP_VALUE_MAX];
    if (__system_property_get(name, v) <= 0) return def;
    char *end = NULL;
    long n = strtol(v, &end, 10);
    if (end == v) return def;
    return (int)n;
}

// Find an evdev node by its reported device name. Returns fd or -1.
static int open_evdev_by_name(const char *want, char *path_out, size_t path_sz) {
    DIR *d = opendir("/dev/input");
    if (!d) { ALOGE("opendir /dev/input: %s", strerror(errno)); return -1; }
    struct dirent *e;
    int found = -1;
    while ((e = readdir(d))) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        char p[64];
        snprintf(p, sizeof(p), "/dev/input/%s", e->d_name);
        int fd = open(p, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) continue;
        char name[128] = {0};
        if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) >= 0 && strcmp(name, want) == 0) {
            found = fd;
            if (path_out) snprintf(path_out, path_sz, "%s", p);
            break;
        }
        close(fd);
    }
    closedir(d);
    return found;
}

// Probe the real touchscreen's coordinate range so injected touches land in the
// panel's own pixel space regardless of model.
static void probe_panel_range(int fd) {
    if (fd < 0) { ALOGW("real touchscreen '%s' not found, using %dx%d fallback",
                        REAL_TS_NAME, g_max_x + 1, g_max_y + 1); return; }
    struct input_absinfo ax, ay;
    if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &ax) >= 0 && ax.maximum > 0) g_max_x = ax.maximum;
    if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), &ay) >= 0 && ay.maximum > 0) g_max_y = ay.maximum;
    ALOGI("panel range from %s: X[0..%d] Y[0..%d]", REAL_TS_NAME, g_max_x, g_max_y);
}

static void emit(int fd, int type, int code, int val) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type; ev.code = code; ev.value = val;
    (void)!write(fd, &ev, sizeof(ev));
}

static void drain(int fd) {
    struct input_event evs[32];
    if (fd < 0) return;
    while (read(fd, evs, sizeof(evs)) > 0) { }
}

// Resolve the configured target of one trigger for the current orientation.
// Returns 0 if the trigger is not positioned yet. RedMagic Control publishes
// sys.rm.trig_rot (0 = portrait, 1 = landscape) from a display rotation
// listener. The stored per-mille are already in the panel's OWN (native/portrait)
// space -- the positioner did the rotation transform when it saved -- so we inject
// them the same way in either orientation; the bit only selects WHICH physical
// point (a given on-screen button sits at a different panel point in landscape
// vs portrait).
static int trigger_target(int is_left, int *x, int *y) {
    int land = (prop_int("sys.rm.trig_rot", 0) != 0);
    const char *kx = land ? (is_left ? "persist.sys.rm.trig_l_x_land" : "persist.sys.rm.trig_r_x_land")
                          : (is_left ? "persist.sys.rm.trig_l_x"      : "persist.sys.rm.trig_r_x");
    const char *ky = land ? (is_left ? "persist.sys.rm.trig_l_y_land" : "persist.sys.rm.trig_r_y_land")
                          : (is_left ? "persist.sys.rm.trig_l_y"      : "persist.sys.rm.trig_r_y");
    int mx = prop_int(kx, -1);
    int my = prop_int(ky, -1);
    if (land && (mx < 0 || my < 0)) {   // landscape not positioned -> use portrait set
        mx = prop_int(is_left ? "persist.sys.rm.trig_l_x" : "persist.sys.rm.trig_r_x", -1);
        my = prop_int(is_left ? "persist.sys.rm.trig_l_y" : "persist.sys.rm.trig_r_y", -1);
    }
    if (mx < 0 || my < 0) return 0;   // not positioned yet
    if (mx > 1000) mx = 1000;
    if (my > 1000) my = 1000;
    *x = (int)((long)mx * g_max_x / 1000);   // per-mille -> panel ABS px
    *y = (int)((long)my * g_max_y / 1000);
    return 1;
}

static int side_enabled(int is_left) {
    return prop_bool(is_left ? "persist.sys.rm.trigger_left" : "persist.sys.rm.trigger_right", 1);
}

// ---------------------------------------------------------------------------
// LEGACY engine: standalone two-pointer touchscreen.
// ---------------------------------------------------------------------------

static int legacy_uinput_create(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) { ALOGE("open /dev/uinput: %s", strerror(errno)); return -1; }
    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_SLOT);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_TOUCH_MAJOR);

    struct uinput_abs_setup abs;
    memset(&abs, 0, sizeof(abs));
    abs.code = ABS_MT_SLOT;        abs.absinfo.minimum = 0; abs.absinfo.maximum = 9;
    ioctl(fd, UI_ABS_SETUP, &abs);
    abs.code = ABS_MT_TRACKING_ID; abs.absinfo.minimum = 0; abs.absinfo.maximum = 65535;
    ioctl(fd, UI_ABS_SETUP, &abs);
    abs.code = ABS_MT_POSITION_X;  abs.absinfo.minimum = 0; abs.absinfo.maximum = g_max_x;
    ioctl(fd, UI_ABS_SETUP, &abs);
    abs.code = ABS_MT_POSITION_Y;  abs.absinfo.minimum = 0; abs.absinfo.maximum = g_max_y;
    ioctl(fd, UI_ABS_SETUP, &abs);
    abs.code = ABS_MT_TOUCH_MAJOR; abs.absinfo.minimum = 0; abs.absinfo.maximum = 255;
    ioctl(fd, UI_ABS_SETUP, &abs);

    struct uinput_setup us;
    memset(&us, 0, sizeof(us));
    snprintf(us.name, UINPUT_MAX_NAME_SIZE, "%s", TRIG_INPUT_PORT);
    us.id.bustype = BUS_VIRTUAL;
    us.id.vendor = 0x6770;   // 'gp'
    us.id.product = 0x0002;  // 0x0001 is dt2w
    us.id.version = 1;
    if (ioctl(fd, UI_DEV_SETUP, &us) < 0) { ALOGE("UI_DEV_SETUP: %s", strerror(errno)); close(fd); return -1; }
    // Associate to the internal display (see input-port-associations.xml) so the
    // injected touches always target the built-in panel even with a monitor attached.
    if (ioctl(fd, UI_SET_PHYS, TRIG_INPUT_PORT) < 0)
        ALOGW("UI_SET_PHYS(%s): %s", TRIG_INPUT_PORT, strerror(errno));
    if (ioctl(fd, UI_DEV_CREATE) < 0) { ALOGE("UI_DEV_CREATE: %s", strerror(errno)); close(fd); return -1; }
    ALOGI("legacy uinput touchscreen '%s' created (%dx%d)", TRIG_INPUT_PORT, g_max_x + 1, g_max_y + 1);
    return fd;
}

// Number of currently-held triggers, so BTN_TOUCH is only released when the last
// pointer lifts.
static int g_legacy_active = 0;
static int g_legacy_tid = 1;

static void legacy_down(int ufd, int slot, int x, int y) {
    emit(ufd, EV_ABS, ABS_MT_SLOT, slot);
    emit(ufd, EV_ABS, ABS_MT_TRACKING_ID, g_legacy_tid++);
    emit(ufd, EV_ABS, ABS_MT_POSITION_X, x);
    emit(ufd, EV_ABS, ABS_MT_POSITION_Y, y);
    emit(ufd, EV_ABS, ABS_MT_TOUCH_MAJOR, 6);
    if (g_legacy_active == 0) emit(ufd, EV_KEY, BTN_TOUCH, 1);
    g_legacy_active++;
    emit(ufd, EV_SYN, SYN_REPORT, 0);
}

static void legacy_up(int ufd, int slot) {
    emit(ufd, EV_ABS, ABS_MT_SLOT, slot);
    emit(ufd, EV_ABS, ABS_MT_TRACKING_ID, -1);
    if (g_legacy_active > 0) g_legacy_active--;
    if (g_legacy_active == 0) emit(ufd, EV_KEY, BTN_TOUCH, 0);
    emit(ufd, EV_SYN, SYN_REPORT, 0);
}

// ---------------------------------------------------------------------------
// MERGED engine (Redmagic-Trigger-Bridge port).
// ---------------------------------------------------------------------------

struct merged {
    int ufd;                    // merged uinput device, -1 while inactive
    int panel_fd, sar_fd[2];    // sar_fd[SLOT_LEFT] / [SLOT_RIGHT]
    int panel_slots;            // panel's ABS_MT_SLOT count
    int phys_slot;              // panel's current slot (its own ABS_MT_SLOT stream)
    int virt_slot;              // last ABS_MT_SLOT we emitted, -1 = unknown
    int phys_down[MAX_PHYS_SLOTS];
    int phys_x[MAX_PHYS_SLOTS], phys_y[MAX_PHYS_SLOTS];
    int phys_x_ok[MAX_PHYS_SLOTS], phys_y_ok[MAX_PHYS_SLOTS];
    int phys_btn_touch;         // panel's own BTN_TOUCH state (swallowed, re-emitted combined)
    int combined_btn;           // BTN_TOUCH/BTN_TOOL_FINGER state we last emitted
    int trig_down[TRIG_SLOTS];
    int trig_x[TRIG_SLOTS], trig_y[TRIG_SLOTS];
    int grabbed_panel, grabbed_sar[2];
    int active;
};

static void merged_reset(struct merged *m) {
    memset(m->phys_down, 0, sizeof(m->phys_down));
    memset(m->phys_x_ok, 0, sizeof(m->phys_x_ok));
    memset(m->phys_y_ok, 0, sizeof(m->phys_y_ok));
    m->phys_slot = 0;
    m->virt_slot = -1;
    m->phys_btn_touch = 0;
    m->combined_btn = 0;
    m->trig_down[SLOT_LEFT] = m->trig_down[SLOT_RIGHT] = 0;
}

static int has_bit(const unsigned long *bits, int bit) {
    return (bits[bit / (8 * sizeof(unsigned long))] >> (bit % (8 * sizeof(unsigned long)))) & 1UL;
}

// Build the merged device as a clone of the panel (same axes/ranges/fuzz) plus
// two extra MT slots for the triggers. Name/phys stay "trigger_map" so the
// input-port-associations.xml pin to the internal display keeps applying.
static int merged_uinput_create(struct merged *m) {
    struct input_absinfo slot_info;
    if (ioctl(m->panel_fd, EVIOCGABS(ABS_MT_SLOT), &slot_info) < 0) {
        ALOGE("panel EVIOCGABS(ABS_MT_SLOT): %s", strerror(errno)); return -1;
    }
    int slots = slot_info.maximum - slot_info.minimum + 1;
    if (slot_info.minimum != 0 || slots <= 0 || slots > MAX_PHYS_SLOTS) {
        ALOGE("panel slot range %d..%d unsupported", slot_info.minimum, slot_info.maximum); return -1;
    }
    m->panel_slots = slots;

    unsigned long absbits[(ABS_MAX + 1 + 8 * sizeof(unsigned long) - 1) / (8 * sizeof(unsigned long))];
    unsigned long keybits[(KEY_MAX + 1 + 8 * sizeof(unsigned long) - 1) / (8 * sizeof(unsigned long))];
    memset(absbits, 0, sizeof(absbits));
    memset(keybits, 0, sizeof(keybits));
    ioctl(m->panel_fd, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits);
    ioctl(m->panel_fd, EVIOCGBIT(EV_KEY, sizeof(keybits)), keybits);

    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) { ALOGE("open /dev/uinput: %s", strerror(errno)); return -1; }
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(fd, UI_SET_KEYBIT, BTN_TOOL_FINGER);
    ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);

    // Axes the panel reports, cloned 1:1 (position + contact size), plus the
    // legacy single-touch pair some engines still look at.
    static const int cloned[] = { ABS_X, ABS_Y, ABS_MT_POSITION_X, ABS_MT_POSITION_Y,
                                  ABS_MT_TOUCH_MAJOR, ABS_MT_TOUCH_MINOR, ABS_MT_WIDTH_MAJOR,
                                  ABS_MT_WIDTH_MINOR, ABS_MT_PRESSURE, ABS_MT_ORIENTATION,
                                  ABS_PRESSURE };
    struct uinput_abs_setup abs;
    for (size_t i = 0; i < sizeof(cloned) / sizeof(cloned[0]); i++) {
        int code = cloned[i];
        struct input_absinfo info;
        int want = has_bit(absbits, code);
        // Always provide the position pair + ABS_X/Y so the device is a touchscreen
        // even if the panel's bitmap read failed.
        if (!want && code != ABS_X && code != ABS_Y &&
            code != ABS_MT_POSITION_X && code != ABS_MT_POSITION_Y) continue;
        memset(&abs, 0, sizeof(abs));
        abs.code = code;
        if (ioctl(m->panel_fd, EVIOCGABS(code), &info) == 0) {
            abs.absinfo = info;
        } else {
            abs.absinfo.maximum = (code == ABS_X || code == ABS_MT_POSITION_X) ? g_max_x : g_max_y;
        }
        if (code == ABS_X)       abs.absinfo.maximum = g_max_x;
        if (code == ABS_Y)       abs.absinfo.maximum = g_max_y;
        ioctl(fd, UI_SET_ABSBIT, code);
        ioctl(fd, UI_ABS_SETUP, &abs);
    }
    memset(&abs, 0, sizeof(abs));
    abs.code = ABS_MT_SLOT; abs.absinfo.minimum = 0; abs.absinfo.maximum = slots + TRIG_SLOTS - 1;
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_SLOT);
    ioctl(fd, UI_ABS_SETUP, &abs);
    abs.code = ABS_MT_TRACKING_ID; abs.absinfo.minimum = 0; abs.absinfo.maximum = 65535;
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(fd, UI_ABS_SETUP, &abs);

    struct uinput_setup us;
    memset(&us, 0, sizeof(us));
    snprintf(us.name, UINPUT_MAX_NAME_SIZE, "%s", TRIG_INPUT_PORT);
    us.id.bustype = BUS_VIRTUAL;
    us.id.vendor = 0x6770;   // 'gp'
    us.id.product = 0x0002;  // 0x0001 is dt2w
    us.id.version = 2;       // 1 = legacy standalone device
    if (ioctl(fd, UI_DEV_SETUP, &us) < 0) { ALOGE("UI_DEV_SETUP: %s", strerror(errno)); close(fd); return -1; }
    if (ioctl(fd, UI_SET_PHYS, TRIG_INPUT_PORT) < 0)
        ALOGW("UI_SET_PHYS(%s): %s", TRIG_INPUT_PORT, strerror(errno));
    if (ioctl(fd, UI_DEV_CREATE) < 0) { ALOGE("UI_DEV_CREATE: %s", strerror(errno)); close(fd); return -1; }
    m->ufd = fd;
    // Give InputFlinger time to add the device before the first frame arrives.
    usleep(250000);
    ALOGI("merged uinput touchscreen '%s' created: %d panel slots + %d trigger slots, %dx%d",
          TRIG_INPUT_PORT, slots, TRIG_SLOTS, g_max_x + 1, g_max_y + 1);
    return 0;
}

static void merged_select_slot(struct merged *m, int slot) {
    if (m->virt_slot == slot) return;
    emit(m->ufd, EV_ABS, ABS_MT_SLOT, slot);
    m->virt_slot = slot;
}

static int merged_any_phys_down(const struct merged *m) {
    for (int s = 0; s < m->panel_slots; s++) if (m->phys_down[s]) return 1;
    return 0;
}

static int merged_first_phys_down(const struct merged *m) {
    for (int s = 0; s < m->panel_slots; s++)
        if (m->phys_down[s] && m->phys_x_ok[s] && m->phys_y_ok[s]) return s;
    return -1;
}

// Legacy ABS_X/ABS_Y "primary pointer": first finger on the panel, else the
// held trigger. A new panel contact reports its tracking id before its
// coordinates -- do not let a simultaneous trigger temporarily become the
// primary or reuse the previous contact's coordinates (Austin's note).
static void merged_primary(struct merged *m) {
    int s = merged_first_phys_down(m);
    if (s >= 0) {
        emit(m->ufd, EV_ABS, ABS_X, m->phys_x[s]);
        emit(m->ufd, EV_ABS, ABS_Y, m->phys_y[s]);
        return;
    }
    if (merged_any_phys_down(m)) return;
    int t = m->trig_down[SLOT_LEFT] ? SLOT_LEFT : m->trig_down[SLOT_RIGHT] ? SLOT_RIGHT : -1;
    if (t < 0) return;
    emit(m->ufd, EV_ABS, ABS_X, m->trig_x[t]);
    emit(m->ufd, EV_ABS, ABS_Y, m->trig_y[t]);
}

static void merged_combined_btn(struct merged *m) {
    int want = m->phys_btn_touch || merged_any_phys_down(m) ||
               m->trig_down[SLOT_LEFT] || m->trig_down[SLOT_RIGHT];
    if (m->combined_btn == want) return;
    emit(m->ufd, EV_KEY, BTN_TOOL_FINGER, want);
    emit(m->ufd, EV_KEY, BTN_TOUCH, want);
    m->combined_btn = want;
}

// Press/release one trigger contact in its reserved slot above the panel's.
static void merged_contact(struct merged *m, int t, int pressed, int x, int y) {
    if (m->ufd < 0 || m->trig_down[t] == pressed) return;
    merged_select_slot(m, m->panel_slots + t);
    if (pressed) {
        m->trig_x[t] = x; m->trig_y[t] = y;
        emit(m->ufd, EV_ABS, ABS_MT_TRACKING_ID, 65535 - t);
        emit(m->ufd, EV_ABS, ABS_MT_POSITION_X, x);
        emit(m->ufd, EV_ABS, ABS_MT_POSITION_Y, y);
        emit(m->ufd, EV_ABS, ABS_MT_TOUCH_MAJOR, 32);
    } else {
        emit(m->ufd, EV_ABS, ABS_MT_TRACKING_ID, -1);
    }
    m->trig_down[t] = pressed;
    merged_primary(m);
    merged_combined_btn(m);
    emit(m->ufd, EV_SYN, SYN_REPORT, 0);
}

// Lift every panel contact we are still showing (deactivation / panel lost).
static void merged_release_phys(struct merged *m) {
    if (m->ufd < 0) return;
    for (int s = 0; s < m->panel_slots; s++) {
        if (!m->phys_down[s]) continue;
        merged_select_slot(m, s);
        emit(m->ufd, EV_ABS, ABS_MT_TRACKING_ID, -1);
        m->phys_down[s] = 0;
        m->phys_x_ok[s] = m->phys_y_ok[s] = 0;
    }
    m->phys_btn_touch = 0;
    merged_combined_btn(m);
    emit(m->ufd, EV_SYN, SYN_REPORT, 0);
}

// Forward one batch of panel events onto the merged device. The panel's slot
// protocol is replayed verbatim; the only rewriting is (a) re-selecting the
// panel's slot before each slot-scoped event, because a trigger frame may have
// moved the merged device's current slot in between, and (b) BTN_TOUCH /
// BTN_TOOL_FINGER, which are swallowed and re-emitted as the combined state.
static void merged_forward_panel(struct merged *m) {
    struct input_event evs[64];
    ssize_t n = read(m->panel_fd, evs, sizeof(evs));
    if (n <= 0 || m->ufd < 0) return;
    size_t cnt = (size_t)n / sizeof(evs[0]);
    for (size_t i = 0; i < cnt; i++) {
        const struct input_event *ev = &evs[i];
        if (ev->type == EV_ABS && ev->code == ABS_MT_SLOT) {
            if (ev->value < 0 || ev->value >= m->panel_slots) continue;
            m->phys_slot = ev->value;
            merged_select_slot(m, ev->value);
            continue;
        }
        if (ev->type == EV_ABS && ev->code >= ABS_MT_TOUCH_MAJOR && ev->code <= ABS_MT_TOOL_Y) {
            merged_select_slot(m, m->phys_slot);   // slot-scoped MT event
            int s = m->phys_slot;
            if (ev->code == ABS_MT_TRACKING_ID) {
                m->phys_down[s] = ev->value >= 0;
                m->phys_x_ok[s] = m->phys_y_ok[s] = 0;
            } else if (ev->code == ABS_MT_POSITION_X) {
                m->phys_x[s] = ev->value; m->phys_x_ok[s] = 1;
            } else if (ev->code == ABS_MT_POSITION_Y) {
                m->phys_y[s] = ev->value; m->phys_y_ok[s] = 1;
            }
            emit(m->ufd, ev->type, ev->code, ev->value);
            continue;
        }
        if (ev->type == EV_KEY && (ev->code == BTN_TOUCH || ev->code == BTN_TOOL_FINGER)) {
            if (ev->code == BTN_TOUCH) m->phys_btn_touch = ev->value != 0;
            continue;
        }
        if (ev->type == EV_ABS && (ev->code == ABS_X || ev->code == ABS_Y)) {
            continue;   // regenerated by merged_primary() on SYN
        }
        if (ev->type == EV_SYN && ev->code == SYN_REPORT) {
            merged_primary(m);
            merged_combined_btn(m);
            emit(m->ufd, EV_SYN, SYN_REPORT, 0);
            continue;
        }
        emit(m->ufd, ev->type, ev->code, ev->value);
    }
}

static void merged_deactivate(struct merged *m, const char *why) {
    if (m->ufd >= 0) {
        merged_contact(m, SLOT_LEFT, 0, 0, 0);
        merged_contact(m, SLOT_RIGHT, 0, 0, 0);
        merged_release_phys(m);
    }
    if (m->grabbed_panel)          { ioctl(m->panel_fd, EVIOCGRAB, 0); m->grabbed_panel = 0; }
    for (int t = 0; t < 2; t++)
        if (m->grabbed_sar[t])     { ioctl(m->sar_fd[t], EVIOCGRAB, 0); m->grabbed_sar[t] = 0; }
    if (m->ufd >= 0) { ioctl(m->ufd, UI_DEV_DESTROY); close(m->ufd); m->ufd = -1; }
    merged_reset(m);
    if (m->active) ALOGI("merged engine OFF (%s)", why);
    m->active = 0;
}

static int merged_activate(struct merged *m) {
    if (m->active) return 1;
    drain(m->panel_fd);
    drain(m->sar_fd[SLOT_LEFT]);
    drain(m->sar_fd[SLOT_RIGHT]);
    merged_reset(m);
    if (merged_uinput_create(m) < 0) { merged_deactivate(m, "uinput failed"); return 0; }
    for (int t = 0; t < 2; t++) {
        if (m->sar_fd[t] < 0) continue;
        if (ioctl(m->sar_fd[t], EVIOCGRAB, 1) < 0) {
            ALOGE("EVIOCGRAB %s trigger: %s", t == SLOT_LEFT ? "LEFT" : "RIGHT", strerror(errno));
            merged_deactivate(m, "sar grab failed"); return 0;
        }
        m->grabbed_sar[t] = 1;
    }
    if (ioctl(m->panel_fd, EVIOCGRAB, 1) < 0) {
        ALOGE("EVIOCGRAB panel: %s", strerror(errno));
        merged_deactivate(m, "panel grab failed"); return 0;
    }
    m->grabbed_panel = 1;
    m->active = 1;
    ALOGI("merged engine ON (game in front): panel + triggers republished as one device");
    return 1;
}

// ---------------------------------------------------------------------------

int main(void) {
    ALOGI("trigger_map starting");
    signal(SIGTERM, on_term);
    signal(SIGINT, on_term);

    char pr[64], pl[64], pt[64];
    int panel_fd = open_evdev_by_name(REAL_TS_NAME, pt, sizeof(pt));
    probe_panel_range(panel_fd);

    int right_fd = open_evdev_by_name(SAR_RIGHT_NAME, pr, sizeof(pr));
    int left_fd  = open_evdev_by_name(SAR_LEFT_NAME,  pl, sizeof(pl));
    if (right_fd < 0 && left_fd < 0) {
        ALOGE("neither trigger evdev node found (%s / %s) -- exiting", SAR_RIGHT_NAME, SAR_LEFT_NAME);
        return 1;
    }
    ALOGI("triggers: LEFT fd=%d (%s), RIGHT fd=%d (%s), panel fd=%d (%s)",
          left_fd, left_fd >= 0 ? pl : "-", right_fd, right_fd >= 0 ? pr : "-",
          panel_fd, panel_fd >= 0 ? pt : "-");

    struct merged m;
    memset(&m, 0, sizeof(m));
    m.ufd = -1;
    m.panel_fd = panel_fd;
    m.sar_fd[SLOT_LEFT] = left_fd;
    m.sar_fd[SLOT_RIGHT] = right_fd;
    merged_reset(&m);

    // Legacy device is created lazily the first time the legacy engine is wanted
    // and stays for the life of the process (as before).
    int legacy_ufd = -1;
    int lg_held[2] = {0, 0};
    // Consecutive merged-activation failures; after MERGE_MAX_FAILS the engine is
    // parked on legacy until the daemon is restarted (trig_map toggle) instead of
    // retrying every GATE_POLL_MS and flooding the log.
    int merge_fails = 0;
    // Monotonic ms at which "want_active" first dropped while the engine was up;
    // 0 = no teardown pending. See DEACTIVATE_GRACE_MS.
    long off_since = 0;

    for (;;) {
        if (g_stop) break;

        int merge_wanted = panel_fd >= 0 && merge_fails < MERGE_MAX_FAILS &&
                           prop_bool("persist.sys.rm.trig_merge", 1);
        int game_front   = prop_bool("persist.sys.power_mode_perf", 0);
        int map_on       = prop_bool("persist.sys.rm.trig_map", 0);
        int want_active  = merge_wanted && game_front && map_on;

        if (want_active) {
            off_since = 0;                  // a pending teardown is cancelled by the flap back
            if (!m.active) {
                if (merged_activate(&m)) {
                    merge_fails = 0;
                } else if (++merge_fails >= MERGE_MAX_FAILS) {
                    ALOGE("merged engine failed %d times -- parking on the legacy engine", merge_fails);
                    merge_wanted = 0;
                } else {
                    merge_wanted = 0;   // legacy device covers this session
                }
            }
        } else if (m.active) {
            // Debounced teardown: keep the grabbing device up (still forwarding the
            // panel + triggers) until the game has stayed gone for the grace window.
            long now = now_ms();
            if (off_since == 0) off_since = now;
            if (now - off_since >= DEACTIVATE_GRACE_MS) {
                merged_deactivate(&m, game_front ? "mapping off" : "game left");
                off_since = 0;
            }
        }
        if (!merge_wanted && legacy_ufd < 0) {
            legacy_ufd = legacy_uinput_create();
            if (legacy_ufd < 0) return 1;
        }

        struct pollfd pfd[3];
        int idx[3];   // 0 = left SAR, 1 = right SAR, 2 = panel
        int nf = 0;
        if (left_fd  >= 0) { pfd[nf].fd = left_fd;  pfd[nf].events = POLLIN; idx[nf] = SLOT_LEFT;  nf++; }
        if (right_fd >= 0) { pfd[nf].fd = right_fd; pfd[nf].events = POLLIN; idx[nf] = SLOT_RIGHT; nf++; }
        if (m.active)      { pfd[nf].fd = panel_fd; pfd[nf].events = POLLIN; idx[nf] = 2;          nf++; }

        int pr_ = poll(pfd, nf, GATE_POLL_MS);
        if (pr_ < 0) { if (errno == EINTR) continue; ALOGE("poll: %s", strerror(errno)); continue; }
        if (pr_ == 0) continue;   // timeout: re-evaluate the gate above

        for (int i = 0; i < nf; i++) {
            if (!(pfd[i].revents & (POLLIN | POLLERR | POLLHUP))) continue;
            if (idx[i] == 2) {
                if (pfd[i].revents & (POLLERR | POLLHUP)) { merged_deactivate(&m, "panel gone"); continue; }
                merged_forward_panel(&m);
                continue;
            }
            int is_left = (idx[i] == SLOT_LEFT);
            struct input_event evs[16];
            ssize_t n = read(pfd[i].fd, evs, sizeof(evs));
            if (n <= 0) continue;
            size_t cnt = (size_t)n / sizeof(evs[0]);
            for (size_t k = 0; k < cnt; k++) {
                const struct input_event *ev = &evs[k];
                if (ev->type != EV_KEY) continue;
                // Only KEY_F7 (right) / KEY_F8 (left); ignore the KEY_F1 the sensors also emit.
                if (is_left && ev->code != KEY_F8) continue;
                if (!is_left && ev->code != KEY_F7) continue;
                if (ev->value != 0 && ev->value != 1) continue;   // no autorepeat

                if (m.active) {
                    int t = is_left ? SLOT_LEFT : SLOT_RIGHT;
                    if (ev->value == 1) {
                        int x, y;
                        if (!side_enabled(is_left) || !trigger_target(is_left, &x, &y)) continue;
                        merged_contact(&m, t, 1, x, y);
                    } else {
                        merged_contact(&m, t, 0, 0, 0);
                    }
                    continue;
                }

                // Legacy engine.
                if (legacy_ufd < 0) continue;
                if (!map_on || !side_enabled(is_left)) continue;   // triggers still emit F7/F8 as usual
                if (ev->value == 1) {
                    int x, y;
                    if (!trigger_target(is_left, &x, &y)) continue;
                    if (is_left && !lg_held[SLOT_LEFT])   { legacy_down(legacy_ufd, SLOT_LEFT,  x, y); lg_held[SLOT_LEFT]  = 1; }
                    if (!is_left && !lg_held[SLOT_RIGHT]) { legacy_down(legacy_ufd, SLOT_RIGHT, x, y); lg_held[SLOT_RIGHT] = 1; }
                } else {
                    if (is_left && lg_held[SLOT_LEFT])   { legacy_up(legacy_ufd, SLOT_LEFT);  lg_held[SLOT_LEFT]  = 0; }
                    if (!is_left && lg_held[SLOT_RIGHT]) { legacy_up(legacy_ufd, SLOT_RIGHT); lg_held[SLOT_RIGHT] = 0; }
                }
            }
        }
    }

    merged_deactivate(&m, "exit");
    if (legacy_ufd >= 0) { ioctl(legacy_ufd, UI_DEV_DESTROY); close(legacy_ufd); }
    ALOGI("trigger_map exiting");
    return 0;
}
