// dt2w_uewake — NX809J (Synaptics TCM / zte_tpd) double-tap-to-wake bridge.
//
// The touch driver detects the double-tap in low-power gesture mode and, instead
// of emitting an input KEY_WAKEUP, sends a netlink uevent "double_tap=true" and
// holds the system awake ~2s via pm_wakeup_ws_event(). Stock RedMagicOS has a
// userspace service that consumes this; LineageOS does not. This daemon is that
// consumer: it arms the gesture, listens for the uevent, and injects KEY_WAKEUP
// through a uinput device (flagged WAKE via dt2w_uewake.kl) so the framework
// wakes the display.
//
// ENFORCING-SELINUX SPLIT (this revision): the gesture node
// /proc/touchscreen/wake_gesture is labelled "syna_proc", a VENDOR type that a
// coredomain may neither name nor access, and whose genfs label is owned by the
// stock vendor policy (so it cannot be relabelled without a bricking conflict).
// We therefore run this daemon as a clean system_ext coredomain that touches
// ONLY platform resources (netlink kobject uevents + /dev/uinput) and delegate
// the actual node write to init: we toggle the property sys.dt2w.arm, and an
// odm init action (vendor_init, which the stock vendor policy already allows to
// write syna_proc) does "write /proc/touchscreen/wake_gesture 1". The daemon
// never opens /proc/touchscreen at all.
//
// RE-ARM REQUIREMENT: the Synaptics driver clears the wake-gesture arm
// (tcm->ztec.is_wakeup_gesture) on every resume and refuses the write while
// suspended. Arming once at boot therefore dies after the first screen-on. So we
// request a re-arm on every screen-on, while awake:
//   1) on the driver's screen-on/lcd-on uevent (event-driven, primary),
//   2) right after we inject a DT2W wake (the screen is about to come on),
//   3) a poll-timeout safety net (the daemon is suspend-frozen, so the timeout
//      only fires while awake — exactly when re-arming is permitted).
//
// Gated on the LineageOS DT2W setting via persist.sys.dt2w.enabled (the init
// service starts/stops this daemon and clears wake_gesture when disabled).

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <linux/netlink.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <sys/socket.h>
#include <sys/system_properties.h>
#include <sys/types.h>

#define LOG_TAG "dt2w_uewake"
#include <log/log.h>

// Re-arm safety-net interval. The screen-on uevent is the primary re-arm; this
// only fires while awake, so it adds negligible churn.
#define REARM_TIMEOUT_MS 8000

// Property bridge to init. The daemon (coredomain) cannot write the vendor
// syna_proc gesture node, so it asks init to: an odm action triggers on
// sys.dt2w.arm=1 and writes /proc/touchscreen/wake_gesture 1. We pulse 0 -> 1 so
// the 0->1 transition re-fires the init trigger on every request.
static const char *ARM_PROP = "sys.dt2w.arm";

static void arm_gesture(const char *why) {
    // Pulse low then high so the init property trigger re-fires each time, even
    // if it was already "1" from a previous request.
    __system_property_set(ARM_PROP, "0");
    if (__system_property_set(ARM_PROP, "1") == 0)
        ALOGI("requested wake_gesture arm via %s=1 (%s)", ARM_PROP, why);
    else
        ALOGE("set %s failed (%s)", ARM_PROP, why);
}

// --- uinput KEY_WAKEUP injector -------------------------------------------
static int uinput_setup_wake(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) { ALOGE("open /dev/uinput: %s", strerror(errno)); return -1; }
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_KEYBIT, KEY_WAKEUP);   // 143

    struct uinput_user_dev uud;
    memset(&uud, 0, sizeof(uud));
    snprintf(uud.name, UINPUT_MAX_NAME_SIZE, "dt2w_uewake");
    uud.id.bustype = BUS_VIRTUAL;
    uud.id.vendor = 0x6770;   // 'gp'
    uud.id.product = 0x0001;
    uud.id.version = 1;
    if (write(fd, &uud, sizeof(uud)) < 0) { ALOGE("uinput dev write: %s", strerror(errno)); close(fd); return -1; }
    if (ioctl(fd, UI_DEV_CREATE) < 0) { ALOGE("UI_DEV_CREATE: %s", strerror(errno)); close(fd); return -1; }
    ALOGI("uinput dt2w_uewake created (KEY_WAKEUP)");
    return fd;
}

