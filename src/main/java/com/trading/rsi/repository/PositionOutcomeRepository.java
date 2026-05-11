package com.trading.rsi.repository;

import com.trading.rsi.domain.PositionOutcome;
import com.trading.rsi.domain.SignalLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface PositionOutcomeRepository extends JpaRepository<PositionOutcome, Long> {

    List<PositionOutcome> findByExitTimeIsNull();

    List<PositionOutcome> findByExitTimeIsNotNull();

    List<PositionOutcome> findBySignalTypeAndExitTimeIsNotNull(SignalLog.SignalType signalType);

    boolean existsBySymbolAndExitTimeIsNull(String symbol);

    Optional<PositionOutcome> findFirstBySymbolAndExitTimeIsNull(String symbol);

    List<PositionOutcome> findByEntryTimeBefore(Instant before);

    List<PositionOutcome> findByEntryTimeBeforeOrderByEntryTimeAsc(Instant before, Pageable pageable);

    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM PositionOutcome p " +
           "WHERE p.symbol = :symbol AND p.entryTime >= :since")
    boolean existsBySymbolSince(@Param("symbol") String symbol, @Param("since") Instant since);
}
