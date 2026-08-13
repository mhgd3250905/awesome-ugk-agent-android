#include <errno.h>
#include <jni.h>
#include <signal.h>
#include <sys/types.h>

JNIEXPORT jboolean JNICALL
Java_com_ugk_pi_terminal_runtime_NativeProcessGroupControl_nativeSignalProcessGroup(
    JNIEnv *environment,
    jobject receiver,
    jint process_group_id,
    jint signal_number
) {
    (void)environment;
    (void)receiver;

    if (process_group_id <= 0 || signal_number <= 0) {
        return JNI_FALSE;
    }

    // POSIX kill with a negative pid targets one process group. ESRCH means
    // the group has already exited, which is equivalent to successful cleanup.
    if (kill(-(pid_t)process_group_id, signal_number) == 0 || errno == ESRCH) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_ugk_pi_terminal_runtime_NativeProcessGroupControl_nativeProcessGroupExists(
    JNIEnv *environment,
    jobject receiver,
    jint process_group_id
) {
    (void)environment;
    (void)receiver;

    if (process_group_id <= 0) {
        return JNI_FALSE;
    }

    // Signal 0 performs existence/permission checks without delivering a
    // signal. EPERM still proves that at least one process remains in the
    // group; ESRCH means the entire group is gone.
    if (kill(-(pid_t)process_group_id, 0) == 0 || errno == EPERM) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}
