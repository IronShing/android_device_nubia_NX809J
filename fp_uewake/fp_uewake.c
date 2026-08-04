// fp_uewake — NX809J (Synaptics TCM / zte_tpd) deep-sleep ULTRASONIC-FP wake+unlock bridge.
//
// The ultrasonic under-display FP works screen-on (UDFPS), but the touch driver,
// in low-power gesture mode, reports an FP-area finger-down as the netlink uevent
// "aod_areameet_down=true" instead of a standard input event — so AOSP
// PowerManager/SystemUI never see it and the screen stays off. This daemon is the
// missing consumer: it arms the FP single-tap gesture, listens for that uevent,
// and (1) injects KEY_WAKEUP so the display wakes and the keyguard + UDFPS overlay
// come up, then (2) injects a short synthetic touch at the FP sensor location so
// the UDFPS overlay fires onFingerDown -> the ultrasonic sensor scans the user's
// REAL held finger -> match -> unlock. Result: place finger on the dark screen ->
// wake + unlock in one motion, like stock. (The synthetic touch only TRIGGERS the
// scan; the biometric comes from the hardware sensor reading the real finger.)
//
// Mirrors dt2w_uewake's enforcing-SELinux split: this coredomain daemon touches
// ONLY platform resources (netlink kobject uevents + /dev/uinput). The vendor
// syna_proc node /proc/touchscreen/single_tap is armed by an odm/vendor_init
// action (fp_uewake_arm.rc) that triggers on the sys.fp.arm property we pulse.
//
// RE-ARM: the driver clears the gesture arm on every resume and refuses the write
// while suspended, so we re-arm on each screen-on (event-driven + a wake-time
// safety-net poll), exactly as dt2w_uewake does. Gated on persist.sys.fp_wake.enabled.

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <linux/netlink.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <sys/socket.h>
#include <sys/system_properties.h>
#include <sys/types.h>

#define LOG_TAG "fp_uewake"
#include <log/log.h>

#define REARM_TIMEOUT_MS 8000

// FP sensor centre (persist.vendor.fingerprint.virtual.sensor_location = 608:2024:94)
#define FP_X 608
#define FP_Y 2024
#define SCR_W 1216
#define SCR_H 2688
// timings (tunable via properties)
#define DEF_OVERLAY_DELAY_MS 320   // wait for keyguard + UDFPS overlay after wake
#define DEF_TOUCH_HOLD_MS    900   // hold synthetic touch long enough for the scan

static const char *ARM_PROP = "sys.fp.arm";

static void arm_gesture(const char *why) {
    __system_property_set(ARM_PROP, "0");
    if (__system_property_set(ARM_PROP, "1") == 0)
        ALOGI("requested single_tap arm via %s=1 (%s)", ARM_PROP, why);
    else
        ALOGE("set %s failed (%s)", ARM_PROP, why);
}

static int prop_int(const char *name, int def) {
    char v[PROP_VALUE_MAX];
    if (__system_property_get(name, v) > 0) { int n = atoi(v); if (n >= 0) return n; }
    return def;
}

static void msleep(long ms) {
    struct timespec ts = { ms / 1000, (ms % 1000) * 1000000L };
    nanosleep(&ts, NULL);
}

static void emit(int fd, int type, int code, int val) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type; ev.code = code; ev.value = val;
    (void)!write(fd, &ev, sizeof(ev));
}

// --- uinput: KEY_WAKEUP injector ------------------------------------------
static int uinput_setup_wake(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) { ALOGE("open /dev/uinput (key): %s", strerror(errno)); return -1; }
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_KEYBIT, KEY_WAKEUP);
    struct uinput_user_dev uud;
    memset(&uud, 0, sizeof(uud));
    snprintf(uud.name, UINPUT_MAX_NAME_SIZE, "fp_uewake");
    uud.id.bustype = BUS_VIRTUAL; uud.id.vendor = 0x6770; uud.id.product = 0x0002; uud.id.version = 1;
    if (write(fd, &uud, sizeof(uud)) < 0) { ALOGE("uinput key write: %s", strerror(errno)); close(fd); return -1; }
    if (ioctl(fd, UI_DEV_CREATE) < 0) { ALOGE("UI_DEV_CREATE key: %s", strerror(errno)); close(fd); return -1; }
    ALOGI("uinput fp_uewake created (KEY_WAKEUP)");
    return fd;
}

// --- uinput: synthetic touchscreen (MT type B) ----------------------------
static int uinput_setup_ts(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) { ALOGE("open /dev/uinput (ts): %s", strerror(errno)); return -1; }
    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_SLOT);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_PRESSURE);
    struct uinput_user_dev uud;
    memset(&uud, 0, sizeof(uud));
    snprintf(uud.name, UINPUT_MAX_NAME_SIZE, "fp_uewake_ts");
    uud.id.bustype = BUS_VIRTUAL; uud.id.vendor = 0x6770; uud.id.product = 0x0003; uud.id.version = 1;
    uud.absmin[ABS_MT_SLOT] = 0;         uud.absmax[ABS_MT_SLOT] = 9;
    uud.absmin[ABS_MT_TRACKING_ID] = 0;  uud.absmax[ABS_MT_TRACKING_ID] = 65535;
    uud.absmin[ABS_MT_POSITION_X] = 0;   uud.absmax[ABS_MT_POSITION_X] = SCR_W;
    uud.absmin[ABS_MT_POSITION_Y] = 0;   uud.absmax[ABS_MT_POSITION_Y] = SCR_H;
    uud.absmin[ABS_MT_PRESSURE] = 0;     uud.absmax[ABS_MT_PRESSURE] = 255;
    if (write(fd, &uud, sizeof(uud)) < 0) { ALOGE("uinput ts write: %s", strerror(errno)); close(fd); return -1; }
    if (ioctl(fd, UI_DEV_CREATE) < 0) { ALOGE("UI_DEV_CREATE ts: %s", strerror(errno)); close(fd); return -1; }
    ALOGI("uinput fp_uewake_ts created (touch)");
    return fd;
}

