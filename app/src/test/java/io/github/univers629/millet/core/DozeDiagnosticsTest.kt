package io.github.univers629.millet.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DozeDiagnosticsTest {

    @Before
    fun setUp() = DozeDiagnostics.reset()

    @After
    fun tearDown() = DozeDiagnostics.reset()

    @Test
    fun encodeKeepsAllTimestamps() {
        DozeDiagnostics.onAttempt(1_000L)
        DozeDiagnostics.onFailure(2_000L, "停在 state=1：Unable to go deep idle")
        DozeDiagnostics.onSuccess(3_000L)

        val snapshot = DozeDiagnostics.decode(DozeDiagnostics.encode())

        assertEquals(1_000L, snapshot?.attemptMillis)
        assertEquals(2_000L, snapshot?.failureMillis)
        assertEquals(3_000L, snapshot?.successMillis)
        assertEquals("停在 state=1：Unable to go deep idle", snapshot?.failureDetail)
    }

    @Test
    fun lastAttemptFailedComparesFailureAndSuccessTimes() {
        DozeDiagnostics.onFailure(2_000L, "拒绝")
        assertTrue(DozeDiagnostics.snapshot().lastAttemptFailed)

        DozeDiagnostics.onSuccess(3_000L)
        assertFalse(DozeDiagnostics.snapshot().lastAttemptFailed)
    }

    @Test
    fun failureAgeTextUsesCoarseBuckets() {
        val snapshot = DozeDiagnostics.Snapshot(
            attemptMillis = 0L,
            failureMillis = 1_000_000L,
            successMillis = 0L,
            failureDetail = "拒绝"
        )

        assertEquals("刚刚", snapshot.failureAgeText(1_030_000L))
        assertEquals("5 分钟前", snapshot.failureAgeText(1_300_000L))
        assertEquals("2 小时前", snapshot.failureAgeText(1_000_000L + 2 * 60 * 60 * 1000L))
        assertEquals("1 天前", snapshot.failureAgeText(1_000_000L + 25 * 60 * 60 * 1000L))
        assertNull(DozeDiagnostics.Snapshot(0L, 0L, 0L, "").failureAgeText(5_000L))
    }

    @Test
    fun decodeRejectsMissingOrBrokenPayload() {
        assertNull(DozeDiagnostics.decode(null))
        assertNull(DozeDiagnostics.decode(""))
        assertNull(DozeDiagnostics.decode("1|2"))
        assertNull(DozeDiagnostics.decode("a|b|c|d"))
    }

    @Test
    fun detailIsTruncatedAndSingleLine() {
        DozeDiagnostics.onFailure(10L, "x".repeat(400))

        val snapshot = DozeDiagnostics.snapshot()

        assertEquals(160, snapshot.failureDetail.length)
        assertFalse(snapshot.encode().contains('\n'))
    }
}
