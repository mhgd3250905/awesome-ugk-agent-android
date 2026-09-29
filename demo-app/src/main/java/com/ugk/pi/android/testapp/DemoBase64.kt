@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.ugk.pi.android.testapp

import kotlin.io.encoding.Base64

/** API-level independent, unwrapped RFC 4648 encoding for model image payloads. */
internal object DemoBase64 {
    fun encode(bytes: ByteArray): String = Base64.Default.encode(bytes)
}
