package com.trading.rsi.service;

import com.trading.rsi.domain.CandleHistory;
import com.trading.rsi.domain.Instrument;
import com.trading.rsi.domain.SignalLog;
import com.trading.rsi.event.SignalEvent;
import com.trading.rsi.model.Candle;
import com.trading.rsi.model.RsiSignal;
import com.trading.rsi.repository.CandleHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects strong trends using two methods (primary + fallback) and generates
 * TREND_BUY_DIP / TREND_SELL_RALLY signals when price pulls back within a confirmed trend.
 *
 * PRIMARY filter — EMA 20 on the configured trend timeframe:
 *   If price > EMA20(1h) → STRONG_UPTREND. Suppress OVERBOUGHT sell signals immediately.
 *   If price < EMA20(1h) → STRONG_DOWNTREND. Suppress OVERSOLD buy signals immediately.
 *
 * FALLBACK 1 — Price momentum (used when EMA history &lt; 20 candles):
 *   If price moved &gt;1% over last 5 candles on the trend timeframe → STRONG_UPTREND / STRONG_DOWNTREND.
 *   Requires only 6 candles of history — available immediately after warmup.
 *
 * FALLBACK 2 — Consecutive signal count (last resort):
 *   After 2+ consecutive OVERBOUGHT full signals → STRONG_UPTREND.
 *   After 2+ consecutive OVERSOLD full signals → STRONG_DOWNTREND.
 *
 * Dip entry (TREND_BUY_DIP):
 *   Triggered when fastest-TF RSI drops below 60 (pulled back from overbought >70)
 *   while price is still above EMA20(1h). This is the classic buy-the-dip setup.
 *
 * Rally entry (TREND_SELL_RALLY):
 *   Triggered when fastest-TF RSI bounces above 40 while price is below EMA20(1h).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TrendDetectionService {

    private final ApplicationEventPublisher eventPublisher;
    private final SignalCooldownService cooldownService;
    private final EmaCalculator emaCalculator;
    private final PriceHistoryService priceHistoryService;
    private final AdxCalculator adxCalculator;
    private final MacdCalculator macdCalculator;
    private final AtrCalculator atrCalculator;
    private final FilterEventCounterService filterEventCounterService;
    private final CandleHistoryRepository candleHistoryRepository;

    @Value("${rsi.trend.ema-period:20}")
    private int emaPeriod;

    @Value("${rsi.trend.ema-timeframe:1h}")
    private String emaTrendTimeframe;

    @Value("${rsi.trend.consecutive-signals-for-trend:2}")
    private int consecutiveSignalsForTrend;

    @Value("${rsi.trend.dip-rsi-threshold:45}")
    private double dipRsiThreshold;

    @Value("${rsi.trend.rally-rsi-threshold:40}")
    private double rallyRsiThreshold;

    @Value("${rsi.trend.trend-timeout-hours:12}")
    private int trendTimeoutHours;

    @Value("${rsi.trend.suppress-counter-trend:true}")
    private boolean suppressCounterTrend;

    @Value("${rsi.trend.sell-rally-enabled:false}")
    private boolean sellRallyEnabled;

    @Value("${rsi.trend.adx-filter-enabled:false}")
    private boolean adxFilterEnabled;

    @Value("${rsi.trend.adx-period:14}")
    private int adxPeriod;

    @Value("${rsi.trend.adx-threshold:20.0}")
    private double adxThreshold;

    @Value("${rsi.trend.macd-filter-enabled:true}")
    private boolean macdFilterEnabled;

    @Value("${rsi.trend.macd-fast-period:12}")
    private int macdFastPeriod;

    @Value("${rsi.trend.macd-slow-period:26}")
    private int macdSlowPeriod;

    @Value("${rsi.trend.macd-signal-period:9}")
    private int macdSignalPeriod;

    @Value("${rsi.trend.macd-divergence-enabled:false}")
    private boolean macdDivergenceEnabled;

    // EMA slope filter — suppresses entries when the trend EMA is essentially flat (chop).
    // Default ON with a very gentle threshold so consolidation ranges are filtered without
    // killing real mild uptrends. See docs/project-log.md (May 2026 SOL chop review).
    @Value("${rsi.trend.ema-slope-filter-enabled:true}")
    private boolean emaSlopeFilterEnabled;

    @Value("${rsi.trend.ema-slope-lookback:5}")
    private int emaSlopeLookback;

    @Value("${rsi.trend.ema-slope-min-pct:0.05}")
    private double emaSlopeMinPct;

    // ATR / price minimum — skips TREND_BUY_DIP when 1h ATR is too small relative to price
    // (range-bound, low-vol market). Default OFF until calibrated against position_outcomes.
    @Value("${rsi.trend.atr-min-pct-filter-enabled:false}")
    private boolean atrMinPctFilterEnabled;

    @Value("${rsi.trend.atr-min-pct:0.4}")
    private double atrMinPct;

    @Value("${rsi.trend.atr-min-pct-period:14}")
    private int atrMinPctPeriod;

    // Dedupe tightening — require the fast-TF RSI to recover meaningfully above the dip
    // threshold (margin) before a second alert can fire at a similar price, and require a
    // larger price move when RSI has not recovered. Both raised May 2026 after SOL fired 3×
    // in 60h while range-bound between $85.15 and $86.14.
    @Value("${rsi.trend.dip-recovery-margin:5.0}")
    private double dipRecoveryMargin;

    @Value("${rsi.trend.dip-min-pct-move:0.5}")
    private double dipMinPctMove;

    @Value("${rsi.trend.crypto-volume-filter-enabled:true}")
    private boolean cryptoVolumeFilterEnabled;

    @Value("${rsi.trend.crypto-volume-multiplier:1.2}")
    private double cryptoVolumeMultiplier;

    @Value("${rsi.trend.crypto-volume-lookback:20}")
    private int cryptoVolumeLookback;

    // Falling-knife / drawdown-velocity filter — suppresses TREND_BUY_DIP when price is dropping
    // too steeply on the fast timeframe (a violent fall, not a healthy pullback). Added Jun 2026
    // after SOL entered at 74.35 mid-knife (prior close 74.94, −0.79% in one 15m candle) while all
    // trend filters passed, then kept falling toward the stop. Default ON, conservative threshold;
    // warmup / disabled are pass-through. Tracked via filter_event_counts (DIP_FALLING_KNIFE).
    @Value("${rsi.trend.dip-max-drop-filter-enabled:true}")
    private boolean dipMaxDropFilterEnabled;

    @Value("${rsi.trend.dip-max-drop-pct:0.6}")
    private double dipMaxDropPct;

    @Value("${rsi.trend.dip-max-drop-lookback:3}")
    private int dipMaxDropLookback;

    private static final int MOMENTUM_LOOKBACK = 5;
    private static final double MOMENTUM_THRESHOLD_PCT = 1.0;

    // Tracks consecutive overbought signal count per symbol
    private final Map<String, Integer> consecutiveOverbought = new ConcurrentHashMap<>();
    private final Map<String, Integer> consecutiveOversold = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastOverboughtTime = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastOversoldTime = new ConcurrentHashMap<>();

    // TREND_BUY_DIP dedupe: suppress repeat dip alerts unless price moved >0.3% or RSI recovered above threshold
    private final Map<String, BigDecimal> lastDipAlertPrice = new ConcurrentHashMap<>();
    private final Map<String, Boolean> dipRsiRecovered = new ConcurrentHashMap<>();

    public enum TrendState {
        STRONG_UPTREND,
        STRONG_DOWNTREND,
        NEUTRAL
    }

    /**
     * Record a full OVERBOUGHT or OVERSOLD signal, updating consecutive counters.
     */
    public void recordSignal(String symbol, SignalLog.SignalType signalType) {
        Instant now = Instant.now();

        if (signalType == SignalLog.SignalType.OVERBOUGHT) {
            // Check for timeout — reset if it's been too long since last overbought
            Instant last = lastOverboughtTime.get(symbol);
            if (last != null && last.isBefore(now.minus(trendTimeoutHours, ChronoUnit.HOURS))) {
                consecutiveOverbought.put(symbol, 0);
            }
            int count = consecutiveOverbought.merge(symbol, 1, Integer::sum);
            lastOverboughtTime.put(symbol, now);
            // Reset opposite counter
            consecutiveOversold.put(symbol, 0);

            if (count >= consecutiveSignalsForTrend) {
                log.info("STRONG UPTREND detected for {} — {} consecutive overbought signals", symbol, count);
            }
        } else if (signalType == SignalLog.SignalType.OVERSOLD) {
            Instant last = lastOversoldTime.get(symbol);
            if (last != null && last.isBefore(now.minus(trendTimeoutHours, ChronoUnit.HOURS))) {
                consecutiveOversold.put(symbol, 0);
            }
            int count = consecutiveOversold.merge(symbol, 1, Integer::sum);
            lastOversoldTime.put(symbol, now);
            consecutiveOverbought.put(symbol, 0);

            if (count >= consecutiveSignalsForTrend) {
                log.info("STRONG DOWNTREND detected for {} — {} consecutive oversold signals", symbol, count);
            }
        }
    }

    /**
     * Get current trend state for a symbol.
     * PRIMARY: EMA 20 on 1h timeframe.
     * FALLBACK 1: Price momentum over last MOMENTUM_LOOKBACK candles.
     * FALLBACK 2: Consecutive signal count if both EMA and momentum unavailable.
     */
    public TrendState getTrendState(String symbol) {
        // PRIMARY: EMA 20 on the configured trend timeframe
        Boolean aboveEma = getPriceVsEma(symbol);
        if (aboveEma != null) {
            return aboveEma ? TrendState.STRONG_UPTREND : TrendState.STRONG_DOWNTREND;
        }

        // FALLBACK 1: Price momentum — needs only MOMENTUM_LOOKBACK + 1 candles
        double changePct = getMomentumChangePct(symbol);
        if (!Double.isNaN(changePct)) {
            if (changePct > MOMENTUM_THRESHOLD_PCT) return TrendState.STRONG_UPTREND;
            if (changePct < -MOMENTUM_THRESHOLD_PCT) return TrendState.STRONG_DOWNTREND;
        }

        // FALLBACK 2: consecutive signal count
        Instant now = Instant.now();
        int obCount = consecutiveOverbought.getOrDefault(symbol, 0);
        Instant lastOb = lastOverboughtTime.get(symbol);
        if (obCount >= consecutiveSignalsForTrend && lastOb != null
                && lastOb.isAfter(now.minus(trendTimeoutHours, ChronoUnit.HOURS))) {
            return TrendState.STRONG_UPTREND;
        }

        int osCount = consecutiveOversold.getOrDefault(symbol, 0);
        Instant lastOs = lastOversoldTime.get(symbol);
        if (osCount >= consecutiveSignalsForTrend && lastOs != null
                && lastOs.isAfter(now.minus(trendTimeoutHours, ChronoUnit.HOURS))) {
            return TrendState.STRONG_DOWNTREND;
        }

        return TrendState.NEUTRAL;
    }

    /**
     * Compute price vs EMA for the trend timeframe.
     * Returns true = price above EMA (uptrend), false = below (downtrend), null = insufficient data or too close (hysteresis).
     * Hysteresis: price within ±0.1% of EMA returns null (NEUTRAL) to prevent whipsaw.
     */
    private Boolean getPriceVsEma(String symbol) {
        String key = priceHistoryService.buildKey(symbol, emaTrendTimeframe);
        List<BigDecimal> history = priceHistoryService.getPriceHistory(key);
        if (history == null || history.size() < emaPeriod) return null;
        BigDecimal ema = emaCalculator.calculate(history, emaPeriod);
        if (ema == null) return null;
        BigDecimal currentPrice = history.get(history.size() - 1);
        double pctFromEma = currentPrice.subtract(ema)
                .divide(ema, 8, RoundingMode.HALF_UP).doubleValue() * 100;
        // Hysteresis: must be >0.1% away from EMA to flip
        if (Math.abs(pctFromEma) < 0.1) return null; // NEUTRAL — too close to call
        boolean above = pctFromEma > 0;
        log.debug("EMA{} ({}) for {}: {} | price {} EMA ({:.2f}%)",
                emaPeriod, emaTrendTimeframe, symbol, ema,
                above ? ">" : "<", pctFromEma);
        return above;
    }

    /**
     * Returns a human-readable explanation of what is driving the current trend classification.
     * Used in Telegram notifications so the user understands why a signal was suppressed or generated.
     */
    public String getTrendReason(String symbol) {
        Boolean aboveEma = getPriceVsEma(symbol);
        if (aboveEma != null) {
            return String.format("price %s EMA%d(%s)", aboveEma ? "above" : "below", emaPeriod, emaTrendTimeframe);
        }
        double changePct = getMomentumChangePct(symbol);
        if (!Double.isNaN(changePct) && (changePct > MOMENTUM_THRESHOLD_PCT || changePct < -MOMENTUM_THRESHOLD_PCT)) {
            return String.format("%.1f%% move in %d %s candles (momentum proxy)",
                    changePct, MOMENTUM_LOOKBACK, emaTrendTimeframe);
        }
        int obCount = consecutiveOverbought.getOrDefault(symbol, 0);
        int osCount = consecutiveOversold.getOrDefault(symbol, 0);
        int count = Math.max(obCount, osCount);
        return String.format("%d consecutive full signals", count);
    }

    /**
     * Returns the consecutive overbought count for a symbol.
     */
    public int getConsecutiveOverboughtCount(String symbol) {
        return consecutiveOverbought.getOrDefault(symbol, 0);
    }

    /**
     * Returns the consecutive oversold count for a symbol.
     */
    public int getConsecutiveOversoldCount(String symbol) {
        return consecutiveOversold.getOrDefault(symbol, 0);
    }

    /**
     * Whether to suppress a counter-trend signal.
     * OVERBOUGHT suppressed in uptrend; OVERSOLD suppressed in downtrend.
     * Logs the reason (EMA-based, momentum-based, or consecutive-based).
     */
    public boolean shouldSuppressCounterTrend(String symbol, SignalLog.SignalType signalType) {
        if (!suppressCounterTrend) return false;

        TrendState trend = getTrendState(symbol);
        String reason = getTrendReason(symbol);

        if (trend == TrendState.STRONG_UPTREND && signalType == SignalLog.SignalType.OVERBOUGHT) {
            log.info("Suppressing OVERBOUGHT sell signal for {} — strong uptrend ({}) — consider BUY THE DIP instead",
                    symbol, reason);
            return true;
        }
        if (trend == TrendState.STRONG_DOWNTREND && signalType == SignalLog.SignalType.OVERSOLD) {
            log.info("Suppressing OVERSOLD buy signal for {} — strong downtrend ({}) — consider SELL THE RALLY instead",
                    symbol, reason);
            return true;
        }
        return false;
    }

    /**
     * Check if RSI values indicate a dip in an uptrend (buy opportunity)
     * or a rally in a downtrend (sell opportunity).
     *
     * TREND_BUY_DIP: fastest TF RSI has pulled back below dipRsiThreshold (default 60)
     *   while price remains above EMA20(1h). Higher TFs staying elevated is expected in a
     *   strong uptrend and is not a disqualifier — it confirms the trend is intact.
     *
     * TREND_SELL_RALLY: fastest TF RSI bounced above rallyRsiThreshold (default 40)
     *   while price remains below EMA20(1h). Lower TFs staying depressed confirms downtrend.
     *
     * Called on every poll cycle.
     */
    public void checkForTrendEntry(Instrument instrument, Map<String, BigDecimal> rsiValues,
                                    BigDecimal currentPrice, Candle triggerCandle) {
        if (Boolean.FALSE.equals(instrument.getTrendBuyDipEnabled())) {
            log.debug("Trend entry skipped for {} — trend-buy-dip-enabled=false", instrument.getSymbol());
            return;
        }
        TrendState trend = getTrendState(instrument.getSymbol());
        if (trend == TrendState.NEUTRAL) return;

        // ADX trend-strength filter (Wilder 1978): suppresses entries during ranging markets.
        // Schwab, Investopedia, FXNX all cite ADX < 20 as the "no-trend" cutoff.
        // When candle history is insufficient, Optional.empty() is returned and we do NOT
        // block the signal — better to alert than to silently skip during warmup.
        if (adxFilterEnabled && adxThreshold > 0) {
            Optional<BigDecimal> adx = adxCalculator.computeAdx(
                    instrument.getSymbol(), emaTrendTimeframe, adxPeriod);
            if (adx.isPresent() && adx.get().doubleValue() < adxThreshold) {
                log.info("Trend entry suppressed for {} — ADX({}) on {} is {} < {} (ranging market)",
                        instrument.getSymbol(), adxPeriod, emaTrendTimeframe,
                        adx.get().toPlainString(), adxThreshold);
                filterEventCounterService.record("ADX_RANGING", instrument.getSymbol());
                return;
            }
        }

        // EMA slope filter: the EMA / price >  EMA gate alone is too permissive in chop, because
        // price oscillating in a tight band still sits above a slow EMA. Requiring the EMA itself
        // to be sloping (up for uptrend, down for downtrend) eliminates flat consolidation ranges.
        // Optional.NaN (warmup) is pass-through.
        if (emaSlopeFilterEnabled && emaSlopeMinPct > 0) {
            double slope = getEmaSlopePct(instrument.getSymbol());
            if (!Double.isNaN(slope)) {
                boolean uptrendFlat   = trend == TrendState.STRONG_UPTREND   && slope <  emaSlopeMinPct;
                boolean downtrendFlat = trend == TrendState.STRONG_DOWNTREND && slope > -emaSlopeMinPct;
                if (uptrendFlat || downtrendFlat) {
                    log.info("Trend entry suppressed for {} — EMA{}({}) slope {}% over {} candles below ±{}% (flat/chop)",
                            instrument.getSymbol(), emaPeriod, emaTrendTimeframe,
                            String.format("%.3f", slope), emaSlopeLookback, emaSlopeMinPct);
                    filterEventCounterService.record("EMA_SLOPE_FLAT", instrument.getSymbol());
                    return;
                }
            }
        }

        // ATR / price minimum: skip TREND_BUY_DIP when 1h ATR is too small relative to price
        // (e.g. SOL drifting in a $1 band). NaN (warmup) is pass-through.
        if (atrMinPctFilterEnabled && atrMinPct > 0) {
            double atrPct = getAtrPctOfPrice(instrument.getSymbol(), currentPrice);
            if (!Double.isNaN(atrPct) && atrPct < atrMinPct) {
                log.info("Trend entry suppressed for {} — {} ATR({}) {}% of price below {}% (range-bound)",
                        instrument.getSymbol(), emaTrendTimeframe, atrMinPctPeriod,
                        String.format("%.3f", atrPct), atrMinPct);
                filterEventCounterService.record("ATR_RANGE_BOUND", instrument.getSymbol());
                return;
            }
        }

        // MACD histogram confirmation for TREND_BUY_DIP (Appel 1979).
        // Only applies to the uptrend path; Optional.empty() is warmup-friendly and does not block.
        if (macdFilterEnabled && trend == TrendState.STRONG_UPTREND) {
            Optional<MacdCalculator.MacdHistogram> hist = macdCalculator.compute(
                    instrument.getSymbol(), emaTrendTimeframe,
                    macdFastPeriod, macdSlowPeriod, macdSignalPeriod);
            if (hist.isPresent() && !hist.get().isBullish() && !hist.get().isRising()) {
                log.info("Trend entry suppressed for {} — MACD histogram not bullish/rising on {} (current={}, previous={})",
                        instrument.getSymbol(), emaTrendTimeframe,
                        hist.get().current().toPlainString(),
                        hist.get().previous().toPlainString());
                filterEventCounterService.record("MACD_HISTOGRAM", instrument.getSymbol());
                return;
            }
        }

        // MACD divergence gate for TREND_BUY_DIP.
        // Deployed OFF (TREND_MACD_DIVERGENCE_ENABLED=false) — monitor via filter_event_counts for 1 week.
        // When enabled: bullish divergence confirms entry; absence blocks entry unless histogram is
        // clearly bullish (>0 AND rising), in which case entry passes regardless (don't over-tighten).
        // Optional.empty() (warmup) is pass-through — same pattern as histogram gate.
        if (macdDivergenceEnabled && trend == TrendState.STRONG_UPTREND) {
            Optional<MacdCalculator.MacdDivergenceResult> div = macdCalculator.computeDivergence(
                    instrument.getSymbol(), emaTrendTimeframe,
                    macdFastPeriod, macdSlowPeriod, macdSignalPeriod, 20);
            if (div.isPresent()) {
                Optional<MacdCalculator.MacdHistogram> hist = macdCalculator.compute(
                        instrument.getSymbol(), emaTrendTimeframe,
                        macdFastPeriod, macdSlowPeriod, macdSignalPeriod);
                boolean histClearlyBullish = hist.map(h -> h.isBullish() && h.isRising()).orElse(false);
                if (!histClearlyBullish && !div.get().bullishDivergence()) {
                    log.info("Trend entry suppressed for {} — MACD divergence absent and histogram not clearly bullish on {} ({})",
                            instrument.getSymbol(), emaTrendTimeframe, div.get().reason());
                    filterEventCounterService.record("MACD_DIVERGENCE_ABSENT", instrument.getSymbol());
                    return;
                }
            }
        }

        BigDecimal fastestRsi = getFastestRsi(rsiValues, instrument.getTimeframes());
        if (fastestRsi == null) return;
        double fastRsi = fastestRsi.doubleValue();

        // Track RSI recovery for TREND_BUY_DIP dedupe — require a margin above the dip threshold
        // so that brushing the threshold (e.g. RSI 48 vs 50) does not count as a real bounce.
        if (fastRsi >= dipRsiThreshold + dipRecoveryMargin) {
            dipRsiRecovered.put(instrument.getSymbol(), true);
        }

        if (trend == TrendState.STRONG_UPTREND) {
            // Fastest TF RSI pulled back below threshold while price remains in uptrend
            if (fastRsi < dipRsiThreshold && fastRsi > 30) {
                // Dedupe: require a meaningful price move OR a confirmed RSI recovery (threshold + margin)
                BigDecimal lastDipPrice = lastDipAlertPrice.get(instrument.getSymbol());
                boolean recovered = dipRsiRecovered.getOrDefault(instrument.getSymbol(), true);
                if (lastDipPrice != null && !recovered) {
                    double pctMove = Math.abs(currentPrice.subtract(lastDipPrice)
                            .divide(lastDipPrice, 8, RoundingMode.HALF_UP).doubleValue() * 100);
                    if (pctMove < dipMinPctMove) {
                        log.info("TREND_BUY_DIP suppressed for {} — price {}% from last dip (need {}%), RSI not recovered (need ≥ {})",
                                instrument.getSymbol(), String.format("%.2f", pctMove),
                                dipMinPctMove, dipRsiThreshold + dipRecoveryMargin);
                        filterEventCounterService.record("DIP_DEDUPE", instrument.getSymbol());
                        return;
                    }
                }

                // Volume confirmation: high volume validates pullbacks (LuxAlgo / r/algotrading).
                // IG CFD volume is unreliable — only applied to CRYPTO. Skip silently during warmup.
                if (!isVolumeConfirmed(instrument, triggerCandle)) {
                    return;
                }

                // Falling-knife guard: skip steep, fast drops (drawdown velocity on the fast TF).
                // A healthy dip is a shallow pullback; a violent multi-candle drop is a knife.
                if (!isDipStabilized(instrument)) {
                    return;
                }

                SignalLog.SignalType type = SignalLog.SignalType.TREND_BUY_DIP;
                if (cooldownService.shouldAlert(instrument.getSymbol(), type)) {
                    int obCount = consecutiveOverbought.getOrDefault(instrument.getSymbol(), 0);
                    BigDecimal strength = BigDecimal.valueOf(dipRsiThreshold - fastRsi)
                            .setScale(4, RoundingMode.HALF_UP);

                    boolean silent = Boolean.FALSE.equals(instrument.getTrendBuyDipNotify());
                    RsiSignal signal = RsiSignal.builder()
                            .symbol(instrument.getSymbol())
                            .instrumentName(instrument.getName())
                            .signalType(type)
                            .currentPrice(currentPrice)
                            .rsiValues(rsiValues)
                            .timeframesAligned(obCount)
                            .totalTimeframes(rsiValues.size())
                            .signalStrength(strength)
                            .triggerCandle(triggerCandle)
                            .silent(silent)
                            .build();

                    log.info("TREND_BUY_DIP{}: {} RSI={} pulled back below {} in uptrend — price {} EMA{}({})",
                            silent ? " [SILENT]" : "",
                            instrument.getName(), String.format("%.1f", fastRsi), dipRsiThreshold,
                            currentPrice, emaPeriod, emaTrendTimeframe);
                    eventPublisher.publishEvent(new SignalEvent(this, signal));
                    cooldownService.recordAlert(instrument.getSymbol(), type);
                    lastDipAlertPrice.put(instrument.getSymbol(), currentPrice);
                    dipRsiRecovered.put(instrument.getSymbol(), false);
                }
            }
        } else if (trend == TrendState.STRONG_DOWNTREND && sellRallyEnabled) {
            // Fastest TF RSI bounced above threshold while price remains in downtrend
            // DISABLED by default (sellRallyEnabled=false) — backtest shows 0% TP, -0.79R
            if (fastRsi > rallyRsiThreshold && fastRsi < 70) {
                SignalLog.SignalType type = SignalLog.SignalType.TREND_SELL_RALLY;
                if (cooldownService.shouldAlert(instrument.getSymbol(), type)) {
                    int osCount = consecutiveOversold.getOrDefault(instrument.getSymbol(), 0);
                    BigDecimal strength = BigDecimal.valueOf(fastRsi - rallyRsiThreshold)
                            .setScale(4, RoundingMode.HALF_UP);

                    RsiSignal signal = RsiSignal.builder()
                            .symbol(instrument.getSymbol())
                            .instrumentName(instrument.getName())
                            .signalType(type)
                            .currentPrice(currentPrice)
                            .rsiValues(rsiValues)
                            .timeframesAligned(osCount)
                            .totalTimeframes(rsiValues.size())
                            .signalStrength(strength)
                            .triggerCandle(triggerCandle)
                            .build();

                    log.info("TREND_SELL_RALLY: {} RSI={} bounced above {} in downtrend — price {} EMA{}({})",
                            instrument.getName(), String.format("%.1f", fastRsi), rallyRsiThreshold,
                            currentPrice, emaPeriod, emaTrendTimeframe);
                    eventPublisher.publishEvent(new SignalEvent(this, signal));
                    cooldownService.recordAlert(instrument.getSymbol(), type);
                }
            }
        }
    }

    /**
     * Returns the % change of the EMA on the trend timeframe over the last {@code emaSlopeLookback}
     * candles. Positive = EMA rising. Returns NaN if insufficient history.
     */
    private double getEmaSlopePct(String symbol) {
        String key = priceHistoryService.buildKey(symbol, emaTrendTimeframe);
        List<BigDecimal> history = priceHistoryService.getPriceHistory(key);
        if (history == null || history.size() < emaPeriod + emaSlopeLookback) return Double.NaN;
        BigDecimal emaNow = emaCalculator.calculate(history, emaPeriod);
        BigDecimal emaPast = emaCalculator.calculate(
                history.subList(0, history.size() - emaSlopeLookback), emaPeriod);
        if (emaNow == null || emaPast == null || emaPast.signum() == 0) return Double.NaN;
        double pct = emaNow.subtract(emaPast)
                .divide(emaPast, 8, RoundingMode.HALF_UP).doubleValue() * 100;
        log.debug("EMA{} slope ({}) for {}: {}% over {} candles",
                emaPeriod, emaTrendTimeframe, symbol,
                String.format("%.3f", pct), emaSlopeLookback);
        return pct;
    }

    /**
     * Returns 1h ATR as a percentage of current price. NaN if ATR or price unavailable.
     */
    private double getAtrPctOfPrice(String symbol, BigDecimal currentPrice) {
        if (currentPrice == null || currentPrice.signum() == 0) return Double.NaN;
        Optional<BigDecimal> atr = atrCalculator.computeAtr(symbol, emaTrendTimeframe, atrMinPctPeriod);
        if (atr.isEmpty()) return Double.NaN;
        return atr.get().divide(currentPrice, 8, RoundingMode.HALF_UP).doubleValue() * 100;
    }

    /**
     * Computes the % price change over the last MOMENTUM_LOOKBACK candles on the trend timeframe.
     * Returns Double.NaN if insufficient history is available.
     */
    private double getMomentumChangePct(String symbol) {
        String key = priceHistoryService.buildKey(symbol, emaTrendTimeframe);
        List<BigDecimal> history = priceHistoryService.getPriceHistory(key);
        if (history == null || history.size() <= MOMENTUM_LOOKBACK) return Double.NaN;
        BigDecimal current = history.get(history.size() - 1);
        BigDecimal reference = history.get(history.size() - 1 - MOMENTUM_LOOKBACK);
        if (reference.compareTo(BigDecimal.ZERO) == 0) return Double.NaN;
        double changePct = current.subtract(reference)
                .divide(reference, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
        log.debug("Momentum {} ({}): {}% over {} candles",
                symbol, emaTrendTimeframe, String.format("%.2f", changePct), MOMENTUM_LOOKBACK);
        return changePct;
    }

    /**
     * Falling-knife guard for TREND_BUY_DIP. Returns false (suppress) when price has fallen more
     * than {@code dipMaxDropPct} over the last {@code dipMaxDropLookback} candles on the fast
     * timeframe — i.e. the dip is a violent drop, not a healthy pullback. Disabled, warmup, and
     * degenerate inputs are pass-through (true) — better to alert than to silently skip.
     */
    private boolean isDipStabilized(Instrument instrument) {
        if (!dipMaxDropFilterEnabled || dipMaxDropPct <= 0) return true;
        String fastTf = Arrays.stream(instrument.getTimeframes().split(","))
                .map(String::trim)
                .min(Comparator.comparingInt(this::timeframeToMinutes))
                .orElse(null);
        if (fastTf == null) return true;
        String key = priceHistoryService.buildKey(instrument.getSymbol(), fastTf);
        List<BigDecimal> history = priceHistoryService.getPriceHistory(key);
        if (history == null || history.size() <= dipMaxDropLookback) return true; // warmup
        BigDecimal current = history.get(history.size() - 1);
        BigDecimal reference = history.get(history.size() - 1 - dipMaxDropLookback);
        if (reference == null || reference.signum() == 0) return true;
        double dropPct = current.subtract(reference)
                .divide(reference, 8, RoundingMode.HALF_UP).doubleValue() * 100;
        if (dropPct < -dipMaxDropPct) {
            log.info("TREND_BUY_DIP suppressed for {} — price dropped {}% over last {} {} candles (falling knife, floor -{}%)",
                    instrument.getSymbol(), String.format("%.2f", dropPct),
                    dipMaxDropLookback, fastTf, dipMaxDropPct);
            filterEventCounterService.record("DIP_FALLING_KNIFE", instrument.getSymbol());
            return false;
        }
        return true;
    }

    /**
     * Volume confirmation for crypto TREND_BUY_DIP.
     * Returns true if:
     *   - instrument is not CRYPTO (filter does not apply to indices/commodities)
     *   - filter is disabled
     *   - insufficient candle history (< lookback)
     *   - trigger candle volume >= mean volume of last `lookback` 15m candles × multiplier
     *
     * Logs skip reason when volume is too low.
     */
    private boolean isVolumeConfirmed(Instrument instrument, Candle triggerCandle) {
        if (instrument.getType() != Instrument.InstrumentType.CRYPTO) {
            return true;
        }
        if (!cryptoVolumeFilterEnabled) {
            return true;
        }
        if (triggerCandle == null || triggerCandle.getVolume() == null) {
            return true;
        }

        List<CandleHistory> lookback = candleHistoryRepository
                .findBySymbolAndTimeframeOrderByCandleTimeDesc(
                        instrument.getSymbol(), "15m", Pageable.ofSize(cryptoVolumeLookback));
        if (lookback == null || lookback.size() < cryptoVolumeLookback) {
            return true; // warmup — better to alert than miss
        }

        double meanVolume = lookback.stream()
                .mapToDouble(c -> c.getVolume() != null ? c.getVolume().doubleValue() : 0.0)
                .average()
                .orElse(0.0);

        double triggerVolume = triggerCandle.getVolume().doubleValue();
        double threshold = meanVolume * cryptoVolumeMultiplier;

        if (triggerVolume >= threshold) {
            log.debug("Volume confirmed for {} — {} >= {}× mean ({})",
                    instrument.getSymbol(), triggerCandle.getVolume(), cryptoVolumeMultiplier,
                    BigDecimal.valueOf(meanVolume).setScale(2, RoundingMode.HALF_UP));
            return true;
        }

        log.info("Trend entry suppressed for {} — volume {} < {}× mean ({})",
                instrument.getSymbol(), triggerCandle.getVolume(), cryptoVolumeMultiplier,
                BigDecimal.valueOf(meanVolume).setScale(2, RoundingMode.HALF_UP));
        filterEventCounterService.record("CRYPTO_VOLUME", instrument.getSymbol());
        return false;
    }

    private BigDecimal getFastestRsi(Map<String, BigDecimal> rsiValues, String timeframesConfig) {
        String fastestTf = Arrays.stream(timeframesConfig.split(","))
                .map(String::trim)
                .min(Comparator.comparingInt(this::timeframeToMinutes))
                .orElse(null);
        if (fastestTf == null) return null;
        return rsiValues.get(fastestTf);
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
}
