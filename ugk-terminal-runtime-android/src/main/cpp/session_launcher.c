#define _POSIX_C_SOURCE 200809L

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>

static const char *const SESSION_REPORT_ENV = "UGK_TERMINAL_SESSION_REPORT_FILE";

static int write_all(int file_descriptor, const char *buffer, size_t length) {
    size_t written = 0;
    while (written < length) {
        ssize_t result = write(file_descriptor, buffer + written, length - written);
        if (result > 0) {
            written += (size_t)result;
            continue;
        }
        if (result < 0 && errno == EINTR) {
            continue;
        }
        return -1;
    }
    return 0;
}

static int report_session_leader(void) {
    const char *path = getenv(SESSION_REPORT_ENV);
    if (path == NULL || path[0] == '\0') {
        fprintf(stderr, "Missing %s\n", SESSION_REPORT_ENV);
        return -1;
    }

    int file_descriptor = open(path, O_WRONLY | O_TRUNC | O_CLOEXEC);
    if (file_descriptor < 0) {
        perror("Unable to open terminal session report file");
        return -1;
    }

    char value[32];
    int value_length = snprintf(value, sizeof(value), "%ld\n", (long)getpid());
    int write_result = value_length > 0 && (size_t)value_length < sizeof(value)
        ? write_all(file_descriptor, value, (size_t)value_length)
        : -1;
    int close_result = close(file_descriptor);
    if (write_result != 0 || close_result != 0) {
        perror("Unable to write terminal session report file");
        return -1;
    }

    // The target shell must not inherit the private report path. It reports no
    // useful capability and keeping it hidden prevents a script from racing a
    // later process-group cancellation request.
    if (unsetenv(SESSION_REPORT_ENV) != 0) {
        perror("Unable to clear terminal session report environment");
        return -1;
    }
    return 0;
}

int main(int argc, char *argv[]) {
    if (argc < 2 || argv[1] == NULL || argv[1][0] == '\0') {
        fprintf(stderr, "Usage: %s <executable> [argument ...]\n", argv[0]);
        return 64;
    }

    // A ProcessBuilder child is not a process-group leader, so setsid creates
    // a dedicated session and process group whose ID is this executable's PID.
    // After exec, that identity is retained by Bash and its ordinary children.
    if (setsid() < 0) {
        perror("Unable to create terminal process session");
        return 126;
    }
    if (report_session_leader() != 0) {
        return 126;
    }

    execv(argv[1], &argv[1]);
    fprintf(stderr, "Unable to execute terminal payload %s: %s\n", argv[1], strerror(errno));
    return 127;
}
