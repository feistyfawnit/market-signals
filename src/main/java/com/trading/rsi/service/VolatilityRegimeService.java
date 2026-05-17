package com.trading.rsi.service;

import com.trading.rsi.domain.Instrument;
import com.trading.rsi.repository.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks ATR expansion across instruments to detect volatility regime changes.
 * When ATR spikes >1.5× its 20-period mean on multiple instruments,
 * we enter "high volatility" mode and should be cautious with new entries.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VolatilityRegimeService {

    private final AtrCalculator atrCalculator;
    private final InstrumentRepository instrumentRepository;

    @Value("${rsi.volatility.atr-period:14}")
    private int atrPeriod;

    @Value("${rsi.volatility.atr-lookback:20}")
    private int atrLookback;

    @Value("${rsi.volatility.expansion-threshold:1.5}")
    private double expansionThreshold;

    @Value("${rsi.volatility.min-instruments:2}")
    private int minInstruments;

    private volatile boolean highVolatility = false;
    private Instant lastVolatilityChange = Instant.now();
    private final Map<String, Double> lastExpansionRatios = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        log.info("VolatilityRegimeService initialized with period={}, lookback={}, threshold={}",
                atrPeriod, atrLookback, expansionThreshold);
    }

    /**
     * Called on every poll cycle to check volatility across instruments.
     */
    public void update() {
        List<Instrument> instruments = instrumentRepository.findByEnabledTrue();
        int expandedCount = 0;

        for (Instrument instrument : instruments) {
            // Use the shortest timeframe for volatility detection
            String shortestTf = pickShortestTimeframe(instrument.getTimeframes());
            if (shortestTf == null) continue;

            Optional<Double> ratio = atrCalculator.atrExpansionRatio(
                    instrument.getSymbol(), shortestTf, atrPeriod, atrLookback);

            if (ratio.isPresent()) {
                lastExpansionRatios.put(instrument.getSymbol(), ratio.get());
                if (ratio.get() >= expansionThreshold) {
                    expandedCount++;
                }
            }
        }

        boolean wasHighVol = highVolatility;
        highVolatility = expandedCount >= minInstruments;

        if (wasHighVol != highVolatility) {
            log.info("Volatility regime change: {} -> {} ({} instruments expanded)",
                    wasHighVol ? "HIGH" : "NORMAL",
                    highVolatility ? "HIGH" : "NORMAL",
                    expandedCount);
            lastVolatilityChange = Instant.now();
        }
    }

    public boolean isHighVolatility() {
        return highVolatility;
    }

    public Instant getLastVolatilityChange() {
        return lastVolatilityChange;
    }

    public Map<String, Double> getExpansionRatios() {
        return Map.copyOf(lastExpansionRatios);
    }

    private String pickShortestTimeframe(String timeframes) {
        if (timeframes == null || timeframes.isBlank()) return null;
        String shortest = null;
        int shortestMinutes = Integer.MAX_VALUE;
        for (String tf : timeframes.split(",")) {
            int mins = timeframeToMinutes(tf.trim());
            if (mins < shortestMinutes) {
                shortestMinutes = mins;
                shortest = tf.trim();
            }
        }
        return shortest;
    }

    private int timeframeToMinutes(String tf) {
        return switch (tf.toLowerCase()) {
            case "1m" -> 1;
            case "3m" -> 3;
            case "5m" -> 5;
            case "15m" -> 15;
            case "30m" -> 30;
            case "1h" -> 60;
            case "2h" -> 120;
            case "4h" -> 240;
            case "1d" -> 1440;
            default -> 9999;
        };
    }
}
