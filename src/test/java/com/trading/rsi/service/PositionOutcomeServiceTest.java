package com.trading.rsi.service;

import com.trading.rsi.domain.CandleHistory;
import com.trading.rsi.domain.PositionOutcome;
import com.trading.rsi.domain.SignalLog;
import com.trading.rsi.event.SignalEvent;
import com.trading.rsi.model.RsiSignal;
import com.trading.rsi.repository.CandleHistoryRepository;
import com.trading.rsi.repository.PositionOutcomeRepository;
import com.trading.rsi.service.TrendDetectionService.TrendState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PositionOutcomeServiceTest {

    @Mock
    private PositionOutcomeRepository positionOutcomeRepository;

    @Mock
    private CandleHistoryRepository candleHistoryRepository;

    @Mock
    private PriceHistoryService priceHistoryService;

    @Mock
    private AtrCalculator atrCalculator;

    @Mock
    private TrendDetectionService trendDetectionService;

    @Mock
    private FilterEventCounterService filterEventCounterService;

    @InjectMocks
    private PositionOutcomeService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "stopPercentCrypto", 2.0);
        ReflectionTestUtils.setField(service, "stopPercentIndex", 0.5);
        ReflectionTestUtils.setField(service, "stopPercentCommodity", 1.0);
        ReflectionTestUtils.setField(service, "signalCooldownHours", 1);
        // ATR disabled here — tests exercise the fixed-pct fallback path.
        // AtrCalculator integration is covered by AtrCalculatorTest.
        ReflectionTestUtils.setField(service, "atrStopsEnabled", false);
        ReflectionTestUtils.setField(service, "atrPeriod", 14);
        ReflectionTestUtils.setField(service, "atrMultiplierTrend", 1.5);
        ReflectionTestUtils.setField(service, "atrMultiplierDefault", 2.0);
        ReflectionTestUtils.setField(service, "trendRrCrypto", 2.0);
        ReflectionTestUtils.setField(service, "trendRrIndex", 3.0);
        ReflectionTestUtils.setField(service, "trendRrCommodity", 3.0);
        lenient().when(trendDetectionService.getTrendState(anyString())).thenReturn(TrendState.NEUTRAL);
    }

    // ── Signal creates position with correct TP/SL ──

    @Test
    void handleSignalEvent_oversold_createsLongPosition() {
        RsiSignal signal = RsiSignal.builder()
                .symbol("BTCUSDT")
                .instrumentName("Bitcoin")
                .signalType(SignalLog.SignalType.OVERSOLD)
                .currentPrice(new BigDecimal("50000"))
                .rsiValues(Map.of("15m", new BigDecimal("25")))
                .timeframesAligned(3)
                .totalTimeframes(3)
                .build();

        when(positionOutcomeRepository.save(any(PositionOutcome.class)))
                .thenAnswer(i -> i.getArgument(0));

        service.handleSignalEvent(new SignalEvent(this, signal));

        ArgumentCaptor<PositionOutcome> captor = ArgumentCaptor.forClass(PositionOutcome.class);
        verify(positionOutcomeRepository).save(captor.capture());
        PositionOutcome pos = captor.getValue();

        assertTrue(pos.getIsLong());
        assertEquals("BTCUSDT", pos.getSymbol());
        assertEquals(SignalLog.SignalType.OVERSOLD, pos.getSignalType());
        // stop = 50000 * 2% / 100 = 1000pt; limit = 1000 * 2 = 2000pt
        assertEquals(new BigDecimal("49000"), pos.getSlPrice());
        assertEquals(new BigDecimal("52000"), pos.getTpPrice());
        assertNull(pos.getExitTime());
    }

    @Test
    void handleSignalEvent_overbought_createsShortPosition() {
        RsiSignal signal = RsiSignal.builder()
                .symbol("BTCUSDT")
                .instrumentName("Bitcoin")
                .signalType(SignalLog.SignalType.OVERBOUGHT)
                .currentPrice(new BigDecimal("50000"))
                .rsiValues(Map.of("15m", new BigDecimal("75")))
                .timeframesAligned(3)
                .totalTimeframes(3)
                .build();

        when(positionOutcomeRepository.save(any(PositionOutcome.class)))
                .thenAnswer(i -> i.getArgument(0));

        service.handleSignalEvent(new SignalEvent(this, signal));

        ArgumentCaptor<PositionOutcome> captor = ArgumentCaptor.forClass(PositionOutcome.class);
        verify(positionOutcomeRepository).save(captor.capture());
        PositionOutcome pos = captor.getValue();

        assertFalse(pos.getIsLong());
        assertEquals(new BigDecimal("51000"), pos.getSlPrice());
        assertEquals(new BigDecimal("48000"), pos.getTpPrice());
    }

    @Test
    void handleSignalEvent_trendBuyDip_usesTighterStops() {
        RsiSignal signal = RsiSignal.builder()
                .symbol("BTCUSDT")
                .instrumentName("Bitcoin")
                .signalType(SignalLog.SignalType.TREND_BUY_DIP)
                .currentPrice(new BigDecimal("50000"))
                .rsiValues(Map.of("15m", new BigDecimal("55")))
                .timeframesAligned(2)
                .totalTimeframes(3)
                .build();

        when(positionOutcomeRepository.save(any(PositionOutcome.class)))
                .thenAnswer(i -> i.getArgument(0));

        service.handleSignalEvent(new SignalEvent(this, signal));

        ArgumentCaptor<PositionOutcome> captor = ArgumentCaptor.forClass(PositionOutcome.class);
        verify(positionOutcomeRepository).save(captor.capture());
        PositionOutcome pos = captor.getValue();

        assertTrue(pos.getIsLong());
        // crypto trend: stop = 50000 * 1% / 100 = 500pt; limit = 500 * 2 (crypto R:R) = 1000pt
        assertEquals(new BigDecimal("49500"), pos.getSlPrice());
        assertEquals(new BigDecimal("51000"), pos.getTpPrice());
    }

    @Test
    void handleSignalEvent_partialSignal_ignored() {
        RsiSignal signal = RsiSignal.builder()
                .symbol("BTCUSDT")
                .instrumentName("Bitcoin")
                .signalType(SignalLog.SignalType.PARTIAL_OVERSOLD)
                .currentPrice(new BigDecimal("50000"))
                .rsiValues(Map.of("15m", new BigDecimal("28")))
                .timeframesAligned(2)
                .totalTimeframes(3)
                .build();

        service.handleSignalEvent(new SignalEvent(this, signal));

        verify(positionOutcomeRepository, never()).save(any());
    }

    @Test
    void handleSignalEvent_recentSignalWithinCooldown_skips() {
        // First signal saves normally
        RsiSignal signal1 = RsiSignal.builder()
                .symbol("BTCUSDT")
                .instrumentName("Bitcoin")
                .signalType(SignalLog.SignalType.OVERSOLD)
                .currentPrice(new BigDecimal("50000"))
                .rsiValues(Map.of("15m", new BigDecimal("25")))
                .timeframesAligned(3)
                .totalTimeframes(3)
                .build();

        when(positionOutcomeRepository.save(any(PositionOutcome.class)))
                .thenAnswer(i -> i.getArgument(0));
        when(positionOutcomeRepository.existsBySymbolAndExitTimeIsNull("BTCUSDT")).thenReturn(false);
        when(positionOutcomeRepository.existsBySymbolSince(eq("BTCUSDT"), any(Instant.class)))
                .thenReturn(false)  // First call: no recent signal
                .thenReturn(true);  // Second call: recent signal exists

        service.handleSignalEvent(new SignalEvent(this, signal1));
        verify(positionOutcomeRepository, times(1)).save(any());

        // Second signal within cooldown should be skipped
        RsiSignal signal2 = RsiSignal.builder()
                .symbol("BTCUSDT")
                .instrumentName("Bitcoin")
                .signalType(SignalLog.SignalType.OVERSOLD)
                .currentPrice(new BigDecimal("50100"))
                .rsiValues(Map.of("15m", new BigDecimal("24")))
                .timeframesAligned(3)
                .totalTimeframes(3)
                .build();

        service.handleSignalEvent(new SignalEvent(this, signal2));
        verify(positionOutcomeRepository, times(1)).save(any()); // Still only 1 save
    }

    // ── Exit condition checks ──

    @Test
    void checkAndClosePosition_tpHit_closesWithProfit() {
        Instant entryTime = Instant.now().minus(2, ChronoUnit.HOURS);
        PositionOutcome pos = PositionOutcome.builder()
                .id(1L)
                .symbol("BTCUSDT")
                .signalType(SignalLog.SignalType.OVERSOLD)
                .entryPrice(new BigDecimal("50000"))
                .entryTime(entryTime)
                .tpPrice(new BigDecimal("52000"))
                .slPrice(new BigDecimal("49000"))
                .isLong(true)
                .build();

        CandleHistory candle = CandleHistory.builder()
                .symbol("BTCUSDT").timeframe("15m")
                .candleTime(entryTime.plus(1, ChronoUnit.HOURS))
                .open(new BigDecimal("50500"))
                .high(new BigDecimal("52500")) // above TP
                .low(new BigDecimal("50400"))
                .close(new BigDecimal("52200"))
                .build();

        when(candleHistoryRepository.findBySymbolAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                eq("BTCUSDT"), eq("15m"), eq(entryTime), any(Instant.class)))
                .thenReturn(List.of(candle));

        service.checkAndClosePosition(pos, Instant.now());

        assertTrue(pos.getTpHit());
        assertFalse(pos.getSlHit());
        assertEquals(new BigDecimal("52000"), pos.getExitPrice());
        assertTrue(pos.getPnlPct().doubleValue() > 0);
        verify(positionOutcomeRepository).save(pos);
    }

    @Test
    void checkAndClosePosition_slHit_closesWithLoss() {
        Instant entryTime = Instant.now().minus(2, ChronoUnit.HOURS);
        PositionOutcome pos = PositionOutcome.builder()
                .id(2L)
                .symbol("BTCUSDT")
                .signalType(SignalLog.SignalType.OVERSOLD)
                .entryPrice(new BigDecimal("50000"))
                .entryTime(entryTime)
                .tpPrice(new BigDecimal("52000"))
                .slPrice(new BigDecimal("49000"))
                .isLong(true)
                .build();

        CandleHistory candle = CandleHistory.builder()
                .symbol("BTCUSDT").timeframe("15m")
                .candleTime(entryTime.plus(1, ChronoUnit.HOURS))
                .open(new BigDecimal("49800"))
                .high(new BigDecimal("49900"))
                .low(new BigDecimal("48500")) // below SL
                .close(new BigDecimal("48800"))
                .build();

        when(candleHistoryRepository.findBySymbolAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                eq("BTCUSDT"), eq("15m"), eq(entryTime), any(Instant.class)))
                .thenReturn(List.of(candle));

        service.checkAndClosePosition(pos, Instant.now());

        assertFalse(pos.getTpHit());
        assertTrue(pos.getSlHit());
        assertEquals(new BigDecimal("49000"), pos.getExitPrice());
        assertTrue(pos.getPnlPct().doubleValue() < 0);
        verify(positionOutcomeRepository).save(pos);
    }

    @Test
    void checkAndClosePosition_24hAutoClose() {
        Instant entryTime = Instant.now().minus(25, ChronoUnit.HOURS);
        PositionOutcome pos = PositionOutcome.builder()
                .id(3L)
                .symbol("BTCUSDT")
                .signalType(SignalLog.SignalType.OVERSOLD)
                .entryPrice(new BigDecimal("50000"))
                .entryTime(entryTime)
                .tpPrice(new BigDecimal("52000"))
                .slPrice(new BigDecimal("49000"))
                .isLong(true)
                .build();

        // Candles exist but neither TP nor SL was hit
        CandleHistory candle = CandleHistory.builder()
                .symbol("BTCUSDT").timeframe("15m")
                .candleTime(entryTime.plus(1, ChronoUnit.HOURS))
                .open(new BigDecimal("50100"))
                .high(new BigDecimal("50500"))
                .low(new BigDecimal("49800"))
                .close(new BigDecimal("50200"))
                .build();

        when(candleHistoryRepository.findBySymbolAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                eq("BTCUSDT"), eq("15m"), eq(entryTime), any(Instant.class)))
                .thenReturn(List.of(candle));
        when(priceHistoryService.getLatestPrice("BTCUSDT")).thenReturn(new BigDecimal("50300"));

        service.checkAndClosePosition(pos, Instant.now());

        assertFalse(pos.getTpHit());
        assertFalse(pos.getSlHit());
        assertEquals(new BigDecimal("50300"), pos.getExitPrice());
        assertNotNull(pos.getExitTime());
        verify(positionOutcomeRepository).save(pos);
    }

    @Test
    void checkAndClosePosition_noExitYet_noChange() {
        Instant entryTime = Instant.now().minus(2, ChronoUnit.HOURS);
        PositionOutcome pos = PositionOutcome.builder()
                .id(4L)
                .symbol("BTCUSDT")
                .signalType(SignalLog.SignalType.OVERSOLD)
                .entryPrice(new BigDecimal("50000"))
                .entryTime(entryTime)
                .tpPrice(new BigDecimal("52000"))
                .slPrice(new BigDecimal("49000"))
                .isLong(true)
                .build();

        // Candle within range — no TP or SL hit, and under 24h
        CandleHistory candle = CandleHistory.builder()
                .symbol("BTCUSDT").timeframe("15m")
                .candleTime(entryTime.plus(1, ChronoUnit.HOURS))
                .open(new BigDecimal("50100"))
                .high(new BigDecimal("50500"))
                .low(new BigDecimal("49800"))
                .close(new BigDecimal("50200"))
                .build();

        when(candleHistoryRepository.findBySymbolAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                eq("BTCUSDT"), eq("15m"), eq(entryTime), any(Instant.class)))
                .thenReturn(List.of(candle));

        service.checkAndClosePosition(pos, Instant.now());

        assertNull(pos.getExitTime());
        verify(positionOutcomeRepository, never()).save(any());
    }
}
