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
 * NOTE on the device node: the hardware reference doc says /dev/input/event1, which is WRONG on
 * this device — event1 is the shoulder-trigger SAR sensor, and the slider is event3. Node numbers
 * are not stable across kernels/boots anyway, so we scan by device name instead of hardcoding.
 */

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <stdio.h>
#include <string.h>
#include <sys/system_properties.h>
#include <unistd.h>

#define LOG_TAG "slider_uewake"
#include <log/log.h>

#define DEV_NAME   "gpio-keys_nubia"
#define SLIDER_SW  SW_PEN_INSERTED      /* 0x0f — repurposed for the game slider */

#define PROP_STATE "sys.rm.slider.state"
#define PROP_EVENT "sys.rm.slider.event"

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

static void publish(int state, unsigned long *seq) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%d", state ? 1 : 0);
    __system_property_set(PROP_STATE, buf);
    snprintf(buf, sizeof(buf), "%lu", ++(*seq));
    __system_property_set(PROP_EVENT, buf);
    ALOGI("slider -> %d (seq %lu)", state ? 1 : 0, *seq);
}

int main(void) {
    unsigned long seq = 0;
    int last = -1;   /* last PUBLISHED state; -1 = nothing published yet */
    int fd = -1;

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

        struct input_event ev;
        ssize_t n = read(fd, &ev, sizeof(ev));
        if (n == (ssize_t)sizeof(ev)) {
            /* The driver re-reports the switch several times per physical slide (measured on
             * hardware: 2-3 EV_SW events for one movement). Publish only on an actual CHANGE,
             * otherwise a consumer that acts on sys.rm.slider.event would fire its action
             * two or three times for a single slide. */
            if (ev.type == EV_SW && ev.code == SLIDER_SW) {
                int v = ev.value ? 1 : 0;
                if (v != last) { last = v; publish(v, &seq); }
            }
        } else if (n < 0 && errno != EINTR) {
            ALOGE("read: %s — reopening", strerror(errno));
            close(fd);
            fd = -1;
            sleep(1);
        }
    }
    return 0;
}
