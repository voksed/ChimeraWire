package com.carnelia.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.random.Random

class BypassProbeTest {

    @Test
    fun splitChunksCoversWholeRecordWithinRange() {
        repeat(50) {
            val chunks = BypassProbe.splitChunks(517, 5..10, Random(it))
            assertEquals(517, chunks.sum())
            assertTrue(chunks.dropLast(1).all { c -> c in 5..10 })
        }
    }

    @Test
    fun splitChunksWithoutRangeIsPassThrough() {
        assertEquals(listOf(517), BypassProbe.splitChunks(517, null, Random))
    }

    @Test
    fun bogonDetectionFlagsSinkholes() {
        assertTrue(BypassProbe.isBogon("127.0.0.1"))
        assertTrue(BypassProbe.isBogon("0.0.0.0"))
        assertTrue(BypassProbe.isBogon("10.1.2.3"))
        assertTrue(BypassProbe.isBogon("192.168.0.1"))
        assertTrue(!BypassProbe.isBogon("142.250.74.14"))
    }

    /** Live check against the real network; run with BYPASS_LIVE=1. */
    @Test
    fun liveProbePrintsReport() {
        assumeTrue(System.getenv("BYPASS_LIVE") == "1")
        val report = BypassProbe.run { println("${it.host}: ${it.verdict} — ${it.detail}") }
        println("best strategy: ${report.best?.label ?: "none needed/found"}")
    }
}
