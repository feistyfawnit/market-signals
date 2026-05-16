package com.trading.rsi.config;

import com.trading.rsi.domain.Instrument;
import com.trading.rsi.repository.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final InstrumentRepository instrumentRepository;
    private final WatchlistProperties watchlistProperties;

    @Override
    public void run(ApplicationArguments args) {
        if (watchlistProperties.getInstruments() == null || watchlistProperties.getInstruments().isEmpty()) {
            log.warn("No instruments configured in application.yml watchlist section");
            return;
        }

        int seeded = 0;
        int updated = 0;
        for (WatchlistProperties.InstrumentConfig config : watchlistProperties.getInstruments()) {
            var existing = instrumentRepository.findBySymbol(config.getSymbol());
            if (existing.isEmpty()) {
                Instrument instrument = Instrument.builder()
                        .symbol(config.getSymbol())
                        .name(config.getName())
                        .source(config.getSource())
                        .type(config.getType())
                        .enabled(config.getEnabled())
                        .oversoldThreshold(config.getOversoldThreshold())
                        .overboughtThreshold(config.getOverboughtThreshold())
                        .timeframes(config.getTimeframes())
                        .trendBuyDipEnabled(config.getTrendBuyDipEnabled())
                        .trendBuyDipNotify(config.getTrendBuyDipNotify())
                        .rsiSignalsEnabled(config.getRsiSignalsEnabled())
                        .igEpic(config.getIgEpic())
                        .build();
                instrumentRepository.save(instrument);
                log.info("Seeded instrument: {} ({})", config.getName(), config.getSymbol());
                seeded++;
            } else {
                Instrument instrument = existing.get();
                instrument.setName(config.getName());
                // Preserve enabled state from DB - don't overwrite from YAML
                // This allows runtime API toggles to persist across restarts
                instrument.setSource(config.getSource());
                instrument.setType(config.getType());
                instrument.setOversoldThreshold(config.getOversoldThreshold());
                instrument.setOverboughtThreshold(config.getOverboughtThreshold());
                instrument.setTimeframes(config.getTimeframes());
                // trendBuyDipEnabled / trendBuyDipNotify are YAML-controlled (strategy config, not runtime state)
                instrument.setTrendBuyDipEnabled(config.getTrendBuyDipEnabled());
                instrument.setTrendBuyDipNotify(config.getTrendBuyDipNotify());
                instrument.setRsiSignalsEnabled(config.getRsiSignalsEnabled());
                instrument.setIgEpic(config.getIgEpic());
                instrumentRepository.save(instrument);
                log.debug("Synced instrument from YAML: {} (enabled={} - preserved from DB)", config.getSymbol(), instrument.getEnabled());
                updated++;
            }
        }

        if (seeded > 0 || updated > 0) {
            log.info("Data initializer complete: {} seeded, {} synced from YAML", seeded, updated);
        }

        // Disable (don't delete) DB instruments not present in YAML config.
        // Candle history is preserved so it can be reused if the instrument returns.
        // Stale rows won't be polled because they'll be disabled.
        List<String> yamlSymbols = watchlistProperties.getInstruments().stream()
                .map(WatchlistProperties.InstrumentConfig::getSymbol)
                .toList();
        List<Instrument> stale = instrumentRepository.findBySymbolNotIn(yamlSymbols);
        if (!stale.isEmpty()) {
            for (Instrument orphan : stale) {
                if (orphan.getEnabled()) {
                    orphan.setEnabled(false);
                    instrumentRepository.save(orphan);
                    log.warn("Disabled stale instrument not in YAML: {} ({}) — candle history retained",
                            orphan.getName(), orphan.getSymbol());
                }
            }
        }

        var enabled = instrumentRepository.findByEnabledTrue();
        log.info("Monitoring {} enabled instruments:", enabled.size());
        for (var inst : enabled) {
            log.info("  {} ({}) — source={}, timeframes={}", inst.getName(), inst.getSymbol(), inst.getSource(), inst.getTimeframes());
        }

        long igCount = enabled.stream().filter(i -> i.getSource() == Instrument.DataSource.IG).count();
        long binanceCount = enabled.stream().filter(i -> i.getSource() == Instrument.DataSource.BINANCE).count();
        if (igCount > 0) {
            log.info("IG instruments: {} configured — requires IG_ENABLED=true + credentials (market hours 08:00-22:00 UTC)", igCount);
        }
        if (binanceCount > 0) {
            log.info("Binance instruments: {} configured — active 24/7", binanceCount);
        }
    }
}
