package com.ugk.pi.terminal.runtime

/**
 * Native-only process-group signaling for the session created by
 * libugk_session_launcher.so. Android's public Java process API does not
 * expose a minSdk-24-compatible way to signal a negative POSIX process group.
 */
internal object NativeProcessGroupControl {
    private val nativeAvailable: Boolean by lazy {
        runCatching { System.loadLibrary("ugk_terminal_native") }.isSuccess
    }

    fun signalProcessGroup(processGroupId: Int, signalNumber: Int): Boolean {
        if (processGroupId <= 0 || signalNumber <= 0 || !nativeAvailable) return false
        return nativeSignalProcessGroup(processGroupId, signalNumber)
    }

    fun processGroupExists(processGroupId: Int): Boolean {
        if (processGroupId <= 0 || !nativeAvailable) return false
        return nativeProcessGroupExists(processGroupId)
    }

    private external fun nativeSignalProcessGroup(processGroupId: Int, signalNumber: Int): Boolean
    private external fun nativeProcessGroupExists(processGroupId: Int): Boolean
}
