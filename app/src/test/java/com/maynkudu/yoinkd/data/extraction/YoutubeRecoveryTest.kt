package com.maynkudu.yoinkd.data.extraction

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class YoutubeRecoveryTest {
    private val forbidden = NativeProcessException("Unable to download video data: HTTP Error 403: Forbidden")

    @Test fun aYoutubeMusicForbiddenRefreshesOnceAndRetriesTheOriginalPage() = runBlocking {
        var refreshes = 0
        var attempts = 0
        val result = recoverYoutubeForbidden("https://music.youtube.com/watch?v=sample", { refreshes++ },
            { attempts++; throw forbidden }, { attempts++; "downloaded" })
        assertEquals("downloaded", result)
        assertEquals(1, refreshes)
        assertEquals(2, attempts)
    }

    @Test fun aPersistentForbiddenStopsAfterOneRecovery() = runBlocking {
        var refreshes = 0
        var attempts = 0
        val failure = runCatching { recoverYoutubeForbidden("https://youtu.be/sample", { refreshes++ },
            { attempts++; throw forbidden }, { attempts++; throw forbidden }) }.exceptionOrNull()
        assertTrue(failure is NativeProcessException)
        assertTrue(failure!!.message!!.contains("still returned HTTP 403"))
        assertEquals(1, refreshes)
        assertEquals(2, attempts)
    }

    @Test fun unrelatedFailuresDoNotRefreshOrRetry() = runBlocking {
        for ((url, error) in listOf("https://example.com/media" to forbidden,
            "https://youtube.com.evil.test/watch?v=sample" to forbidden,
            "https://youtube.com/watch?v=sample" to NativeProcessException("HTTP Error 404: Not Found"))) {
            var refreshes = 0
            var retries = 0
            val failure = runCatching { recoverYoutubeForbidden(url, { refreshes++ }, { throw error }, { retries++ }) }.exceptionOrNull()
            assertSame(error, failure)
            assertEquals(0, refreshes)
            assertEquals(0, retries)
        }
    }

    @Test fun cancellationNeverStartsARecovery() = runBlocking {
        var refreshes = 0
        val failure = runCatching { recoverYoutubeForbidden("https://youtube.com/watch?v=sample", { refreshes++ },
            { throw CancellationException("paused") }, { "unexpected" }) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(0, refreshes)
    }
}
