package com.trading.rsi.repository;

import com.trading.rsi.domain.SignalLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SignalLogRepository extends JpaRepository<SignalLog, Long> {
    List<SignalLog> findBySymbolOrderByCreatedAtDesc(String symbol);
    List<SignalLog> findByCreatedAtAfter(LocalDateTime after);
    List<SignalLog> findByCreatedAtBefore(LocalDateTime before);
    List<SignalLog> findByCreatedAtBeforeOrderByCreatedAtAsc(LocalDateTime before, Pageable pageable);
    Optional<SignalLog> findFirstBySymbolAndCreatedAtAfterOrderByCreatedAtDesc(String symbol, LocalDateTime after);

    @Query("SELECT s.symbol, COUNT(s) FROM SignalLog s WHERE s.signalType = :type AND s.createdAt >= :since GROUP BY s.symbol")
    List<Object[]> countFiredBySymbolSince(@Param("type") SignalLog.SignalType type, @Param("since") LocalDateTime since);
}
