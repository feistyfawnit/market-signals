package com.trading.rsi.service;

import com.trading.rsi.event.SignalEvent;
import com.trading.rsi.model.Candle;
import com.trading.rsi.model.RsiSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.trading.rsi.domain.CandleHistory;
import com.trading.rsi.repository.CandleHistoryRepository;
import org.springframework.data.domain.Pageable;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
@Slf4j
public class AlertCsvService {

    private static final String ALERT_CSV_HEADER =
            "alert_id,symbol,instrument_name,signal_type,signal_time," +
            "current_price,rsi_1m,rsi_5m,rsi_15m,rsi_30m,rsi_1h,rsi_4h," +
            "timeframes_aligned,total_timeframes,signal_strength," +
            "candle_time,candle_open,candle_high,candle_low,candle_close,candle_volume," +
            "price_1h_later,price_4h_later,price_24h_later," +
            "hour_utc,day_of_week";

    private static final int COL_SYMBOL      = 1;
    private static final int COL_SIGNAL_TIME = 4;
    private static final int COL_PRICE_1H    = 21;
    private static final int COL_PRICE_4H    = 22;
    private static final int COL_PRICE_24H   = 23;
    private static final int MIN_COLUMNS_FOR_BACKFILL = 24; // price cols are 21-23

    private static final DateTimeFormatter MONTH_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);

    private final PriceHistoryService priceHistoryService;
    private final CandleHistoryRepository candleHistoryRepository;

    @Value("${archive.enabled:true}")
    private boolean enabled;

    @Value("${archive.dir:./signal_archive}")
    private String archiveDir;

    private final Map<String, ReentrantLock> fileLocks = new ConcurrentHashMap<>();

    public AlertCsvService(PriceHistoryService priceHistoryService,
                            CandleHistoryRepository candleHistoryRepository) {
        this.priceHistoryService = priceHistoryService;
        this.candleHistoryRepository = candleHistoryRepository;
    }

    /**
     * Fires on every SignalEvent (full, partial, or watch) and immediately appends
     * one row to signal_alerts_YYYY-MM.csv in archive.dir.
     * Outcome price columns are left blank for the backfill job to populate.
     */
    @EventListener
    @Async
    public void handleSignalEvent(SignalEvent event) {
        if (!enabled) return;

        RsiSignal signal = event.getSignal();
        Instant signalTime = Instant.now();
        String month = MONTH_FORMAT.format(signalTime);

        Path archivePath = Path.of(archiveDir);
        try {
            Files.createDirectories(archivePath);
        } catch (IOException e) {
            log.error("AlertCsv: failed to create archive directory '{}': {}", archiveDir, e.getMessage());
            return;
        }

        Path csvFile = archivePath.resolve("signal_alerts_" + month + ".csv");
        ReentrantLock lock = getLock(csvFile);
        lock.lock();
        try {
            boolean fileExists = Files.exists(csvFile);
            try (BufferedWriter writer = Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8,
                    fileExists ? StandardOpenOption.APPEND : StandardOpenOption.CREATE)) {
                if (!fileExists) {
                    writer.write(ALERT_CSV_HEADER);
                    writer.newLine();
                }
                writer.write(toAlertCsvRow(signal, signalTime));
                writer.newLine();
            }
            log.info("AlertCsv: wrote {} {} to {}", signal.getSymbol(), signal.getSignalType(), csvFile.getFileName());
        } catch (IOException e) {
            log.error("AlertCsv: failed to write alert row to '{}': {}", csvFile, e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs every hour. Scans all signal_alerts_*.csv files and backfills any
     * price_1h_later / price_4h_later / price_24h_later cells that are empty
     * and whose elapsed time threshold has now been reached.
     *
     * Uses the most recently known in-memory price for each symbol.
     * If the service was restarted and has no price yet, the row is skipped
     * and retried on the next hourly run.
     */
    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    public void backfillOutcomePrices() {
        if (!enabled) return;

        Path archivePath = Path.of(archiveDir);
        if (!Files.exists(archivePath)) return;

        List<Path> alertFiles;
        try {
            alertFiles = Files.list(archivePath)
                    .filter(p -> p.getFileName().toString().startsWith("signal_alerts_")
                              && p.getFileName().toString().endsWith(".csv"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.error("AlertCsv backfill: failed to list archive directory: {}", e.getMessage());
            return;
        }

        int totalUpdated = 0;
        for (Path csvFile : alertFiles) {
            totalUpdated += backfillFile(csvFile);
        }

        if (totalUpdated > 0) {
            log.info("AlertCsv backfill: filled {} outcome price cell(s) across {} file(s)",
                    totalUpdated, alertFiles.size());
        } else {
            log.debug("AlertCsv backfill: no outcome prices to fill this run");
        }
    }

    private int backfillFile(Path csvFile) {
        ReentrantLock lock = getLock(csvFile);
        lock.lock();
        try {
            List<String> lines = Files.readAllLines(csvFile, StandardCharsets.UTF_8);
            if (lines.size() <= 1) return 0;

            Instant now = Instant.now();
            boolean anyChanged = false;
            int updatedCells = 0;

            List<String> updated = new ArrayList<>(lines.size());
            updated.add(lines.get(0));

            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank()) {
                    updated.add(line);
                    continue;
                }
                String[] fields = line.split(",", -1);
                if (fields.length < MIN_COLUMNS_FOR_BACKFILL) {
                    updated.add(line);
                    continue;
                }

                Instant signalTime;
                try {
                    signalTime = Instant.parse(fields[COL_SIGNAL_TIME]);
                } catch (Exception e) {
                    updated.add(line);
                    continue;
                }

                String symbol = fields[COL_SYMBOL];
                long elapsedHours = Duration.between(signalTime, now).toHours();
                boolean changed = false;

                if (elapsedHours >= 1 && fields[COL_PRICE_1H].isEmpty()) {
                    BigDecimal price = resolvePrice(symbol, signalTime.plus(Duration.ofHours(1)));
                    if (price != null) {
                        fields[COL_PRICE_1H] = price.toPlainString();
                        changed = true;
                        updatedCells++;
                    }
                }
                if (elapsedHours >= 4 && fields[COL_PRICE_4H].isEmpty()) {
                    BigDecimal price = resolvePrice(symbol, signalTime.plus(Duration.ofHours(4)));
                    if (price != null) {
                        fields[COL_PRICE_4H] = price.toPlainString();
                        changed = true;
                        updatedCells++;
                    }
                }
                if (elapsedHours >= 24 && fields[COL_PRICE_24H].isEmpty()) {
                    BigDecimal price = resolvePrice(symbol, signalTime.plus(Duration.ofHours(24)));
                    if (price != null) {
                        fields[COL_PRICE_24H] = price.toPlainString();
                        changed = true;
                        updatedCells++;
                    }
                }

                updated.add(changed ? String.join(",", fields) : line);
                if (changed) anyChanged = true;
            }

            if (anyChanged) {
                Files.write(csvFile, updated, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
                log.debug("AlertCsv backfill: rewrote {} ({} cell(s) updated)", csvFile.getFileName(), updatedCells);
            }
            return updatedCells;

        } catch (IOException e) {
            log.error("AlertCsv backfill: error processing '{}': {}", csvFile, e.getMessage());
            return 0;
        } finally {
            lock.unlock();
        }
    }

    private String toAlertCsvRow(RsiSignal signal, Instant signalTime) {
        Map<String, BigDecimal> rsi = signal.getRsiValues();
        Candle c = signal.getTriggerCandle();

        ZonedDateTime signalUtc = signalTime.atZone(ZoneOffset.UTC);
        return String.join(",",
                UUID.randomUUID().toString(),
                csvEscape(signal.getSymbol()),
                csvEscape(signal.getInstrumentName()),
                signal.getSignalType().name(),
                signalTime.toString(),
                nullOrValue(signal.getCurrentPrice()),
                nullOrValue(rsi.get("1m")),
                nullOrValue(rsi.get("5m")),
                nullOrValue(rsi.get("15m")),
                nullOrValue(rsi.get("30m")),
                nullOrValue(rsi.get("1h")),
                nullOrValue(rsi.get("4h")),
                String.valueOf(signal.getTimeframesAligned()),
                String.valueOf(signal.getTotalTimeframes()),
                nullOrValue(signal.getSignalStrength()),
                c != null ? nullOrValue(c.getTimestamp()) : "",
                c != null ? nullOrValue(c.getOpen())      : "",
                c != null ? nullOrValue(c.getHigh())      : "",
                c != null ? nullOrValue(c.getLow())       : "",
                c != null ? nullOrValue(c.getClose())     : "",
                c != null ? nullOrValue(c.getVolume())    : "",
                "",
                "",
                "",
                String.valueOf(signalUtc.getHour()),
                signalUtc.getDayOfWeek().name()
        );
    }

    private BigDecimal resolvePrice(String symbol, Instant targetInstant) {
        List<CandleHistory> candles = candleHistoryRepository
                .findBySymbolAndCandleTimeLessThanEqualOrderByCandleTimeDesc(symbol, targetInstant, Pageable.ofSize(1));
        if (!candles.isEmpty()) return candles.get(0).getClose();
        BigDecimal price = priceHistoryService.getLatestPrice(symbol);
        if (price != null) return price;
        List<CandleHistory> latest = candleHistoryRepository
                .findBySymbolOrderByCandleTimeDesc(symbol, Pageable.ofSize(1));
        return latest.isEmpty() ? null : latest.get(0).getClose();
    }

    private ReentrantLock getLock(Path file) {
        return fileLocks.computeIfAbsent(file.toAbsolutePath().toString(), k -> new ReentrantLock());
    }

    private String csvEscape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String nullOrValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
