package com.ugk.pi.android

import org.junit.Assert.assertEquals
import org.junit.Test

class JavaNetHttpTransportTest {
    @Test
    fun storesConnectAndReadTimeouts() {
        val transport = JavaNetHttpTransport(
            connectTimeoutMillis = 1234,
            readTimeoutMillis = 5678
        )

        assertEquals(1234, transport.connectTimeoutMillis)
        assertEquals(5678, transport.readTimeoutMillis)
    }
}
