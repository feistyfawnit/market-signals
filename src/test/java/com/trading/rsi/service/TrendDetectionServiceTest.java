package com.trading.rsi.service;

import com.trading.rsi.domain.CandleHistory;
import com.trading.rsi.domain.Instrument;
import com.trading.rsi.domain.SignalLog;
import com.trading.rsi.event.SignalEvent;
import com.trading.rsi.model.Candle;
import com.trading.rsi.repository.CandleHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for TREND_BUY_DIP dedupe logic in TrendDetectionService.
 */
@ExtendWith(MockitoExtension.class)
class TrendDetectionServiceTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private SignalCooldownService cooldownService;

    @Mock
    private EmaCalculator emaCalculator;

    @Mock
    private PriceHistoryService priceHistoryService;

    @Mock
    private CandleHistoryRepository candleHistoryRepository;

    @Mock
    private FilterEventCounterService filterEventCounterService;

    @InjectMocks
    private TrendDetectionService trendDetectionService;

    private Instrument instrument;
    private Candle triggerCandle;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(trendDetectionService, "emaPeriod", 20);
        ReflectionTestUtils.setField(trendDetectionService, "emaTrendTimeframe", "1h");
        ReflectionTestUtils.setField(trendDetectionService, "consecutiveSignalsForTrend", 2);
        ReflectionTestUtils.setField(trendDetectionService, "dipRsiThreshold", 45.0);
        ReflectionTestUtils.setField(trendDetectionService, "rallyRsiThreshold", 55.0);
        ReflectionTestUtils.setField(trendDetectionService, "adxFilterEnabled", false);
        ReflectionTestUtils.setField(trendDetectionService, "trendTimeoutHours", 12);
        ReflectionTestUtils.setField(trendDetectionService, "suppressCounterTrend", true);
        ReflectionTestUtils.setField(trendDetectionService, "cryptoVolumeFilterEnabled", true);
        ReflectionTestUtils.setField(trendDetectionService, "cryptoVolumeMultiplier", 1.2);
        ReflectionTestUtils.setField(trendDetectionService, "cryptoVolumeLookback", 20);

        instrument = Instrument.builder()
                .symbol("SOLUSDT")
                .name("Solana")
                .source(Instrument.DataSource.BINANCE)
                .type(Instrument.InstrumentType.CRYPTO)
                .enabled(true)
                .timeframes("15m,1h,4h")
                .oversoldThreshold(30)
                .overboughtThreshold(70)
                .build();

        triggerCandle = Candle.builder()
                .timestamp(Instant.now())
                .open(new BigDecimal("88"))
                .high(new BigDecimal("89"))
                .low(new BigDecimal("87"))
                .close(new BigDecimal("88"))
                .volume(new BigDecimal("10000"))
                .build();
    }

    /**
     * Stub priceHistoryService + emaCalculator so getTrendState() returns STRONG_UPTREND.
     */
    private void stubUptrend() {
        List<BigDecimal> history = IntStream.range(0, 25)
                .mapToObj(i -> BigDecimal.valueOf(80 + i))
                .toList();
        lenient().when(priceHistoryService.buildKey("SOLUSDT", "1h")).thenReturn("SOLUSDT:1h");
        lenient().when(priceHistoryService.getPriceHistory("SOLUSDT:1h")).thenReturn(history);
        lenient().when(emaCalculator.isPriceAboveEma(history, 20)).thenReturn(true);
        lenient().when(emaCalculator.calculate(history, 20)).thenReturn(BigDecimal.valueOf(90));
    }

    // ── Scenario 1: Repeat dip at similar price, RSI never recovers → SUPPRESSED ──

    @Test
    void dipDedupe_similarPrice_noRsiRecovery_secondSuppressed() {
        stubUptrend();
        when(cooldownService.shouldAlert(eq("SOLUSDT"), eq(SignalLog.SignalType.TREND_BUY_DIP)))
                .thenReturn(true);

        BigDecimal price1 = new BigDecimal("88.00");
        BigDecimal price2 = new BigDecimal("88.10"); // 0.11% move — below 0.3% threshold

        // RSI at 55 → above dipRsiThreshold (45) → does NOT trigger dip. Use 42 for dip trigger.
        Map<String, BigDecimal> dipRsi = Map.of(
                "15m", new BigDecimal("42"),
                "1h", new BigDecimal("65"),
                "4h", new BigDecimal("72")
        );

        // First dip call → should fire (RSI 42 < 45 threshold)
        trendDetectionService.checkForTrendEntry(instrument, dipRsi, price1, triggerCandle);
        verify(eventPublisher, times(1)).publishEvent(any(SignalEvent.class));

        // Second dip call at similar price, RSI stayed below 45 → should be SUPPRESSED
        trendDetectionService.checkForTrendEntry(instrument, dipRsi, price2, triggerCandle);
        verify(eventPublisher, times(1)).publishEvent(any(SignalEvent.class)); // still 1
    }

    // ── Scenario 2: Repeat dip at similar price, RSI recovers above 45 → FIRES ──

    @Test
    void dipDedupe_similarPrice_rsiRecovers_secondFires() {
        stubUptrend();
        when(cooldownService.shouldAlert(eq("SOLUSDT"), eq(SignalLog.SignalType.TREND_BUY_DIP)))
                .thenReturn(true);

        BigDecimal price1 = new BigDecimal("88.00");
        BigDecimal price2 = new BigDecimal("88.10"); // similar price

        Map<String, BigDecimal> dipRsi = Map.of(
                "15m", new BigDecimal("42"),
                "1h", new BigDecimal("65"),
                "4h", new BigDecimal("72")
        );

        // First dip → fires (RSI 42 < 45)
        trendDetectionService.checkForTrendEntry(instrument, dipRsi, price1, triggerCandle);
        verify(eventPublisher, times(1)).publishEvent(any(SignalEvent.class));

        // RSI recovers above threshold (poll cycle with RSI >= 45)
        Map<String, BigDecimal> recoveredRsi = Map.of(
                "15m", new BigDecimal("48"),
                "1h", new BigDecimal("68"),
                "4h", new BigDecimal("74")
        );
        trendDetectionService.checkForTrendEntry(instrument, recoveredRsi, price2, triggerCandle);
        // RSI=48 >= 45 → sets dipRsiRecovered=true; RSI not < 45, so no new dip signal

        // Second dip after recovery → should fire
        trendDetectionService.checkForTrendEntry(instrument, dipRsi, price2, triggerCandle);
        verify(eventPublisher, times(2)).publishEvent(any(SignalEvent.class));
    }

    // ── Scenario 3: Repeat dip where price moved >0.3% → FIRES ──

    @Test
    void dipDedupe_priceMoved_secondFires() {
        stubUptrend();
        when(cooldownService.shouldAlert(eq("SOLUSDT"), eq(SignalLog.SignalType.TREND_BUY_DIP)))
                .thenReturn(true);

        BigDecimal price1 = new BigDecimal("88.00");
        BigDecimal price2 = new BigDecimal("88.50"); // 0.57% move — above 0.3% threshold

        Map<String, BigDecimal> dipRsi = Map.of(
                "15m", new BigDecimal("42"),
                "1h", new BigDecimal("65"),
                "4h", new BigDecimal("72")
        );

        // First dip → fires (RSI 42 < 45)
        trendDetectionService.checkForTrendEntry(instrument, dipRsi, price1, triggerCandle);
        verify(eventPublisher, times(1)).publishEvent(any(SignalEvent.class));

        // Second dip at >0.3% different price (RSI never recovered above 45) → should FIRE
        trendDetectionService.checkForTrendEntry(instrument, dipRsi, price2, triggerCandle);
        verify(eventPublisher, times(2)).publishEvent(any(SignalEvent.class));
    }

    // ── Volume filter: crypto TREND_BUY_DIP ──

    private List<CandleHistory> buildVolumeHistory(int count, double volumePerCandle) {
        List<CandleHistory> list = new ArrayList<>();
        Instant base = Instant.now().minusSeconds(count * 900L);
        for (int i = 0; i < count; i++) {
            list.add(CandleHistory.builder()
                    .symbol("SOLUSDT")
                    .timeframe("15m")
                    .candleTime(base.plusSeconds(i * 900L))
                    .open(BigDecimal.valueOf(100))
                    .high(BigDecimal.valueOf(101))
                    .low(BigDecimal.valueOf(99))
                    .close(BigDecimal.valueOf(100))
                    .volume(BigDecimal.valueOf(volumePerCandle))
                    .build());
        }
        return list;
    }

    @Test
    void cryptoVolumeFilter_volumeAboveThreshold_fires() {
        stubUptrend();
        when(cooldownService.shouldAlert(eq("SOLUSDT"), eq(SignalLog.SignalType.TREND_BUY_DIP)))
                .thenReturn(true);

        // 20 candles with mean volume 1000; trigger volume 1300 = 1.3× → passes 1.2× threshold
        List<CandleHistory> history = buildVolumeHistory(20, 1000.0);
        when(candleHistoryRepository.findBySymbolAndTimeframeOrderByCandleTimeDesc(
                eq("SOLUSDT"), eq("15m"), any(Pageable.class)))
                .thenReturn(history);

        Map<String, BigDecimal> dipRsi = Map.of(
                "15m", new BigDecimal("42"),
                "1h", new BigDecimal("65"),
                "4h", new BigDecimal("72")
        );
        Candle highVolumeCandle = Candle.builder()
                .timestamp(Instant.now())
                .open(new BigDecimal("88"))
                .high(new BigDecimal("89"))
                .low(new BigDecimal("87"))
                .close(new BigDecimal("88"))
                .volume(new BigDecimal("1300"))
                .build();

        trendDetectionService.checkForTrendEntry(instrument, dipRsi, new BigDecimal("88.00"), highVolumeCandle);
        verify(eventPublisher, times(1)).publishEvent(any(SignalEvent.class));
    }

    @Test
    void cryptoVolumeFilter_volumeBelowThreshold_suppressed() {
        stubUptrend();

        // 20 candles with mean volume 1000; trigger volume 1000 = 1.0× → fails 1.2× threshold
        List<CandleHistory> history = buildVolumeHistory(20, 1000.0);
        when(candleHistoryRepository.findBySymbolAndTimeframeOrderByCandleTimeDesc(
                eq("SOLUSDT"), eq("15m"), any(Pageable.class)))
                .thenReturn(history);

        Map<String, BigDecimal> dipRsi = Map.of(
                "15m", new BigDecimal("42"),
                "1h", new BigDecimal("65"),
                "4h", new BigDecimal("72")
        );
        Candle lowVolumeCandle = Candle.builder()
                .timestamp(Instant.now())
                .open(new BigDecimal("88"))
                .high(new BigDecimal("89"))
                .low(new BigDecimal("87"))
                .close(new BigDecimal("88"))
                .volume(new BigDecimal("1000"))
                .build();

        trendDetectionService.checkForTrendEntry(instrument, dipRsi, new BigDecimal("88.00"), lowVolumeCandle);
        verify(eventPublisher, never()).publishEvent(any(SignalEvent.class));
    }

    @Test
    void cryptoVolumeFilter_notAppliedToIndices() {
        when(cooldownService.shouldAlert(eq("IX.D.DAX.DAILY.IP"), eq(SignalLog.SignalType.TREND_BUY_DIP)))
                .thenReturn(true);

        Instrument index = Instrument.builder()
                .symbol("IX.D.DAX.DAILY.IP")
                .name("DAX 40")
                .source(Instrument.DataSource.IG)
                .type(Instrument.InstrumentType.INDEX)
                .enabled(true)
                .timeframes("15m,30m,1h")
                .oversoldThreshold(30)
                .overboughtThreshold(70)
                .build();

        // Establish STRONG_UPTREND via consecutive overbought signals (fallback 2)
        trendDetectionService.recordSignal("IX.D.DAX.DAILY.IP", SignalLog.SignalType.OVERBOUGHT);
        trendDetectionService.recordSignal("IX.D.DAX.DAILY.IP", SignalLog.SignalType.OVERBOUGHT);

        // Even with empty repo (no candles), index should skip volume check and fire
        Map<String, BigDecimal> dipRsi = Map.of(
                "15m", new BigDecimal("42"),
                "30m", new BigDecimal("65"),
                "1h", new BigDecimal("72")
        );

        trendDetectionService.checkForTrendEntry(index, dipRsi, new BigDecimal("18000.00"), triggerCandle);
        verify(eventPublisher, times(1)).publishEvent(any(SignalEvent.class));
    }

    // ── Verify first dip always fires (sanity) ──

    @Test
    void dipDedupe_firstDip_alwaysFires() {
        stubUptrend();
        when(cooldownService.shouldAlert(eq("SOLUSDT"), eq(SignalLog.SignalType.TREND_BUY_DIP)))
                .thenReturn(true);

        Map<String, BigDecimal> dipRsi = Map.of(
                "15m", new BigDecimal("42"),
                "1h", new BigDecimal("65"),
                "4h", new BigDecimal("72")
        );

        // RSI 42 < 45 threshold → should fire
        trendDetectionService.checkForTrendEntry(instrument, dipRsi, new BigDecimal("88.00"), triggerCandle);

        ArgumentCaptor<SignalEvent> captor = ArgumentCaptor.forClass(SignalEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(SignalLog.SignalType.TREND_BUY_DIP, captor.getValue().getSignal().getSignalType());
    }
}
