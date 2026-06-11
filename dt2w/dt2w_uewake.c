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
// Gated on the LineageOS DT2W setting via persist.sys.dt2w.enabled (set by the
// init service from the secure setting). Off => gesture disarmed (no idle cost).

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <linux/netlink.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <sys/socket.h>
#include <sys/types.h>

#define LOG_TAG "dt2w_uewake"
#include <log/log.h>

static const char *WAKE_GESTURE_NODE = "/proc/touchscreen/wake_gesture";
static const char *ENABLED_PROP_NODE = "/sys/power/wake_lock"; // held briefly on fire

static int write_node(const char *path, const char *val) {
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t n = write(fd, val, strlen(val));
    close(fd);
    return (n < 0) ? -1 : 0;
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

int main(void) {
    ALOGI("dt2w_uewake starting");
    // Arm the driver's low-power double-tap gesture. (Disarm/rearm on the
    // DT2W toggle is handled by the init service writing this same node;
    // arming here ensures it's live whenever the daemon runs.)
    if (write_node(WAKE_GESTURE_NODE, "1") == 0)
        ALOGI("armed %s=1", WAKE_GESTURE_NODE);
    else
        ALOGE("arm %s failed: %s", WAKE_GESTURE_NODE, strerror(errno));

    int ufd = uinput_setup_wake();
    if (ufd < 0) return 1;
    int nls = netlink_open();
    if (nls < 0) return 1;

    char buf[4096];
    for (;;) {
        ssize_t len = recv(nls, buf, sizeof(buf) - 1, 0);
        if (len <= 0) {
            if (errno == EINTR) continue;
            ALOGE("recv: %s", strerror(errno));
            continue;
        }
        buf[len] = '\0';
        // Match the touch driver's gesture uevent. "double_tap=true" is unique
        // to the gesture path (ufp_report_gesture_uevent).
        if (has_token(buf, (int)len, "double_tap=true")) {
            inject_wakeup(ufd);
        }
    }
    return 0;
}
