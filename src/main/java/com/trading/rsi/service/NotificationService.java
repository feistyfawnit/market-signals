package com.trading.rsi.service;

import com.trading.rsi.domain.SignalLog;
import com.trading.rsi.event.SignalEvent;
import com.trading.rsi.model.RsiSignal;
import com.trading.rsi.model.StochasticResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationService {
    
    private final ClaudeEnrichmentService claudeEnrichmentService;
    private final DeepSeekEnrichmentService deepSeekEnrichmentService;
    private final AppSettingsService appSettingsService;
    private final TelegramNotificationService telegramNotificationService;
    private final TrendDetectionService trendDetectionService;
    private final AtomicBoolean noTradeModeActive = new AtomicBoolean(false);
    private final Set<String> mutedSymbols = ConcurrentHashMap.newKeySet();

    @PostConstruct
    void loadPersistedSettings() {
        boolean savedNoTradeMode = appSettingsService.getBoolean(AppSettingsService.KEY_NO_TRADE_MODE, false);
        noTradeModeActive.set(savedNoTradeMode);
        Set<String> savedMuted = appSettingsService.getStringSet(AppSettingsService.KEY_MUTED_SYMBOLS);
        mutedSymbols.addAll(savedMuted);
        log.info("Settings loaded from DB — noTradeMode={} mutedSymbols={}", savedNoTradeMode, savedMuted);
    }
    
    @Value("${rsi.quiet-hours.enabled:true}")
    private boolean quietHoursEnabled;
    
    @Value("${rsi.quiet-hours.start-hour:22}")
    private int quietHoursStart;
    
    @Value("${rsi.quiet-hours.end-hour:8}")
    private int quietHoursEnd;

    @Value("${rsi.quiet-hours.suppress-partials-on-weekends:true}")
    private boolean suppressPartialsOnWeekends;

    @Value("${rsi.demo.account-balance:10000}")
    private int demoAccountBalance;

    @Value("${rsi.demo.risk-percent:1}")
    private int demoRiskPercent;

    @Value("${rsi.demo.stop-percent-crypto:2.0}")
    private double stopPercentCrypto;

    @Value("${rsi.demo.stop-percent-index:0.5}")
    private double stopPercentIndex;

    @Value("${rsi.demo.stop-percent-commodity:1.0}")
    private double stopPercentCommodity;

    @Value("${rsi.demo.account-currency:EUR}")
    private String accountCurrency;
    
    @EventListener
    @Async
    public void handleSignalEvent(SignalEvent event) {
        
        RsiSignal signal = event.getSignal();
        if (signal.isSilent()) {
            log.debug("Silent mode active for {} {} — recording only, no Telegram alert",
                    signal.getSymbol(), signal.getSignalType());
            return;
        }
        boolean isFullSignal = signal.getTimeframesAligned() >= signal.getTotalTimeframes();
        if (mutedSymbols.contains(signal.getSymbol())) {
            log.debug("Symbol {} is muted, suppressing all signals", signal.getSymbol());
            return;
        }
        if (!isFullSignal && noTradeModeActive.get()) {
            log.debug("No-trade mode active, suppressing partial/watch signal for {}", signal.getSymbol());
            return;
        }
        if (!isFullSignal && isQuietHours()) {
            log.debug("Quiet hours active, suppressing partial signal for {}", signal.getSymbol());
            return;
        }
        if (!isFullSignal && suppressPartialsOnWeekends && isWeekend()) {
            log.debug("Weekend suppression active, skipping partial/watch signal for {}", signal.getSymbol());
            return;
        }
        if (isFullSignal && isQuietHours()) {
            log.info("Quiet hours but FULL signal — sending for {}", signal.getSymbol());
        }
        if (isFullSignal && isWeekend()) {
            log.info("Weekend but FULL signal — sending for {}", signal.getSymbol());
        }
        
        String aiContext = claudeEnrichmentService.enrichSignal(signal);
        String deepSeekContext = deepSeekEnrichmentService.enrich(signal).orElse(null);
        sendNotification(signal, aiContext, deepSeekContext);
    }
    
    public void enableNoTradeMode() {
        noTradeModeActive.set(true);
        appSettingsService.setBoolean(AppSettingsService.KEY_NO_TRADE_MODE, true);
        log.info("No-trade mode ENABLED — PARTIAL and WATCH signals suppressed");
    }

    public void disableNoTradeMode() {
        noTradeModeActive.set(false);
        appSettingsService.setBoolean(AppSettingsService.KEY_NO_TRADE_MODE, false);
        log.info("No-trade mode DISABLED — all signals active");
    }

    public boolean isNoTradeModeActive() {
        return noTradeModeActive.get();
    }

    public void muteSymbol(String symbol) {
        mutedSymbols.add(symbol.toUpperCase());
        appSettingsService.setStringSet(AppSettingsService.KEY_MUTED_SYMBOLS, mutedSymbols);
        log.info("Symbol {} muted — all alerts suppressed", symbol);
    }

    public void unmuteSymbol(String symbol) {
        mutedSymbols.remove(symbol.toUpperCase());
        appSettingsService.setStringSet(AppSettingsService.KEY_MUTED_SYMBOLS, mutedSymbols);
        log.info("Symbol {} unmuted — alerts active", symbol);
    }

    public Set<String> getMutedSymbols() {
        return Set.copyOf(mutedSymbols);
    }

    private void sendNotification(RsiSignal signal, String aiContext, String deepSeekContext) {
        String title = buildNotificationTitle(signal);
        String message = buildNotificationMessage(signal, aiContext, deepSeekContext);
        telegramNotificationService.send(title, message);
    }
    
    private String buildNotificationTitle(RsiSignal signal) {
        String emoji = switch (signal.getSignalType()) {
            case OVERSOLD -> "🟢";
            case OVERBOUGHT -> "🔴";
            case PARTIAL_OVERSOLD -> "🟡";
            case PARTIAL_OVERBOUGHT -> "🟠";
            case WATCH_OVERSOLD -> "👀";
            case WATCH_OVERBOUGHT -> "👀";
            case TREND_BUY_DIP -> "📈";
            case TREND_SELL_RALLY -> "📉";
        };
        
        String signalName = switch (signal.getSignalType()) {
            case OVERSOLD -> "BUY SIGNAL";
            case OVERBOUGHT -> "SELL SIGNAL";
            case PARTIAL_OVERSOLD -> "Partial Buy";
            case PARTIAL_OVERBOUGHT -> "Partial Sell";
            case WATCH_OVERSOLD -> "WATCH Buy";
            case WATCH_OVERBOUGHT -> "WATCH Sell";
            case TREND_BUY_DIP -> "BUY DIP";
            case TREND_SELL_RALLY -> "SELL RALLY";
        };
        
        return "<b>" + emoji + " " + escapeHtml(signal.getInstrumentName()) + " " + signalName + "</b>";
    }
    
    private String buildNotificationMessage(RsiSignal signal, String aiContext, String deepSeekContext) {
        StringBuilder message = new StringBuilder();

        // Price (bold for emphasis)
        message.append("Price: <b>").append(formatPrice(signal.getCurrentPrice(), signal.getSymbol())).append("</b>\n");

        // RSI compact one-liner: "RSI 3/3: 15m 75.4 \u00b7 30m 78.9 \u00b7 1h 79.5"
        String rsiLine = signal.getRsiValues().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + " " + e.getValue().setScale(1, RoundingMode.HALF_UP))
                .collect(Collectors.joining(" \u00b7 "));
        message.append("RSI ").append(signal.getTimeframesAligned())
               .append("/").append(signal.getTotalTimeframes())
               .append(": ").append(rsiLine).append("\n");

        // Stochastic \u2014 collapsed when all TFs share same label
        if (signal.getStochasticValues() != null && !signal.getStochasticValues().isEmpty()) {
            appendStochastics(message, signal.getStochasticValues());
        }

        // Trend context
        TrendDetectionService.TrendState trendState = trendDetectionService.getTrendState(signal.getSymbol());
        if (trendState != TrendDetectionService.TrendState.NEUTRAL) {
            String trendEmoji = trendState == TrendDetectionService.TrendState.STRONG_UPTREND ? "\uD83D\uDC02" : "\uD83D\uDC3B";
            String trendLabel = trendState == TrendDetectionService.TrendState.STRONG_UPTREND ? "STRONG UPTREND" : "STRONG DOWNTREND";
            String trendReason = trendDetectionService.getTrendReason(signal.getSymbol());
            message.append("\n").append(trendEmoji).append(" <b>").append(trendLabel)
                   .append("</b> \u2014 <i>").append(escapeHtml(trendReason)).append("</i>\n");
        }

        // Crypto overnight warning (one-liner)
        if (isCryptoSymbol(signal.getSymbol())) {
            message.append("<i>\u26a0\ufe0f CFD overnight ~\u20ac0.50-0.70/unit \u2014 close by 22:00 UTC</i>\n");
        }

        // Action hint \u2014 only for non-obvious signal types
        boolean isTrendSignal = signal.getSignalType() == SignalLog.SignalType.TREND_BUY_DIP
                || signal.getSignalType() == SignalLog.SignalType.TREND_SELL_RALLY;
        boolean isWatch = signal.getSignalType() == SignalLog.SignalType.WATCH_OVERSOLD
                || signal.getSignalType() == SignalLog.SignalType.WATCH_OVERBOUGHT;
        if (isTrendSignal) {
            boolean isLong = signal.getSignalType() == SignalLog.SignalType.TREND_BUY_DIP;
            message.append(isLong ? "<i>\u2192 Go LONG with tight stop in confirmed uptrend</i>\n"
                                  : "<i>\u2192 Go SHORT with tight stop in confirmed downtrend</i>\n");
        } else if (isWatch) {
            String watchHint = signal.getSignalType() == SignalLog.SignalType.WATCH_OVERSOLD
                    ? "1 TF oversold + others approaching \u2014 check chart"
                    : "1 TF overbought + others approaching \u2014 check chart";
            message.append("<i>\u2192 ").append(watchHint).append("</i>\n");
        }

        // Partial: show which TFs still need to align
        boolean isPartial = signal.getSignalType() == SignalLog.SignalType.PARTIAL_OVERSOLD
                || signal.getSignalType() == SignalLog.SignalType.PARTIAL_OVERBOUGHT;
        if (isPartial) {
            boolean oversoldSignal = signal.getSignalType() == SignalLog.SignalType.PARTIAL_OVERSOLD;
            double threshold = oversoldSignal ? 30.0 : 70.0;
            message.append("\u23f3 Waiting:");
            signal.getRsiValues().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        double rsi = entry.getValue().doubleValue();
                        boolean aligned = oversoldSignal ? rsi < threshold : rsi > threshold;
                        if (!aligned) {
                            double gap = oversoldSignal ? rsi - threshold : threshold - rsi;
                            message.append(String.format(" %s %.1f\u2192%s%.0f (%.1fpt)",
                                    entry.getKey(), rsi, oversoldSignal ? "&lt;" : "&gt;", threshold, gap));
                        }
                    });
            message.append("\n");
        }

        message.append(buildDemoGuidance(signal));

        if (aiContext != null && !aiContext.isBlank()) {
            message.append("\n\uD83D\uDCF0 ").append(escapeHtml(aiContext));
        }
        if (deepSeekContext != null && !deepSeekContext.isBlank()) {
            message.append("\n\uD83E\uDD16 ").append(escapeHtml(deepSeekContext));
        }

        return message.toString();
    }

    private String buildDemoGuidance(RsiSignal signal) {
        double stopPct = inferStopPercent(signal.getSymbol());
        boolean isLong = signal.getSignalType() == SignalLog.SignalType.OVERSOLD
                || signal.getSignalType() == SignalLog.SignalType.PARTIAL_OVERSOLD
                || signal.getSignalType() == SignalLog.SignalType.WATCH_OVERSOLD
                || signal.getSignalType() == SignalLog.SignalType.TREND_BUY_DIP;
        boolean isPartial = signal.getSignalType() == SignalLog.SignalType.PARTIAL_OVERSOLD
                || signal.getSignalType() == SignalLog.SignalType.PARTIAL_OVERBOUGHT;
        boolean isTrendSignal = signal.getSignalType() == SignalLog.SignalType.TREND_BUY_DIP
                || signal.getSignalType() == SignalLog.SignalType.TREND_SELL_RALLY;

        BigDecimal entry = signal.getCurrentPrice();
        double effectiveStopPct = isTrendSignal ? stopPct * 0.5 : stopPct;
        long stopPts = Math.max(
                Math.round(entry.doubleValue() * effectiveStopPct / 100.0),
                Math.max(Math.round(entry.doubleValue() * 0.002), 2)  // floor: 0.2% of price or 2pts (IG minDealDistance proxy)
        );
        long limitPts = isTrendSignal ? stopPts * 3 : stopPts * 2;

        BigDecimal riskAmount = BigDecimal.valueOf(demoAccountBalance)
                .multiply(BigDecimal.valueOf(demoRiskPercent / 100.0))
                .setScale(2, RoundingMode.HALF_UP);
        String sizePerPoint = stopPts > 0
                ? riskAmount.divide(BigDecimal.valueOf(stopPts), 2, RoundingMode.HALF_UP).toPlainString()
                : "n/a";

        String direction = isLong ? "LONG" : "SHORT";
        String rrLabel = isTrendSignal ? "3:1" : "2:1";
        double rrMultiplier = isTrendSignal ? 3.0 : 2.0;
        String profitAmt = riskAmount.multiply(BigDecimal.valueOf(rrMultiplier))
                .setScale(0, RoundingMode.HALF_UP).toPlainString();

        StringBuilder sb = new StringBuilder("\n");
        if (isPartial) {
            sb.append("\uD83D\uDCCA WATCHING ").append(signal.getTimeframesAligned())
              .append("/").append(signal.getTotalTimeframes()).append(" TFs")
              .append(" | If confirmed: <b>").append(direction).append("</b>")
              .append(" | ").append(sizePerPoint).append(" ").append(accountCurrency).append("/pt")
              .append(" | Stop ").append(stopPts).append("pt")
              .append(" | Limit ").append(limitPts).append("pt (").append(rrLabel)
              .append(", ~").append(accountCurrency).append(profitAmt).append(")");
        } else {
            sb.append("\uD83D\uDCCA <b>").append(direction).append("</b>")
              .append(" | ").append(sizePerPoint).append(" ").append(accountCurrency).append("/pt")
              .append(" | Stop ").append(stopPts).append("pt")
              .append(" | Limit ").append(limitPts).append("pt (").append(rrLabel)
              .append(", ~").append(accountCurrency).append(profitAmt).append(")");
        }
        if (isCryptoSymbol(signal.getSymbol())) {
            sb.append("\n<i>[Binance price \u2014 adjust stops on IG entry accordingly]</i>");
        }
        return sb.toString();
    }

    private boolean isCryptoSymbol(String symbol) {
        return !symbol.startsWith("IX.") && !symbol.startsWith("CS.") && !symbol.startsWith("CC.");
    }

    private String formatPrice(BigDecimal price, String symbol) {
        if (price == null) return "N/A";
        String currencySymbol = inferCurrencySymbol(symbol);
        return currencySymbol + String.format("%,.2f", price.doubleValue());
    }

    private String inferCurrencySymbol(String symbol) {
        // DAX (Germany) trades in EUR
        if (symbol.contains("DAX")) return "\u20ac";
        // FTSE (UK) trades in GBP (actually GBX pence, but shown as GBP)
        if (symbol.contains("FTSE")) return "\u00a3";
        // Everything else (crypto, US indices, commodities) primarily USD-based
        return "$";
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void appendStochastics(StringBuilder message, Map<String, StochasticResult> stochasticValues) {
        Set<String> labels = stochasticValues.values().stream()
                .map(StochasticResult::label)
                .collect(Collectors.toSet());
        if (labels.size() == 1) {
            String label = labels.iterator().next();
            double avgK = stochasticValues.values().stream()
                    .mapToDouble(s -> s.k().doubleValue())
                    .average().orElse(0);
            String tfs = stochasticValues.keySet().stream()
                    .sorted()
                    .collect(Collectors.joining("/"));
            message.append("Stoch ").append(tfs).append(": ALL <b>")
                   .append(label).append("</b> (%K ~").append(String.format("%.0f", avgK)).append(")\n");
        } else {
            String stochLine = stochasticValues.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey() + " %K=" + e.getValue().k() + " (" + e.getValue().label() + ")")
                    .collect(Collectors.joining(" \u00b7 "));
            message.append("Stoch: ").append(stochLine).append("\n");
        }
    }

    private double inferStopPercent(String symbol) {
        if (symbol.startsWith("IX.")) return stopPercentIndex;
        if (symbol.startsWith("CS.") || symbol.startsWith("CC.")) return stopPercentCommodity;
        return stopPercentCrypto;
    }

    public void sendRawNotification(String title, String message) {
        telegramNotificationService.send(title, message);
    }

    private boolean isQuietHours() {
        if (!quietHoursEnabled) {
            return false;
        }
        
        int currentHour = ZonedDateTime.now(ZoneOffset.UTC).getHour();
        
        return quietHoursStart > quietHoursEnd
                ? currentHour >= quietHoursStart || currentHour < quietHoursEnd
                : currentHour >= quietHoursStart && currentHour < quietHoursEnd;
    }

    private boolean isWeekend() {
        DayOfWeek day = ZonedDateTime.now(ZoneOffset.UTC).getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }
}
