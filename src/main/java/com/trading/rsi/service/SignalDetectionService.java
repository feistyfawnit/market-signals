package com.trading.rsi.service;

import com.trading.rsi.domain.Instrument;
import com.trading.rsi.domain.SignalLog;
import com.trading.rsi.event.SignalEvent;
import com.trading.rsi.model.Candle;
import com.trading.rsi.model.RsiSignal;
import com.trading.rsi.model.StochasticResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class SignalDetectionService {
    
    private final RsiCalculator rsiCalculator;
    private final StochasticCalculator stochasticCalculator;
    private final IGMarketDataClient igMarketDataClient;
    private final PriceHistoryService priceHistoryService;
    private final MarketDataService marketDataService;
    private final ApplicationEventPublisher eventPublisher;
    private final SignalCooldownService cooldownService;
    private final PartialSignalMonitorService partialSignalMonitorService;
    private final TrendDetectionService trendDetectionService;
    private final CrossAssetCorrelationService crossAssetCorrelationService;
    private final VolatilityRegimeService volatilityRegimeService;
    private final FilterEventCounterService filterEventCounterService;
    
    @Value("${rsi.period:14}")
    private int rsiPeriod;

    @Value("${rsi.watch-proximity-threshold:40}")
    private int watchProximityThreshold;

    @Value("${rsi.partial-require-fast-tf-aligned:true}")
    private boolean partialRequireFastTfAligned;

    @Value("${rsi.watch-signals.enabled:false}")
    private boolean watchSignalsEnabled;

    private static final int MINIMUM_HISTORY = 28;
    private static final long[] BACKOFF_MINUTES = {2, 5, 15, 60};

    private final Map<String, Integer> warmupFailures = new ConcurrentHashMap<>();
    private final Map<String, Instant> warmupBackoffUntil = new ConcurrentHashMap<>();
    
    public void analyzeInstrument(Instrument instrument) {
        List<String> timeframes = Arrays.asList(instrument.getTimeframes().split(","));
        Map<String, BigDecimal> rsiValues = new HashMap<>();
        BigDecimal currentPrice = null;
        Candle triggerCandle = null;
        
        for (String timeframe : timeframes) {
            String key = priceHistoryService.buildKey(instrument.getSymbol(), timeframe.trim());
            log.debug("Analyzing {} with key: {}", instrument.getSymbol(), key);
            
            if (!priceHistoryService.hasMinimumHistory(key, MINIMUM_HISTORY)) {
                if (isWarmupBackedOff(key)) {
                    log.debug("Warmup backoff active for {} {} — next retry at {}", instrument.getSymbol(), timeframe.trim(), warmupBackoffUntil.get(key));
                    continue;
                }
                // Skip IG warmup if circuit breaker is open (allowance exceeded)
                if (instrument.getSource() == Instrument.DataSource.IG && igMarketDataClient.isCircuitOpen()) {
                    log.debug("Skipping warmup for {} {} — IG circuit breaker open", instrument.getSymbol(), timeframe.trim());
                    continue;
                }
                log.info("Insufficient history for {} {}, warming up...", instrument.getSymbol(), timeframe);
                warmupHistory(instrument, timeframe.trim(), key);
            }
            
            List<BigDecimal> history = priceHistoryService.getPriceHistory(key);
            
            if (history.isEmpty()) {
                log.warn("No history available for {} {} (key: {})", instrument.getSymbol(), timeframe, key);
                continue;
            }
            
            log.debug("{} {} history size={} last={}",
                    instrument.getSymbol(), timeframe.trim(), history.size(), history.get(history.size() - 1));
            
            if (currentPrice == null) {
                currentPrice = history.get(history.size() - 1);
                triggerCandle = priceHistoryService.getLatestCandle(key);
            }
            
            BigDecimal rsi = rsiCalculator.calculateRsi(history, rsiPeriod);
            
            if (rsi != null) {
                rsiValues.put(timeframe.trim(), rsi);
                log.debug("{} {} RSI: {}", instrument.getSymbol(), timeframe, rsi);
            } else {
                log.warn("RSI calculation returned null for {} {}", instrument.getSymbol(), timeframe);
            }
        }
        
        if (rsiValues.isEmpty() || currentPrice == null) {
            log.debug("Insufficient data to analyze {}", instrument.getSymbol());
            return;
        }
        
        detectSignals(instrument, rsiValues, currentPrice, timeframes, triggerCandle);

        // Check for trend-following entries (buy-the-dip / sell-the-rally)
        trendDetectionService.checkForTrendEntry(instrument, rsiValues, currentPrice, triggerCandle);

        // Update active partial monitoring on every poll cycle
        partialSignalMonitorService.updatePartials(instrument.getSymbol(), rsiValues);
    }
    
    private void detectSignals(Instrument instrument, Map<String, BigDecimal> rsiValues,
                               BigDecimal currentPrice, List<String> timeframeList, Candle triggerCandle) {
        int totalTimeframes = timeframeList.size();
        
        int oversoldCount = 0;
        int overboughtCount = 0;

        for (BigDecimal rsi : rsiValues.values()) {
            if (rsi.compareTo(BigDecimal.valueOf(instrument.getOversoldThreshold())) < 0) {
                oversoldCount++;
            }
            if (rsi.compareTo(BigDecimal.valueOf(instrument.getOverboughtThreshold())) > 0) {
                overboughtCount++;
            }
        }
        
        SignalLog.SignalType signalType = null;
        int alignedCount = 0;
        
        // Count how many TFs are within the proximity band (threshold < rsi < proximity)
        int oversoldProximityCount = 0;
        int overboughtProximityCount = 0;
        for (BigDecimal rsi : rsiValues.values()) {
            double val = rsi.doubleValue();
            if (val >= instrument.getOversoldThreshold() && val < watchProximityThreshold) {
                oversoldProximityCount++;
            }
            if (val <= instrument.getOverboughtThreshold() && val > (100 - watchProximityThreshold)) {
                overboughtProximityCount++;
            }
        }

        if (oversoldCount == totalTimeframes) {
            signalType = SignalLog.SignalType.OVERSOLD;
            alignedCount = oversoldCount;
        } else if (oversoldCount >= totalTimeframes - 1 && oversoldCount > 0) {
            if (!partialRequireFastTfAligned || isFastestTfAligned(rsiValues, instrument.getTimeframes(), true, instrument.getOversoldThreshold())) {
                signalType = SignalLog.SignalType.PARTIAL_OVERSOLD;
                alignedCount = oversoldCount;
            } else {
                log.debug("Suppressing PARTIAL_OVERSOLD for {} — fastest TF is lagging (momentum turning)", instrument.getName());
            }
        } else if (overboughtCount == totalTimeframes) {
            signalType = SignalLog.SignalType.OVERBOUGHT;
            alignedCount = overboughtCount;
        } else if (overboughtCount >= totalTimeframes - 1 && overboughtCount > 0) {
            if (!partialRequireFastTfAligned || isFastestTfAligned(rsiValues, instrument.getTimeframes(), false, instrument.getOverboughtThreshold())) {
                signalType = SignalLog.SignalType.PARTIAL_OVERBOUGHT;
                alignedCount = overboughtCount;
            } else {
                log.debug("Suppressing PARTIAL_OVERBOUGHT for {} — fastest TF is lagging (momentum turning)", instrument.getName());
            }
        } else if (watchSignalsEnabled && oversoldCount >= 1 && oversoldProximityCount >= 1) {
            signalType = SignalLog.SignalType.WATCH_OVERSOLD;
            alignedCount = oversoldCount;
        } else if (watchSignalsEnabled && overboughtCount >= 1 && overboughtProximityCount >= 1) {
            signalType = SignalLog.SignalType.WATCH_OVERBOUGHT;
            alignedCount = overboughtCount;
        }
        
        if (signalType != null && cooldownService.shouldAlert(instrument.getSymbol(), signalType)) {
            // Suppress buy signals during risk-off regime (oil spikes + indices/crypto falling)
            boolean isBuySignal = signalType == SignalLog.SignalType.OVERSOLD
                    || signalType == SignalLog.SignalType.PARTIAL_OVERSOLD
                    || signalType == SignalLog.SignalType.WATCH_OVERSOLD
                    || signalType == SignalLog.SignalType.TREND_BUY_DIP;
            if (isBuySignal && crossAssetCorrelationService.isRiskOff()) {
                log.info("RISK-OFF SUPPRESSED: {} {} — risk-off regime active (oil spiking, indices/crypto falling)",
                        signalType, instrument.getSymbol());
                filterEventCounterService.record("RISK_OFF", instrument.getSymbol());
                return;
            }

            // Suppress sell signals during risk-on regime (indices + crypto rising)
            boolean isSellSignal = signalType == SignalLog.SignalType.OVERBOUGHT
                    || signalType == SignalLog.SignalType.PARTIAL_OVERBOUGHT
                    || signalType == SignalLog.SignalType.WATCH_OVERBOUGHT
                    || signalType == SignalLog.SignalType.TREND_SELL_RALLY;
            if (isSellSignal && crossAssetCorrelationService.isRiskOn()) {
                log.info("RISK-ON SUPPRESSED: {} {} — risk-on regime active (indices/crypto rising)",
                        signalType, instrument.getSymbol());
                filterEventCounterService.record("RISK_ON", instrument.getSymbol());
                return;
            }

            // Suppress all signals during high volatility (ATR expansion across multiple instruments)
            if (volatilityRegimeService.isHighVolatility()) {
                log.info("HIGH VOLATILITY SUPPRESSED: {} {} — ATR expansion across {} instruments",
                        signalType, instrument.getSymbol(),
                        volatilityRegimeService.getExpansionRatios().size());
                filterEventCounterService.record("HIGH_VOLATILITY", instrument.getSymbol());
                return;
            }

            boolean isFullSignal = alignedCount >= totalTimeframes;

            // Suppress partial/watch signals for crypto (volatile, need full alignment for reliable signals)
            if (instrument.isCrypto() && !isFullSignal) {
                log.debug("Suppressing {} for {} — crypto requires full signal alignment (avoid partial noise)",
                        signalType, instrument.getName());
                return;
            }

            // Record full signals for trend tracking
            if (isFullSignal) {
                trendDetectionService.recordSignal(instrument.getSymbol(), signalType);
            }

            // Suppress counter-trend signals in strong trends
            if (isFullSignal && trendDetectionService.shouldSuppressCounterTrend(instrument.getSymbol(), signalType)) {
                log.info("Counter-trend {} suppressed for {} — trend override active",
                        signalType, instrument.getName());
                return;
            }

            Map<String, StochasticResult> stochasticValues = isFullSignal
                    ? calculateStochastics(instrument, timeframeList)
                    : null;

            RsiSignal signal = Objects.requireNonNull(RsiSignal.builder()
                    .symbol(instrument.getSymbol())
                    .instrumentName(instrument.getName())
                    .signalType(signalType)
                    .currentPrice(currentPrice)
                    .rsiValues(rsiValues)
                    .timeframesAligned(alignedCount)
                    .totalTimeframes(totalTimeframes)
                    .signalStrength(calculateSignalStrength(rsiValues, signalType, instrument))
                    .triggerCandle(triggerCandle)
                    .stochasticValues(stochasticValues)
                    .build());
            
            log.info("Signal detected: {} {} - {} timeframes aligned, RSI values: {}", 
                    instrument.getName(), signalType, alignedCount, rsiValues);
            
            eventPublisher.publishEvent(new SignalEvent(this, signal));
            cooldownService.recordAlert(instrument.getSymbol(), signalType);

            // Register active monitoring for PARTIAL signals so the lagging TF is tracked
            if (signalType == SignalLog.SignalType.PARTIAL_OVERSOLD
                    || signalType == SignalLog.SignalType.PARTIAL_OVERBOUGHT) {
                boolean isOversold = signalType == SignalLog.SignalType.PARTIAL_OVERSOLD;
                double threshold = isOversold
                        ? instrument.getOversoldThreshold()
                        : instrument.getOverboughtThreshold();
                // Find the lagging timeframe (the one NOT past the threshold)
                for (Map.Entry<String, BigDecimal> entry : rsiValues.entrySet()) {
                    boolean aligned = isOversold
                            ? entry.getValue().doubleValue() < threshold
                            : entry.getValue().doubleValue() > threshold;
                    if (!aligned) {
                        partialSignalMonitorService.registerPartial(
                                instrument.getSymbol(), instrument.getName(),
                                signalType, entry.getKey(), entry.getValue(), threshold);
                        break;
                    }
                }
            }
        }
    }
    
    /**
     * Returns true if the fastest (shortest-period) configured timeframe is on the aligned side.
     * For PARTIAL_OVERBOUGHT: the fast TF must be >threshold.
     * For PARTIAL_OVERSOLD: the fast TF must be <threshold.
     * If the slow TFs are aligned but the fast TF is lagging, momentum has already turned — suppress.
     */
    private boolean isFastestTfAligned(Map<String, BigDecimal> rsiValues, String timeframesConfig,
                                        boolean oversold, double threshold) {
        String fastestTf = Arrays.stream(timeframesConfig.split(","))
                .map(String::trim)
                .min(Comparator.comparingInt(this::timeframeToMinutes))
                .orElse(null);
        if (fastestTf == null) return true;
        BigDecimal fastRsi = rsiValues.get(fastestTf);
        if (fastRsi == null) return true;
        return oversold
                ? fastRsi.doubleValue() < threshold
                : fastRsi.doubleValue() > threshold;
    }

    private int timeframeToMinutes(String tf) {
        return switch (tf.toLowerCase()) {
            case "1m"  -> 1;
            case "3m"  -> 3;
            case "5m"  -> 5;
            case "10m" -> 10;
            case "15m" -> 15;
            case "30m" -> 30;
            case "1h"  -> 60;
            case "2h"  -> 120;
            case "4h"  -> 240;
            case "1d"  -> 1440;
            default    -> 9999;
        };
    }

    private Map<String, StochasticResult> calculateStochastics(Instrument instrument, List<String> timeframes) {
        Map<String, StochasticResult> results = new HashMap<>();
        for (String tf : timeframes) {
            String key = priceHistoryService.buildKey(instrument.getSymbol(), tf.trim());
            StochasticResult stoch = stochasticCalculator.calculate(priceHistoryService.getCandleHistory(key));
            if (stoch != null) {
                results.put(tf.trim(), stoch);
                log.debug("Stochastic {} {}: K={} D={} ({})", instrument.getSymbol(), tf.trim(), stoch.k(), stoch.d(), stoch.label());
            }
        }
        return results.isEmpty() ? null : results;
    }

    private BigDecimal calculateSignalStrength(Map<String, BigDecimal> rsiValues, SignalLog.SignalType signalType, Instrument instrument) {
        BigDecimal totalDeviation = BigDecimal.ZERO;
        
        for (BigDecimal rsi : rsiValues.values()) {
            if (signalType == SignalLog.SignalType.OVERSOLD || signalType == SignalLog.SignalType.PARTIAL_OVERSOLD
                    || signalType == SignalLog.SignalType.WATCH_OVERSOLD) {
                totalDeviation = totalDeviation.add(BigDecimal.valueOf(instrument.getOversoldThreshold()).subtract(rsi).abs());
            } else {
                totalDeviation = totalDeviation.add(rsi.subtract(BigDecimal.valueOf(instrument.getOverboughtThreshold())).abs());
            }
        }
        
        return totalDeviation.divide(BigDecimal.valueOf(rsiValues.size()), 4, RoundingMode.HALF_UP);
    }
    
    private boolean isWarmupBackedOff(String key) {
        Instant backoffUntil = warmupBackoffUntil.get(key);
        return backoffUntil != null && Instant.now().isBefore(backoffUntil);
    }

    private void recordWarmupFailure(String key, String symbol, String timeframe) {
        int failures = warmupFailures.merge(key, 1, (a, b) -> a + b);
        int backoffIndex = Math.min(failures - 1, BACKOFF_MINUTES.length - 1);
        long backoffMins = BACKOFF_MINUTES[backoffIndex];
        warmupBackoffUntil.put(key, Instant.now().plusSeconds(backoffMins * 60));
        log.warn("Warmup failed for {} {} (attempt {}) — backing off for {} min",
                symbol, timeframe, failures, backoffMins);
    }

    private void recordWarmupSuccess(String key) {
        warmupFailures.remove(key);
        warmupBackoffUntil.remove(key);
    }

    private void warmupHistory(Instrument instrument, String timeframe, String key) {
        try {
            // WARNING: Blocking call - should only be called from non-reactive threads
            // (e.g., @Scheduled methods). Calling from reactive pipelines will cause errors.
            List<Candle> candles = marketDataService.fetchCandles(instrument, timeframe, MINIMUM_HISTORY)
                    .block(Duration.ofSeconds(10));

            if (candles != null && !candles.isEmpty()) {
                BigDecimal firstPrice = candles.get(0).getClose();
                BigDecimal lastPrice = candles.get(candles.size() - 1).getClose();
                BigDecimal minPrice = firstPrice;
                BigDecimal maxPrice = firstPrice;
                for (Candle candle : candles) {
                    BigDecimal close = candle.getClose();
                    if (close.compareTo(minPrice) < 0) minPrice = close;
                    if (close.compareTo(maxPrice) > 0) maxPrice = close;
                }

                log.info("Warmup {} {}: {} candles, first={}, last={}, min={}, max={}, range={}",
                        instrument.getSymbol(), timeframe, candles.size(),
                        firstPrice, lastPrice, minPrice, maxPrice, maxPrice.subtract(minPrice));

                candles.forEach(candle -> priceHistoryService.updatePriceHistory(key, candle));
                recordWarmupSuccess(key);
            } else {
                recordWarmupFailure(key, instrument.getSymbol(), timeframe);
            }
        } catch (Exception e) {
            log.warn("Error warming up history for {} {}: {}", instrument.getSymbol(), timeframe, e.getMessage());
            recordWarmupFailure(key, instrument.getSymbol(), timeframe);
        }
    }
}
