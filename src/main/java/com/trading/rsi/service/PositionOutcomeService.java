package com.trading.rsi.service;

import com.trading.rsi.domain.CandleHistory;
import com.trading.rsi.domain.PositionOutcome;
import com.trading.rsi.domain.SignalLog;
import com.trading.rsi.event.SignalEvent;
import com.trading.rsi.model.RsiSignal;
import com.trading.rsi.repository.CandleHistoryRepository;
import com.trading.rsi.repository.PositionOutcomeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tracks position outcomes for actionable signals (OVERSOLD, OVERBOUGHT, TREND_BUY_DIP, TREND_SELL_RALLY).
 * On signal: inserts an open position with calculated TP/SL levels.
 * Exit-check job: checks finest available candle highs/lows for exit conditions (5m crypto, 15m IG).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PositionOutcomeService {

    private final PositionOutcomeRepository positionOutcomeRepository;
    private final CandleHistoryRepository candleHistoryRepository;
    private final PriceHistoryService priceHistoryService;
    private final TrendDetectionService trendDetectionService;
    private final AtrCalculator atrCalculator;
    private final FilterEventCounterService filterEventCounterService;
    private final IGTradingService igTradingService;
    private final TelegramNotificationService telegramNotificationService;

    private static final Duration MAX_HOLDING = Duration.ofHours(16); // lowered 24→16h May 2026 — frees slot for next signal sooner when position stagnates
    private static final String ATR_TIMEFRAME = "15m";

    private static final Set<SignalLog.SignalType> TRACKED_SIGNALS = Set.of(
            SignalLog.SignalType.OVERSOLD,
            SignalLog.SignalType.OVERBOUGHT,
            SignalLog.SignalType.TREND_BUY_DIP,
            SignalLog.SignalType.TREND_SELL_RALLY
    );

    @Value("${rsi.demo.stop-percent-crypto:2.0}")
    private double stopPercentCrypto;

    @Value("${rsi.demo.stop-percent-index:0.5}")
    private double stopPercentIndex;

    @Value("${rsi.demo.stop-percent-commodity:1.0}")
    private double stopPercentCommodity;

    @Value("${rsi.signal.cooldown-hours:1}")
    private int signalCooldownHours;

    @Value("${rsi.quiet-hours.enabled:true}")
    private boolean quietHoursEnabled;

    @Value("${rsi.quiet-hours.start-hour:22}")
    private int quietHoursStart;

    @Value("${rsi.quiet-hours.end-hour:8}")
    private int quietHoursEnd;

    @Value("${rsi.demo.atr-stops-enabled:true}")
    private boolean atrStopsEnabled;

    @Value("${rsi.demo.atr-period:14}")
    private int atrPeriod;

    @Value("${rsi.demo.atr-multiplier-trend:1.5}")
    private double atrMultiplierTrend;

    @Value("${rsi.demo.atr-multiplier-default:2.0}")
    private double atrMultiplierDefault;

    // Reward:Risk by asset class (trend signals only; non-trend always uses 2:1)
    @Value("${rsi.demo.trend-rr-crypto:2.0}")
    private double trendRrCrypto;

    @Value("${rsi.demo.trend-rr-index:3.0}")
    private double trendRrIndex;

    @Value("${rsi.demo.trend-rr-commodity:3.0}")
    private double trendRrCommodity;

    private boolean isQuietHours() {
        if (!quietHoursEnabled) return false;
        int h = ZonedDateTime.now(ZoneOffset.UTC).getHour();
        return quietHoursStart > quietHoursEnd
                ? h >= quietHoursStart || h < quietHoursEnd
                : h >= quietHoursStart && h < quietHoursEnd;
    }

    @EventListener
    @Async
    public void handleSignalEvent(SignalEvent event) {
        RsiSignal signal = event.getSignal();
        if (!TRACKED_SIGNALS.contains(signal.getSignalType())) return;

        // Guard: skip during quiet hours — keeps position tracker in sync with Telegram suppression
        if (isQuietHours()) {
            log.debug("Quiet hours — skipping position open for {} {}", signal.getSymbol(), signal.getSignalType());
            filterEventCounterService.record("QUIET_HOURS", signal.getSymbol());
            return;
        }

        // Guard: skip if open position exists or signal fired within cooldown window
        if (positionOutcomeRepository.existsBySymbolAndExitTimeIsNull(signal.getSymbol())) {
            Optional<PositionOutcome> openPos =
                    positionOutcomeRepository.findFirstBySymbolAndExitTimeIsNull(signal.getSymbol());
            boolean isOverdue = openPos
                    .map(p -> p.getEntryTime().plus(MAX_HOLDING).isBefore(Instant.now()))
                    .orElse(false);
            if (isOverdue) {
                log.info("Open position for {} is overdue (>{}h) — force-closing before new {} entry",
                        signal.getSymbol(), MAX_HOLDING.toHours(), signal.getSignalType());
                openPos.ifPresent(p -> checkAndClosePosition(p, Instant.now()));
            } else {
                log.info("Skipping position for {} {} — open position already exists (DUPE_OPEN_POSITION)",
                        signal.getSymbol(), signal.getSignalType());
                filterEventCounterService.record("DUPE_OPEN_POSITION", signal.getSymbol());
                return;
            }
        }
        Instant cooldownSince = Instant.now().minus(Duration.ofHours(signalCooldownHours));
        if (positionOutcomeRepository.existsBySymbolSince(signal.getSymbol(), cooldownSince)) {
            log.info("Skipping position for {} {} — signal within last {}h cooldown (POSITION_COOLDOWN)",
                    signal.getSymbol(), signal.getSignalType(), signalCooldownHours);
            filterEventCounterService.record("POSITION_COOLDOWN", signal.getSymbol());
            return;
        }

        boolean isLong = signal.getSignalType() == SignalLog.SignalType.OVERSOLD
                || signal.getSignalType() == SignalLog.SignalType.TREND_BUY_DIP;
        boolean isTrend = signal.getSignalType() == SignalLog.SignalType.TREND_BUY_DIP
                || signal.getSignalType() == SignalLog.SignalType.TREND_SELL_RALLY;

        BigDecimal entry = signal.getCurrentPrice();
        long stopPts = computeStopPoints(entry, signal.getSymbol(), isTrend);
        double rr = isTrend ? trendRewardRatio(signal.getSymbol()) : 2.0;
        long limitPts = Math.max(Math.round(stopPts * rr), stopPts + 1);

        BigDecimal tpPrice;
        BigDecimal slPrice;
        if (isLong) {
            slPrice = entry.subtract(BigDecimal.valueOf(stopPts));
            tpPrice = entry.add(BigDecimal.valueOf(limitPts));
        } else {
            slPrice = entry.add(BigDecimal.valueOf(stopPts));
            tpPrice = entry.subtract(BigDecimal.valueOf(limitPts));
        }

        String trendState = trendDetectionService.getTrendState(signal.getSymbol()).name();

        List<BigDecimal> rsiList = signal.getRsiValues() == null ? List.of()
                : signal.getRsiValues().values().stream().sorted().toList();
        BigDecimal rsiFast = rsiList.size() >= 1 ? rsiList.get(0) : null;
        BigDecimal rsiMid  = rsiList.size() >= 2 ? rsiList.get(1) : null;
        BigDecimal rsiSlow = rsiList.size() >= 3 ? rsiList.get(2) : null;

        BigDecimal stochK = null, stochD = null;
        if (signal.getStochasticValues() != null && !signal.getStochasticValues().isEmpty()) {
            var stoch = signal.getStochasticValues().values().iterator().next();
            stochK = stoch.k().setScale(2, RoundingMode.HALF_UP);
            stochD = stoch.d().setScale(2, RoundingMode.HALF_UP);
        }

        PositionOutcome position = PositionOutcome.builder()
                .symbol(signal.getSymbol())
                .signalType(signal.getSignalType())
                .entryPrice(entry)
                .entryTime(Instant.now())
                .tpPrice(tpPrice)
                .slPrice(slPrice)
                .isLong(isLong)
                .trendState(trendState)
                .rsiFast(rsiFast)
                .rsiMid(rsiMid)
                .rsiSlow(rsiSlow)
                .stochK(stochK)
                .stochD(stochD)
                .stopPts(stopPts)
                .build();

        positionOutcomeRepository.save(position);
        log.info("Position opened: {} {} at {} — TP {} SL {} ({})",
                signal.getSymbol(), signal.getSignalType(), entry, tpPrice, slPrice, isLong ? "LONG" : "SHORT");
    }

    /**
     * Every 10 minutes: check open positions for exit conditions using finest available candles.
     */
    @Scheduled(cron = "0 */10 * * * *", zone = "UTC")
    public void checkExitConditions() {
        List<PositionOutcome> openPositions = positionOutcomeRepository.findByExitTimeIsNull();
        if (openPositions.isEmpty()) return;

        log.info("Checking exit conditions for {} open positions", openPositions.size());
        Instant now = Instant.now();

        for (PositionOutcome pos : openPositions) {
            try {
                checkAndClosePosition(pos, now);
            } catch (Exception e) {
                log.error("Error checking position {} for {}: {}", pos.getId(), pos.getSymbol(), e.getMessage());
            }
        }

        reconcileWithIg(openPositions);
    }

    /**
     * Every 15 minutes: for positions with an IG deal ID, check if unrealised gain has
     * crossed 50% of the initial stop distance and ratchet the stop toward current price.
     * Stop only moves in the profitable direction — never reverses.
     */
    @Scheduled(fixedDelay = 15 * 60 * 1000L)
    public void checkTrailingStops() {
        if (!igTradingService.isIgAvailable()) return;

        List<PositionOutcome> candidates = positionOutcomeRepository.findByExitTimeIsNull().stream()
                .filter(p -> p.getIgDealId() != null && p.getStopPts() != null)
                .toList();

        if (candidates.isEmpty()) return;

        log.debug("Checking trailing stops for {} IG-linked positions", candidates.size());
        for (PositionOutcome pos : candidates) {
            try {
                tryTrailStop(pos);
            } catch (Exception e) {
                log.error("Trail-stop error for position {} ({}): {}", pos.getId(), pos.getSymbol(), e.getMessage());
            }
        }
    }

    private void tryTrailStop(PositionOutcome pos) {
        BigDecimal currentPrice = priceHistoryService.getLatestPrice(pos.getSymbol());
        if (currentPrice == null) return;

        long stopPts = pos.getStopPts();
        long trailThresholdPts = Math.max(1L, stopPts / 2);   // 50% of initial stop distance
        long trailDistance = trailThresholdPts;                // trail distance = threshold (matches signal spec: "trail Xpt below new highs")

        BigDecimal unrealisedGain;
        if (Boolean.TRUE.equals(pos.getIsLong())) {
            unrealisedGain = currentPrice.subtract(pos.getEntryPrice());
        } else {
            unrealisedGain = pos.getEntryPrice().subtract(currentPrice);
        }

        if (unrealisedGain.compareTo(BigDecimal.valueOf(trailThresholdPts)) < 0) return;

        // Two-stage trail (matches signal Telegram spec):
        //   Stage 1: at +threshold pts → stop to entry (breakeven)
        //   Stage 2: trail at trailDistance below new highs, never below breakeven
        BigDecimal newStopLevel;
        if (Boolean.TRUE.equals(pos.getIsLong())) {
            BigDecimal breakevenStop = pos.getEntryPrice();
            BigDecimal trailStop = currentPrice.subtract(BigDecimal.valueOf(trailDistance));
            // Use the tighter of breakeven or trail — once price runs far enough, trailStop > breakevenStop
            newStopLevel = trailStop.max(breakevenStop);
            if (newStopLevel.compareTo(pos.getSlPrice()) <= 0) return;  // never reverse
        } else {
            BigDecimal breakevenStop = pos.getEntryPrice();
            BigDecimal trailStop = currentPrice.add(BigDecimal.valueOf(trailDistance));
            newStopLevel = trailStop.min(breakevenStop);
            if (newStopLevel.compareTo(pos.getSlPrice()) >= 0) return;  // never reverse
        }

        BigDecimal oldStop = pos.getSlPrice();

        boolean igUpdated = igTradingService.updateStopLevel(pos.getIgDealId(), newStopLevel);
        if (!igUpdated) {
            log.warn("IG stop update failed for {} deal {} — DB stop not moved", pos.getSymbol(), pos.getIgDealId());
            return;
        }

        pos.setSlPrice(newStopLevel);
        positionOutcomeRepository.save(pos);

        BigDecimal gainPct = unrealisedGain
                .divide(pos.getEntryPrice(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP);

        String title = String.format("\uD83D\uDD12 %s stop trailed: %s \u2192 %s (+%s%% gain)",
                displayName(pos.getSymbol()),
                oldStop.stripTrailingZeros().toPlainString(),
                newStopLevel.stripTrailingZeros().toPlainString(),
                gainPct.toPlainString());
        telegramNotificationService.send(title, "");
        log.info("Trail stop: {} igDealId={} {} \u2192 {} (gain={}%)",
                pos.getSymbol(), pos.getIgDealId(),
                oldStop.toPlainString(), newStopLevel.toPlainString(), gainPct.toPlainString());
    }

    private void reconcileWithIg(List<PositionOutcome> openPositions) {
        List<PositionOutcome> igLinked = openPositions.stream()
                .filter(p -> p.getIgDealId() != null && p.getExitTime() == null)
                .toList();
        if (igLinked.isEmpty()) return;

        Optional<Set<String>> igDealIdsOpt = igTradingService.fetchOpenIgDealIds();
        if (igDealIdsOpt.isEmpty()) {
            log.debug("IG unavailable for reconciliation \u2014 skipping");
            return;
        }

        Set<String> igDealIds = igDealIdsOpt.get();
        for (PositionOutcome pos : igLinked) {
            if (!igDealIds.contains(pos.getIgDealId())) {
                log.warn("Position {} ({}) igDealId={} not found in IG \u2014 marking closed (reconciliation)",
                        pos.getId(), pos.getSymbol(), pos.getIgDealId());
                BigDecimal exitPrice = priceHistoryService.getLatestPrice(pos.getSymbol());
                if (exitPrice == null) exitPrice = pos.getEntryPrice();
                closePosition(pos, exitPrice, Instant.now(), false, false);
            }
        }
    }

    private static String displayName(String symbol) {
        if (symbol.endsWith("USDT")) return symbol.substring(0, symbol.length() - 4);
        if (symbol.endsWith("USD")) return symbol.substring(0, symbol.length() - 3);
        return symbol;
    }

    void checkAndClosePosition(PositionOutcome pos, Instant now) {
        String timeframe = finestExitTimeframe(pos.getSymbol());
        List<CandleHistory> candles = candleHistoryRepository
                .findBySymbolAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                        pos.getSymbol(), timeframe, pos.getEntryTime(), now);

        boolean tpHit = false;
        boolean slHit = false;
        BigDecimal exitPrice = null;
        Instant exitTime = null;

        for (CandleHistory candle : candles) {
            if (pos.getIsLong()) {
                if (candle.getLow().compareTo(pos.getSlPrice()) <= 0) {
                    slHit = true;
                    exitPrice = pos.getSlPrice();
                    exitTime = candle.getCandleTime();
                    break;
                }
                if (candle.getHigh().compareTo(pos.getTpPrice()) >= 0) {
                    tpHit = true;
                    exitPrice = pos.getTpPrice();
                    exitTime = candle.getCandleTime();
                    break;
                }
            } else {
                if (candle.getHigh().compareTo(pos.getSlPrice()) >= 0) {
                    slHit = true;
                    exitPrice = pos.getSlPrice();
                    exitTime = candle.getCandleTime();
                    break;
                }
                if (candle.getLow().compareTo(pos.getTpPrice()) <= 0) {
                    tpHit = true;
                    exitPrice = pos.getTpPrice();
                    exitTime = candle.getCandleTime();
                    break;
                }
            }
        }

        // 24h auto-close if neither TP nor SL hit
        if (!tpHit && !slHit && pos.getEntryTime().plus(MAX_HOLDING).isBefore(now)) {
            // Use latest known price for the symbol
            BigDecimal latestPrice = priceHistoryService.getLatestPrice(pos.getSymbol());
            if (latestPrice != null) {
                exitPrice = latestPrice;
                exitTime = now;
            } else if (!candles.isEmpty()) {
                CandleHistory lastCandle = candles.get(candles.size() - 1);
                exitPrice = lastCandle.getClose();
                exitTime = lastCandle.getCandleTime();
            }
        }

        if (exitPrice != null && exitTime != null) {
            closePosition(pos, exitPrice, exitTime, tpHit, slHit);
        }
    }

    private void closePosition(PositionOutcome pos, BigDecimal exitPrice, Instant exitTime,
                                boolean tpHit, boolean slHit) {
        BigDecimal pnlPct;
        if (pos.getIsLong()) {
            pnlPct = exitPrice.subtract(pos.getEntryPrice())
                    .divide(pos.getEntryPrice(), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        } else {
            pnlPct = pos.getEntryPrice().subtract(exitPrice)
                    .divide(pos.getEntryPrice(), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }

        double holdingHours = Duration.between(pos.getEntryTime(), exitTime).toMinutes() / 60.0;

        pos.setExitPrice(exitPrice);
        pos.setExitTime(exitTime);
        pos.setTpHit(tpHit);
        pos.setSlHit(slHit);
        pos.setPnlPct(pnlPct);
        pos.setHoldingHours(Math.round(holdingHours * 10.0) / 10.0);
        positionOutcomeRepository.save(pos);

        String reason = tpHit ? "TP HIT" : slHit ? "SL HIT" : "24h AUTO-CLOSE";
        log.info("Position closed: {} {} — {} at {} — P&L {}% ({}h)",
                pos.getSymbol(), pos.getSignalType(), reason, exitPrice,
                pnlPct.toPlainString(), String.format("%.1f", holdingHours));
    }

    /**
     * P&L summary: win rate, avg P&L, expectancy by signal type.
     */
    public Map<String, Object> getPnlSummary() {
        List<PositionOutcome> closed = positionOutcomeRepository.findByExitTimeIsNotNull();
        List<PositionOutcome> open = positionOutcomeRepository.findByExitTimeIsNull();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("openPositions", open.size());
        result.put("closedPositions", closed.size());

        if (closed.isEmpty()) {
            result.put("message", "No closed positions yet — check back after signals fire and 24h elapses");
            return result;
        }

        // Overall stats
        result.put("overall", computeStats(closed));

        // By signal type
        Map<String, Map<String, Object>> byType = new LinkedHashMap<>();
        closed.stream()
                .collect(Collectors.groupingBy(p -> p.getSignalType().name()))
                .forEach((type, positions) -> byType.put(type, computeStats(positions)));
        result.put("bySignalType", byType);

        return result;
    }

    private Map<String, Object> computeStats(List<PositionOutcome> positions) {
        long wins = positions.stream().filter(p -> p.getPnlPct().compareTo(BigDecimal.ZERO) > 0).count();
        long losses = positions.stream().filter(p -> p.getPnlPct().compareTo(BigDecimal.ZERO) <= 0).count();
        double winRate = positions.isEmpty() ? 0 : (double) wins / positions.size() * 100;

        double avgPnl = positions.stream()
                .mapToDouble(p -> p.getPnlPct().doubleValue())
                .average().orElse(0);

        double avgWin = positions.stream()
                .filter(p -> p.getPnlPct().compareTo(BigDecimal.ZERO) > 0)
                .mapToDouble(p -> p.getPnlPct().doubleValue())
                .average().orElse(0);

        double avgLoss = positions.stream()
                .filter(p -> p.getPnlPct().compareTo(BigDecimal.ZERO) <= 0)
                .mapToDouble(p -> p.getPnlPct().doubleValue())
                .average().orElse(0);

        double expectancy = (winRate / 100.0) * avgWin + (1 - winRate / 100.0) * avgLoss;

        long tpHits = positions.stream().filter(p -> Boolean.TRUE.equals(p.getTpHit())).count();
        long slHits = positions.stream().filter(p -> Boolean.TRUE.equals(p.getSlHit())).count();
        long autoCloses = positions.size() - tpHits - slHits;

        double avgHolding = positions.stream()
                .filter(p -> p.getHoldingHours() != null)
                .mapToDouble(PositionOutcome::getHoldingHours)
                .average().orElse(0);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", positions.size());
        stats.put("wins", wins);
        stats.put("losses", losses);
        stats.put("winRatePct", Math.round(winRate * 10.0) / 10.0);
        stats.put("avgPnlPct", Math.round(avgPnl * 100.0) / 100.0);
        stats.put("avgWinPct", Math.round(avgWin * 100.0) / 100.0);
        stats.put("avgLossPct", Math.round(avgLoss * 100.0) / 100.0);
        stats.put("expectancyPct", Math.round(expectancy * 100.0) / 100.0);
        stats.put("tpHits", tpHits);
        stats.put("slHits", slHits);
        stats.put("autoCloses", autoCloses);
        stats.put("avgHoldingHours", Math.round(avgHolding * 10.0) / 10.0);
        return stats;
    }

    /**
     * Retroactively recalculate all closed positions using the finest available candle
     * timeframe (15m for all asset classes). Useful after upgrading resolution logic.
     */
    public int recalculateClosedPositions() {
        List<PositionOutcome> closed = positionOutcomeRepository.findByExitTimeIsNotNull();
        int updated = 0;
        for (PositionOutcome pos : closed) {
            try {
                Instant now = Instant.now();
                String timeframe = finestExitTimeframe(pos.getSymbol());
                List<CandleHistory> candles = candleHistoryRepository
                        .findBySymbolAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                pos.getSymbol(), timeframe, pos.getEntryTime(), now);

                boolean tpHit = false;
                boolean slHit = false;
                BigDecimal exitPrice = null;
                Instant exitTime = null;

                for (CandleHistory candle : candles) {
                    if (Boolean.TRUE.equals(pos.getIsLong())) {
                        if (candle.getLow().compareTo(pos.getSlPrice()) <= 0) {
                            slHit = true; exitPrice = pos.getSlPrice(); exitTime = candle.getCandleTime();
                            break;
                        }
                        if (candle.getHigh().compareTo(pos.getTpPrice()) >= 0) {
                            tpHit = true; exitPrice = pos.getTpPrice(); exitTime = candle.getCandleTime();
                            break;
                        }
                    } else {
                        if (candle.getHigh().compareTo(pos.getSlPrice()) >= 0) {
                            slHit = true; exitPrice = pos.getSlPrice(); exitTime = candle.getCandleTime();
                            break;
                        }
                        if (candle.getLow().compareTo(pos.getTpPrice()) <= 0) {
                            tpHit = true; exitPrice = pos.getTpPrice(); exitTime = candle.getCandleTime();
                            break;
                        }
                    }
                }

                if (!tpHit && !slHit && pos.getEntryTime().plus(MAX_HOLDING).isBefore(now)) {
                    if (!candles.isEmpty()) {
                        CandleHistory last = candles.get(candles.size() - 1);
                        exitPrice = last.getClose();
                        exitTime = last.getCandleTime();
                    }
                }

                if (exitPrice != null && exitTime != null) {
                    boolean changed = !Objects.equals(pos.getTpHit(), tpHit)
                            || !Objects.equals(pos.getSlHit(), slHit)
                            || !Objects.equals(pos.getExitPrice(), exitPrice)
                            || !Objects.equals(pos.getExitTime(), exitTime);
                    if (changed) {
                        pos.setTpHit(tpHit);
                        pos.setSlHit(slHit);
                        pos.setExitPrice(exitPrice);
                        pos.setExitTime(exitTime);
                        BigDecimal pnlPct;
                        if (Boolean.TRUE.equals(pos.getIsLong())) {
                            pnlPct = exitPrice.subtract(pos.getEntryPrice())
                                    .divide(pos.getEntryPrice(), 4, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(100));
                        } else {
                            pnlPct = pos.getEntryPrice().subtract(exitPrice)
                                    .divide(pos.getEntryPrice(), 4, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(100));
                        }
                        pos.setPnlPct(pnlPct);
                        double holdingHours = Duration.between(pos.getEntryTime(), exitTime).toMinutes() / 60.0;
                        pos.setHoldingHours(Math.round(holdingHours * 10.0) / 10.0);
                        positionOutcomeRepository.save(pos);
                        updated++;
                        log.info("Recalculated position {} {}: {} -> {} (exit {})",
                                pos.getId(), pos.getSymbol(),
                                tpHit ? "TP" : slHit ? "SL" : "24h",
                                pnlPct.toPlainString() + "%", exitPrice);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to recalculate position {}: {}", pos.getId(), e.getMessage());
            }
        }
        log.info("Recalculated {} / {} closed positions with finer candles", updated, closed.size());
        return updated;
    }

    private String finestExitTimeframe(String symbol) {
        // All asset classes: 15m is the finest timeframe populated in candle_history
        // for every configured instrument (crypto + IG). 5m is NOT polled for crypto
        // under current config, which previously caused every crypto position to
        // force-close at 24h instead of hitting TP/SL based on intraday candle wicks.
        return "15m";
    }

    private double inferStopPercent(String symbol) {
        if (symbol.startsWith("IX.")) return stopPercentIndex;
        if (symbol.startsWith("CS.") || symbol.startsWith("CC.")) return stopPercentCommodity;
        return stopPercentCrypto;
    }

    private double trendRewardRatio(String symbol) {
        if (symbol.startsWith("IX.")) return trendRrIndex;
        if (symbol.startsWith("CS.") || symbol.startsWith("CC.")) return trendRrCommodity;
        return trendRrCrypto;
    }

    /**
     * Compute stop distance in price points.
     *
     * Preferred: ATR(N) * multiplier on the 15m timeframe — adapts to current volatility.
     * Fallback: fixed-pct based on asset class (halved for trend signals to reflect
     * higher-probability setups and tighter risk).
     *
     * A minimum stop of 0.2% (or 2 price points) prevents sub-tick stops on low-priced
     * instruments.
     */
    long computeStopPoints(BigDecimal entry, String symbol, boolean isTrend) {
        long floor = Math.max(Math.round(entry.doubleValue() * 0.002), 2);

        if (atrStopsEnabled) {
            var atrOpt = atrCalculator.computeAtr(symbol, ATR_TIMEFRAME, atrPeriod);
            if (atrOpt.isPresent()) {
                double multiplier = isTrend ? atrMultiplierTrend : atrMultiplierDefault;
                long atrPts = Math.round(atrOpt.get().doubleValue() * multiplier);
                long result = Math.max(atrPts, floor);
                log.debug("ATR stop for {} {}: ATR={} x{} = {}pt (entry={})",
                        symbol, isTrend ? "TREND" : "NORMAL",
                        atrOpt.get().toPlainString(), multiplier, result, entry.toPlainString());
                return result;
            }
            log.debug("ATR unavailable for {} — falling back to fixed-pct stop", symbol);
        }

        double stopPct = inferStopPercent(symbol);
        double effectiveStopPct = isTrend ? stopPct * 0.5 : stopPct;
        long fixedPts = Math.round(entry.doubleValue() * effectiveStopPct / 100.0);
        return Math.max(fixedPts, floor);
    }
}
