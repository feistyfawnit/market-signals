package com.trading.rsi.service;

import com.trading.rsi.model.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PriceHistoryService#isValidOhlc(Candle)}.
 *
 * <p>Defensive validation against the IG session-close artefact (zero-OHLC
 * candles) observed May 2026.
 */
class PriceHistoryServiceValidationTest {

    private static Candle buildCandle(BigDecimal o, BigDecimal h, BigDecimal l, BigDecimal c) {
        return Candle.builder()
                .timestamp(java.time.Instant.now())
                .open(o).high(h).low(l).close(c)
                .volume(BigDecimal.TEN)
                .build();
    }

    @Test
    void validCandle_positiveOhlc_returnsTrue() {
        Candle c = buildCandle(new BigDecimal("100"), new BigDecimal("105"), new BigDecimal("99"), new BigDecimal("104"));
        assertTrue(PriceHistoryService.isValidOhlc(c));
    }

    @Test
    void zeroOpen_returnsFalse() {
        Candle c = buildCandle(BigDecimal.ZERO, new BigDecimal("105"), new BigDecimal("99"), new BigDecimal("104"));
        assertFalse(PriceHistoryService.isValidOhlc(c));
    }

    @Test
    void zeroHigh_returnsFalse() {
        Candle c = buildCandle(new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("99"), new BigDecimal("104"));
        assertFalse(PriceHistoryService.isValidOhlc(c));
    }

    @Test
    void zeroLow_returnsFalse() {
        Candle c = buildCandle(new BigDecimal("100"), new BigDecimal("105"), BigDecimal.ZERO, new BigDecimal("104"));
        assertFalse(PriceHistoryService.isValidOhlc(c));
    }

    @Test
    void zeroClose_returnsFalse() {
        Candle c = buildCandle(new BigDecimal("100"), new BigDecimal("105"), new BigDecimal("99"), BigDecimal.ZERO);
        assertFalse(PriceHistoryService.isValidOhlc(c));
    }

    @Test
    void allZero_igSessionCloseArtefact_returnsFalse() {
        // Exact pattern observed on S&P at 22:00 UTC May 2026
        Candle c = buildCandle(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        assertFalse(PriceHistoryService.isValidOhlc(c));
    }

    @Test
    void nullOpen_returnsFalse() {
        Candle c = buildCandle(null, new BigDecimal("105"), new BigDecimal("99"), new BigDecimal("104"));
        assertFalse(PriceHistoryService.isValidOhlc(c));
    }

    @Test
    void nullCandle_returnsFalse() {
        assertFalse(PriceHistoryService.isValidOhlc(null));
    }

    @Test
    void negativeOhlc_returnsFalse() {
        // Extremely unlikely, but defence-in-depth
        Candle c = buildCandle(new BigDecimal("-1"), new BigDecimal("105"), new BigDecimal("99"), new BigDecimal("104"));
        assertFalse(PriceHistoryService.isValidOhlc(c));
    }
}
