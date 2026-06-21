package com.trading.rsi.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends Telegram inline keyboard confirmation messages before live trades are executed.
 * Polls getUpdates every 5s — but ONLY when a confirmation is pending, so idle overhead is zero.
 *
 * Flow:
 *  1. awaitConfirmation() sends the keyboard and blocks the calling (async) thread ≤120s
 *  2. pollCallbacks() detects the button press and completes the CompletableFuture
 *  3. If no response within 2 min, auto-skips the trade
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TelegramConfirmationService {

    private final WebClient.Builder webClientBuilder;
    private final TelegramNotificationService telegramNotificationService;

    @Value("${notifications.telegram.bot-token:}")
    private String botToken;

    @Value("${notifications.telegram.chat-ids:}")
    private String chatIds;

    // How long to wait for a CONFIRM/SKIP button press before auto-skipping. Raised 120s→300s
    // (Jun 20 2026): 2 min was too tight for a mobile push → unlock → decide flow, causing
    // missed entries (a SOL dip that the sim caught +€32 was entered late manually for a ~€50
    // loss). 5 min keeps the entry close enough to the signal price while giving real reaction
    // time. Override with TRADING_CONFIRMATION_TIMEOUT_SECONDS.
    @Value("${trading.confirmation.timeout-seconds:300}")
    private long confirmationTimeoutSeconds;

    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private volatile long lastUpdateId = -1;

    /**
     * Polls Telegram getUpdates every 5 seconds.
     * No-op when idle (no pending confirmations or bot not configured).
     */
    @Scheduled(fixedDelay = 5000)
    public void pollCallbacks() {
        if (pending.isEmpty() || botToken.isBlank()) return;
        try {
            String url = "/bot" + botToken + "/getUpdates?timeout=0&offset=" + (lastUpdateId + 1);
            GetUpdatesResponse response = webClientBuilder.baseUrl("https://api.telegram.org").build()
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(GetUpdatesResponse.class)
                    .block();
            if (response == null || !response.isOk() || response.getResult() == null) return;
            for (Update update : response.getResult()) {
                lastUpdateId = Math.max(lastUpdateId, update.getUpdateId());
                CallbackQuery cq = update.getCallbackQuery();
                if (cq == null || cq.getData() == null) continue;
                String data = cq.getData();
                boolean confirmed = data.endsWith(":confirm");
                boolean skip = data.endsWith(":skip");
                if (!confirmed && !skip) continue;
                String key = data.substring(0, data.lastIndexOf(':'));
                Pending p = pending.get(key);
                if (p != null && !p.future.isDone()) {
                    p.future.complete(confirmed);
                    answerCallback(cq.getId(), confirmed ? "\u2705 Trade confirmed" : "\u23ed Trade skipped");
                    finalizeMessage(cq, p, confirmed);
                    log.info("Trade confirmation received: key={} confirmed={}", key, confirmed);
                }
            }
        } catch (Exception e) {
            log.error("Telegram callback poll failed: {}", e.getMessage());
        }
    }

    /**
     * Sends an inline keyboard to Telegram and blocks the calling thread for up to 120s
     * waiting for a YES/SKIP response. Returns true to execute, false to skip.
     * If Telegram is not enabled, returns true immediately (auto-execute).
     */
    public boolean awaitConfirmation(String symbol, String direction, BigDecimal price) {
        if (!telegramNotificationService.isEnabled()) {
            log.debug("Telegram not enabled — auto-confirming trade {} {}", direction, symbol);
            return true;
        }
        String key = UUID.randomUUID().toString();
        Pending p = new Pending(new CompletableFuture<>());
        pending.put(key, p);
        try {
            sendInlineKeyboard(symbol, direction, price, key, p);
            log.info("Awaiting Telegram confirmation for {} {} @ {} (key={}, timeout={}s)",
                    direction, symbol, price, key, confirmationTimeoutSeconds);
            return p.future.get(confirmationTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.info("Confirmation timed out for {} {} — auto-skipping", direction, symbol);
            return false;
        } catch (Exception e) {
            log.error("Confirmation await error for {} {}: {}", direction, symbol, e.getMessage());
            return false;
        } finally {
            pending.remove(key);
        }
    }

    /**
     * Sends the inline keyboard without blocking — for the /test-confirm endpoint.
     * Registers a real pending entry so pressing the button still shows a response in Telegram.
     * Auto-expires after 2 minutes.
     */
    public void sendTestKeyboard(String symbol, String direction, BigDecimal price) {
        if (!telegramNotificationService.isEnabled()) {
            log.warn("Telegram not enabled — test keyboard not sent");
            return;
        }
        String key = "TEST_" + UUID.randomUUID();
        Pending p = new Pending(new CompletableFuture<>());
        pending.put(key, p);
        sendInlineKeyboard(symbol, direction, price, key, p);
        CompletableFuture.delayedExecutor(confirmationTimeoutSeconds, TimeUnit.SECONDS)
                .execute(() -> { pending.remove(key); p.future.completeExceptionally(new TimeoutException("test expired")); });
        log.info("Test confirmation keyboard sent for {} {} @ {}", direction, symbol, price);
    }

    private void sendInlineKeyboard(String symbol, String direction, BigDecimal price, String key, Pending p) {
        if (botToken.isBlank()) return;
        String chatId = Arrays.stream(chatIds.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).findFirst().orElse("");
        if (chatId.isBlank()) return;

        String emoji = "BUY".equals(direction) ? "\uD83D\uDFE2" : "\uD83D\uDD34";
        String text = String.format("%s %s %s @ %s\n\nConfirm trade? Auto-skips in 2 min.",
                emoji, direction, symbol, price.stripTrailingZeros().toPlainString());
        p.prompt = text;

        Map<String, Object> payload = Map.of(
                "chat_id", chatId,
                "text", text,
                "reply_markup", Map.of("inline_keyboard", List.of(List.of(
                        Map.of("text", "\u2705 CONFIRM", "callback_data", key + ":confirm"),
                        Map.of("text", "\u23ed SKIP",    "callback_data", key + ":skip")
                )))
        );
        webClientBuilder.baseUrl("https://api.telegram.org").build()
                .post()
                .uri("/bot" + botToken + "/sendMessage")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .doOnError(e -> log.error("Failed to send confirmation keyboard: {}", e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    /**
     * Rewrites the prompt message to show the decision and strips the inline keyboard,
     * so the user gets a persistent confirmation and can't press the button twice.
     */
    private void finalizeMessage(CallbackQuery cq, Pending p, boolean confirmed) {
        if (botToken.isBlank() || cq.getMessage() == null || cq.getMessage().getChat() == null) return;
        String decision = confirmed ? "\n\n\u2705 CONFIRMED \u2014 placing trade\u2026" : "\n\n\u23ed SKIPPED";
        String newText = (p.prompt != null ? p.prompt : "Trade") + decision;
        Map<String, Object> payload = Map.of(
                "chat_id", cq.getMessage().getChat().getId(),
                "message_id", cq.getMessage().getMessageId(),
                "text", newText
        );
        webClientBuilder.baseUrl("https://api.telegram.org").build()
                .post()
                .uri("/bot" + botToken + "/editMessageText")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .doOnError(e -> log.warn("editMessageText failed: {}", e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    private void answerCallback(String callbackQueryId, String text) {
        if (botToken.isBlank()) return;
        webClientBuilder.baseUrl("https://api.telegram.org").build()
                .post()
                .uri("/bot" + botToken + "/answerCallbackQuery")
                .bodyValue(Map.of("callback_query_id", callbackQueryId, "text", text))
                .retrieve()
                .bodyToMono(String.class)
                .doOnError(e -> log.warn("answerCallbackQuery failed: {}", e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    @Data
    private static class GetUpdatesResponse {
        private boolean ok;
        private List<Update> result;
    }

    @Data
    private static class Update {
        @JsonProperty("update_id")
        private long updateId;
        @JsonProperty("callback_query")
        private CallbackQuery callbackQuery;
    }

    @Data
    private static class CallbackQuery {
        private String id;
        private String data;
        private Message message;
    }

    @Data
    private static class Message {
        @JsonProperty("message_id")
        private long messageId;
        private Chat chat;
    }

    @Data
    private static class Chat {
        private long id;
    }

    private static class Pending {
        private final CompletableFuture<Boolean> future;
        private volatile String prompt;
        private Pending(CompletableFuture<Boolean> future) {
            this.future = future;
        }
    }
}
