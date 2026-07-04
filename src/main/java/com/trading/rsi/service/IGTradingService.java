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
import java.math.RoundingMode;
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

    // A protective stop must clear the live bid/offer spread or the position is stopped out the
    // instant it opens (a BUY fills at the offer but is valued at the bid). On the demo Solana
    // feed the spread is ~2.2pt while our computed stop was 2pt — guaranteed insta-stop. Floor the
    // stop/limit distance at spread × this multiplier so there is genuine room beyond the spread.
    @Value("${trading.auto-execution.spread-stop-multiplier:2.0}")
    private double spreadStopMultiplier;

    // Skip live trade when the bid/offer spread consumes more than this % of the stop distance.
    // Prevents low-edge entries (e.g. Sunday 22:00 UTC open or thin markets). 0 disables the guard.
    @Value("${trading.auto-execution.max-spread-pct-of-stop:25.0}")
    private double maxSpreadPctOfStop;

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

        // Per-instrument auto-execute: skip the confirmation keyboard for proven-profit
        // instruments (e.g. SOL/BTC/ETH) where missing the 120s window is the bigger risk.
        // S&P/DAX/Gold/Silver still require manual approval.
        boolean instrumentAutoExecute = instrumentRepository.findBySymbol(signal.getSymbol())
                .map(i -> Boolean.TRUE.equals(i.getAutoExecuteEnabled()))
                .orElse(false);

        // Guard: quiet hours — no trade prompts or executions during the sleep window, UNLESS
        // the instrument is auto-execute (no human needs to be awake to approve it). Manual-
        // approval instruments (S&P/DAX/Gold/Silver) still skip so their confirmation keyboard
        // never fires at 3am. NotificationService/PositionOutcomeService independently keep
        // suppressing Telegram pushes for ALL instruments during quiet hours — overnight
        // auto-executed trades are silent and show up in the next `/pnl-report`, same as the
        // existing "silent" (trend-buy-dip-notify:false) instrument pattern. Jul 4 2026.
        if (isQuietHours() && !instrumentAutoExecute) {
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

        // Determine if we should send the confirmation keyboard.
        // Skip the keyboard if the instrument has auto-execute enabled.
        boolean canConfirm = !instrumentAutoExecute
                && telegramConfirmationService != null
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

        // Send confirmation keyboard if manual approval is required (and instrument not auto-execute)
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
        } else if (autoExecutionEnabled && (instrumentAutoExecute || !requireManualApproval)) {
            log.info("Auto-executing {} {} (instrument auto-execute={})",
                    signal.getSymbol(), signal.getSignalType(), instrumentAutoExecute);
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
        double rr = risk[0] > 0 ? (double) risk[1] / risk[0] : 2.0;

        // The stop must clear BOTH IG's reported minimum distance AND the live bid/offer spread
        // (× multiplier) — otherwise the position is stopped out the instant it opens.
        BigDecimal spread = marketSpread(md);
        BigDecimal spreadFloor = spread.multiply(BigDecimal.valueOf(spreadStopMultiplier));
        BigDecimal minDist = minStopOrLimitDistance(md).max(spreadFloor);
        BigDecimal step = minStepDistance(md);
        BigDecimal stopDistance = roundUpToStep(BigDecimal.valueOf(risk[0]).max(minDist), step);

        // Spread-as-%-of-stop guard. If the spread consumes too much of the planned stop,
        // the edge is too low to open (common at Sunday open or in thin markets).
        if (maxSpreadPctOfStop > 0 && stopDistance.compareTo(BigDecimal.ZERO) > 0) {
            double spreadPctOfStop = spread.multiply(BigDecimal.valueOf(100))
                    .divide(stopDistance, 4, RoundingMode.HALF_UP)
                    .doubleValue();
            if (spreadPctOfStop > maxSpreadPctOfStop) {
                log.warn("Skipping IG deal for {} — spread {}pt is {}% of stop ({}%), max={}%",
                        signal.getSymbol(), spread.stripTrailingZeros().toPlainString(),
                        spreadPctOfStop, stopDistance.stripTrailingZeros().toPlainString(), maxSpreadPctOfStop);
                telegramNotificationService.send("⚠️ Trade skipped — spread too wide",
                        String.format("%s %s: spread %spt = %.1f%% of stop (max %.1f%%)",
                                direction, signal.getSymbol(), spread.stripTrailingZeros().toPlainString(),
                                spreadPctOfStop, maxSpreadPctOfStop));
                recordEntrySpread(signal, spread, signal.getCurrentPrice());
                return Mono.empty();
            }
        }

        // Keep the reward:risk ratio after any widening of the stop, and never let the limit
        // fall below the stop or IG's minimum distance.
        BigDecimal limitDistance = roundUpToStep(
                stopDistance.multiply(BigDecimal.valueOf(rr))
                        .max(BigDecimal.valueOf(risk[1]))
                        .max(minDist), step);

        DealRequest dealRequest = new DealRequest(epic, expiry, direction, size, "MARKET", currencyCode,
                true, false, stopDistance, limitDistance);
        log.info("Placing IG deal: epic={} expiry={} dir={} size={} ccy={} stopDist={} limitDist={} (spread={} minDist={})",
                epic, expiry, direction, size, currencyCode, stopDistance, limitDistance, spread, minDist);
        recordEntrySpread(signal, spread, signal.getCurrentPrice());

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

    /** Live bid/offer spread in points, or ZERO when the snapshot is unavailable. */
    private BigDecimal marketSpread(MarketDetailsResponse md) {
        if (md != null && md.getSnapshot() != null
                && md.getSnapshot().getBid() != null && md.getSnapshot().getOffer() != null) {
            BigDecimal spread = md.getSnapshot().getOffer().subtract(md.getSnapshot().getBid());
            return spread.compareTo(BigDecimal.ZERO) > 0 ? spread : BigDecimal.ZERO;
        }
        return BigDecimal.ZERO;
    }

    /** IG's minimum step distance for stops/limits (points), defaulting to 1 when unreported. */
    private BigDecimal minStepDistance(MarketDetailsResponse md) {
        if (md != null && md.getDealingRules() != null
                && md.getDealingRules().getMinStepDistance() != null
                && md.getDealingRules().getMinStepDistance().getValue() != null
                && md.getDealingRules().getMinStepDistance().getValue().compareTo(BigDecimal.ZERO) > 0) {
            return md.getDealingRules().getMinStepDistance().getValue();
        }
        return BigDecimal.ONE;
    }

    /** Rounds a distance UP to the nearest multiple of the market's step distance. */
    private BigDecimal roundUpToStep(BigDecimal value, BigDecimal step) {
        if (step == null || step.compareTo(BigDecimal.ZERO) <= 0) {
            return value;
        }
        BigDecimal steps = value.divide(step, 0, java.math.RoundingMode.CEILING);
        return steps.multiply(step);
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
            // GET /positions (v2) lists open positions — NOT /positions/otc, which only supports
            // POST/PUT/DELETE and returns 404 on GET. The response shape is identical
            // ({positions:[{position, market}]}) so OtcPositionsResponse still maps cleanly.
            OtcPositionsResponse response = authService.getClient().get()
                    .uri("/positions")
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

    /**
     * Records the bid/offer spread at entry on the open position outcome.
     * Called whether the deal proceeds or is skipped, so we can analyse how much of the
     * stop the spread consumed and whether weekend/thin-market entries underperform.
     */
    private void recordEntrySpread(RsiSignal signal, BigDecimal spread, BigDecimal entryPrice) {
        if (spread == null || spread.compareTo(BigDecimal.ZERO) <= 0) return;
        positionOutcomeRepository.findFirstBySymbolAndExitTimeIsNull(signal.getSymbol())
                .ifPresent(pos -> {
                    pos.setEntrySpreadPts(spread);
                    BigDecimal pct = entryPrice != null && entryPrice.compareTo(BigDecimal.ZERO) > 0
                            ? spread.multiply(BigDecimal.valueOf(100))
                                    .divide(entryPrice, 6, RoundingMode.HALF_UP)
                                    .setScale(4, RoundingMode.HALF_UP)
                            : null;
                    pos.setEntrySpreadPct(pct);
                    positionOutcomeRepository.save(pos);
                    log.debug("Recorded entry spread for {}: {}pt ({}%)", signal.getSymbol(), spread, pct);
                });
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
        private MarketSnapshot snapshot;
    }

    @Data
    private static class MarketSnapshot {
        private BigDecimal bid;
        private BigDecimal offer;
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
        private DealingRuleValue minStepDistance;
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
