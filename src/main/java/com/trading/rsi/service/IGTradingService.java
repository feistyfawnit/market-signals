package com.trading.rsi.service;

import com.trading.rsi.model.RsiSignal;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import com.trading.rsi.event.SignalEvent;
import com.trading.rsi.domain.SignalLog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phase 4: Semi-automated trading via IG API.
 *
 * DISABLED BY DEFAULT. Requires explicit opt-in:
 *   TRADING_AUTO_EXECUTION_ENABLED=true
 *
 * Prerequisites before enabling:
 *  - 3+ months of validated paper trading results
 *  - IG demo account testing complete (minimum 1 month)
 *  - Risk management parameters reviewed and set conservatively
 *  - Kill switch tested and confirmed working
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IGTradingService {

    private final IGAuthService authService;

    @Value("${trading.auto-execution.enabled:false}")
    private boolean autoExecutionEnabled;

    @Value("${trading.auto-execution.max-position-percent:2}")
    private int maxPositionPercent;

    @Value("${trading.auto-execution.max-concurrent-positions:2}")
    private int maxConcurrentPositions;

    @Value("${trading.auto-execution.daily-loss-limit-percent:2}")
    private int dailyLossLimitPercent;

    @Value("${trading.auto-execution.require-manual-approval:true}")
    private boolean requireManualApproval;

    private final AtomicBoolean killSwitchActive = new AtomicBoolean(false);
    private int openPositionCount = 0;
    private BigDecimal dailyPnl = BigDecimal.ZERO;
    private Instant dailyPnlResetTime = Instant.now();

    @EventListener
    public void handleSignalEvent(SignalEvent event) {
        if (!autoExecutionEnabled) {
            log.debug("Auto-execution disabled — signal logged only");
            return;
        }

        if (killSwitchActive.get()) {
            log.warn("KILL SWITCH ACTIVE — all auto-trading paused. Signal ignored: {}",
                    event.getSignal().getSymbol());
            return;
        }

        if (!authService.isEnabled()) {
            log.warn("IG API not configured — cannot auto-trade");
            return;
        }

        RsiSignal signal = event.getSignal();

        if (signal.getSignalType() == SignalLog.SignalType.PARTIAL_OVERSOLD
                || signal.getSignalType() == SignalLog.SignalType.PARTIAL_OVERBOUGHT) {
            log.info("Partial signal for {} — monitoring only, no auto-trade", signal.getSymbol());
            return;
        }

        if (!checkRiskLimits()) {
            return;
        }

        if (requireManualApproval) {
            log.info("MANUAL APPROVAL REQUIRED for {} {} — auto-execution queued but not sent",
                    signal.getSymbol(), signal.getSignalType());
            return;
        }

        executeTrade(signal);
    }

    private boolean checkRiskLimits() {
        resetDailyPnlIfNeeded();

        if (openPositionCount >= maxConcurrentPositions) {
            log.info("Max concurrent positions ({}) reached — skipping trade", maxConcurrentPositions);
            return false;
        }

        BigDecimal dailyLossThreshold = BigDecimal.valueOf(-dailyLossLimitPercent);
        if (dailyPnl.compareTo(dailyLossThreshold) < 0) {
            log.warn("Daily loss limit hit ({}%) — auto-trading paused for today", dailyLossLimitPercent);
            return false;
        }

        return true;
    }

    private void executeTrade(RsiSignal signal) {
        log.info("Executing trade for {} {} at price {}",
                signal.getSymbol(), signal.getSignalType(), signal.getCurrentPrice());

        IGAuthService.IGSession session = authService.getSession();
        if (session == null) {
            log.error("No IG session available — trade aborted");
            return;
        }

        try {
            String direction = switch (signal.getSignalType()) {
                case OVERSOLD, TREND_BUY_DIP -> "BUY";
                case OVERBOUGHT, TREND_SELL_RALLY -> "SELL";
                default -> null;
            };

            if (direction == null) {
                log.warn("No direction for signal type {} — trade aborted", signal.getSignalType());
                return;
            }

            DealRequest dealRequest = new DealRequest(
                    signal.getSymbol(),
                    direction,
                    "1",
                    "MARKET",
                    false
            );

            authService.getClient().post()
                    .uri("/positions/otc")
                    .header("X-IG-API-KEY", authService.getApiKey())
                    .header("CST", session.getCst())
                    .header("X-SECURITY-TOKEN", session.getSecurityToken())
                    .header("Version", "2")
                    .bodyValue(dealRequest)
                    .retrieve()
                    .bodyToMono(DealResponse.class)
                    .doOnSuccess(response -> {
                        openPositionCount++;
                        log.info("Trade placed: {} {} deal ref: {}",
                                direction, signal.getSymbol(),
                                response != null ? response.getDealReference() : "unknown");
                    })
                    .doOnError(e -> log.error("Trade placement failed for {}: {}", signal.getSymbol(), e.getMessage()))
                    .subscribe();

        } catch (Exception e) {
            log.error("Trade execution error for {}: {}", signal.getSymbol(), e.getMessage());
        }
    }

    public void activateKillSwitch() {
        killSwitchActive.set(true);
        log.warn("⚠️  KILL SWITCH ACTIVATED — all auto-trading stopped immediately");
    }

    public void deactivateKillSwitch() {
        killSwitchActive.set(false);
        log.info("Kill switch deactivated — auto-trading resumed");
    }

    public boolean isKillSwitchActive() {
        return killSwitchActive.get();
    }

    public boolean isAutoExecutionEnabled() {
        return autoExecutionEnabled;
    }

    private void resetDailyPnlIfNeeded() {
        Instant now = Instant.now();
        if (now.toEpochMilli() - dailyPnlResetTime.toEpochMilli() > 86_400_000L) {
            dailyPnl = BigDecimal.ZERO;
            dailyPnlResetTime = now;
            log.info("Daily P&L reset");
        }
    }

    @Data
    private static class DealRequest {
        private final String epic;
        private final String direction;
        private final String size;
        private final String orderType;
        private final boolean guaranteedStop;
    }

    @Data
    private static class DealResponse {
        private String dealReference;
        private String status;
    }
}
