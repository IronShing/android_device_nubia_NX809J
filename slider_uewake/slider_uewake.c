/*
 * slider_uewake — NX809J "Magic Slider" bridge.
 *
 * The physical slider is an input SWITCH, not a key: the gpio-keys_nubia driver reports it as
 * EV_SW / SW_PEN_INSERTED (the pen-insert switch code is repurposed for the game slider), value
 * 1 = slid towards "game", 0 = slid back.
 *
 * On stock RedMagicOS the event is consumed by ZTE's SlideKeysCtrl, which lives inside their
 * system server. An AOSP-based ROM has no such component, so nothing reacts and the slider appears
 * dead (reported on XDA). This daemon restores the missing consumer: it watches the switch and
 * publishes the state as a property, so a normal system app (RedMagicControl) can act on it
 * without needing root or direct input access.
 *
 * Deliberately minimal, mirroring dt2w_uewake / fp_uewake: this is a coredomain daemon that does
 * ONLY platform work (read an input device, set a system_internal_prop). It launches nothing
 * itself — policy belongs in the app that owns the UI.
 *
 * Published properties:
 *   sys.rm.slider.state   "1" / "0"  — current slider position, updated on every transition
 *   sys.rm.slider.event   monotonically increasing counter, so an observer can distinguish a
 *                         genuine re-toggle from a re-read of the same state
 *
 * Push channel (added after the screen-off torch bug): properties are passive — nothing wakes a
 * reader when one changes, and RedMagicControl had to poll them. With the screen off the SoC
 * is suspended; the slider GPIO is a wakeup source, so the kernel and this daemon see the slide,
 * but the SoC is back asleep before the app's next poll tick, and the action fired only on the
 * next unrelated wakeup (the user tapping the screen). Two things fix that:
 *   1. every slide takes a short kernel wake lock (/sys/power/wake_lock, timed), so the SoC
 *      stays up long enough for the consumer to act;
 *   2. the event is pushed down an init-owned socket (/dev/socket/slider_uewake): one line
 *      "<state> <seq>\n" per slide to every connected client. A blocked read() is woken by the
 *      kernel the instant we write, so the app needs no poll at all. The properties stay as
 *      the resync source (a client reads them when it connects).
 *
 * NOTE on the device node: the hardware reference doc says /dev/input/event1, which is WRONG on
 * this device — event1 is the shoulder-trigger SAR sensor, and the slider is event3. Node numbers
 * are not stable across kernels/boots anyway, so we scan by device name instead of hardcoding.
 */

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include <cutils/sockets.h>

#define LOG_TAG "slider_uewake"
#include <log/log.h>

#define DEV_NAME   "gpio-keys_nubia"
#define SLIDER_SW  SW_PEN_INSERTED      /* 0x0f — repurposed for the game slider */

#define PROP_STATE "sys.rm.slider.state"
#define PROP_MODE   "persist.sys.rm.slider.mode"
#define PROP_KEY_ON  "persist.sys.rm.slider.key_on"
#define PROP_KEY_OFF "persist.sys.rm.slider.key_off"

/* Must match SliderWatcher.MODE_KEYCODE. Every other mode is handled in RedMagicControl;
 * this one lives here because only a real input device is visible to key remappers. */
#define MODE_KEYCODE 4
#define PROP_EVENT "sys.rm.slider.event"

/* Name in the init .rc "socket" line; init creates /dev/socket/slider_uewake and hands us the
 * listening fd through the environment. */
#define CTL_SOCKET "slider_uewake"
#define MAX_CLIENTS 4

/* How long the SoC is kept awake after a slide, in ns. The app grabs its own wake lock as soon
 * as it reads the event, so this only has to cover scheduling + one socket round trip; 2 s is
 * generous. A timed lock needs no matching unlock, so a daemon crash cannot pin the SoC awake. */
#define WAKE_LOCK_NS "2000000000"

static int test_bit(const unsigned long *arr, int bit) {
    return (arr[bit / (8 * sizeof(long))] >> (bit % (8 * sizeof(long)))) & 1;
}

/* Find the slider by NAME, and only accept it if it really carries our switch. */
static int open_slider(void) {
    DIR *d = opendir("/dev/input");
    if (!d) {
        ALOGE("opendir /dev/input: %s", strerror(errno));
        return -1;
    }
    struct dirent *e;
    int fd = -1;
    while ((e = readdir(d)) != NULL) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        char path[64];
        snprintf(path, sizeof(path), "/dev/input/%s", e->d_name);
        int f = open(path, O_RDONLY);
        if (f < 0) continue;

        char name[128] = {0};
        unsigned long swbits[(SW_MAX / (8 * sizeof(long))) + 1];
        memset(swbits, 0, sizeof(swbits));
        if (ioctl(f, EVIOCGNAME(sizeof(name) - 1), name) >= 0 &&
            strcmp(name, DEV_NAME) == 0 &&
            ioctl(f, EVIOCGBIT(EV_SW, sizeof(swbits)), swbits) >= 0 &&
            test_bit(swbits, SLIDER_SW)) {
            ALOGI("slider found: %s (%s)", path, name);
            fd = f;
            break;
        }
        close(f);
    }
    closedir(d);
    if (fd < 0) ALOGE("no input device named '%s' exposing SW 0x%x", DEV_NAME, SLIDER_SW);
    return fd;
}

