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
// This daemon is the native equivalent of the stock mapper: it reads the two
// trigger nodes directly and, while enabled, injects a real multi-touch touch at
// a user-chosen screen coordinate through a uinput virtual touchscreen. LEFT uses
// MT slot 0, RIGHT uses MT slot 1, so both triggers (and the user's own fingers,
// which land on the separate physical panel) coexist. 0 ms userspace hop, one
// clean MotionEvent stream per trigger.
//
// SELinux: like dt2w_uewake this runs as a clean system_ext coredomain doing only
// evdev reads (/dev/input/*, platform-labelled input_device) + /dev/uinput. It
// never touches any vendor-labelled node.
//
// Config (all live, re-read on every trigger press so RedMagicControl edits apply
// without a restart):
//   persist.sys.rm.trigger_left   "true"/"false"  master enable, LEFT  (existing)
//   persist.sys.rm.trigger_right  "true"/"false"  master enable, RIGHT (existing)
//   persist.sys.rm.trig_l_x / _l_y   LEFT  touch target, per-mille (0..1000 of
//   persist.sys.rm.trig_r_x / _r_y   RIGHT touch target, the panel W/H). Normalized
//                                 so RedMagic Control (display-pixel space) and this
//                                 daemon (touch-ABS space) need not share a resolution.
//   persist.sys.rm.trig_map       "true"/"false"  master "map to touch" switch;
//                                 when false the triggers keep firing F7/F8 only.
// Coordinate space is the physical panel's, discovered at runtime from the real
// touchscreen's ABS_MT_POSITION_X/Y range, so this is panel-agnostic.

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
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

// MT slots reserved for the two triggers. Kept low and fixed; the user's fingers
// are on a DIFFERENT input device so there is no slot collision to worry about.
#define SLOT_LEFT   0
#define SLOT_RIGHT  1

static int g_max_x = 1215;   // sensible RM692H5 fallback until probed
static int g_max_y = 2687;

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
        int fd = open(p, O_RDONLY | O_CLOEXEC);
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
static void probe_panel_range(void) {
    char path[64];
    int fd = open_evdev_by_name(REAL_TS_NAME, path, sizeof(path));
    if (fd < 0) { ALOGW("real touchscreen '%s' not found, using %dx%d fallback",
                        REAL_TS_NAME, g_max_x + 1, g_max_y + 1); return; }
    struct input_absinfo ax, ay;
    if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &ax) >= 0 && ax.maximum > 0) g_max_x = ax.maximum;
    if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), &ay) >= 0 && ay.maximum > 0) g_max_y = ay.maximum;
    close(fd);
    ALOGI("panel range from %s: X[0..%d] Y[0..%d]", REAL_TS_NAME, g_max_x, g_max_y);
}

static int uinput_setup_touch(void) {
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
    ALOGI("uinput virtual touchscreen '%s' created (%dx%d)", TRIG_INPUT_PORT, g_max_x + 1, g_max_y + 1);
    return fd;
}

static void emit(int fd, int type, int code, int val) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type; ev.code = code; ev.value = val;
    (void)!write(fd, &ev, sizeof(ev));
}

// Number of currently-held triggers, so BTN_TOUCH is only released when the last
// pointer lifts.
static int g_active = 0;
static int g_next_tid = 1;

static void touch_down(int ufd, int slot, int x, int y) {
    emit(ufd, EV_ABS, ABS_MT_SLOT, slot);
    emit(ufd, EV_ABS, ABS_MT_TRACKING_ID, g_next_tid++);
    emit(ufd, EV_ABS, ABS_MT_POSITION_X, x);
    emit(ufd, EV_ABS, ABS_MT_POSITION_Y, y);
    emit(ufd, EV_ABS, ABS_MT_TOUCH_MAJOR, 6);
    if (g_active == 0) emit(ufd, EV_KEY, BTN_TOUCH, 1);
    g_active++;
    emit(ufd, EV_SYN, SYN_REPORT, 0);
}

