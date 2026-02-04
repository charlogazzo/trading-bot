package com.foreshock.tradingbot.alpaca;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

public class AlpacaHourlyLoaderTest {

    @Test
    public void testLoadBarsThrowsOnInvalidTimeframe() {
        ZonedDateTime start = ZonedDateTime.of(LocalDateTime.of(2024, 1, 1, 0, 0), ZoneId.of("UTC"));
        ZonedDateTime end = ZonedDateTime.of(LocalDateTime.of(2024, 1, 2, 0, 0), ZoneId.of("UTC"));

        // Should validate timeframe before attempting any network call
        assertThrows(IllegalArgumentException.class, () ->
                AlpacaHourlyLoader.loadBars("AAPL", "BAD_TF", start, end, "", ""));
    }

    @Test
    public void testLoadBarsDurationUnsupported() {
        ZonedDateTime start = ZonedDateTime.of(LocalDateTime.of(2024, 1, 1, 0, 0), ZoneId.of("UTC"));
        ZonedDateTime end = ZonedDateTime.of(LocalDateTime.of(2024, 1, 2, 0, 0), ZoneId.of("UTC"));

        // Duration.ofSeconds(30) is not mapped in the convenience overload and should throw
        assertThrows(IllegalArgumentException.class, () ->
                AlpacaHourlyLoader.loadBars("AAPL", Duration.ofSeconds(30), start, end, "", ""));
    }

    @Test
    public void testParseAlpacaTimeFrameToDurationTwelveMonths() {
        // 12Month should be approximated as 360 days (12 * 30)
        assertEquals(Duration.ofDays(360), AlpacaHourlyLoader.parseAlpacaTimeFrameToDuration("12Month"));
    }

    @Test
    public void testValidTimeframes() {
        assertTrue(AlpacaHourlyLoader.isValidAlpacaTimeFrame("1Min"));
        assertTrue(AlpacaHourlyLoader.isValidAlpacaTimeFrame("5Min"));
        assertTrue(AlpacaHourlyLoader.isValidAlpacaTimeFrame("15Min"));
        assertTrue(AlpacaHourlyLoader.isValidAlpacaTimeFrame("30Min"));
        assertTrue(AlpacaHourlyLoader.isValidAlpacaTimeFrame("1Hour"));
        assertTrue(AlpacaHourlyLoader.isValidAlpacaTimeFrame("12Hour"));
        assertTrue(AlpacaHourlyLoader.isValidAlpacaTimeFrame("1Day"));
        assertTrue(AlpacaHourlyLoader.isValidAlpacaTimeFrame("1Week"));
        assertTrue(AlpacaHourlyLoader.isValidAlpacaTimeFrame("1Month"));
        assertTrue(AlpacaHourlyLoader.isValidAlpacaTimeFrame("3Month"));
        assertTrue(AlpacaHourlyLoader.isValidAlpacaTimeFrame("12Month"));
    }

    @Test
    public void testInvalidTimeframes() {
        assertFalse(AlpacaHourlyLoader.isValidAlpacaTimeFrame(null));
        assertFalse(AlpacaHourlyLoader.isValidAlpacaTimeFrame("0Min"));
        assertFalse(AlpacaHourlyLoader.isValidAlpacaTimeFrame("60Min"));
        assertFalse(AlpacaHourlyLoader.isValidAlpacaTimeFrame("0Hour"));
        assertFalse(AlpacaHourlyLoader.isValidAlpacaTimeFrame("25Hour"));
        assertFalse(AlpacaHourlyLoader.isValidAlpacaTimeFrame("2Weeks"));
        assertFalse(AlpacaHourlyLoader.isValidAlpacaTimeFrame("5Month"));
    }

    @Test
    public void testParseToDuration() {
        assertEquals(Duration.ofMinutes(1), AlpacaHourlyLoader.parseAlpacaTimeFrameToDuration("1Min"));
        assertEquals(Duration.ofMinutes(5), AlpacaHourlyLoader.parseAlpacaTimeFrameToDuration("5Min"));
        assertEquals(Duration.ofMinutes(15), AlpacaHourlyLoader.parseAlpacaTimeFrameToDuration("15Min"));
        assertEquals(Duration.ofHours(1), AlpacaHourlyLoader.parseAlpacaTimeFrameToDuration("1Hour"));
        assertEquals(Duration.ofHours(12), AlpacaHourlyLoader.parseAlpacaTimeFrameToDuration("12Hour"));
        assertEquals(Duration.ofDays(1), AlpacaHourlyLoader.parseAlpacaTimeFrameToDuration("1Day"));
        assertEquals(Duration.ofDays(7), AlpacaHourlyLoader.parseAlpacaTimeFrameToDuration("1Week"));
        assertEquals(Duration.ofDays(30), AlpacaHourlyLoader.parseAlpacaTimeFrameToDuration("1Month"));
        assertEquals(Duration.ofDays(90), AlpacaHourlyLoader.parseAlpacaTimeFrameToDuration("3Month"));
    }

    @Test
    public void testDurationToTimeframeMapping() {
        assertEquals("1Min", AlpacaHourlyLoader.durationToAlpacaTimeframe(Duration.ofMinutes(1)));
        assertEquals("5Min", AlpacaHourlyLoader.durationToAlpacaTimeframe(Duration.ofMinutes(5)));
        assertEquals("15Min", AlpacaHourlyLoader.durationToAlpacaTimeframe(Duration.ofMinutes(15)));
        assertEquals("30Min", AlpacaHourlyLoader.durationToAlpacaTimeframe(Duration.ofMinutes(30)));
        assertEquals("1Hour", AlpacaHourlyLoader.durationToAlpacaTimeframe(Duration.ofHours(1)));
        assertEquals("4Hour", AlpacaHourlyLoader.durationToAlpacaTimeframe(Duration.ofHours(4)));
        assertEquals("1Day", AlpacaHourlyLoader.durationToAlpacaTimeframe(Duration.ofDays(1)));
    }
}
