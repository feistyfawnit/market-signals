package com.trading.rsi.repository;

import com.trading.rsi.domain.FilterEventCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

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
}
