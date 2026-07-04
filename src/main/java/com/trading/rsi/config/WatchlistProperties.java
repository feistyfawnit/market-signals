package com.trading.rsi.config;

import com.trading.rsi.domain.Instrument;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "watchlist")
public class WatchlistProperties {

    private List<InstrumentConfig> instruments;

    @Data
    public static class InstrumentConfig {
        private String symbol;
        private String name;
        private Instrument.DataSource source;
        private Instrument.InstrumentType type;
        private Boolean enabled = true;
        private Integer oversoldThreshold = 30;
        private Integer overboughtThreshold = 70;
        private String timeframes = "1m,5m,1h,4h";
        private Boolean trendBuyDipEnabled = true;
        private Boolean trendBuyDipNotify = true;
        private Boolean rsiSignalsEnabled = true;
        private Boolean autoExecuteEnabled = false;
        private String igEpic;
    }
}
