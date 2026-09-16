#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <unistd.h>

#define MAX_SLOTS 64

typedef struct {
    int active;
    int changed;
    int just_down;
    int just_up;
    int x, y, pressure;
} Slot;

static int has_abs_bit(const unsigned long *bits, int code) {
    int word = code / (8 * (int)sizeof(unsigned long));
    int bit = code % (8 * (int)sizeof(unsigned long));
    return (bits[word] >> bit) & 1UL;
}

static int choose_touch(char *path, size_t path_sz,
                        struct input_absinfo *ax, struct input_absinfo *ay,
                        struct input_absinfo *ap, struct input_absinfo *aslot) {
    int best_fd = -1, best_score = -1;
    char best_path[128] = {0};
    struct input_absinfo bax = {0}, bay = {0}, bap = {0}, bslot = {0};

    for (int i = 0; i < 40; i++) {
        char p[64];
        snprintf(p, sizeof(p), "/dev/input/event%d", i);
        int fd = open(p, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
        if (fd < 0) continue;

        unsigned long absbits[(ABS_MAX + 8 * sizeof(unsigned long)) / (8 * sizeof(unsigned long))];
        memset(absbits, 0, sizeof(absbits));
        if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits) < 0 ||
            !has_abs_bit(absbits, ABS_MT_POSITION_X) ||
            !has_abs_bit(absbits, ABS_MT_POSITION_Y) ||
            !has_abs_bit(absbits, ABS_MT_SLOT)) {
            close(fd); continue;
        }

        struct input_absinfo tx, ty, ts, tp = {0};
        if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &tx) < 0 ||
            ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), &ty) < 0 ||
            ioctl(fd, EVIOCGABS(ABS_MT_SLOT), &ts) < 0) {
            close(fd); continue;
        }
        if (has_abs_bit(absbits, ABS_MT_PRESSURE))
            ioctl(fd, EVIOCGABS(ABS_MT_PRESSURE), &tp);

        char name[128] = {0};
        ioctl(fd, EVIOCGNAME(sizeof(name)), name);
        int score = 10;
        if (strcasestr(name, "touch")) score += 20;
        if (strcasestr(name, "fts")) score += 15;
        if (strcasestr(name, "goodix")) score += 15;
        if (strcasestr(name, "synapt")) score += 15;
        if (ts.maximum >= 9) score += 10;
        long xr = (long)tx.maximum - tx.minimum;
        long yr = (long)ty.maximum - ty.minimum;
        if (xr > 500 && yr > 1000) score += 10;

        if (score > best_score) {
            if (best_fd >= 0) close(best_fd);
            best_fd = fd; best_score = score;
            strncpy(best_path, p, sizeof(best_path)-1);
            bax = tx; bay = ty; bap = tp; bslot = ts;
        } else close(fd);
    }

    if (best_fd < 0) return -1;
    strncpy(path, best_path, path_sz-1);
    *ax = bax; *ay = bay; *ap = bap; *aslot = bslot;
    return best_fd;
}

static float normv(int v, const struct input_absinfo *a) {
    int d = a->maximum - a->minimum;
    if (d <= 0) return 0.f;
    float n = (float)(v - a->minimum) / (float)d;
    if (n < 0.f) n = 0.f; if (n > 1.f) n = 1.f;
    return n;
}

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    pid_t app_pid = (pid_t)atoi(argv[1]);
    if (app_pid <= 1) return 3;

    prctl(PR_SET_PDEATHSIG, SIGTERM);
    signal(SIGPIPE, SIG_DFL);

    char path[128];
    struct input_absinfo ax, ay, ap, aslot;
    int fd = choose_touch(path, sizeof(path), &ax, &ay, &ap, &aslot);
    if (fd < 0) { printf("ERR no-touch\n"); fflush(stdout); return 4; }

    // Exclusive grab is the whole point: Android's palm / gesture pipeline no longer gets to
    // cancel the toe because of a large foot contact. Volume/power keys use other input devices.
    if (ioctl(fd, EVIOCGRAB, 1) < 0) {
        printf("ERR grab-%d\n", errno); fflush(stdout); close(fd); return 5;
    }

    int max_slot = aslot.maximum + 1;
    if (max_slot < 1) max_slot = 10;
    if (max_slot > MAX_SLOTS) max_slot = MAX_SLOTS;
    Slot slots[MAX_SLOTS]; memset(slots, 0, sizeof(slots));
    int cur = 0;

    printf("READY\n"); fflush(stdout);

    struct pollfd pfd = { .fd = fd, .events = POLLIN };
    while (1) {
        char proc[64]; snprintf(proc, sizeof(proc), "/proc/%d", app_pid);
        if (access(proc, F_OK) != 0) break;

        int pr = poll(&pfd, 1, 250);
        if (pr < 0) { if (errno == EINTR) continue; break; }
        if (pr == 0) continue;

        struct input_event ev[64];
        ssize_t n = read(fd, ev, sizeof(ev));
        if (n <= 0) { if (errno == EAGAIN || errno == EINTR) continue; break; }
        int count = (int)(n / sizeof(struct input_event));
        for (int i = 0; i < count; i++) {
            struct input_event *e = &ev[i];
            if (e->type == EV_ABS) {
                if (e->code == ABS_MT_SLOT) {
                    cur = e->value;
                    if (cur < 0) cur = 0; if (cur >= max_slot) cur = max_slot-1;
                } else if (e->code == ABS_MT_TRACKING_ID) {
                    Slot *s = &slots[cur];
                    if (e->value < 0) { if (s->active) { s->active = 0; s->just_up = 1; s->changed = 1; } }
                    else { s->active = 1; s->just_down = 1; s->just_up = 0; s->changed = 1; }
                } else if (e->code == ABS_MT_POSITION_X) { slots[cur].x = e->value; slots[cur].changed = 1; }
                else if (e->code == ABS_MT_POSITION_Y) { slots[cur].y = e->value; slots[cur].changed = 1; }
                else if (e->code == ABS_MT_PRESSURE) { slots[cur].pressure = e->value; slots[cur].changed = 1; }
            } else if (e->type == EV_SYN && e->code == SYN_REPORT) {
                for (int sidx = 0; sidx < max_slot; sidx++) {
                    Slot *s = &slots[sidx];
                    if (!s->changed) continue;
                    float x = normv(s->x, &ax), y = normv(s->y, &ay);
                    float p = 1.f;
                    if (ap.maximum > ap.minimum) p = normv(s->pressure, &ap);
                    if (p < .05f) p = .05f;
                    char k = s->just_up ? 'U' : (s->just_down ? 'D' : 'M');
                    printf("%c %d %.7f %.7f %.4f\n", k, sidx, x, y, p);
                    s->changed = 0; s->just_down = 0; s->just_up = 0;
                }
                fflush(stdout);
            }
        }
    }

    ioctl(fd, EVIOCGRAB, 0);
    close(fd);
    return 0;
}
