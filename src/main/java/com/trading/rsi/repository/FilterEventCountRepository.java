package com.trading.rsi.repository;

import com.trading.rsi.domain.FilterEventCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface FilterEventCountRepository extends JpaRepository<FilterEventCount, Long> {

    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO filter_event_counts (filter_name, symbol, day, count)
        VALUES (:f, :s, :d, 1)
        ON CONFLICT (filter_name, symbol, day)
        DO UPDATE SET count = filter_event_counts.count + 1
        """, nativeQuery = true)
    void increment(@Param("f") String filterName,
                   @Param("s") String symbol,
                   @Param("d") LocalDate day);

    @Query(value = """
        SELECT f.symbol, f.filter_name, SUM(f.count) AS total
        FROM filter_event_counts f
        WHERE f.day >= :since
        GROUP BY f.symbol, f.filter_name
        ORDER BY f.symbol, total DESC
        """, nativeQuery = true)
    List<Object[]> sumBySymbolAndFilterSince(@Param("since") LocalDate since);

    @Query(value = """
        SELECT f.symbol, f.filter_name, SUM(f.count) AS total
        FROM filter_event_counts f
        WHERE f.day >= :since AND f.symbol = :symbol
        GROUP BY f.symbol, f.filter_name
        ORDER BY total DESC
        """, nativeQuery = true)
    List<Object[]> sumBySymbolSince(@Param("since") LocalDate since, @Param("symbol") String symbol);

    @Query(value = """
        SELECT po.id, po.symbol, po.exit_time, COALESCE(SUM(fec.count), 0) AS cooldown_count
        FROM position_outcomes po
        LEFT JOIN filter_event_counts fec
            ON fec.symbol = po.symbol
            AND fec.filter_name = 'POSITION_COOLDOWN'
            AND fec.day IN (CAST(po.exit_time AS DATE), CAST(po.exit_time AS DATE) + 1)
        WHERE po.tp_hit IS NOT TRUE
            AND po.sl_hit IS NOT TRUE
            AND po.exit_time IS NOT NULL
            AND po.exit_time >= :since
        GROUP BY po.id, po.symbol, po.exit_time
        HAVING COALESCE(SUM(fec.count), 0) > 0
        ORDER BY po.exit_time DESC
        """, nativeQuery = true)
    List<Object[]> findRaceConditionCandidatesSince(@Param("since") Instant since);

    /**
     * Suppressed-signal retrospective.
     *
     * <p>For each (symbol, trend-entry filter) pair active in the window, returns:
     * <ul>
     *   <li>total suppression count</li>
     *   <li>number of distinct days observed (each one suppression-day with daily-rollup data on day and day+1)</li>
     *   <li>average next-day percent move ((next_day_close - day_close) / day_close * 100) across those days</li>
     * </ul>
     *
     * <p>Single native query, INNER JOINs against {@code daily_price_summary} so days without
     * end-of-day or next-day rollup data (e.g. weekends for IG instruments) are excluded
     * naturally. Only TREND_BUY_DIP-relevant filters are inspected — regime / administrative
     * filters (RISK_OFF, POSITION_COOLDOWN, DUPE_OPEN_POSITION, ...) are intentionally excluded.
     *
     * <p>Filters covered (must match the strings recorded by TrendDetectionService):
     * ADX_RANGING, MACD_HISTOGRAM, MACD_DIVERGENCE_ABSENT, EMA_SLOPE_FLAT,
     * ATR_RANGE_BOUND, DIP_DEDUPE, CRYPTO_VOLUME.
     *
     * <p>HAVING COUNT(*) >= 2 ensures at least two daily observations before drawing
     * a conclusion (single-day samples are noise).
     *
     * <p>CPU cost: one query per report build (not per request). No hot-path cost.
     *
     * @param since first day to include (inclusive)
     * @param today current UTC date — rows with day &gt;= today are excluded because
     *              the +1-day price has not happened yet
     * @return list of {@code [symbol, filter_name, total_suppressions, days_observed, avg_next_day_pct]}
     */
    @Query(value = """
        SELECT
            fec.symbol,
            fec.filter_name,
            SUM(fec.count)                                                  AS total_suppressions,
            COUNT(*)                                                        AS days_observed,
            AVG((nxt.close_price - sd.close_price) / sd.close_price * 100.0) AS avg_next_day_pct
        FROM filter_event_counts fec
        JOIN daily_price_summary sd
            ON sd.symbol = fec.symbol AND sd.summary_date = fec.day
        JOIN daily_price_summary nxt
            ON nxt.symbol = fec.symbol AND nxt.summary_date = fec.day + 1
        WHERE fec.day >= :since
            AND fec.day < :today
            AND fec.filter_name IN (
                'ADX_RANGING', 'MACD_HISTOGRAM', 'MACD_DIVERGENCE_ABSENT',
                'EMA_SLOPE_FLAT', 'ATR_RANGE_BOUND', 'DIP_DEDUPE', 'CRYPTO_VOLUME'
            )
        GROUP BY fec.symbol, fec.filter_name
        HAVING COUNT(*) >= 2
        ORDER BY fec.symbol, total_suppressions DESC
        """, nativeQuery = true)
    List<Object[]> findSuppressionRetrospectiveSince(
            @Param("since") LocalDate since,
            @Param("today") LocalDate today);
}
