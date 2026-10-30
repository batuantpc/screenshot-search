package com.screenshotsearch.app.media

import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFailureTest {
    @Test
    fun `missing probe is permanent unreadable failure`() {
        val cause = FileNotFoundException("gone")

        val failure = MediaFailure.fromProbe(cause, MediaFailure.Companion.Probe.MISSING)

        assertTrue(failure is MediaFailure.Unreadable)
        assertTrue(failure.isPermanent)
        assertEquals("unreadable", failure.kind)
        assertSame(cause, failure.cause)
    }

    @Test
    fun `denied probe is permanent access failure`() {
        val failure = MediaFailure.fromProbe(
            SecurityException("denied"),
            MediaFailure.Companion.Probe.DENIED,
        )

        assertTrue(failure is MediaFailure.AccessDenied)
        assertTrue(failure.isPermanent)
        assertEquals("access_denied", failure.kind)
    }

    @Test
    fun `readable and unknown probes remain bounded retry candidates`() {
        listOf(
            MediaFailure.Companion.Probe.READABLE,
            MediaFailure.Companion.Probe.UNKNOWN,
        ).forEach { probe ->
            val failure = MediaFailure.fromProbe(IOException("temporary"), probe)

            assertTrue(failure is MediaFailure.Transient)
            assertFalse(failure.isPermanent)
            assertEquals("transient", failure.kind)
        }
    }
}
