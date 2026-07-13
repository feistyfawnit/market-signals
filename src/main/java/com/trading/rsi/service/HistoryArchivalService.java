package com.trading.rsi.service;

import com.trading.rsi.domain.CandleHistory;
import com.trading.rsi.domain.PositionOutcome;
import com.trading.rsi.domain.SignalLog;
import com.trading.rsi.repository.CandleHistoryRepository;
import com.trading.rsi.repository.PositionOutcomeRepository;
import com.trading.rsi.repository.SignalLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class HistoryArchivalService {

    private static final String SIGNAL_LOG_CSV_HEADER =
            "id,symbol,instrument_name,signal_type,current_price,rsi_1m,rsi_5m,rsi_15m,rsi_30m,rsi_1h,rsi_4h," +
            "timeframes_aligned,signal_strength,created_at";

    private static final String POSITION_OUTCOME_CSV_HEADER =
            "id,direction,signal_type,symbol,entry_time,entry_price,stop_price,target_price," +
            "result,exit_time,pnl_pct,est_eur_pnl,holding_hrs";

    private static final String CANDLE_HISTORY_CSV_HEADER =
            "id,symbol,timeframe,candle_time,open,high,low,close,volume";

    private final SignalLogRepository signalLogRepository;
    private final PositionOutcomeRepository positionOutcomeRepository;
    private final CandleHistoryRepository candleHistoryRepository;

    @Value("${archive.enabled:true}")
    private boolean enabled;

    @Value("${archive.dir:./signal_archive}")
    private String archiveDir;

    @Value("${archive.retention-days:90}")
    private int retentionDays;

    @Value("${archive.batch-size:500}")
    private int batchSize;

    @Value("${rsi.demo.account-balance:10000}")
    private int demoAccountBalance;

    @Value("${rsi.demo.risk-percent:1}")
    private double demoRiskPercent;

    public HistoryArchivalService(SignalLogRepository signalLogRepository,
                                  PositionOutcomeRepository positionOutcomeRepository,
                                  CandleHistoryRepository candleHistoryRepository) {
        this.signalLogRepository = signalLogRepository;
        this.positionOutcomeRepository = positionOutcomeRepository;
        this.candleHistoryRepository = candleHistoryRepository;
    }

    private static final DateTimeFormatter INSTANT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    /**
     * Runs every Sunday at 03:00 UTC. Archives signal_logs and position_outcomes
     * older than retention-days, then deletes them from the DB.
     */
    @Scheduled(cron = "0 0 3 * * SUN", zone = "UTC")
    public void archiveOldSignalLogs() {
        if (!enabled) {
            log.debug("Archival is disabled (archive.enabled=false)");
            return;
        }
        archiveSignalLogs();
        archivePositionOutcomes();
    }

    /**
     * Runs monthly (1st at 02:00 UTC). Archives candle_history older than 60 days,
     * then deletes pruned rows from the DB.
     */
    @Scheduled(cron = "0 0 2 1 * *", zone = "UTC")
    public void archiveOldCandles() {
        if (!enabled) {
            log.debug("Archival is disabled (archive.enabled=false)");
            return;
        }
        archiveCandleHistory();
    }

    private void archiveSignalLogs() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(retentionDays);
        Path archivePath = getOrCreateArchivePath();
        if (archivePath == null) return;

        int totalArchived = 0;
        boolean foundAny = false;
        Pageable page = PageRequest.of(0, batchSize);
        while (true) {
            List<SignalLog> batch = signalLogRepository.findByCreatedAtBeforeOrderByCreatedAtAsc(cutoff, page);
            if (batch.isEmpty()) {
                if (!foundAny) {
                    log.info("Archival: no signal_logs older than {} days to archive", retentionDays);
                }
                break;
            }

            foundAny = true;
            log.info("Archival: processing {} signal_logs older than {} days", batch.size(), retentionDays);
            if (!writeSignalLogs(batch, archivePath)) {
                return;
            }
            signalLogRepository.deleteAllById(batch.stream().map(SignalLog::getId).toList());
            totalArchived += batch.size();
        }
        if (foundAny) {
            log.info("Archival complete: {} signal_logs moved to CSV", totalArchived);
        }
    }

    private void archivePositionOutcomes() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        Path archivePath = getOrCreateArchivePath();
        if (archivePath == null) return;

        int totalArchived = 0;
        boolean foundAny = false;
        Pageable page = PageRequest.of(0, batchSize);
        while (true) {
            List<PositionOutcome> batch = positionOutcomeRepository
                    .findByEntryTimeBeforeOrderByEntryTimeAsc(cutoff, page);
            if (batch.isEmpty()) {
                if (!foundAny) {
                    log.info("Archival: no position_outcomes older than {} days to archive", retentionDays);
                }
                break;
            }

            foundAny = true;
            log.info("Archival: processing {} position_outcomes older than {} days", batch.size(), retentionDays);
            if (!writePositionOutcomes(batch, archivePath)) {
                return;
            }
            positionOutcomeRepository.deleteAllById(batch.stream().map(PositionOutcome::getId).toList());
            totalArchived += batch.size();
        }
        if (foundAny) {
            log.info("Archival complete: {} position_outcomes moved to CSV", totalArchived);
        }
    }

    private void archiveCandleHistory() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(60));
        Path archivePath = getOrCreateArchivePath();
        if (archivePath == null) return;

        int totalArchived = 0;
        boolean foundAny = false;
        Pageable page = PageRequest.of(0, batchSize);
        while (true) {
            List<CandleHistory> batch = candleHistoryRepository
                    .findByCandleTimeBeforeOrderByCandleTimeAsc(cutoff, page);
            if (batch.isEmpty()) {
                if (!foundAny) {
                    log.info("Archival: no candle_history older than 60 days to archive");
                }
                break;
            }

            foundAny = true;
            log.info("Archival: processing {} candle_history rows older than 60 days", batch.size());
            if (!writeCandles(batch, archivePath)) {
                return;
            }
            candleHistoryRepository.deleteAllById(batch.stream().map(CandleHistory::getId).toList());
            totalArchived += batch.size();
        }
        if (foundAny) {
            log.info("Archival complete: {} candle_history rows moved to CSV", totalArchived);
        }
    }

    private Path getOrCreateArchivePath() {
        Path archivePath = Path.of(archiveDir);
        try {
            Files.createDirectories(archivePath);
            return archivePath;
        } catch (IOException e) {
            log.error("Archival: failed to create archive directory '{}': {}", archiveDir, e.getMessage());
            return null;
        }
    }

    private boolean writeSignalLogs(List<SignalLog> logs, Path archivePath) {
        Map<String, List<SignalLog>> byMonth = logs.stream()
                .collect(Collectors.groupingBy(entry ->
                        entry.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM"))));

        for (Map.Entry<String, List<SignalLog>> entry : byMonth.entrySet()) {
            String month = entry.getKey();
            List<SignalLog> monthLogs = entry.getValue();
            Path csvFile = archivePath.resolve("signal_logs_" + month + ".csv");
            boolean fileExists = Files.exists(csvFile);
            try (BufferedWriter writer = Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8,
                    fileExists ? StandardOpenOption.APPEND : StandardOpenOption.CREATE)) {
                if (!fileExists) {
                    writer.write(SIGNAL_LOG_CSV_HEADER);
                    writer.newLine();
                }
                for (SignalLog record : monthLogs) {
                    writer.write(toCsvRow(record));
                    writer.newLine();
                }
                log.info("Archival: wrote {} signal_logs to {}", monthLogs.size(), csvFile.toAbsolutePath());
            } catch (IOException e) {
                log.error("Archival: failed to write '{}': {} — aborting signal log archival", csvFile, e.getMessage());
                return false;
            }
        }
        return true;
    }

    private boolean writePositionOutcomes(List<PositionOutcome> positions, Path archivePath) {
        Map<String, List<PositionOutcome>> byMonth = positions.stream()
                .collect(Collectors.groupingBy(p ->
                        INSTANT_FMT.format(p.getEntryTime()).substring(0, 7)));

        for (Map.Entry<String, List<PositionOutcome>> entry : byMonth.entrySet()) {
            String month = entry.getKey();
            List<PositionOutcome> monthPositions = entry.getValue();
            Path csvFile = archivePath.resolve("position_outcomes_" + month + ".csv");
            boolean fileExists = Files.exists(csvFile);
            try (BufferedWriter writer = Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8,
                    fileExists ? StandardOpenOption.APPEND : StandardOpenOption.CREATE)) {
                if (!fileExists) {
                    writer.write(POSITION_OUTCOME_CSV_HEADER);
                    writer.newLine();
                }
                for (PositionOutcome p : monthPositions) {
                    writer.write(toCsvRow(p));
                    writer.newLine();
                }
                log.info("Archival: wrote {} position_outcomes to {}", monthPositions.size(), csvFile.toAbsolutePath());
            } catch (IOException e) {
                log.error("Archival: failed to write '{}': {} — aborting position outcome archival", csvFile, e.getMessage());
                return false;
            }
        }
        return true;
    }

    private boolean writeCandles(List<CandleHistory> candles, Path archivePath) {
        Map<String, List<CandleHistory>> byMonth = candles.stream()
                .collect(Collectors.groupingBy(c ->
                        INSTANT_FMT.format(c.getCandleTime()).substring(0, 7)));

        for (Map.Entry<String, List<CandleHistory>> entry : byMonth.entrySet()) {
            String month = entry.getKey();
            List<CandleHistory> monthCandles = entry.getValue();
            Path csvFile = archivePath.resolve("candles_" + month + ".csv");
            boolean fileExists = Files.exists(csvFile);
            try (BufferedWriter writer = Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8,
                    fileExists ? StandardOpenOption.APPEND : StandardOpenOption.CREATE)) {
                if (!fileExists) {
                    writer.write(CANDLE_HISTORY_CSV_HEADER);
                    writer.newLine();
                }
                for (CandleHistory c : monthCandles) {
                    writer.write(toCsvRow(c));
                    writer.newLine();
                }
                log.info("Archival: wrote {} candles to {}", monthCandles.size(), csvFile.toAbsolutePath());
            } catch (IOException e) {
                log.error("Archival: failed to write '{}': {} — aborting candle archival", csvFile, e.getMessage());
                return false;
            }
        }
        return true;
    }

    private String toCsvRow(SignalLog s) {
        return String.join(",",
                String.valueOf(s.getId()),
                csvEscape(s.getSymbol()),
                csvEscape(s.getInstrumentName()),
                csvEscape(String.valueOf(s.getSignalType())),
                nullOrValue(s.getCurrentPrice()),
                nullOrValue(s.getRsi1m()),
                nullOrValue(s.getRsi5m()),
                nullOrValue(s.getRsi15m()),
                nullOrValue(s.getRsi30m()),
                nullOrValue(s.getRsi1h()),
                nullOrValue(s.getRsi4h()),
                nullOrValue(s.getTimeframesAligned()),
                nullOrValue(s.getSignalStrength()),
                nullOrValue(s.getCreatedAt())
        );
    }

    private String toCsvRow(PositionOutcome p) {
        String direction = Boolean.TRUE.equals(p.getIsLong()) ? "LONG" : "SHORT";
        String result = p.getExitTime() == null ? "OPEN"
                : Boolean.TRUE.equals(p.getTpHit()) ? "WIN"
                : Boolean.TRUE.equals(p.getSlHit()) ? "LOSS" : "EXPIRED";
        double estEur = 0;
        if (p.getPnlPct() != null && p.getExitTime() != null) {
            estEur = estEur(p);
        }
        return String.join(",",
                String.valueOf(p.getId()),
                direction,
                csvEscape(String.valueOf(p.getSignalType())),
                csvEscape(p.getSymbol()),
                p.getEntryTime() != null ? INSTANT_FMT.format(p.getEntryTime()) : "",
                p.getEntryPrice() != null ? p.getEntryPrice().toPlainString() : "",
                p.getSlPrice() != null ? p.getSlPrice().toPlainString() : "",
                p.getTpPrice() != null ? p.getTpPrice().toPlainString() : "",
                result,
                p.getExitTime() != null ? INSTANT_FMT.format(p.getExitTime()) : "",
                p.getPnlPct() != null ? String.format("%+.2f", p.getPnlPct().doubleValue()) : "",
                p.getExitTime() != null ? String.format("%+.0f", estEur) : "",
                p.getHoldingHours() != null ? String.format("%.1f", p.getHoldingHours()) : ""
        );
    }

    private String toCsvRow(CandleHistory c) {
        return String.join(",",
                String.valueOf(c.getId()),
                csvEscape(c.getSymbol()),
                csvEscape(c.getTimeframe()),
                c.getCandleTime() != null ? INSTANT_FMT.format(c.getCandleTime()) : "",
                c.getOpen() != null ? c.getOpen().toPlainString() : "",
                c.getHigh() != null ? c.getHigh().toPlainString() : "",
                c.getLow() != null ? c.getLow().toPlainString() : "",
                c.getClose() != null ? c.getClose().toPlainString() : "",
                c.getVolume() != null ? c.getVolume().toPlainString() : ""
        );
    }

    private double estEur(PositionOutcome p) {
        if (p.getPnlPct() == null || p.getExitTime() == null
                || p.getEntryPrice() == null) return 0;
        double entry = p.getEntryPrice().doubleValue();
        if (entry <= 0) return 0;
        // Use stopPts (initial stop distance, never mutated by trailing) — same fix as
        // PositionReportService.estEur. slPrice is the trailed stop and overstates R.
        double stopPct;
        if (p.getStopPts() != null && p.getStopPts() > 0) {
            stopPct = (double) p.getStopPts() / entry * 100.0;
        } else if (p.getSlPrice() != null) {
            stopPct = Math.abs(entry - p.getSlPrice().doubleValue()) / entry * 100.0;
        } else {
            return 0;
        }
        if (stopPct <= 0) return 0;
        double riskEur = demoAccountBalance * demoRiskPercent / 100.0;
        double rMultiple = p.getPnlPct().doubleValue() / stopPct;
        return rMultiple * riskEur;
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
