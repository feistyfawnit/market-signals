package com.trading.rsi.repository;

import com.trading.rsi.domain.CandleHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Repository
public interface CandleHistoryRepository extends JpaRepository<CandleHistory, Long> {

    List<CandleHistory> findBySymbolAndTimeframeOrderByCandleTimeAsc(String symbol, String timeframe);

    List<CandleHistory> findBySymbolAndTimeframeOrderByCandleTimeDesc(String symbol, String timeframe, Pageable pageable);

    List<CandleHistory> findByCandleTimeBefore(Instant before);

    List<CandleHistory> findByCandleTimeBeforeOrderByCandleTimeAsc(Instant before, Pageable pageable);

    List<CandleHistory> findBySymbolAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
            String symbol, String timeframe, Instant from, Instant to);

    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO candle_history (symbol, timeframe, candle_time, open, high, low, close, volume)
        VALUES (:symbol, :timeframe, :candleTime, :open, :high, :low, :close, :volume)
        ON CONFLICT ON CONSTRAINT uq_candle_symbol_timeframe_time DO NOTHING
        """, nativeQuery = true)
    void insertIgnore(@Param("symbol") String symbol,
                      @Param("timeframe") String timeframe,
                      @Param("candleTime") Instant candleTime,
                      @Param("open") BigDecimal open,
                      @Param("high") BigDecimal high,
                      @Param("low") BigDecimal low,
                      @Param("close") BigDecimal close,
                      @Param("volume") BigDecimal volume);

    long countBySymbolAndTimeframe(String symbol, String timeframe);

    List<CandleHistory> findBySymbolOrderByCandleTimeDesc(String symbol, Pageable pageable);

    List<CandleHistory> findBySymbolAndCandleTimeLessThanEqualOrderByCandleTimeDesc(String symbol, Instant candleTime, Pageable pageable);

    @Modifying
    @Transactional
    @Query(value = """
        DELETE FROM candle_history
        WHERE symbol = :symbol AND timeframe = :timeframe
        AND id NOT IN (
            SELECT id FROM candle_history
            WHERE symbol = :symbol AND timeframe = :timeframe
            ORDER BY candle_time DESC
            LIMIT :keep
        )
        """, nativeQuery = true)
    void trimToLatest(@Param("symbol") String symbol,
                      @Param("timeframe") String timeframe,
                      @Param("keep") int keep);

    @Modifying
    @Transactional
    void deleteBySymbol(String symbol);
}