/* --- uinput: configurable key injector -------------------------------------
 *
 * XDA #337: the slider is an EV_SW switch, so it carries no keycode and remapper apps have
 * nothing to bind to. Injecting through /dev/uinput creates a real input device, so the press
 * travels the normal EventHub -> InputReader -> InputDispatcher path and is visible both to
 * apps reading /dev/input and (via Generic.kl) as an ordinary Android keycode.
 *
 * The keycodes are user-configurable, but uinput requires every emittable code to be declared
 * with UI_SET_KEYBIT *before* UI_DEV_CREATE. So the device is (re)created whenever the
 * configured pair changes -- which is only when the user edits the setting.
 */
static int g_ufd = -1;
static int g_on = -1, g_off = -1;

static int prop_int(const char *name, int def) {
    char v[PROP_VALUE_MAX];
    if (__system_property_get(name, v) <= 0) return def;
    char *end = NULL;
    long n = strtol(v, &end, 10);
    if (end == v || n < 0 || n > KEY_MAX) return def;
    return (int)n;
}

static void uinput_close(void) {
    if (g_ufd >= 0) { ioctl(g_ufd, UI_DEV_DESTROY); close(g_ufd); g_ufd = -1; }
    g_on = g_off = -1;
}

static int uinput_open(int on_code, int off_code) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) { ALOGE("open /dev/uinput: %s", strerror(errno)); return -1; }
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    if (on_code  > 0) ioctl(fd, UI_SET_KEYBIT, on_code);
    if (off_code > 0) ioctl(fd, UI_SET_KEYBIT, off_code);
    struct uinput_user_dev uud;
    memset(&uud, 0, sizeof(uud));
    snprintf(uud.name, UINPUT_MAX_NAME_SIZE, "slider_uewake");
    uud.id.bustype = BUS_VIRTUAL; uud.id.vendor = 0x6770; uud.id.product = 0x0004; uud.id.version = 1;
    if (write(fd, &uud, sizeof(uud)) < 0) { ALOGE("uinput write: %s", strerror(errno)); close(fd); return -1; }
    if (ioctl(fd, UI_DEV_CREATE) < 0) { ALOGE("UI_DEV_CREATE: %s", strerror(errno)); close(fd); return -1; }
    ALOGI("uinput slider_uewake created (on=%d off=%d)", on_code, off_code);
    return fd;
}

static void emit(int fd, int type, int code, int value) {
    struct input_event e;
    memset(&e, 0, sizeof(e));
    e.type = type; e.code = code; e.value = value;
    if (write(fd, &e, sizeof(e)) < 0) ALOGE("uinput emit: %s", strerror(errno));
}

/* Fire the keycode configured for this slider position. No-op unless the user selected the
 * key-code action, and a code of 0 means "nothing for this direction". */
static void inject_keycode(int state) {
    if (prop_int(PROP_MODE, 0) != MODE_KEYCODE) { uinput_close(); return; }

    int on_code  = prop_int(PROP_KEY_ON, 0);
    int off_code = prop_int(PROP_KEY_OFF, 0);
    if (on_code <= 0 && off_code <= 0) { uinput_close(); return; }

    if (g_ufd < 0 || on_code != g_on || off_code != g_off) {
        uinput_close();
        g_ufd = uinput_open(on_code, off_code);
        if (g_ufd < 0) return;
        g_on = on_code; g_off = off_code;
    }

    int code = state ? on_code : off_code;
    if (code <= 0) return;                 /* this direction is deliberately unbound */
    emit(g_ufd, EV_KEY, code, 1); emit(g_ufd, EV_SYN, SYN_REPORT, 0);
    emit(g_ufd, EV_KEY, code, 0); emit(g_ufd, EV_SYN, SYN_REPORT, 0);
    ALOGI("slider -> keycode %d (state %d)", code, state ? 1 : 0);
}

/* Timed kernel wake lock. Needs CAP_BLOCK_SUSPEND (rc: capabilities BLOCK_SUSPEND) and
 * sepolicy wakelock_use(). Logged once on failure, not per slide. */
static void hold_awake(void) {
    static int warned;
    int fd = open("/sys/power/wake_lock", O_WRONLY | O_CLOEXEC);
    if (fd < 0 || write(fd, "slider_uewake " WAKE_LOCK_NS, sizeof("slider_uewake " WAKE_LOCK_NS) - 1) < 0) {
        if (!warned) { warned = 1; ALOGE("wake_lock: %s (screen-off slides may be delayed)", strerror(errno)); }
    }
    if (fd >= 0) close(fd);
}

static int g_clients[MAX_CLIENTS];

static void client_drop(int i) {
    close(g_clients[i]);
    g_clients[i] = -1;
}

/* One line per slide to everyone connected. A client that cannot take it (gone, or wedged with
 * a full buffer -- the sockets are non-blocking) is dropped; it reconnects and resyncs from the
 * properties. */