static void inject_wakeup(int ufd) {
    emit(ufd, EV_KEY, KEY_WAKEUP, 1); emit(ufd, EV_SYN, SYN_REPORT, 0);
    emit(ufd, EV_KEY, KEY_WAKEUP, 0); emit(ufd, EV_SYN, SYN_REPORT, 0);
}

static void inject_fp_touch(int tfd, long hold_ms) {
    emit(tfd, EV_ABS, ABS_MT_SLOT, 0);
    emit(tfd, EV_ABS, ABS_MT_TRACKING_ID, 77);
    emit(tfd, EV_ABS, ABS_MT_POSITION_X, FP_X);
    emit(tfd, EV_ABS, ABS_MT_POSITION_Y, FP_Y);
    emit(tfd, EV_ABS, ABS_MT_PRESSURE, 128);
    emit(tfd, EV_KEY, BTN_TOUCH, 1);
    emit(tfd, EV_SYN, SYN_REPORT, 0);
    msleep(hold_ms);
    emit(tfd, EV_ABS, ABS_MT_SLOT, 0);
    emit(tfd, EV_ABS, ABS_MT_TRACKING_ID, -1);
    emit(tfd, EV_KEY, BTN_TOUCH, 0);
    emit(tfd, EV_SYN, SYN_REPORT, 0);
}

// --- netlink uevent listener ----------------------------------------------
static int netlink_open(void) {
    struct sockaddr_nl nls;
    memset(&nls, 0, sizeof(nls));
    nls.nl_family = AF_NETLINK; nls.nl_pid = 0; nls.nl_groups = 1;
    int s = socket(PF_NETLINK, SOCK_DGRAM | SOCK_CLOEXEC, NETLINK_KOBJECT_UEVENT);
    if (s < 0) { ALOGE("netlink socket: %s", strerror(errno)); return -1; }
    int sz = 2 * 1024 * 1024;
    setsockopt(s, SOL_SOCKET, SO_RCVBUFFORCE, &sz, sizeof(sz));
    if (bind(s, (struct sockaddr *)&nls, sizeof(nls)) < 0) { ALOGE("netlink bind: %s", strerror(errno)); close(s); return -1; }
    return s;
}

static int has_token(const char *buf, int len, const char *tok) {
    int tl = (int)strlen(tok);
    for (int i = 0; i < len; ) {
        const char *s = buf + i;
        int sl = (int)strnlen(s, len - i);
        if (sl == tl && memcmp(s, tok, tl) == 0) return 1;
        i += sl + 1;
    }
    return 0;
}

static int has_substr(const char *buf, int len, const char *needle) {
    int nl = (int)strlen(needle);
    if (nl == 0 || len < nl) return 0;
    for (int i = 0; i + nl <= len; i++)
        if (memcmp(buf + i, needle, nl) == 0) return 1;
    return 0;
}

static int is_screen_on_uevent(const char *buf, int len) {
    return has_substr(buf, len, "screen_on") || has_substr(buf, len, "lcd_on")
        || has_substr(buf, len, "lcd=on") || has_substr(buf, len, "POWER=on");
}

int main(void) {
    ALOGI("fp_uewake starting");
    int kfd = uinput_setup_wake();
    int tfd = uinput_setup_ts();
    if (kfd < 0 || tfd < 0) return 1;
    int nls = netlink_open();
    if (nls < 0) return 1;

    arm_gesture("startup");

    struct pollfd pfd = { .fd = nls, .events = POLLIN };
    char buf[4096];
    for (;;) {
        int pr = poll(&pfd, 1, REARM_TIMEOUT_MS);
        if (pr < 0) { if (errno == EINTR) continue; ALOGE("poll: %s", strerror(errno)); continue; }
        if (pr == 0) { arm_gesture("rearm-timeout"); continue; }
        if (!(pfd.revents & POLLIN)) continue;

        ssize_t len = recv(nls, buf, sizeof(buf) - 1, 0);
        if (len <= 0) { if (errno == EINTR) continue; ALOGE("recv: %s", strerror(errno)); continue; }
        buf[len] = '\0';

        // FP-area finger-down (screen off): wake, then trigger the scan of the held finger.
        if (has_token(buf, (int)len, "aod_areameet_down=true")) {
            inject_wakeup(kfd);
            msleep(prop_int("persist.sys.fp_wake.overlay_ms", DEF_OVERLAY_DELAY_MS));
            inject_fp_touch(tfd, prop_int("persist.sys.fp_wake.hold_ms", DEF_TOUCH_HOLD_MS));
            arm_gesture("post-wake");
            ALOGI("FP finger-down -> wake + fp-touch");
            continue;
        }
        if (is_screen_on_uevent(buf, (int)len)) {
            arm_gesture("screen-on");
        }
    }
    return 0;
}
