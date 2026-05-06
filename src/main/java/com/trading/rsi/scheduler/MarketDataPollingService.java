package com.trading.rsi.scheduler;

import com.trading.rsi.domain.Instrument;
import com.trading.rsi.model.Candle;
import com.trading.rsi.repository.InstrumentRepository;
import com.trading.rsi.service.CrossAssetCorrelationService;
import com.trading.rsi.service.IGMarketDataClient;
import com.trading.rsi.service.MarketDataService;
import com.trading.rsi.service.PriceHistoryService;
import com.trading.rsi.service.SignalDetectionService;
import com.trading.rsi.service.VolatilityRegimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class MarketDataPollingService {
    
    private final InstrumentRepository instrumentRepository;
    private final IGMarketDataClient igMarketDataClient;
    private final MarketDataService marketDataService;
    private final PriceHistoryService priceHistoryService;
    private final SignalDetectionService signalDetectionService;
    private final CrossAssetCorrelationService crossAssetCorrelationService;
    private final VolatilityRegimeService volatilityRegimeService;
    
    @Value("${rsi.market-hours.ig-start-utc:6}")
    private int igMarketStartUtc;

    @Value("${rsi.market-hours.ig-end-utc:22}")
    private int igMarketEndUtc;

    @Value("${rsi.market-hours.ig-sunday-start-utc:22}")
    private int igSundayStartUtc;

    // Track last candle timestamp per instrument+timeframe to avoid duplicate updates
    private final Map<String, Instant> lastCandleTimestamps = new ConcurrentHashMap<>();

    // Track last IG fetch time per instrument+timeframe to skip calls when candle period hasn't elapsed
    private final Map<String, Instant> lastIgFetchTime = new ConcurrentHashMap<>();

    // Log IG market-closed skip once per instrument per session (avoids log spam)
    private final Set<String> igSkipLoggedOnce = ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelayString = "${rsi.polling.interval-seconds:30}000")
    public void pollMarketData() {
        List<Instrument> instruments = instrumentRepository.findByEnabledTrue();
        
        if (instruments.isEmpty()) {
            log.debug("No enabled instruments to monitor");
            return;
        }
        
        // Binance instruments: poll at full speed (no weekly data point cap)
        List<Instrument> binanceInstruments = instruments.stream()
                .filter(i -> i.getSource() != Instrument.DataSource.IG)
                .toList();
        
        log.debug("Polling {} Binance instruments", binanceInstruments.size());
        
        // Update regime detection on each poll cycle
        crossAssetCorrelationService.update();
        volatilityRegimeService.update();
        
        for (int i = 0; i < binanceInstruments.size(); i++) {
            Instrument instrument = binanceInstruments.get(i);
            try {
                if (i > 0) {
                    Thread.sleep(500);
                }
                updateInstrumentData(instrument);
                signalDetectionService.analyzeInstrument(instrument);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Polling interrupted");
                return;
            } catch (Exception e) {
                log.error("Error processing instrument {}: {}", instrument.getSymbol(), e.getMessage(), e);
            }
        }
    }

    // IG instruments: separate slower poll to stay within 10,000 data points/week allowance
    @Scheduled(fixedDelayString = "${rsi.polling.ig-interval-seconds:900}000")
    public void pollIgMarketData() {
        List<Instrument> igInstruments = instrumentRepository.findByEnabledTrue().stream()
                .filter(i -> i.getSource() == Instrument.DataSource.IG)
                .toList();

        if (igInstruments.isEmpty()) {
            return;
        }

        if (igMarketDataClient.isCircuitOpen()) {
            log.debug("IG circuit breaker open — skipping all {} IG instruments", igInstruments.size());
            return;
        }

        log.debug("Polling {} IG instruments", igInstruments.size());

        for (int i = 0; i < igInstruments.size(); i++) {
            Instrument instrument = igInstruments.get(i);
            try {
                if (i > 0) {
                    Thread.sleep(1000);
                }
                if (isIgOutsideMarketHours(instrument)) {
                    if (igSkipLoggedOnce.add(instrument.getSymbol())) {
                        int marketClose = instrument.getMarketCloseUtc() != null 
                                ? instrument.getMarketCloseUtc() 
                                : igMarketEndUtc;
                        log.info("Skipping {} ({}) — IG market closed (outside {}:00–{}:00 UTC)",
                                instrument.getName(), instrument.getSymbol(), igMarketStartUtc, marketClose);
                    }
                    continue;
                } else {
                    igSkipLoggedOnce.remove(instrument.getSymbol());
                }
                updateIgInstrumentData(instrument);
                signalDetectionService.analyzeInstrument(instrument);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("IG polling interrupted");
                return;
            } catch (Exception e) {
                log.error("Error processing IG instrument {}: {}", instrument.getSymbol(), e.getMessage(), e);
            }
        }
    }
    
    private boolean isIgOutsideMarketHours(Instrument instrument) {
        if (instrument.getSource() != Instrument.DataSource.IG) {
            return false;
        }
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        int hourUtc = now.getHour();
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY) return true;
        if (day == DayOfWeek.SUNDAY) return hourUtc < igSundayStartUtc;
        
        // Use per-instrument market close time if configured, otherwise fall back to global
        int marketClose = instrument.getMarketCloseUtc() != null 
                ? instrument.getMarketCloseUtc() 
                : igMarketEndUtc;
        return hourUtc < igMarketStartUtc || hourUtc >= marketClose;
    }

    private void updateInstrumentData(Instrument instrument) {
        List<String> timeframes = Arrays.asList(instrument.getTimeframes().split(","));
        
        for (String timeframe : timeframes) {
            String trimmedTimeframe = timeframe.trim();
            String timestampKey = instrument.getSymbol() + ":" + trimmedTimeframe;
            
            marketDataService.fetchCandles(instrument, trimmedTimeframe, 1)
                    .subscribe(
                            candles -> handleCandleResponse(instrument, trimmedTimeframe, timestampKey, candles),
                            error -> handleFetchError(instrument.getSymbol(), trimmedTimeframe, error)
                    );
        }
    }

    // IG-specific: skip API call if the candle period hasn't elapsed yet (saves data points)
    private void updateIgInstrumentData(Instrument instrument) {
        List<String> timeframes = Arrays.asList(instrument.getTimeframes().split(","));

        for (String timeframe : timeframes) {
            String trimmedTimeframe = timeframe.trim();
            String timestampKey = instrument.getSymbol() + ":" + trimmedTimeframe;

            // Skip if we already fetched within this candle's period
            Instant lastFetch = lastIgFetchTime.get(timestampKey);
            if (lastFetch != null) {
                Duration candlePeriod = timeframeToDuration(trimmedTimeframe);
                if (candlePeriod != null && Instant.now().isBefore(lastFetch.plus(candlePeriod))) {
                    log.debug("Skipping IG fetch for {} {} — candle period not elapsed",
                            instrument.getSymbol(), trimmedTimeframe);
                    continue;
                }
            }

            marketDataService.fetchCandles(instrument, trimmedTimeframe, 1)
                    .subscribe(
                            candles -> {
                                lastIgFetchTime.put(timestampKey, Instant.now());
                                handleCandleResponse(instrument, trimmedTimeframe, timestampKey, candles);
                            },
                            error -> handleFetchError(instrument.getSymbol(), trimmedTimeframe, error)
                    );
        }
    }

    private void handleCandleResponse(Instrument instrument, String timeframe, String timestampKey, List<Candle> candles) {
        if (!candles.isEmpty()) {
            Candle latestCandle = candles.get(0);
            Instant candleTimestamp = latestCandle.getTimestamp();

            // Only update if this is a new candle we haven't seen
            Instant lastTimestamp = lastCandleTimestamps.get(timestampKey);
            if (lastTimestamp == null || candleTimestamp.isAfter(lastTimestamp)) {
                String key = priceHistoryService.buildKey(instrument.getSymbol(), timeframe);
                priceHistoryService.updatePriceHistory(key, latestCandle);
                lastCandleTimestamps.put(timestampKey, candleTimestamp);
                log.info("Updated {} {} with new candle at {}: {}",
                        instrument.getSymbol(), timeframe,
                        candleTimestamp, latestCandle.getClose());
            } else {
                log.debug("Skipping duplicate candle for {} {} at {} (last was {})",
                        instrument.getSymbol(), timeframe,
                        candleTimestamp, lastTimestamp);
            }
        }
    }

    private void handleFetchError(String symbol, String timeframe, Throwable error) {
        String msg = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        if (msg.contains("400") || msg.contains("market closed") || msg.contains("data unavailable")) {
            log.debug("Market data unavailable for {} {} (market likely closed): {}",
                    symbol, timeframe, msg);
        } else if (msg.contains("exceeded-account-historical-data-allowance") || msg.contains("allowance")) {
            log.error("IG historical data allowance EXCEEDED for {} {} — all IG polling will fail until weekly reset. " +
                    "Reduce IG instrument count or increase ig-interval-seconds.", symbol, timeframe);
        } else {
            log.warn("Failed to fetch candles for {} {}: {}", symbol, timeframe, msg);
        }
    }

    private Duration timeframeToDuration(String timeframe) {
        return switch (timeframe.toLowerCase()) {
            case "1m" -> Duration.ofMinutes(1);
            case "5m" -> Duration.ofMinutes(5);
            case "15m" -> Duration.ofMinutes(15);
            case "30m" -> Duration.ofMinutes(30);
            case "1h" -> Duration.ofHours(1);
            case "2h" -> Duration.ofHours(2);
            case "4h" -> Duration.ofHours(4);
            case "1d" -> Duration.ofDays(1);
            default -> null;
        };
    }
}
