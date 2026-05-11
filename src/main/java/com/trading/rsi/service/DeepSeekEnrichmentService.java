package com.trading.rsi.service;

import com.trading.rsi.domain.SignalLog;
import com.trading.rsi.model.RsiSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DeepSeekEnrichmentService {

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final boolean enabled;

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    public DeepSeekEnrichmentService(
            @Value("${deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${deepseek.api-key:}") String apiKey,
            @Value("${deepseek.model:deepseek-chat}") String model,
            @Value("${deepseek.enabled:false}") boolean enabled,
            WebClient.Builder builder) {
        this.webClient = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.model = model;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isEmpty();
    }

    /**
     * Enriches a TREND_BUY_DIP signal with one sentence of market context.
     * Returns empty if disabled, wrong signal type, timeout (&gt;2s), or any error.
     */
    public Optional<String> enrich(RsiSignal signal) {
        if (!isEnabled()) return Optional.empty();
        if (signal.getSignalType() != SignalLog.SignalType.TREND_BUY_DIP) return Optional.empty();

        try {
            String prompt = buildPrompt(signal);
            DeepSeekRequest request = new DeepSeekRequest(model, 60,
                    List.of(new ChatMessage("user", prompt)));

            DeepSeekResponse response = webClient.post()
                    .uri("/v1/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(DeepSeekResponse.class)
                    .timeout(TIMEOUT)
                    .block();

            if (response != null && response.getChoices() != null && !response.getChoices().isEmpty()) {
                DeepSeekResponse.Choice choice = response.getChoices().get(0);
                if (choice.getMessage() != null) {
                    String content = choice.getMessage().getContent();
                    if (content != null && !content.isBlank()) {
                        return Optional.of(content.trim());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("DeepSeek enrichment skipped for {} — {}", signal.getSymbol(), e.getMessage());
        }
        return Optional.empty();
    }

    private String buildPrompt(RsiSignal signal) {
        String rsiSummary = signal.getRsiValues() == null ? "n/a"
                : signal.getRsiValues().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(e -> e.getKey() + " " + e.getValue().setScale(1, RoundingMode.HALF_UP))
                        .collect(Collectors.joining(", "));
        return String.format(
                "%s TREND_BUY_DIP signal at %s. RSI: %s. " +
                "Reply in exactly one sentence: key market context for this dip-buy setup right now.",
                signal.getSymbol(), signal.getCurrentPrice(), rsiSummary);
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    private static class DeepSeekRequest {
        private String model;
        private int max_tokens;
        private List<ChatMessage> messages;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    private static class ChatMessage {
        private String role;
        private String content;
    }

    @lombok.Data
    private static class DeepSeekResponse {
        private List<Choice> choices;

        @lombok.Data
        static class Choice {
            private ChoiceMessage message;

            @lombok.Data
            static class ChoiceMessage {
                private String content;
            }
        }
    }
}
