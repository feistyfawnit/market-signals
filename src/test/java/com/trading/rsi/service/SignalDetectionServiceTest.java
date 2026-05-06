package com.trading.rsi.service;

import com.trading.rsi.domain.Instrument;
import com.trading.rsi.event.SignalEvent;
import com.trading.rsi.model.Candle;
import com.trading.rsi.model.StochasticResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SignalDetectionServiceTest {

    @Mock
    private RsiCalculator rsiCalculator;

    @Mock
    private StochasticCalculator stochasticCalculator;

    @Mock
    private IGMarketDataClient igMarketDataClient;

    @Mock
    private PriceHistoryService priceHistoryService;

    @Mock
    private MarketDataService marketDataService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private SignalCooldownService cooldownService;

    @Mock
    private PartialSignalMonitorService partialSignalMonitorService;

    @Mock
    private TrendDetectionService trendDetectionService;

    @Mock
    private CrossAssetCorrelationService crossAssetCorrelationService;

    @Mock
    private VolatilityRegimeService volatilityRegimeService;

    @Mock
    private FilterEventCounterService filterEventCounterService;

    @InjectMocks
    private SignalDetectionService signalDetectionService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(signalDetectionService, "rsiPeriod", 14);
        ReflectionTestUtils.setField(signalDetectionService, "watchProximityThreshold", 40);
        ReflectionTestUtils.setField(signalDetectionService, "partialRequireFastTfAligned", true);
        ReflectionTestUtils.setField(signalDetectionService, "watchSignalsEnabled", false);
    }

    @Test
    void analyzeInstrument_withInsufficientHistory_attemptsWarmup() {
        Instrument instrument = createInstrument("BTCUSDT", "15m,1h,4h", Instrument.DataSource.BINANCE);
        String key = "BTCUSDT:15m";

        lenient().when(priceHistoryService.buildKey("BTCUSDT", "15m")).thenReturn(key);
        lenient().when(priceHistoryService.buildKey("BTCUSDT", "1h")).thenReturn("BTCUSDT:1h");
        lenient().when(priceHistoryService.buildKey("BTCUSDT", "4h")).thenReturn("BTCUSDT:4h");
        lenient().when(priceHistoryService.hasMinimumHistory(anyString(), eq(28))).thenReturn(false);
        lenient().when(igMarketDataClient.isCircuitOpen()).thenReturn(false);

        // Warmup returns some candles
        Candle candle = Candle.builder()
            .timestamp(Instant.now())
            .open(new BigDecimal("50000"))
            .high(new BigDecimal("51000"))
            .low(new BigDecimal("49000"))
            .close(new BigDecimal("50500"))
            .volume(new BigDecimal("1000"))
            .build();
        
        when(marketDataService.fetchCandles(any(), anyString(), anyInt()))
            .thenReturn(reactor.core.publisher.Mono.just(List.of(candle)));

        signalDetectionService.analyzeInstrument(instrument);

        // Verify warmup was attempted via the blocking call pattern
        verify(marketDataService, atLeastOnce()).fetchCandles(any(), anyString(), anyInt());
    }

    @Test
    void detectSignals_fullOversold_firesSignal() {
        // Setup mocks for price history
        when(priceHistoryService.buildKey(anyString(), anyString())).thenReturn("key");
        when(priceHistoryService.hasMinimumHistory(anyString(), eq(28))).thenReturn(true);
        when(priceHistoryService.getPriceHistory(anyString())).thenReturn(
            java.util.stream.IntStream.range(0, 30)
                .mapToObj(i -> new BigDecimal(100 + i))
                .toList()
        );
        when(priceHistoryService.getLatestCandle(anyString())).thenReturn(
            Candle.builder()
                .timestamp(Instant.now())
                .close(new BigDecimal("129"))
                .build()
        );
        when(priceHistoryService.getCandleHistory(anyString())).thenReturn(List.of());
        when(rsiCalculator.calculateRsi(any(), anyInt())).thenReturn(new BigDecimal("25"));
        when(cooldownService.shouldAlert(anyString(), any())).thenReturn(true);
        when(stochasticCalculator.calculate(any())).thenReturn(
            new StochasticResult(new BigDecimal("15"), new BigDecimal("20"))
        );

        // Trigger signal detection
        Instrument instrument = createInstrument("BTCUSDT", "15m,1h,4h", Instrument.DataSource.BINANCE);
        signalDetectionService.analyzeInstrument(instrument);

        // Verify that a signal event was published
        verify(eventPublisher, atLeastOnce()).publishEvent(any(SignalEvent.class));
    }

    @Test
    void isFastestTfAligned_fastTfAligned_returnsTrue() {
        Map<String, BigDecimal> rsiValues = Map.of(
            "15m", new BigDecimal("25"), // aligned (<30)
            "1h", new BigDecimal("35"),  // not aligned
            "4h", new BigDecimal("38")   // not aligned
        );

        // Use reflection to test the private method
        java.lang.reflect.Method method;
        try {
            method = SignalDetectionService.class.getDeclaredMethod(
                "isFastestTfAligned", Map.class, String.class, boolean.class, double.class);
            method.setAccessible(true);
            
            Boolean result = (Boolean) method.invoke(
                signalDetectionService, rsiValues, "15m,1h,4h", true, 30.0);
            
            assertTrue(result, "Should be aligned when fastest TF is below threshold");
        } catch (Exception e) {
            fail("Reflection failed: " + e.getMessage());
        }
    }

    @Test
    void isFastestTfAligned_fastTfNotAligned_returnsFalse() {
        Map<String, BigDecimal> rsiValues = Map.of(
            "15m", new BigDecimal("35"), // not aligned (>=30)
            "1h", new BigDecimal("25"),  // aligned
            "4h", new BigDecimal("28")   // aligned
        );

        try {
            java.lang.reflect.Method method = SignalDetectionService.class.getDeclaredMethod(
                "isFastestTfAligned", Map.class, String.class, boolean.class, double.class);
            method.setAccessible(true);
            
            Boolean result = (Boolean) method.invoke(
                signalDetectionService, rsiValues, "15m,1h,4h", true, 30.0);
            
            assertFalse(result, "Should NOT be aligned when fastest TF is above threshold");
        } catch (Exception e) {
            fail("Reflection failed: " + e.getMessage());
        }
    }

    @Test
    void calculateSignalStrength_oversold_returnsDeviationFromThreshold() {
        Map<String, BigDecimal> rsiValues = Map.of(
            "15m", new BigDecimal("25"),
            "1h", new BigDecimal("28"),
            "4h", new BigDecimal("22")
        );

        try {
            java.lang.reflect.Method method = SignalDetectionService.class.getDeclaredMethod(
                "calculateSignalStrength", Map.class, com.trading.rsi.domain.SignalLog.SignalType.class, Instrument.class);
            method.setAccessible(true);
            
            Instrument instrument = createInstrument("BTCUSDT", "15m,1h,4h", Instrument.DataSource.BINANCE);
            
            BigDecimal result = (BigDecimal) method.invoke(
                signalDetectionService, rsiValues, com.trading.rsi.domain.SignalLog.SignalType.OVERSOLD, instrument);
            
            assertNotNull(result);
            // (5 + 2 + 8) / 3 = 5
            assertEquals(0, result.compareTo(new BigDecimal("5.0")),
                "Signal strength should be average deviation from threshold");
        } catch (Exception e) {
            fail("Reflection failed: " + e.getMessage());
        }
    }

    @Test
    void timeframeToMinutes_validTimeframes_returnsCorrectMinutes() {
        try {
            java.lang.reflect.Method method = SignalDetectionService.class.getDeclaredMethod(
                "timeframeToMinutes", String.class);
            method.setAccessible(true);
            
            assertEquals(1, method.invoke(signalDetectionService, "1m"));
            assertEquals(5, method.invoke(signalDetectionService, "5m"));
            assertEquals(15, method.invoke(signalDetectionService, "15m"));
            assertEquals(30, method.invoke(signalDetectionService, "30m"));
            assertEquals(60, method.invoke(signalDetectionService, "1h"));
            assertEquals(120, method.invoke(signalDetectionService, "2h"));
            assertEquals(240, method.invoke(signalDetectionService, "4h"));
            assertEquals(1440, method.invoke(signalDetectionService, "1d"));
        } catch (Exception e) {
            fail("Reflection failed: " + e.getMessage());
        }
    }

    @Test
    void timeframeToMinutes_invalidTimeframe_returnsLargeNumber() {
        try {
            java.lang.reflect.Method method = SignalDetectionService.class.getDeclaredMethod(
                "timeframeToMinutes", String.class);
            method.setAccessible(true);
            
            assertEquals(9999, method.invoke(signalDetectionService, "invalid"));
        } catch (Exception e) {
            fail("Reflection failed: " + e.getMessage());
        }
    }

    private Instrument createInstrument(String symbol, String timeframes, Instrument.DataSource source) {
        Instrument instrument = new Instrument();
        instrument.setSymbol(symbol);
        instrument.setName(symbol);
        instrument.setTimeframes(timeframes);
        instrument.setSource(source);
        instrument.setEnabled(true);
        instrument.setOversoldThreshold(30);
        instrument.setOverboughtThreshold(70);
        instrument.setType(Instrument.InstrumentType.CRYPTO);
        return instrument;
    }
}
