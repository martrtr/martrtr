#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define TOUCH_DEV_PATH "/dev/xiaomi-touch"
#define TOUCH_ID 0
#define SET_CUR_VALUE 0
#define GET_CUR_VALUE 1
#define TOUCH_MAGIC 't'
#define TOUCH_IOC_SET_CUR_VALUE _IO(TOUCH_MAGIC, SET_CUR_VALUE)
#define TOUCH_IOC_GET_CUR_VALUE _IO(TOUCH_MAGIC, GET_CUR_VALUE)

static int open_touch(void) {
    return open(TOUCH_DEV_PATH, O_RDWR | O_CLOEXEC);
}

static int set_mode(int mode, int value) {
    int fd = open_touch();
    if (fd < 0) return -1;
    int arg[3] = {TOUCH_ID, mode, value};
    int rc = ioctl(fd, TOUCH_IOC_SET_CUR_VALUE, &arg);
    int saved = errno;
    close(fd);
    errno = saved;
    return rc;
}

static int get_mode(int mode, int *value) {
    int fd = open_touch();
    if (fd < 0) return -1;
    int arg[3] = {TOUCH_ID, mode, 0};
    int rc = ioctl(fd, TOUCH_IOC_GET_CUR_VALUE, &arg);
    int saved = errno;
    close(fd);
    errno = saved;
    if (rc == 0 && value) *value = arg[0];
    return rc;
}

int main(int argc, char **argv) {
    if (argc == 3 && strcmp(argv[1], "get") == 0) {
        int mode = atoi(argv[2]);
        int value = 0;
        if (get_mode(mode, &value) != 0) return 2;
        printf("%d\n", value);
        return 0;
    }
    if (argc == 4 && strcmp(argv[1], "set") == 0) {
        int mode = atoi(argv[2]);
        int value = atoi(argv[3]);
        if (set_mode(mode, value) != 0) return 3;
        return 0;
    }
    fprintf(stderr, "usage: %s get MODE | set MODE VALUE\n", argv[0]);
    return 1;
}
