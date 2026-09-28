package com.ugk.pi.android.testapp

/** Only an event in the current uninterrupted app segment may receive a post frame. */
internal class DemoOperationPostEvidence {
    private var eventId: Int? = null
    private var packageName: String? = null

    fun recordEvent(id: Int, packageName: String, isPageNotification: Boolean = false) {
        // A window transition can settle a preceding action. It must not steal
        // that action's eventual stable observation. Another real action does.
        if (isPageNotification && pendingFor(packageName) != null) return
        eventId = id
        this.packageName = packageName
    }

    fun observePackage(packageName: String) {
        if (this.packageName != packageName) invalidate()
    }

    fun pendingFor(packageName: String): Int? = eventId.takeIf { this.packageName == packageName }

    fun complete(id: Int?, packageName: String): Boolean {
        if (id == null || pendingFor(packageName) != id) return false
        invalidate()
        return true
    }

    fun invalidate() { eventId = null; packageName = null }
}
