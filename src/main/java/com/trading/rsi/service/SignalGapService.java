package com.trading.rsi.service;

import com.trading.rsi.domain.Instrument;
import com.trading.rsi.domain.SignalLog;
import com.trading.rsi.repository.FilterEventCountRepository;
import com.trading.rsi.repository.InstrumentRepository;
import com.trading.rsi.repository.SignalLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyses filter_event_counts to surface which filters are suppressing TREND_BUY_DIP
 * signals most often, and detects POSITION_COOLDOWN race conditions that follow 24h auto-closes.
 *
 * <p>Note on filter granularity: filter_event_counts stores day-level counts only (no timestamps).
 * Race condition detection matches POSITION_COOLDOWN on the same day (or next day) as an auto-close
 * — it cannot pinpoint exact 2h windows but is a reliable proxy for the pattern.
 *
 * <p>Note on suppression scope: ADX_RANGING / MACD_HISTOGRAM / CRYPTO_VOLUME are trend-specific
 * (fired by TrendDetectionService). DUPE_OPEN_POSITION / POSITION_COOLDOWN / RISK_OFF / RISK_ON /
 * HIGH_VOLATILITY cover all signal types and are not TREND_BUY_DIP-exclusive.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SignalGapService {

    private final FilterEventCountRepository filterEventCountRepository;
    private final SignalLogRepository signalLogRepository;
    private final InstrumentRepository instrumentRepository;

    private static final DateTimeFormatter REPORT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

    static final Map<String, String> SHORT_NAMES = Map.ofEntries(
            Map.entry("SOLUSDT",               "SOL"),
            Map.entry("BTCUSDT",               "BTC"),
            Map.entry("ETHUSDT",               "ETH"),
            Map.entry("BCHUSDT",               "BCH"),
            Map.entry("IX.D.DAX.DAILY.IP",     "DAX"),
            Map.entry("IX.D.FTSE.DAILY.IP",    "FTSE"),
            Map.entry("IX.D.SPTRD.DAILY.IP",   "S&P"),
            Map.entry("IX.D.NASDAQ.CASH.IP",   "NAS"),
            Map.entry("CS.D.USCGC.TODAY.IP",   "GOLD"),
            Map.entry("CS.D.USCSI.TODAY.IP",   "SILV"),
            Map.entry("CC.D.LCO.USS.IP",       "OIL")
    );

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Full signal-gaps report for GET /api/positions/signal-gaps.
     * Covers the last 30 days.
     */
    public Map<String, Object> getSignalGapsReport() {
        LocalDate since30 = LocalDate.now(ZoneOffset.UTC).minusDays(30);
        LocalDateTime since30dt = since30.atStartOfDay();

        Map<String, Map<String, Long>> suppressionsBySymbol = buildSuppressionMap(since30);
        Map<String, Long> firedBySymbol = buildFiredMap(since30dt);

        Set<String> allSymbols = new LinkedHashSet<>(suppressionsBySymbol.keySet());
        allSymbols.addAll(firedBySymbol.keySet());

        Map<String, Object> perSymbol = new LinkedHashMap<>();
        for (String sym : allSymbols) {
            Map<String, Long> filters = suppressionsBySymbol.getOrDefault(sym, Map.of());
            long totalSuppressed = filters.values().stream().mapToLong(Long::longValue).sum();
            long totalFired = firedBySymbol.getOrDefault(sym, 0L);
            long total = totalSuppressed + totalFired;

            Map<String, Long> sortedFilters = filters.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .collect(Collectors.toMap(
                            Map.Entry::getKey, Map.Entry::getValue,
                            (a, b) -> a, LinkedHashMap::new));

            Map<String, Object> symData = new LinkedHashMap<>();
            symData.put("trendSignalsFired", totalFired);
            symData.put("totalSuppressed", totalSuppressed);
            symData.put("suppressionRate", total > 0
                    ? String.format("%.1f%%", 100.0 * totalSuppressed / total) : "0.0%");
            symData.put("filters", sortedFilters);
            perSymbol.put(sym, symData);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("periodDays", 30);
        result.put("notes", List.of(
                "trendSignalsFired = TREND_BUY_DIP entries in signal_logs (passed all detection filters)",
                "totalSuppressed = all filter_event_counts entries (not TREND_BUY_DIP-exclusive for DUPE/COOLDOWN/REGIME filters)",
                "ADX_RANGING / MACD_HISTOGRAM / CRYPTO_VOLUME are TREND_BUY_DIP-specific",
                "raceConditionCandidates: filter_event_counts is day-granularity only — matches POSITION_COOLDOWN on same/next day as auto-close"
        ));
        result.put("bySymbol", perSymbol);
        result.put("raceConditionCandidates", buildRaceConditionCandidates(since30));
        return result;
    }

    /**
     * Compact markdown section for the daily P&L report.
     * Shows top 3 suppression reasons per active TREND_BUY_DIP symbol over the last 7 days.
     */
    public String buildTrendSuppressionSection() {
        LocalDate since7 = LocalDate.now(ZoneOffset.UTC).minusDays(7);

        List<String> activeSymbols = instrumentRepository.findByEnabledTrue().stream()
                .filter(i -> !Boolean.FALSE.equals(i.getTrendBuyDipEnabled()))
                .map(Instrument::getSymbol)
                .toList();

        if (activeSymbols.isEmpty()) return "";

        StringBuilder md = new StringBuilder();
        md.append("\n## Trend Signal Suppressions (Last 7 Days)\n\n");
        md.append("| Sym | Filter | Count |\n");
        md.append("|-----|--------|-------|\n");

        boolean any = false;
        for (String sym : activeSymbols) {
            List<Object[]> rows = filterEventCountRepository.sumBySymbolSince(since7, sym);
            if (rows.isEmpty()) continue;
            any = true;
            String shortName = SHORT_NAMES.getOrDefault(sym, sym);
            rows.stream().limit(3).forEach(row -> {
                String filter = (String) row[1];
                long count = ((Number) row[2]).longValue();
                md.append("| ").append(shortName)
                  .append(" | ").append(filter)
                  .append(" | ").append(count).append(" |\n");
            });
        }

        if (!any) {
            md.append("\n*No suppressions recorded for active TREND_BUY_DIP symbols.*\n");
        }

        md.append("\n*Trend-specific: ADX_RANGING, MACD_HISTOGRAM, CRYPTO_VOLUME. "
                + "General: DUPE_OPEN_POSITION, POSITION_COOLDOWN, RISK_OFF, HIGH_VOLATILITY.*\n");
        return md.toString();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Map<String, Map<String, Long>> buildSuppressionMap(LocalDate since) {
        List<Object[]> rows = filterEventCountRepository.sumBySymbolAndFilterSince(since);
        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String symbol = (String) row[0];
            String filter = (String) row[1];
            long count = ((Number) row[2]).longValue();
            result.computeIfAbsent(symbol, k -> new LinkedHashMap<>()).put(filter, count);
        }
        return result;
    }

    private Map<String, Long> buildFiredMap(LocalDateTime since) {
        List<Object[]> rows = signalLogRepository.countFiredBySymbolSince(
                SignalLog.SignalType.TREND_BUY_DIP, since);
        Map<String, Long> result = new LinkedHashMap<>();
        for (Object[] row : rows) {
            result.put((String) row[0], ((Number) row[1]).longValue());
        }
        return result;
    }

    private List<Map<String, Object>> buildRaceConditionCandidates(LocalDate since30) {
        Instant since30i = since30.atStartOfDay().toInstant(ZoneOffset.UTC);
        List<Object[]> rows;
        try {
            rows = filterEventCountRepository.findRaceConditionCandidatesSince(since30i);
        } catch (Exception e) {
            log.warn("Race condition query failed: {}", e.getMessage());
            return List.of();
        }

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Object[] row : rows) {
            long posId = ((Number) row[0]).longValue();
            String symbol = (String) row[1];
            Object exitTimeRaw = row[2];
            long cooldownCount = ((Number) row[3]).longValue();

            String exitTimeStr;
            if (exitTimeRaw instanceof java.sql.Timestamp ts) {
                exitTimeStr = ts.toInstant().atZone(ZoneOffset.UTC).format(REPORT_FMT);
            } else {
                exitTimeStr = exitTimeRaw != null ? exitTimeRaw.toString() : "unknown";
            }

            Map<String, Object> c = new LinkedHashMap<>();
            c.put("positionId", posId);
            c.put("symbol", symbol);
            c.put("autoCloseAt", exitTimeStr);
            c.put("positionCooldownSameDay", cooldownCount);
            c.put("note", "POSITION_COOLDOWN fired on the same day as a 24h auto-close — likely blocked valid re-entry signals post-exit");
            candidates.add(c);
        }
        return candidates;
    }
}
