package com.trading.rsi.service;

import com.trading.rsi.domain.SignalLog;
import com.trading.rsi.model.RsiSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the minimum stop floor in NotificationService.buildDemoGuidance().
 * Floor rule: stop >= max(computed, 0.2% of price, 2pts)
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private ClaudeEnrichmentService claudeEnrichmentService;

    @Mock
    private AppSettingsService appSettingsService;

    @Mock
    private TelegramNotificationService telegramNotificationService;

    @Mock
    private TrendDetectionService trendDetectionService;

    @InjectMocks
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(notificationService, "demoAccountBalance", 10000);
        ReflectionTestUtils.setField(notificationService, "demoRiskPercent", 1);
        ReflectionTestUtils.setField(notificationService, "stopPercentCrypto", 2.0);
        ReflectionTestUtils.setField(notificationService, "stopPercentIndex", 0.5);
        ReflectionTestUtils.setField(notificationService, "stopPercentCommodity", 1.0);
        ReflectionTestUtils.setField(notificationService, "accountCurrency", "EUR");
    }

    // ── Minimum stop floor with SOL-like instrument (~$88) ──

    @Test
    void buildDemoGuidance_trendBuyDip_lowPrice_stopFloorApplies() throws Exception {
        // SOL at ~$88: computed stop = round(88 * 1.0 / 100) = 1pt
        // Floor: max(round(88 * 0.002), 2) = max(0, 2) = 2pt
        // Result: max(1, 2) = 2pt
        RsiSignal signal = RsiSignal.builder()
                .symbol("SOLUSDT")
                .instrumentName("Solana")
                .signalType(SignalLog.SignalType.TREND_BUY_DIP)
                .currentPrice(new BigDecimal("88.00"))
                .rsiValues(Map.of("15m", new BigDecimal("55")))
                .timeframesAligned(2)
                .totalTimeframes(3)
                .signalStrength(new BigDecimal("5.0"))
                .build();

        String result = invokeBuildDemoGuidance(signal);

        // Stop must be exactly 2pt (floor), not the computed 1pt
        assertTrue(result.contains("Stop 2pt"),
                "Stop floor (2pt) should apply for SOL TREND_BUY_DIP. Got: " + result);
        // Trend signals use 3:1 R:R → limit = 2 * 3 = 6pt
        assertTrue(result.contains("Limit 6pt"),
                "Limit should be 3x stop for trend signal. Got: " + result);
        assertTrue(result.contains("3:1"),
                "R:R ratio should be 3:1 for trend signals. Got: " + result);
    }

    @Test
    void buildDemoGuidance_fullOversold_lowPrice_stopFloorApplies() throws Exception {
        // SOL at ~$88 with full OVERSOLD (not trend): computed stop = round(88 * 2.0 / 100) = 2pt
        // Floor: max(round(88 * 0.002), 2) = max(0, 2) = 2pt
        // Result: max(2, 2) = 2pt — floor matches computed
        RsiSignal signal = RsiSignal.builder()
                .symbol("SOLUSDT")
                .instrumentName("Solana")
                .signalType(SignalLog.SignalType.OVERSOLD)
                .currentPrice(new BigDecimal("88.00"))
                .rsiValues(Map.of("15m", new BigDecimal("25")))
                .timeframesAligned(3)
                .totalTimeframes(3)
                .signalStrength(new BigDecimal("5.0"))
                .build();

        String result = invokeBuildDemoGuidance(signal);

        assertTrue(result.contains("Stop 2pt"),
                "Stop should be 2pt for full OVERSOLD at $88. Got: " + result);
        // Full signal uses 2:1 R:R → limit = 2 * 2 = 4pt
        assertTrue(result.contains("Limit 4pt"),
                "Limit should be 2x stop for full signal. Got: " + result);
    }

    @Test
    void buildDemoGuidance_trendBuyDip_includesTrailingStopLine() throws Exception {
        RsiSignal signal = RsiSignal.builder()
                .symbol("SOLUSDT")
                .instrumentName("Solana")
                .signalType(SignalLog.SignalType.TREND_BUY_DIP)
                .currentPrice(new BigDecimal("88.00"))
                .rsiValues(Map.of("15m", new BigDecimal("55")))
                .timeframesAligned(2)
                .totalTimeframes(3)
                .signalStrength(new BigDecimal("5.0"))
                .build();

        String result = invokeBuildDemoGuidance(signal);

        // Trailing-stop guidance: at +1pt (stop/2) move stop to entry, then trail 1pt below new highs.
        assertTrue(result.contains("Trail:"),
                "Actionable trend signal must include trailing-stop guidance line. Got: " + result);
        assertTrue(result.contains("at +1pt"),
                "Trail trigger should be +1pt (half of 2pt stop) for SOL. Got: " + result);
        assertTrue(result.contains("trail 1pt below new highs"),
                "Trail step should be 1pt below new highs for LONG. Got: " + result);
    }

    @Test
    void buildDemoGuidance_fullOverbought_trailingMentionsLows() throws Exception {
        RsiSignal signal = RsiSignal.builder()
                .symbol("SOLUSDT")
                .instrumentName("Solana")
                .signalType(SignalLog.SignalType.OVERBOUGHT)
                .currentPrice(new BigDecimal("88.00"))
                .rsiValues(Map.of("15m", new BigDecimal("75")))
                .timeframesAligned(3)
                .totalTimeframes(3)
                .signalStrength(new BigDecimal("5.0"))
                .build();

        String result = invokeBuildDemoGuidance(signal);

        assertTrue(result.contains("Trail:"),
                "Actionable SHORT signal must include trailing-stop guidance line. Got: " + result);
        assertTrue(result.contains("trail 1pt below new lows"),
                "Trail step on SHORT should reference new lows. Got: " + result);
    }

    @Test
    void buildDemoGuidance_partialSignal_noTrailingLine() throws Exception {
        RsiSignal signal = RsiSignal.builder()
                .symbol("SOLUSDT")
                .instrumentName("Solana")
                .signalType(SignalLog.SignalType.PARTIAL_OVERSOLD)
                .currentPrice(new BigDecimal("88.00"))
                .rsiValues(Map.of("15m", new BigDecimal("25")))
                .timeframesAligned(2)
                .totalTimeframes(3)
                .signalStrength(new BigDecimal("5.0"))
                .build();

        String result = invokeBuildDemoGuidance(signal);

        assertFalse(result.contains("Trail:"),
                "Partial signal must NOT include trailing-stop guidance. Got: " + result);
    }

    @Test
    void buildDemoGuidance_higherPrice_computedStopExceedsFloor() throws Exception {
        // BTC at $65,000: computed stop = round(65000 * 1.0 / 100) = 650pt (trend halved)
        // Floor: max(round(65000 * 0.002), 2) = max(130, 2) = 130pt
        // Result: max(650, 130) = 650pt — computed wins
        RsiSignal signal = RsiSignal.builder()
                .symbol("BTCUSDT")
                .instrumentName("Bitcoin")
                .signalType(SignalLog.SignalType.TREND_BUY_DIP)
                .currentPrice(new BigDecimal("65000.00"))
                .rsiValues(Map.of("15m", new BigDecimal("55")))
                .timeframesAligned(2)
                .totalTimeframes(3)
                .signalStrength(new BigDecimal("5.0"))
                .build();

        String result = invokeBuildDemoGuidance(signal);

        assertTrue(result.contains("Stop 650pt"),
                "Computed stop (650pt) should exceed floor for BTC. Got: " + result);
        assertTrue(result.contains("Limit 1950pt"),
                "Limit should be 3x stop for trend signal. Got: " + result);
    }

    private String invokeBuildDemoGuidance(RsiSignal signal) throws Exception {
        java.lang.reflect.Method method = NotificationService.class.getDeclaredMethod(
                "buildDemoGuidance", RsiSignal.class);
        method.setAccessible(true);
        return (String) method.invoke(notificationService, signal);
    }
}