static void touch_up(int ufd, int slot) {
    emit(ufd, EV_ABS, ABS_MT_SLOT, slot);
    emit(ufd, EV_ABS, ABS_MT_TRACKING_ID, -1);
    if (g_active > 0) g_active--;
    if (g_active == 0) emit(ufd, EV_KEY, BTN_TOUCH, 0);
    emit(ufd, EV_SYN, SYN_REPORT, 0);
}

int main(void) {
    ALOGI("trigger_map starting");
    probe_panel_range();

    int ufd = uinput_setup_touch();
    if (ufd < 0) return 1;

    char pr[64], pl[64];
    int right_fd = open_evdev_by_name(SAR_RIGHT_NAME, pr, sizeof(pr));
    int left_fd  = open_evdev_by_name(SAR_LEFT_NAME,  pl, sizeof(pl));
    if (right_fd < 0 && left_fd < 0) {
        ALOGE("neither trigger evdev node found (%s / %s) -- exiting", SAR_RIGHT_NAME, SAR_LEFT_NAME);
        return 1;
    }
    ALOGI("triggers: LEFT fd=%d (%s), RIGHT fd=%d (%s)", left_fd, pl, right_fd, pr);

    struct pollfd pfd[2];
    int nf = 0;
    int idx_left = -1;
    if (left_fd  >= 0) { pfd[nf].fd = left_fd;  pfd[nf].events = POLLIN; idx_left  = nf; nf++; }
    if (right_fd >= 0) { pfd[nf].fd = right_fd; pfd[nf].events = POLLIN; nf++; }

    // Track down-state per side so we release the exact slot on key-up.
    int left_down = 0, right_down = 0;

    for (;;) {
        int pr_ = poll(pfd, nf, -1);
        if (pr_ < 0) { if (errno == EINTR) continue; ALOGE("poll: %s", strerror(errno)); continue; }
        for (int i = 0; i < nf; i++) {
            if (!(pfd[i].revents & POLLIN)) continue;
            struct input_event ev;
            ssize_t n = read(pfd[i].fd, &ev, sizeof(ev));
            if (n != (ssize_t)sizeof(ev)) continue;
            if (ev.type != EV_KEY) continue;
            int is_left = (i == idx_left);
            // Only KEY_F7 (right) / KEY_F8 (left); ignore the KEY_F1 the sensors also emit.
            if (is_left && ev.code != KEY_F8) continue;
            if (!is_left && ev.code != KEY_F7) continue;

            int mapping_on = prop_bool("persist.sys.rm.trig_map", 0);
            int side_on = prop_bool(is_left ? "persist.sys.rm.trigger_left"
                                            : "persist.sys.rm.trigger_right", 1);
            if (!mapping_on || !side_on) continue;   // triggers still emit F7/F8 as usual

            if (ev.value == 1) {   // key down
                // Pick the coordinate set for the current orientation. RedMagic Control
                // publishes sys.rm.trig_rot (0 = portrait, 1 = landscape) from a display
                // rotation listener. The stored per-mille are already in the panel's OWN
                // (native/portrait) space -- the positioner did the rotation transform when
                // it saved -- so we inject them the same way in either orientation; the bit
                // only selects WHICH physical point (a given on-screen button sits at a
                // different panel point in landscape vs portrait).
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
                if (mx < 0 || my < 0) continue;   // not positioned yet
                if (mx > 1000) mx = 1000;
                if (my > 1000) my = 1000;
                int x = (int)((long)mx * g_max_x / 1000);   // per-mille -> panel ABS px
                int y = (int)((long)my * g_max_y / 1000);
                if (is_left && !left_down)  { touch_down(ufd, SLOT_LEFT,  x, y); left_down = 1; }
                if (!is_left && !right_down){ touch_down(ufd, SLOT_RIGHT, x, y); right_down = 1; }
            } else if (ev.value == 0) { // key up
                if (is_left && left_down)   { touch_up(ufd, SLOT_LEFT);  left_down = 0; }
                if (!is_left && right_down) { touch_up(ufd, SLOT_RIGHT); right_down = 0; }
            }
        }
    }
    return 0;
}
