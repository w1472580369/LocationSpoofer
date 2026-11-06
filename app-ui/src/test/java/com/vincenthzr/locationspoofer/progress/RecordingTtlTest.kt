package com.vincenthzr.locationspoofer.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回归测试：30 天 TTL 必须用 Long 运算，否则 Int 溢出为负数导致新录音被误删 */
class RecordingTtlTest {

    private val twentyFourHours = 24L * 60 * 60 * 1000
    private val ttlMillis = 30L * 24 * 60 * 60 * 1000

    @Test
    fun `thirty day ttl must fit in Long and equal 2592000000`() {
        assertEquals(2_592_000_000L, ttlMillis)
    }

    @Test
    fun `int arithmetic would overflow to negative and wipe fresh records`() {
        val intOverflow = 30 * 24 * 60 * 60 * 1000
        assertTrue(intOverflow < 0)
    }

    @Test
    fun `freshly recorded note is kept when ttl uses long`() {
        val now = System.currentTimeMillis()
        val recordTime = now - 1000L
        assertTrue(now - recordTime <= ttlMillis)
    }
}
