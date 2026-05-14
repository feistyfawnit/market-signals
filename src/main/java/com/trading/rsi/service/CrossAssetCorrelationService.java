package com.trading.rsi.service;

import com.trading.rsi.domain.Instrument;
import com.trading.rsi.repository.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monitors price movements across all enabled instruments and detects
 * when multiple asset classes move together (risk-on/risk-off).
 *
 * When oil spikes + indices drop + crypto drops simultaneously,
 * we're in a "risk-off" regime and should suppress buy signals.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CrossAssetCorrelationService {

    private final PriceHistoryService priceHistoryService;
    private final InstrumentRepository instrumentRepository;

    @Value("${rsi.correlation.lookback-minutes:15}")
    private int lookbackMinutes;

    @Value("${rsi.correlation.min-instruments-for-regime:3}")
    private int minInstrumentsForRegime;

    @Value("${rsi.correlation.price-change-threshold-pct:0.5}")
    private double priceChangeThresholdPct;

    // Cache of recent price snapshots per symbol
    private final Map<String, Deque<PriceSnapshot>> recentPrices = new ConcurrentHashMap<>();

    // Current regime state
    private volatile Regime currentRegime = Regime.NEUTRAL;
    private Instant lastRegimeChange = Instant.now();

    public enum Regime {
        RISK_ON,    // indices up, crypto up, oil flat/down
        RISK_OFF,   // indices down, crypto down, oil up
        NEUTRAL     // no clear direction
    }

    @PostConstruct
    void init() {
        log.info("CrossAssetCorrelationService initialized with lookback={}min, threshold={}%",
                lookbackMinutes, priceChangeThresholdPct);
    }

    /**
     * Called on every poll cycle to update price snapshots and detect regime changes.
     */
    public void update() {
        List<Instrument> instruments = instrumentRepository.findByEnabledTrue();
        Instant now = Instant.now();
        Instant cutoff = now.minus(Duration.ofMinutes(lookbackMinutes));

        // Classify each instrument's direction
        List<String> rising = new ArrayList<>();
        List<String> falling = new ArrayList<>();

        for (Instrument instrument : instruments) {
            BigDecimal currentPrice = priceHistoryService.getLatestPrice(instrument.getSymbol());
            if (currentPrice == null) continue;

            Deque<PriceSnapshot> snapshots = recentPrices.computeIfAbsent(
                    instrument.getSymbol(), key -> new ArrayDeque<>());

            PriceSnapshot baseline;
            synchronized (snapshots) {
                while (!snapshots.isEmpty() && snapshots.peekFirst().timestamp().isBefore(cutoff)) {
                    snapshots.removeFirst();
                }
                snapshots.addLast(new PriceSnapshot(currentPrice, now));
                baseline = snapshots.peekFirst();
            }

            if (baseline == null || baseline.price().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            double changePct = currentPrice.subtract(baseline.price())
                    .divide(baseline.price(), 8, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue();

            if (changePct > priceChangeThresholdPct) {
                rising.add(instrument.getSymbol());
            } else if (changePct < -priceChangeThresholdPct) {
                falling.add(instrument.getSymbol());
            }
        }

        // Prune deques for disabled instruments (prevent memory leak from stale cache entries)
        Set<String> enabledSymbols = instruments.stream()
                .map(Instrument::getSymbol)
                .collect(java.util.stream.Collectors.toSet());
        recentPrices.entrySet().removeIf(entry -> !enabledSymbols.contains(entry.getKey()));

        // Also remove empty deques
        recentPrices.entrySet().removeIf(entry -> entry.getValue().isEmpty());

        // Determine regime based on which asset classes are moving
        Regime newRegime = determineRegime(rising, falling, instruments);

        if (newRegime != currentRegime) {
            log.info("Regime change: {} -> {} (rising={}, falling={})",
                    currentRegime, newRegime, rising.size(), falling.size());
            currentRegime = newRegime;
            lastRegimeChange = now;
        }
    }

    private Regime determineRegime(List<String> rising, List<String> falling, List<Instrument> instruments) {
        // Classify instruments by type
        List<String> cryptoRising = new ArrayList<>();
        List<String> cryptoFalling = new ArrayList<>();
        List<String> indexRising = new ArrayList<>();
        List<String> indexFalling = new ArrayList<>();
        List<String> commodityRising = new ArrayList<>();
        List<String> commodityFalling = new ArrayList<>();

        for (Instrument inst : instruments) {
            String sym = inst.getSymbol();
            if (rising.contains(sym)) {
                switch (inst.getType()) {
                    case CRYPTO -> cryptoRising.add(sym);
                    case INDEX -> indexRising.add(sym);
                    case COMMODITY -> commodityRising.add(sym);
                    case FX -> {}
                }
            }
            if (falling.contains(sym)) {
                switch (inst.getType()) {
                    case CRYPTO -> cryptoFalling.add(sym);
                    case INDEX -> indexFalling.add(sym);
                    case COMMODITY -> commodityFalling.add(sym);
                    case FX -> {}
                }
            }
        }

        // Risk-off: commodities rising (oil/gold) + indices falling + crypto falling
        boolean commoditiesRising = commodityRising.size() >= 1;
        boolean indicesFalling = indexFalling.size() >= 1;
        boolean cryptoFallingFlag = cryptoFalling.size() >= 1;
        // Count asset classes (each category contributes 0 or 1), not individual instruments
        int totalMoving = (commoditiesRising ? 1 : 0) + (indicesFalling ? 1 : 0) + (cryptoFallingFlag ? 1 : 0);

        if (totalMoving >= minInstrumentsForRegime && commoditiesRising && (indicesFalling || cryptoFallingFlag)) {
            return Regime.RISK_OFF;
        }

        // Risk-on: indices rising + crypto rising
        boolean indicesRising = indexRising.size() >= 1;
        boolean cryptoRisingFlag = cryptoRising.size() >= 1;
        int totalRising = (indicesRising ? 1 : 0) + (cryptoRisingFlag ? 1 : 0);

        if (totalRising >= 2 && indicesRising && cryptoRisingFlag) {
            return Regime.RISK_ON;
        }

        return Regime.NEUTRAL;
    }

    public Regime getCurrentRegime() {
        return currentRegime;
    }

    public boolean isRiskOff() {
        return currentRegime == Regime.RISK_OFF;
    }

    public boolean isRiskOn() {
        return currentRegime == Regime.RISK_ON;
    }

    public Instant getLastRegimeChange() {
        return lastRegimeChange;
    }

    private record PriceSnapshot(BigDecimal price, Instant timestamp) {}
}
