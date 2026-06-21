package com.trading.rsi.service;

import com.trading.rsi.domain.Instrument;
import com.trading.rsi.domain.SignalLog;
import com.trading.rsi.event.SignalEvent;
import com.trading.rsi.model.RsiSignal;
import com.trading.rsi.repository.InstrumentRepository;
import com.trading.rsi.repository.PositionOutcomeRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

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
    private final InstrumentRepository instrumentRepository;
    private final PositionOutcomeRepository positionOutcomeRepository;
    private final TelegramConfirmationService telegramConfirmationService;
    private final TelegramNotificationService telegramNotificationService;

    // Reuse the single risk-sizing model so live IG deals open with the SAME stop/limit distance
    // as the paper sim. @Lazy breaks the PositionOutcomeService↔IGTradingService construction cycle
    // (PositionOutcomeService already injects this bean). Field injection is intentional here.
    @Autowired
    @Lazy
    private PositionOutcomeService positionOutcomeService;

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

    @Value("${trading.confirmation.enabled:false}")
    private boolean confirmationOnlyEnabled;

    // IG's reported minDealSize is unreliable on the demo feed (e.g. Solana reports 4.0 but
    // the true minimum is 7). Multiply the reported minimum by this buffer so deals clear the
    // real floor. Scales naturally per market — verified ×2 accepts SOL/BTC/ETH on the demo.
    @Value("${trading.auto-execution.min-size-multiplier:2}")
    private double minSizeMultiplier;

    @Value("${rsi.demo.account-currency:EUR}")
    private String accountCurrency;

    @Value("${rsi.quiet-hours.enabled:true}")
    private boolean quietHoursEnabled;

    @Value("${rsi.quiet-hours.start-hour:22}")
    private int quietHoursStart;

    @Value("${rsi.quiet-hours.end-hour:8}")
    private int quietHoursEnd;

    private final AtomicBoolean killSwitchActive = new AtomicBoolean(false);
    private BigDecimal dailyPnl = BigDecimal.ZERO;
    private Instant dailyPnlResetTime = Instant.now();

    @EventListener
    @Async
    public void handleSignalEvent(SignalEvent event) {
        RsiSignal signal = event.getSignal();

        // Skip partial signals — monitoring only
        if (signal.getSignalType() == SignalLog.SignalType.PARTIAL_OVERSOLD
                || signal.getSignalType() == SignalLog.SignalType.PARTIAL_OVERBOUGHT) {
            log.info("Partial signal for {} — monitoring only, no trade action", signal.getSymbol());
            return;
        }

        // Silent signals are recorded for forward P&L only — no Telegram prompt, no trade.
        // Mirrors NotificationService, which also suppresses alerts when silent.
        if (signal.isSilent()) {
            log.info("Silent signal for {} {} — recording only, no confirmation prompt or trade",
                    signal.getSymbol(), signal.getSignalType());
            return;
        }

        // Guard: quiet hours — no trade prompts or executions during sleep window.
        // NotificationService and PositionOutcomeService already enforce this; this
        // keeps IGTradingService in sync so confirmation keyboards don't fire at 3am.
        if (isQuietHours()) {
            log.info("Quiet hours — skipping trade action for {} {}", signal.getSymbol(), signal.getSignalType());
            return;
        }

        String direction = switch (signal.getSignalType()) {
            case OVERSOLD, TREND_BUY_DIP -> "BUY";
            case OVERBOUGHT, TREND_SELL_RALLY -> "SELL";
            default -> null;
        };
        if (direction == null) {
            log.warn("No direction for signal type {} — trade aborted", signal.getSignalType());
            return;
        }

        // Determine if we should send the confirmation keyboard
        boolean canConfirm = telegramConfirmationService != null
                && (confirmationOnlyEnabled || (autoExecutionEnabled && requireManualApproval));

        // If auto-execution is off and confirmation-only is off, nothing to do
        if (!autoExecutionEnabled && !confirmationOnlyEnabled) {
            log.debug("Auto-execution and confirmation-only both disabled — signal logged only");
            return;
        }

        if (autoExecutionEnabled) {
            if (killSwitchActive.get()) {
                log.warn("KILL SWITCH ACTIVE — all auto-trading paused. Signal ignored: {}", signal.getSymbol());
                return;
            }
            if (!authService.isEnabled()) {
                log.warn("IG API not configured — cannot auto-trade");
                return;
            }
            if (!checkRiskLimits()) {
                return;
            }
        }

        // Send confirmation keyboard if manual approval is required or confirmation-only mode
        boolean userConfirmed = true;  // default: auto-confirm if no keyboard
        if (canConfirm) {
            log.info("Sending confirmation keyboard for {} {} @ {}",
                    signal.getSymbol(), direction, signal.getCurrentPrice());
            boolean confirmed = telegramConfirmationService.awaitConfirmation(
                    signal.getSymbol(), direction, signal.getCurrentPrice());
            if (!confirmed) {
                log.info("Trade skipped (user/timeout) for {} {}", signal.getSymbol(), signal.getSignalType());
                return;
            }
            userConfirmed = true;
        } else if (autoExecutionEnabled && !requireManualApproval) {
            log.info("Auto-executing {} {} without manual approval", signal.getSymbol(), signal.getSignalType());
        }

        // Execute real IG trade only if auto-execution is enabled and confirmed
        if (autoExecutionEnabled && userConfirmed) {
            executeTrade(signal, direction);
        } else if (confirmationOnlyEnabled && userConfirmed) {
            log.info("Confirmation-only: user confirmed {} {} — paper trade tracked by PositionOutcomeService",
                    signal.getSymbol(), direction);
        }
    }

    private boolean isQuietHours() {
        if (!quietHoursEnabled) return false;
        int h = ZonedDateTime.now(ZoneOffset.UTC).getHour();
        return quietHoursStart > quietHoursEnd
                ? h >= quietHoursStart || h < quietHoursEnd
                : h >= quietHoursStart && h < quietHoursEnd;
    }

    private boolean checkRiskLimits() {
        resetDailyPnlIfNeeded();

        // Count live IG-linked positions from the DB rather than a manual counter. A manual
        // counter that only ever incremented (never decremented on close) would permanently
        // wedge this gate at the cap after a couple of trades, silently suppressing every
        // future confirmation keyboard. Deriving from open positions is self-correcting.
        long openIgPositions = positionOutcomeRepository.findByExitTimeIsNull().stream()
                .filter(p -> p.getIgDealId() != null)
                .count();
        if (openIgPositions >= maxConcurrentPositions) {
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

    private void executeTrade(RsiSignal signal, String direction) {
        log.info("Executing trade for {} {} at price {}",
                signal.getSymbol(), signal.getSignalType(), signal.getCurrentPrice());

        IGAuthService.IGSession session = authService.getSession();
        if (session == null) {
            log.error("No IG session available — trade aborted");
            return;
        }

        try {
            String epic = instrumentRepository.findBySymbol(signal.getSymbol())
                    .map(Instrument::getIgEpic)
                    .filter(e -> e != null && !e.isBlank())
                    .orElse(signal.getSymbol());

            // Dealing rules (expiry, currency, min deal size) vary per market — fetch them so the
            // OTC payload is always valid. IG v2 /positions/otc requires expiry, currencyCode and
            // forceOpen, and rejects sizes below the market minimum (e.g. Solana min is 4.0).
            authService.getClient().get()
                    .uri("/markets/{epic}", epic)
                    .header("X-IG-API-KEY", authService.getApiKey())
                    .header("CST", session.getCst())
                    .header("X-SECURITY-TOKEN", session.getSecurityToken())
                    .header("Version", "3")
                    .retrieve()
                    .bodyToMono(MarketDetailsResponse.class)
                    .flatMap(md -> placeDeal(epic, direction, md, session, signal))
                    .doOnError(e -> {
                        log.error("Trade placement failed for {}: {}", signal.getSymbol(), e.getMessage());
                        telegramNotificationService.send("\u274c Trade placement failed",
                                String.format("%s %s — %s", direction, signal.getSymbol(), e.getMessage()));
                    })
                    .onErrorResume(e -> Mono.empty())
                    .subscribe();

        } catch (Exception e) {
            log.error("Trade execution error for {}: {}", signal.getSymbol(), e.getMessage());
        }
    }

    private Mono<Void> placeDeal(String epic, String direction, MarketDetailsResponse md,
                                 IGAuthService.IGSession session, RsiSignal signal) {
        String expiry = (md != null && md.getInstrument() != null && md.getInstrument().getExpiry() != null)
                ? md.getInstrument().getExpiry() : "-";
        String currencyCode = resolveCurrencyCode(md);
        String size = resolveDealSize(md);

        // Open the deal WITH a protective stop + limit (in points), using the same risk model as
        // the paper sim. Without this the IG position is naked and the trailing job has no stop to
        // ratchet until the trade is already in profit. Clamp up to IG's reported minimum distance
        // so the deal is not rejected for a too-tight stop on low-priced markets.
        boolean isTrend = signal.getSignalType() == SignalLog.SignalType.TREND_BUY_DIP
                || signal.getSignalType() == SignalLog.SignalType.TREND_SELL_RALLY;
        long[] risk = positionOutcomeService.computeRiskPoints(signal.getCurrentPrice(), signal.getSymbol(), isTrend);
        BigDecimal minDist = minStopOrLimitDistance(md);
        BigDecimal stopDistance = BigDecimal.valueOf(risk[0]).max(minDist);
        BigDecimal limitDistance = BigDecimal.valueOf(risk[1]).max(minDist);

        DealRequest dealRequest = new DealRequest(epic, expiry, direction, size, "MARKET", currencyCode,
                true, false, stopDistance, limitDistance);
        log.info("Placing IG deal: epic={} expiry={} dir={} size={} ccy={} stopDist={} limitDist={}",
                epic, expiry, direction, size, currencyCode, stopDistance, limitDistance);

        return authService.getClient().post()
                .uri("/positions/otc")
                .header("X-IG-API-KEY", authService.getApiKey())
                .header("CST", session.getCst())
                .header("X-SECURITY-TOKEN", session.getSecurityToken())
                .header("Version", "2")
                .bodyValue(dealRequest)
                .retrieve()
                .bodyToMono(DealResponse.class)
                .delayElement(Duration.ofSeconds(2))
                .flatMap(dealResp -> {
                    if (dealResp == null || dealResp.getDealReference() == null) {
                        log.warn("Null deal response for {} — skipping confirm", signal.getSymbol());
                        telegramNotificationService.send("\u26a0\ufe0f Trade not placed",
                                String.format("%s %s — IG returned no deal reference.", direction, signal.getSymbol()));
                        return Mono.empty();
                    }
                    log.info("Trade placed: {} {} deal ref: {}", direction, signal.getSymbol(), dealResp.getDealReference());
                    return confirmDeal(dealResp.getDealReference(), session, signal);
                });
    }

    /** Prefer the account currency when the market supports it, otherwise the market's first dealing currency. */
    private String resolveCurrencyCode(MarketDetailsResponse md) {
        if (md == null || md.getInstrument() == null || md.getInstrument().getCurrencies() == null
                || md.getInstrument().getCurrencies().isEmpty()) {
            return accountCurrency;
        }
        List<String> codes = md.getInstrument().getCurrencies().stream()
                .map(MarketCurrency::getCode)
                .filter(c -> c != null && !c.isBlank())
                .toList();
        if (codes.isEmpty()) {
            return accountCurrency;
        }
        return codes.contains(accountCurrency) ? accountCurrency : codes.get(0);
    }

    /**
     * Smallest valid order = reported market minimum × buffer. IG's reported minDealSize
     * understates the true demo minimum for some markets (Solana reports 4.0 but rejects
     * anything below 7), so the buffer absorbs that gap. Scales per market, keeping notional
     * proportional (BTC 0.01→0.02, ETH 0.2→0.4, SOL 4→8).
     */
    private String resolveDealSize(MarketDetailsResponse md) {
        BigDecimal min = (md != null && md.getDealingRules() != null
                && md.getDealingRules().getMinDealSize() != null
                && md.getDealingRules().getMinDealSize().getValue() != null)
                ? md.getDealingRules().getMinDealSize().getValue()
                : BigDecimal.ONE;
        BigDecimal size = min.multiply(BigDecimal.valueOf(minSizeMultiplier));
        return size.stripTrailingZeros().toPlainString();
    }

    /**
     * IG's minimum normal stop/limit distance (in points) for the market, or ZERO when the
     * dealing rules don't report one. Used to clamp our computed stop/limit up so the deal is
     * not rejected for being too tight on low-priced instruments.
     */
    private BigDecimal minStopOrLimitDistance(MarketDetailsResponse md) {
        if (md != null && md.getDealingRules() != null
                && md.getDealingRules().getMinNormalStopOrLimitDistance() != null
                && md.getDealingRules().getMinNormalStopOrLimitDistance().getValue() != null) {
            return md.getDealingRules().getMinNormalStopOrLimitDistance().getValue();
        }
        return BigDecimal.ZERO;
    }

    private Mono<Void> confirmDeal(String dealRef, IGAuthService.IGSession session, RsiSignal signal) {
        return authService.getClient().get()
                .uri("/confirms/{dealReference}", dealRef)
                .header("X-IG-API-KEY", authService.getApiKey())
                .header("CST", session.getCst())
                .header("X-SECURITY-TOKEN", session.getSecurityToken())
                .header("Version", "1")
                .retrieve()
                .bodyToMono(DealConfirmResponse.class)
                .doOnSuccess(confirm -> handleConfirm(confirm, dealRef, signal))
                .doOnError(e -> log.error("Confirm fetch failed for deal ref {}: {}", dealRef, e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    private void handleConfirm(DealConfirmResponse confirm, String dealRef, RsiSignal signal) {
        if (confirm == null) {
            log.error("Null confirm response for deal ref {}", dealRef);
            return;
        }
        if ("ACCEPTED".equals(confirm.getDealStatus())) {
            telegramNotificationService.send("\u2705 Trade placed",
                    String.format("%s %s ACCEPTED%s (dealId %s)", signal.getSignalType(), signal.getSymbol(),
                            confirm.getLevel() != null ? " @ " + confirm.getLevel().stripTrailingZeros().toPlainString() : "",
                            confirm.getDealId()));
            positionOutcomeRepository.findFirstBySymbolAndExitTimeIsNull(signal.getSymbol())
                    .ifPresentOrElse(pos -> {
                        pos.setIgDealRef(dealRef);
                        pos.setIgDealId(confirm.getDealId());
                        positionOutcomeRepository.save(pos);
                        log.info("Position {} ({}) confirmed — igDealId={}", pos.getId(), pos.getSymbol(), confirm.getDealId());
                    }, () -> log.warn("No open DB position found for {} to attach igDealId={}", signal.getSymbol(), confirm.getDealId()));
        } else {
            log.warn("Trade REJECTED for {} — reason={}", signal.getSymbol(), confirm.getReason());
            telegramNotificationService.send("\u274c Trade rejected",
                    String.format("%s %s — %s", signal.getSignalType(), signal.getSymbol(), confirm.getReason()));
            positionOutcomeRepository.findFirstBySymbolAndExitTimeIsNull(signal.getSymbol())
                    .ifPresent(pos -> {
                        pos.setExitPrice(pos.getEntryPrice());
                        pos.setExitTime(Instant.now());
                        pos.setTpHit(false);
                        pos.setSlHit(false);
                        pos.setPnlPct(BigDecimal.ZERO);
                        pos.setHoldingHours(0.0);
                        positionOutcomeRepository.save(pos);
                        log.info("DB position {} ({}) closed — IG rejected: {}", pos.getId(), pos.getSymbol(), confirm.getReason());
                    });
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

    /**
     * Updates the stop level for an open IG position via PUT /positions/otc/{dealId}.
     * Returns true if the API call succeeded.
     */
    public boolean updateStopLevel(String dealId, BigDecimal newStopLevel) {
        if (!authService.isEnabled()) return false;
        IGAuthService.IGSession session = authService.getSession();
        if (session == null) {
            log.error("No IG session — cannot update stop level for deal {}", dealId);
            return false;
        }
        try {
            UpdateStopRequest req = new UpdateStopRequest(newStopLevel, Boolean.FALSE, null, null, null);
            authService.getClient().put()
                    .uri("/positions/otc/{dealId}", dealId)
                    .header("X-IG-API-KEY", authService.getApiKey())
                    .header("CST", session.getCst())
                    .header("X-SECURITY-TOKEN", session.getSecurityToken())
                    .header("Version", "2")
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(DealResponse.class)
                    .block(Duration.ofSeconds(5));
            log.info("Stop level updated for deal {}: → {}", dealId, newStopLevel);
            return true;
        } catch (Exception e) {
            log.error("Failed to update stop level for deal {}: {}", dealId, e.getMessage());
            return false;
        }
    }

    /**
     * Returns the set of deal IDs currently open on IG.
     * Returns Optional.empty() when IG is unavailable or the call fails —
     * callers must treat empty as "do not reconcile", not "no open positions".
     */
    public Optional<Set<String>> fetchOpenIgDealIds() {
        if (!authService.isEnabled()) return Optional.empty();
        IGAuthService.IGSession session = authService.getSession();
        if (session == null) return Optional.empty();
        try {
            OtcPositionsResponse response = authService.getClient().get()
                    .uri("/positions/otc")
                    .header("X-IG-API-KEY", authService.getApiKey())
                    .header("CST", session.getCst())
                    .header("X-SECURITY-TOKEN", session.getSecurityToken())
                    .header("Version", "2")
                    .retrieve()
                    .bodyToMono(OtcPositionsResponse.class)
                    .block(Duration.ofSeconds(5));
            if (response == null || response.getPositions() == null) return Optional.of(Set.of());
            Set<String> dealIds = response.getPositions().stream()
                    .filter(p -> p.getPosition() != null && p.getPosition().getDealId() != null)
                    .map(p -> p.getPosition().getDealId())
                    .collect(Collectors.toSet());
            return Optional.of(dealIds);
        } catch (Exception e) {
            log.error("Failed to fetch open IG positions: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** True if IG credentials are configured (independent of whether a live session exists). */
    public boolean isIgAvailable() {
        return authService.isEnabled();
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class DealRequest {
        private final String epic;
        private final String expiry;
        private final String direction;
        private final String size;
        private final String orderType;
        private final String currencyCode;
        private final boolean forceOpen;
        private final boolean guaranteedStop;
        // Protective stop/limit distance in points (omitted from JSON when null).
        private final BigDecimal stopDistance;
        private final BigDecimal limitDistance;
    }

    @Data
    private static class MarketDetailsResponse {
        private MarketInstrument instrument;
        private MarketDealingRules dealingRules;
    }

    @Data
    private static class MarketInstrument {
        private String expiry;
        private List<MarketCurrency> currencies;
    }

    @Data
    private static class MarketCurrency {
        private String code;
    }

    @Data
    private static class MarketDealingRules {
        private MinDealSize minDealSize;
        private DealingRuleValue minNormalStopOrLimitDistance;
    }

    @Data
    private static class MinDealSize {
        private BigDecimal value;
    }

    @Data
    private static class DealingRuleValue {
        private String unit;
        private BigDecimal value;
    }

    @Data
    private static class DealResponse {
        private String dealReference;
        private String status;
    }

    @Data
    private static class DealConfirmResponse {
        private String dealId;
        private String dealReference;
        private String dealStatus;
        private String reason;
        private BigDecimal level;
        private BigDecimal stopLevel;
        private BigDecimal limitLevel;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class UpdateStopRequest {
        private BigDecimal stopLevel;
        private Boolean trailingStop;
        private BigDecimal limitLevel;
        private BigDecimal trailingStopDistance;
        private BigDecimal trailingStopIncrement;
    }

    @Data
    private static class OtcPositionsResponse {
        private List<OtcPositionWrapper> positions;
    }

    @Data
    private static class OtcPositionWrapper {
        private OtcPosition position;
        private OtcMarket market;
    }

    @Data
    private static class OtcPosition {
        private String dealId;
        private String dealReference;
        private String direction;
        private BigDecimal size;
        private BigDecimal level;
        private BigDecimal stopLevel;
        private BigDecimal limitLevel;
    }

    @Data
    private static class OtcMarket {
        private String epic;
        private String instrumentName;
    }
}
