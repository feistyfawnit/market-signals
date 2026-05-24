package com.trading.rsi.service;

import com.trading.rsi.repository.FilterEventCountRepository;
import com.trading.rsi.repository.InstrumentRepository;
import com.trading.rsi.repository.SignalLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SignalGapService#buildSuppressionRetrospectiveSection(int)} and the
 * private verdict-band helper.
 *
 * <p>These cover the rendering / bucketing logic only — the SQL itself is exercised in
 * production runs against PostgreSQL.
 */
@ExtendWith(MockitoExtension.class)
class SignalGapServiceTest {

    @Mock private FilterEventCountRepository filterEventCountRepository;
    @Mock private SignalLogRepository signalLogRepository;
    @Mock private InstrumentRepository instrumentRepository;

    @InjectMocks private SignalGapService service;

    // ── verdictFor() bucket boundaries ────────────────────────────────────────

    @Test
    void verdictFor_negativeAvg_isCorrect() {
        assertEquals("✅ Correct", SignalGapService.verdictFor(-0.50));
        assertEquals("✅ Correct", SignalGapService.verdictFor(-2.00));
    }

    @Test
    void verdictFor_zeroAvg_isCorrect() {
        // exactly 0% → suppression neither helped nor hurt; treated as ✅ (no missed win)
        assertEquals("✅ Correct", SignalGapService.verdictFor(0.0));
    }

    @Test
    void verdictFor_smallPositiveAvg_isMarginal() {
        assertEquals("➖ Marginal", SignalGapService.verdictFor(0.30));
        assertEquals("➖ Marginal", SignalGapService.verdictFor(0.99));
        assertEquals("➖ Marginal", SignalGapService.verdictFor(1.00));   // boundary inclusive
    }

    @Test
    void verdictFor_largePositiveAvg_reconsider() {
        assertEquals("⚠️ Reconsider", SignalGapService.verdictFor(1.01));
        assertEquals("⚠️ Reconsider", SignalGapService.verdictFor(2.50));
    }

    // ── buildSuppressionRetrospectiveSection() rendering ──────────────────────

    @Test
    void retrospective_emptyResult_emitsExplanatoryStub() {
        when(filterEventCountRepository.findSuppressionRetrospectiveSince(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());

        String md = service.buildSuppressionRetrospectiveSection(14);

        assertTrue(md.contains("## Suppressed-Signal Retrospective (Last 14 Days)"));
        assertTrue(md.contains("No filter (symbol, day) pairs"));
        assertFalse(md.contains("| Sym |"), "should not emit table when no rows");
    }

    @Test
    void retrospective_queryFailure_returnsEmptyString() {
        when(filterEventCountRepository.findSuppressionRetrospectiveSince(any(LocalDate.class), any(LocalDate.class)))
                .thenThrow(new RuntimeException("simulated DB outage"));

        String md = service.buildSuppressionRetrospectiveSection(14);

        // Failures must not break the report — empty string lets PositionReportService skip the section silently.
        assertEquals("", md, "query failure should not propagate to the report");
    }

    @Test
    void retrospective_rendersAllVerdictBucketsCorrectly() {
        // Three rows hitting all three verdict bands.
        // Note: native query returns Object[] with Numbers (typically BigInteger / BigDecimal from PG).
        Object[] correctRow = new Object[]{
                "SOLUSDT", "ADX_RANGING",
                java.math.BigInteger.valueOf(191L),     // total_suppressions (PG SUM → numeric → BigInteger)
                java.math.BigInteger.valueOf(7L),       // days_observed (PG COUNT → bigint → BigInteger)
                new BigDecimal("-0.32")                  // avg_next_day_pct (PG AVG of decimal → numeric)
        };
        Object[] marginalRow = new Object[]{
                "SOLUSDT", "EMA_SLOPE_FLAT",
                java.math.BigInteger.valueOf(36L),
                java.math.BigInteger.valueOf(3L),
                new BigDecimal("0.45")
        };
        Object[] reconsiderRow = new Object[]{
                "IX.D.SPTRD.DAILY.IP", "ADX_RANGING",
                java.math.BigInteger.valueOf(252L),
                java.math.BigInteger.valueOf(5L),
                new BigDecimal("1.41")
        };

        when(filterEventCountRepository.findSuppressionRetrospectiveSince(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(correctRow, marginalRow, reconsiderRow));

        String md = service.buildSuppressionRetrospectiveSection(14);

        // Header + table present
        assertTrue(md.contains("## Suppressed-Signal Retrospective (Last 14 Days)"));
        assertTrue(md.contains("| Sym | Filter | Suppressions | Days | Avg Next-Day | Verdict |"));

        // Symbol short-name mapping applied
        assertTrue(md.contains("| SOL | ADX_RANGING | 191 | 7 | -0.32% | ✅ Correct |"));
        assertTrue(md.contains("| SOL | EMA_SLOPE_FLAT | 36 | 3 | +0.45% | ➖ Marginal |"));
        assertTrue(md.contains("| S&P | ADX_RANGING | 252 | 5 | +1.41% | ⚠️ Reconsider |"));

        // Methodology footer present
        assertTrue(md.contains("Verdict bands"));
        assertTrue(md.contains("daily-bucket aggregation"));
    }
}
