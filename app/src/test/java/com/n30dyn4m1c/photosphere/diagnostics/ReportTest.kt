package com.n30dyn4m1c.photosphere.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTest {

    @Test
    fun `account names are redacted case-insensitively`() {
        val out = Report.redact("Opened by Dani Pop and dani pop", listOf("Dani Pop"))
        assertFalse(out.contains("Dani"))
        assertTrue(out.contains("<account>"))
    }

    @Test
    fun `short names are left alone`() {
        assertTrue(Report.redact("Frame 12 captured", listOf("Al")).contains("Al"))
    }

    @Test
    fun `emails and tokens are redacted`() {
        val out = Report.redact(
            "Mail me at dani@example.com with bearer abcDEF123456 and access_token=xyz987654321",
            emptyList(),
        )
        assertTrue(out.contains("<email>"))
        assertTrue(out.contains("Bearer <token>"))
        assertTrue(out.contains("access_token=<token>"))
        assertFalse(out.contains("dani@example.com"))
    }
}