static void notify_clients(int state, unsigned long seq) {
    char line[48];
    int n = snprintf(line, sizeof(line), "%d %lu\n", state ? 1 : 0, seq);
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (g_clients[i] < 0) continue;
        if (send(g_clients[i], line, n, MSG_NOSIGNAL | MSG_DONTWAIT) != n) {
            ALOGW("client %d dropped: %s", i, strerror(errno));
            client_drop(i);
        }
    }
}

static void publish(int state, unsigned long *seq) {
    char buf[32];
    hold_awake();
    snprintf(buf, sizeof(buf), "%d", state ? 1 : 0);
    __system_property_set(PROP_STATE, buf);
    snprintf(buf, sizeof(buf), "%lu", ++(*seq));
    __system_property_set(PROP_EVENT, buf);
    notify_clients(state, *seq);
    ALOGI("slider -> %d (seq %lu)", state ? 1 : 0, *seq);
}

static void accept_client(int lfd) {
    int c = accept4(lfd, NULL, NULL, SOCK_NONBLOCK | SOCK_CLOEXEC);
    if (c < 0) { ALOGE("accept: %s", strerror(errno)); return; }
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (g_clients[i] < 0) { g_clients[i] = c; ALOGI("client %d connected", i); return; }
    }
    ALOGW("too many clients, refusing one");
    close(c);
}

int main(void) {
    unsigned long seq = 0;
    int last = -1;   /* last PUBLISHED state; -1 = nothing published yet */
    int fd = -1;
    for (int i = 0; i < MAX_CLIENTS; i++) g_clients[i] = -1;

    /* Listening socket created by init (rc "socket" line). Optional: without it the daemon
     * still publishes properties, the app just falls back to its slow poll. */
    int lfd = android_get_control_socket(CTL_SOCKET);
    if (lfd < 0 || listen(lfd, MAX_CLIENTS) < 0) {
        ALOGE("control socket '%s' unavailable: %s", CTL_SOCKET, strerror(errno));
        if (lfd >= 0) close(lfd);
        lfd = -1;
    }

    for (;;) {
        if (fd < 0) {
            fd = open_slider();
            if (fd < 0) { sleep(5); continue; }   /* driver not up yet — keep trying */

            /* Publish the CURRENT position at startup so an observer is never out of sync
             * after a reboot or a daemon restart. EVIOCGSW reports live switch state. */
            unsigned long swstate[(SW_MAX / (8 * sizeof(long))) + 1];
            memset(swstate, 0, sizeof(swstate));
            if (ioctl(fd, EVIOCGSW(sizeof(swstate)), swstate) >= 0) {
                last = test_bit(swstate, SLIDER_SW) ? 1 : 0;
                publish(last, &seq);
            }
        }

        struct pollfd pfd[2 + MAX_CLIENTS];
        int np = 0;
        pfd[np++] = (struct pollfd){ .fd = fd, .events = POLLIN };
        if (lfd >= 0) pfd[np++] = (struct pollfd){ .fd = lfd, .events = POLLIN };
        int cidx[MAX_CLIENTS], nc = 0;
        for (int i = 0; i < MAX_CLIENTS; i++) {
            if (g_clients[i] < 0) continue;
            /* clients never send anything; readiness here means hangup */
            cidx[nc++] = i;
            pfd[np++] = (struct pollfd){ .fd = g_clients[i], .events = POLLIN | POLLHUP };
        }

        if (poll(pfd, np, -1) < 0) {
            if (errno == EINTR) continue;
            ALOGE("poll: %s", strerror(errno));
            sleep(1);
            continue;
        }

        int pi = 0;
        if (pfd[pi++].revents) {
            struct input_event ev;
            ssize_t n = read(fd, &ev, sizeof(ev));
            if (n == (ssize_t)sizeof(ev)) {
                /* The driver re-reports the switch several times per physical slide (measured on
                 * hardware: 2-3 EV_SW events for one movement). Publish only on an actual CHANGE,
                 * otherwise a consumer that acts on sys.rm.slider.event would fire its action
                 * two or three times for a single slide. */
                if (ev.type == EV_SW && ev.code == SLIDER_SW) {
                    int v = ev.value ? 1 : 0;
                    /* inject only on a genuine slide, never on the startup resync above, or the
                     * phone would emit a phantom key every boot and after every daemon restart. */
                    if (v != last) { last = v; publish(v, &seq); inject_keycode(v); }
                }
            } else if (n < 0 && errno != EINTR && errno != EAGAIN) {
                ALOGE("read: %s — reopening", strerror(errno));
                close(fd);
                fd = -1;
                sleep(1);
                continue;
            }
        }
        if (lfd >= 0 && pfd[pi++].revents) accept_client(lfd);
        for (int k = 0; k < nc; k++) {
            if (!pfd[pi + k].revents) continue;
            char junk[16];
            ssize_t r = recv(g_clients[cidx[k]], junk, sizeof(junk), MSG_DONTWAIT);
            if (r <= 0 && !(r < 0 && errno == EAGAIN)) {
                ALOGI("client %d disconnected", cidx[k]);
                client_drop(cidx[k]);
            }
        }
    }
    return 0;
}
