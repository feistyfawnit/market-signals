package com.trading.rsi.service;

import com.trading.rsi.domain.CandleHistory;
import com.trading.rsi.repository.CandleHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;

/**
 * Computes the MACD (Moving Average Convergence Divergence) indicator
 * — Gerald Appel, 1979 — for a symbol on a given timeframe.
 *
 * MACD line     = EMA(close, fast) − EMA(close, slow)        e.g. EMA12 − EMA26
 * Signal line   = EMA(MACD line, signal)                      e.g. EMA9 of MACD
 * Histogram     = MACD line − Signal line
 *
 * For a TREND_BUY_DIP confirmation we only care about the histogram:
 *   histogram &gt; 0           → bullish momentum is intact
 *   histogram &gt; previous    → bullish momentum is rising (turning up from a dip)
 * Either condition passes the filter.
 *
 * Needs at least slow + signal + 1 candles to produce a usable histogram pair.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MacdCalculator {

    private final CandleHistoryRepository candleHistoryRepository;

    /** Latest histogram value and its previous value (one candle earlier). */
    public record MacdHistogram(BigDecimal current, BigDecimal previous) {
        public boolean isBullish() {
            return current.signum() > 0;
        }
        public boolean isRising() {
            return current.compareTo(previous) > 0;
        }
    }

    /** Divergence result: MACD line vs price over a lookback window. */
    public record MacdDivergenceResult(boolean bullishDivergence, boolean bearishDivergence, String reason) {}

    public Optional<MacdHistogram> compute(String symbol, String timeframe,
                                            int fastPeriod, int slowPeriod, int signalPeriod) {
        if (fastPeriod < 2 || slowPeriod <= fastPeriod || signalPeriod < 2) {
            return Optional.empty();
        }

        // Memory: bounded fetch — Pageable.ofSize(slowPeriod + signalPeriod + 2).
        // At default params (26 + 9 + 2 = 37 candles, ~9 KB) vs the prior unbounded query
        // which loaded up to 1,440 candles (~360 KB) — the highest previous heap risk.
        List<CandleHistory> candles = new ArrayList<>(candleHistoryRepository
                .findBySymbolAndTimeframeOrderByCandleTimeDesc(symbol, timeframe,
                        Pageable.ofSize(slowPeriod + signalPeriod + 2)));
        Collections.reverse(candles); // DESC → ASC for EMA computation

        // We need enough bars so that:
        //  - the slow EMA is seeded (slow bars), then
        //  - we generate enough MACD-line values to seed the signal EMA (signal bars), then
        //  - we have at least 2 histogram values (current + previous).
        int required = slowPeriod + signalPeriod + 1;
        if (candles.size() < required) {
            log.debug("MACD unavailable for {}:{} — only {} candles (need {})",
                    symbol, timeframe, candles.size(), required);
            return Optional.empty();
        }

        int n = candles.size();
        double[] closes = new double[n];
        for (int i = 0; i < n; i++) {
            closes[i] = candles.get(i).getClose().doubleValue();
        }

        double[] fastEma = ema(closes, fastPeriod);
        double[] slowEma = ema(closes, slowPeriod);

        // MACD line is only valid from index slowPeriod-1 onward (where slow EMA is seeded).
        int macdStart = slowPeriod - 1;
        int macdLen = n - macdStart;
        double[] macdLine = new double[macdLen];
        for (int i = 0; i < macdLen; i++) {
            macdLine[i] = fastEma[macdStart + i] - slowEma[macdStart + i];
        }

        if (macdLen < signalPeriod + 1) {
            return Optional.empty();
        }

        double[] signalEma = ema(macdLine, signalPeriod);
        // Histogram is valid where the signal EMA is seeded (index signalPeriod-1 onward in macdLine space).
        int last = macdLen - 1;
        int prev = macdLen - 2;
        double histLast = macdLine[last] - signalEma[last];
        double histPrev = macdLine[prev] - signalEma[prev];

        return Optional.of(new MacdHistogram(
                BigDecimal.valueOf(histLast).setScale(6, RoundingMode.HALF_UP),
                BigDecimal.valueOf(histPrev).setScale(6, RoundingMode.HALF_UP)));
    }

    /**
     * Computes MACD divergence over a bounded lookback window.
     *
     * Memory: Pageable.ofSize(slowPeriod + lookback) — at default params (26 + 20 = 46 candles, ~11 KB).
     * The prior unbounded compute() loaded up to 1,440 candles (~360 KB); this method never
     * exceeds slowPeriod + lookback rows regardless of how much history is stored.
     *
     * Divergence logic: split the lookback window into two halves.
     *   Bullish: second-half price low &lt; first-half price low AND
     *            MACD line at that low is higher (hidden upward momentum).
     *   Bearish: second-half price high &gt; first-half price high AND
     *            MACD line at that high is lower (hidden downward momentum).
     *
     * Returns Optional.empty() when insufficient candle history exists (warmup-friendly).
     */
    public Optional<MacdDivergenceResult> computeDivergence(String symbol, String timeframe,
            int fastPeriod, int slowPeriod, int signalPeriod, int lookback) {
        if (fastPeriod < 2 || slowPeriod <= fastPeriod || lookback < 4) {
            return Optional.empty();
        }

        List<CandleHistory> raw = candleHistoryRepository
                .findBySymbolAndTimeframeOrderByCandleTimeDesc(symbol, timeframe,
                        Pageable.ofSize(slowPeriod + lookback));
        List<CandleHistory> candles = new ArrayList<>(raw);
        Collections.reverse(candles);

        int n = candles.size();
        if (n < slowPeriod + lookback) {
            log.debug("MACD divergence unavailable for {}:{} — only {} candles (need {})",
                    symbol, timeframe, n, slowPeriod + lookback);
            return Optional.empty();
        }

        double[] closes = new double[n];
        for (int i = 0; i < n; i++) {
            closes[i] = candles.get(i).getClose().doubleValue();
        }

        double[] fastEma = ema(closes, fastPeriod);
        double[] slowEma = ema(closes, slowPeriod);

        int macdStart = slowPeriod - 1; // MACD line valid from this index onward
        int divLen = n - macdStart;     // = lookback + 1
        double[] macdLine = new double[divLen];
        for (int i = 0; i < divLen; i++) {
            macdLine[i] = fastEma[macdStart + i] - slowEma[macdStart + i];
        }

        int half = divLen / 2;

        // Find the price extreme and its corresponding MACD value in each half.
        double firstLowClose = Double.MAX_VALUE, firstLowMacd = 0;
        double firstHighClose = -Double.MAX_VALUE, firstHighMacd = 0;
        for (int i = 0; i < half; i++) {
            double c = closes[macdStart + i];
            double m = macdLine[i];
            if (c < firstLowClose)  { firstLowClose = c;  firstLowMacd = m; }
            if (c > firstHighClose) { firstHighClose = c; firstHighMacd = m; }
        }
        double secondLowClose = Double.MAX_VALUE, secondLowMacd = 0;
        double secondHighClose = -Double.MAX_VALUE, secondHighMacd = 0;
        for (int i = half; i < divLen; i++) {
            double c = closes[macdStart + i];
            double m = macdLine[i];
            if (c < secondLowClose)  { secondLowClose = c;  secondLowMacd = m; }
            if (c > secondHighClose) { secondHighClose = c; secondHighMacd = m; }
        }

        boolean bullish = secondLowClose < firstLowClose && secondLowMacd > firstLowMacd;
        boolean bearish = secondHighClose > firstHighClose && secondHighMacd < firstHighMacd;
        String reason = bullish ? "price lower low, MACD higher low"
                      : bearish ? "price higher high, MACD lower high"
                      : "no divergence detected";

        log.debug("MACD divergence {}:{} — bullish={} bearish={} ({})", symbol, timeframe, bullish, bearish, reason);
        return Optional.of(new MacdDivergenceResult(bullish, bearish, reason));
    }

    /**
     * Standard EMA series, seeded with an SMA of the first `period` values.
     * For indices &lt; period-1 the value is the seed SMA (placeholder; callers should
     * not consume those indices).
     */
    private double[] ema(double[] values, int period) {
        double k = 2.0 / (period + 1);
        double[] out = new double[values.length];
        if (values.length < period) return out;

        double sum = 0;
        for (int i = 0; i < period; i++) sum += values[i];
        double seed = sum / period;
        for (int i = 0; i < period; i++) out[i] = seed; // placeholder; not used by callers
        out[period - 1] = seed;

        double ema = seed;
        for (int i = period; i < values.length; i++) {
            ema = values[i] * k + ema * (1 - k);
            out[i] = ema;
        }
        return out;
    }
}
