package com.nimo.bookkeeping.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 日期工具。重点守住计划 9.2「月份边界、闰年、时区变化不导致交易日期错一天」。
 */
class DatesTest {

    @Test
    fun today_isIsoDate() {
        assertTrue(Dates.isValidIsoDate(Dates.today()))
    }

    // ---- DatePicker 的 UTC 毫秒 ↔ ISO 日期 ----

    @Test
    fun utcMillisRoundTrip_acrossTheDayIsStable() {
        listOf("2026-01-01", "2026-10-12", "2028-02-29", "2026-12-31").forEach { iso ->
            val millis = Dates.utcMillisFromIsoDate(iso)
            assertEquals(iso, Dates.isoDateFromUtcMillis(millis!!))
        }
    }

    @Test
    fun utcMillis_isMidnightUtcOfThatDay() {
        val millis = Dates.utcMillisFromIsoDate("2026-10-12")!!
        val utc = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
        assertEquals(0, utc.hour)
        assertEquals(0, utc.minute)
        assertEquals(12, utc.dayOfMonth)
        assertEquals(LocalDate.of(2026, 10, 12), utc.toLocalDate())
    }

    @Test
    fun utcMillisConverter_ignoresSystemTimeZone() {
        // 选中的那一刻在 UTC 上仍是当天 00:00，本地时区无论正负都不能把日期挪走
        val millis = Dates.utcMillisFromIsoDate("2026-10-12")!!
        assertEquals("2026-10-12", Dates.isoDateFromUtcMillis(millis))
        // 当天最后一毫秒也必须还原成同一天（不是"错一天"）
        assertEquals("2026-10-12", Dates.isoDateFromUtcMillis(millis + 86_399_999L))
    }

    @Test
    fun utcMillisFromIsoDate_rejectsMalformedInput() {
        assertNull(Dates.utcMillisFromIsoDate("2026-02-30"))
        assertNull(Dates.utcMillisFromIsoDate("2026/10/12"))
        assertNull(Dates.utcMillisFromIsoDate(""))
        assertNull(Dates.utcMillisFromIsoDate("abc"))
    }

    // ---- 边界日期本身 ----

    @Test
    fun leapDay_isValidOnlyInLeapYears() {
        assertTrue(Dates.isValidIsoDate("2028-02-29"))
        assertEquals(false, Dates.isValidIsoDate("2026-02-29"))
    }

    @Test
    fun monthBounds_coversWholeMonthHalfOpen() {
        val (from, to) = Dates.monthBounds(2026, 12)
        assertEquals("2026-12-01", from)
        assertEquals("2027-01-01", to)
    }
}
