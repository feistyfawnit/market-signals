package com.trading.rsi.service;

import com.trading.rsi.domain.CandleHistory;
import com.trading.rsi.repository.CandleHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Computes the Average Directional Index (ADX) — Wilder's trend-strength
 * indicator — for a symbol on a given timeframe.
 *
 * ADX values are scaled 0–100:
 *   &lt; 20   — non-trending / ranging (skip trend-following entries)
 *   20–25  — possible emerging trend
 *   25+    — established trend
 *   40+    — very strong trend (may indicate exhaustion)
 *
 * Algorithm (Wilder, 1978):
 *
 *   TR_i   = max( high_i - low_i,
 *                 |high_i - close_{i-1}|,
 *                 |low_i  - close_{i-1}| )
 *   +DM_i  = (up move &gt; down move AND up move &gt; 0) ? up move   : 0
 *   -DM_i  = (down move &gt; up move AND down move &gt; 0) ? down move : 0
 *       where up move   = high_i - high_{i-1}
 *             down move = low_{i-1} - low_i
 *
 *   Smooth TR / +DM / -DM with Wilder's smoothing over `period` bars:
 *     first value = sum of first `period`
 *     next values = prev - (prev / period) + current
 *
 *   +DI = 100 × (+DM smoothed / TR smoothed)
 *   -DI = 100 × (-DM smoothed / TR smoothed)
 *   DX  = 100 × |+DI - -DI| / (+DI + -DI)
 *   ADX = Wilder smoothing of DX over `period` bars
 *
 * Needs at least 2 × period + 1 candles to produce a first ADX value.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AdxCalculator {

    private final CandleHistoryRepository candleHistoryRepository;

    // ADX cascades two Wilder-smoothing stages (TR/DM, then DX→ADX), so the seed bias decays
    // slightly slower than a single-stage indicator like ATR. Buffer on top of the strict
    // 2×period+1 requirement leaves negligible seed influence — see AGENTS.md § Efficiency
    // Guardrails ("DB queries must be bounded") and AtrCalculator for the same pattern.
    private static final int SMOOTHING_BUFFER_MULTIPLIER = 5;

    /**
     * Compute the latest ADX value for symbol on timeframe using the given period.
     * Returns empty if insufficient history.
     */
    public Optional<BigDecimal> computeAdx(String symbol, String timeframe, int period) {
        if (period < 2) return Optional.empty();

        // Bounded query — no full-table scan. Fetch most-recent N candles desc, then
        // reverse to ascending time order (required by the smoothing walk below).
        int fetchSize = period * (2 + SMOOTHING_BUFFER_MULTIPLIER) + 1;
        List<CandleHistory> candles = candleHistoryRepository
                .findBySymbolAndTimeframeOrderByCandleTimeDesc(symbol, timeframe, PageRequest.of(0, fetchSize));
        Collections.reverse(candles);

        // Need 2 × period + 1 bars: period for DI smoothing, then period DX values
        // to seed the ADX average.
        int required = 2 * period + 1;
        if (candles.size() < required) {
            log.debug("ADX unavailable for {}:{} — only {} candles (need {})",
                    symbol, timeframe, candles.size(), required);
            return Optional.empty();
        }

        int n = candles.size();
        double[] tr = new double[n];
        double[] plusDm = new double[n];
        double[] minusDm = new double[n];

        for (int i = 1; i < n; i++) {
            CandleHistory cur = candles.get(i);
            CandleHistory prev = candles.get(i - 1);
            double h = cur.getHigh().doubleValue();
            double l = cur.getLow().doubleValue();
            double pc = prev.getClose().doubleValue();
            double ph = prev.getHigh().doubleValue();
            double pl = prev.getLow().doubleValue();

            tr[i] = Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc)));

            double up = h - ph;
            double down = pl - l;
            plusDm[i]  = (up > down && up > 0) ? up : 0;
            minusDm[i] = (down > up && down > 0) ? down : 0;
        }

        // Wilder-smoothed TR / +DM / -DM series, starting at index `period`.
        double trSum = 0, plusDmSum = 0, minusDmSum = 0;
        for (int i = 1; i <= period; i++) {
            trSum += tr[i];
            plusDmSum += plusDm[i];
            minusDmSum += minusDm[i];
        }
        double trS = trSum, plusS = plusDmSum, minusS = minusDmSum;

        // Walk forward, computing DX at each step and smoothing ADX over `period` DX values.
        // Seed ADX from the mean of the first `period` DX values (indexes [period+1 .. 2*period]).
        double adx = Double.NaN;
        double dxSum = 0;
        int dxCount = 0;

        for (int i = period + 1; i < n; i++) {
            trS   = trS   - (trS   / period) + tr[i];
            plusS = plusS - (plusS / period) + plusDm[i];
            minusS = minusS - (minusS / period) + minusDm[i];
            if (trS == 0) continue;

            double plusDi = 100.0 * plusS / trS;
            double minusDi = 100.0 * minusS / trS;
            double diSum = plusDi + minusDi;
            if (diSum == 0) continue;
            double dx = 100.0 * Math.abs(plusDi - minusDi) / diSum;

            if (Double.isNaN(adx)) {
                dxSum += dx;
                dxCount++;
                if (dxCount == period) {
                    adx = dxSum / period;
                }
            } else {
                adx = (adx * (period - 1) + dx) / period;
            }
        }

        if (Double.isNaN(adx)) return Optional.empty();
        return Optional.of(BigDecimal.valueOf(adx).setScale(2, RoundingMode.HALF_UP));
    }
}
