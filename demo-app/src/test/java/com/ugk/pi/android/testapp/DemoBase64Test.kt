package com.ugk.pi.android.testapp

import org.junit.Assert.assertEquals
import org.junit.Test

class DemoBase64Test {
    @Test fun encodesStandardBase64WithoutLineBreaks() {
        assertEquals("", DemoBase64.encode(byteArrayOf()))
        assertEquals("Zm9v", DemoBase64.encode("foo".toByteArray()))
        assertEquals("/wA=", DemoBase64.encode(byteArrayOf(0xff.toByte(), 0x00)))
    }
}
