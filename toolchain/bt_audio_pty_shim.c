/*
 * BT-Audio LOCAL TEST ONLY: PowerShell 7.1's System.IO.Ports tries to query
 * modem-control pins on a socat pseudo-terminal. Linux ptys have no such pins:
 * TIOCMGET/TIOCMSET return ENOTTY, and SerialPort.Open() aborts. This shim is
 * only for the older fallback PowerShell tested here, NOT the normal setup.
 *
 * Leave every successful ioctl, real serial port, and non-modem ioctl alone.
 * Only turn ENOTTY on a /dev/pts/ device's modem-pin query into "all pins off" (or a
 * no-op for a set). The caller sets DTR/RTS and Handshake to false/None. This
 * is NOT a substitute for testing a real COM port on Windows.
 *
 * Built as a shared library and LD_PRELOADed into the fallback pwsh process
 * only. This wrapper targets Linux x86_64's ioctl calling convention.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

int ioctl(int fd, unsigned long request, ...) {
    void *arg;
    va_list ap;
    va_start(ap, request);
    arg = va_arg(ap, void *);
    va_end(ap);

    static int (*next_ioctl)(int, unsigned long, ...) = NULL;
    if (!next_ioctl) next_ioctl = dlsym(RTLD_NEXT, "ioctl");
    if (!next_ioctl) { errno = ENOSYS; return -1; }

    int rc = next_ioctl(fd, request, arg);
    if (rc != -1 || errno != ENOTTY || !arg ||
        (request != TIOCMGET && request != TIOCMSET &&
         request != TIOCMBIC && request != TIOCMBIS)) return rc;

    int saved_errno = errno;
    char proc_fd[64], target[128];
    snprintf(proc_fd, sizeof(proc_fd), "/proc/self/fd/%d", fd);
    ssize_t size = readlink(proc_fd, target, sizeof(target) - 1);
    if (size > 0) {
        target[size] = '\0';
        if (strncmp(target, "/dev/pts/", 9) == 0) {
            if (request == TIOCMGET) *(int *)arg = 0;
            errno = 0;
            return 0;
        }
    }
    errno = saved_errno;
    return rc;
}