static void emit(int fd, int type, int code, int val) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type; ev.code = code; ev.value = val;
    (void)!write(fd, &ev, sizeof(ev));
}

static void inject_wakeup(int ufd) {
    emit(ufd, EV_KEY, KEY_WAKEUP, 1);
    emit(ufd, EV_SYN, SYN_REPORT, 0);
    emit(ufd, EV_KEY, KEY_WAKEUP, 0);
    emit(ufd, EV_SYN, SYN_REPORT, 0);
    ALOGI("DT2W: double_tap -> injected KEY_WAKEUP");
}

// --- netlink uevent listener ----------------------------------------------
static int netlink_open(void) {
    struct sockaddr_nl nls;
    memset(&nls, 0, sizeof(nls));
    nls.nl_family = AF_NETLINK;
    nls.nl_pid = 0;            // kernel-assigned
    nls.nl_groups = 1;         // kobject uevents
    int s = socket(PF_NETLINK, SOCK_DGRAM | SOCK_CLOEXEC, NETLINK_KOBJECT_UEVENT);
    if (s < 0) { ALOGE("netlink socket: %s", strerror(errno)); return -1; }
    int sz = 2 * 1024 * 1024;
    setsockopt(s, SOL_SOCKET, SO_RCVBUFFORCE, &sz, sizeof(sz));
    if (bind(s, (struct sockaddr *)&nls, sizeof(nls)) < 0) { ALOGE("netlink bind: %s", strerror(errno)); close(s); return -1; }
    return s;
}

// uevent payload is a sequence of NUL-separated KEY=VALUE tokens.
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

// Loose substring search across the whole uevent payload (tokens are NUL-joined,
// so a substring may appear in any KEY=VALUE field, e.g. "...=screen_on").
static int has_substr(const char *buf, int len, const char *needle) {
    int nl = (int)strlen(needle);
    if (nl == 0 || len < nl) return 0;
    for (int i = 0; i + nl <= len; i++)
        if (memcmp(buf + i, needle, nl) == 0) return 1;
    return 0;
}

// Screen turned on -> driver has (or is about to) clear the arm on resume.
static int is_screen_on_uevent(const char *buf, int len) {
    // Match the panel/lcd resume notification. Match "_on" forms only so the
    // screen-off uevent ("screen_off"/"lcd_off") never triggers a re-arm.
    return has_substr(buf, len, "screen_on") || has_substr(buf, len, "lcd_on")
        || has_substr(buf, len, "lcd=on") || has_substr(buf, len, "POWER=on");
}

int main(void) {
    ALOGI("dt2w_uewake starting");

    int ufd = uinput_setup_wake();
    if (ufd < 0) return 1;
    int nls = netlink_open();
    if (nls < 0) return 1;

    arm_gesture("startup");

    struct pollfd pfd = { .fd = nls, .events = POLLIN };
    char buf[4096];
    for (;;) {
        int pr = poll(&pfd, 1, REARM_TIMEOUT_MS);
        if (pr < 0) {
            if (errno == EINTR) continue;
            ALOGE("poll: %s", strerror(errno));
            continue;
        }
        if (pr == 0) {
            // Safety net (only runs while awake): re-arm. The init action is a
            // plain write, so an unconditional request is cheap and idempotent.
            arm_gesture("rearm-timeout");
            continue;
        }
        if (!(pfd.revents & POLLIN)) continue;

        ssize_t len = recv(nls, buf, sizeof(buf) - 1, 0);
        if (len <= 0) {
            if (errno == EINTR) continue;
            ALOGE("recv: %s", strerror(errno));
            continue;
        }
        buf[len] = '\0';

        // Gesture wake: inject KEY_WAKEUP, then re-arm (the screen is coming on,
        // and the upcoming resume would otherwise clear the arm).
        if (has_token(buf, (int)len, "double_tap=true")) {
            inject_wakeup(ufd);
            arm_gesture("post-wake");
            continue;
        }
        // Any other screen-on (e.g. power-button wake): re-arm for the next sleep.
        if (is_screen_on_uevent(buf, (int)len)) {
            arm_gesture("screen-on");
        }
    }
    return 0;
}
