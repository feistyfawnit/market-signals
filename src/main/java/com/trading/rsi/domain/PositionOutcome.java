package com.trading.rsi.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "position_outcomes",
    indexes = {
        @Index(name = "idx_pos_symbol", columnList = "symbol"),
        @Index(name = "idx_pos_exit_time", columnList = "exit_time")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PositionOutcome {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", nullable = false)
    private SignalLog.SignalType signalType;

    @Column(name = "entry_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal entryPrice;

    @Column(name = "entry_time", nullable = false)
    private Instant entryTime;

    @Column(name = "exit_price", precision = 20, scale = 8)
    private BigDecimal exitPrice;

    @Column(name = "exit_time")
    private Instant exitTime;

    @Column(name = "tp_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal tpPrice;

    @Column(name = "sl_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal slPrice;

    @Column(name = "tp_hit")
    private Boolean tpHit;

    @Column(name = "sl_hit")
    private Boolean slHit;

    @Column(name = "pnl_pct", precision = 10, scale = 4)
    private BigDecimal pnlPct;

    @Column(name = "holding_hours")
    private Double holdingHours;

    @Column(name = "is_long", nullable = false)
    private Boolean isLong;

    @Column(name = "trend_state", length = 20)
    private String trendState;

    @Column(name = "rsi_fast", precision = 6, scale = 2)
    private BigDecimal rsiFast;

    @Column(name = "rsi_mid", precision = 6, scale = 2)
    private BigDecimal rsiMid;

    @Column(name = "rsi_slow", precision = 6, scale = 2)
    private BigDecimal rsiSlow;

    @Column(name = "stoch_k", precision = 6, scale = 2)
    private BigDecimal stochK;

    @Column(name = "stoch_d", precision = 6, scale = 2)
    private BigDecimal stochD;

    @Column(name = "ig_deal_ref")
    private String igDealRef;

    @Column(name = "ig_deal_id")
    private String igDealId;

    /** Initial stop distance in price points, computed at position open and never changed.
     *  Used by trailing stop logic to compute the 50%-of-stop threshold without losing
     *  the original value after slPrice has been updated by trailing. */
    @Column(name = "stop_pts")
    private Long stopPts;

    /** Bid/offer spread at entry (points). Captured from the live IG market snapshot when
     *  the trade is placed, so we can measure how much of the stop the spread consumed.
     *  Null for paper positions or when the IG snapshot is unavailable. */
    @Column(name = "entry_spread_pts", precision = 20, scale = 8)
    private BigDecimal entrySpreadPts;

    /** Bid/offer spread at entry as a percentage of entry price. */
    @Column(name = "entry_spread_pct", precision = 10, scale = 4)
    private BigDecimal entrySpreadPct;
}
