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
}
