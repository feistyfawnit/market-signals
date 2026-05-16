package com.trading.rsi.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "instruments")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Instrument {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String symbol;
    
    @Column(nullable = false)
    private String name;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DataSource source;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InstrumentType type;
    
    @Builder.Default
    @Column(nullable = false)
    private Boolean enabled = true;
    
    @Builder.Default
    @Column(name = "oversold_threshold")
    private Integer oversoldThreshold = 30;
    
    @Builder.Default
    @Column(name = "overbought_threshold")
    private Integer overboughtThreshold = 70;
    
    @Builder.Default
    @Column(name = "timeframes")
    private String timeframes = "1m,5m,1h,4h";

    @Builder.Default
    @Column(name = "trend_buy_dip_enabled")
    private Boolean trendBuyDipEnabled = true;

    /**
     * When false, TREND_BUY_DIP signals are still recorded (SignalLog + PositionOutcome)
     * but the Telegram notification is suppressed. Lets us collect forward P&L data on
     * an instrument whose trend performance we're unsure about without alert noise.
     * Independent of `trendBuyDipEnabled` (which suppresses detection entirely).
     */
    @Builder.Default
    @Column(name = "trend_buy_dip_notify")
    private Boolean trendBuyDipNotify = true;

    /**
     * When false, all RSI-based signals (OVERSOLD, OVERBOUGHT, PARTIAL_*, WATCH_*) are
     * suppressed for this instrument. Price data still accumulates and the instrument
     * remains eligible for CrossAssetCorrelationService regime detection.
     * Use for instruments that produce zero edge on RSI signals (e.g. Gold).
     */
    @Builder.Default
    @Column(name = "rsi_signals_enabled")
    private Boolean rsiSignalsEnabled = true;

    @Column(name = "market_close_utc")
    private Integer marketCloseUtc;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    public boolean isCrypto() {
        return type == InstrumentType.CRYPTO;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    public enum DataSource {
        BINANCE,
        FINNHUB,
        IG,
        TWELVE_DATA
    }
    
    public enum InstrumentType {
        CRYPTO,
        INDEX,
        COMMODITY,
        FX
    }
}
