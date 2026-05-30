package com.trading.rsi.service;

import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class IGAuthService {

    private final WebClient igClient;
    private final String baseUrl;
    private final String apiKey;
    private final String username;
    private final String password;
    private final boolean enabled;

    private final AtomicReference<IGSession> session = new AtomicReference<>();
    private volatile Instant lastAuthFailure = null;
    private static final long AUTH_BACKOFF_SECONDS = 300; // 5 minutes between failed auth attempts

    public IGAuthService(
            @Value("${market.ig.base-url:https://demo-api.ig.com/gateway/deal}") String baseUrl,
            @Value("${market.ig.api-key:}") String apiKey,
            @Value("${market.ig.username:}") String username,
            @Value("${market.ig.password:}") String password,
            @Value("${market.ig.enabled:false}") boolean enabled,
            WebClient.Builder builder) {
        this.baseUrl = baseUrl;
        this.igClient = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.username = username;
        this.password = password;
        this.enabled = enabled;
    }

    @PostConstruct
    public void init() {
        if (isEnabled()) {
            log.info("IG API enabled — authenticating on startup ({})", isDemoUrl() ? "DEMO" : "LIVE");
            authenticate();
        } else {
            log.info("IG API disabled — set IG_ENABLED=true and credentials to activate indices");
        }
    }

    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isEmpty()
                && username != null && !username.isEmpty();
    }

    public WebClient getClient() {
        return igClient;
    }

    public String getApiKey() {
        return apiKey;
    }

    public IGSession getSession() {
        if (session.get() == null) {
            if (lastAuthFailure != null &&
                    Instant.now().isBefore(lastAuthFailure.plusSeconds(AUTH_BACKOFF_SECONDS))) {
                log.debug("IG auth on backoff — last failure {}s ago, retry in {}s",
                        java.time.Duration.between(lastAuthFailure, Instant.now()).toSeconds(),
                        AUTH_BACKOFF_SECONDS - java.time.Duration.between(lastAuthFailure, Instant.now()).toSeconds());
                return null;
            }
            authenticate();
        }
        return session.get();
    }

    public void invalidateSession() {
        session.set(null);
        log.warn("IG session invalidated — will re-authenticate on next request");
    }

    public void authenticate() {
        if (!isEnabled()) {
            log.debug("IG API disabled or not configured — skipping authentication");
            return;
        }
        try {
            IGAuthRequest request = new IGAuthRequest(username, password, false);
            var response = igClient.post()
                    .uri("/session")
                    .header("X-IG-API-KEY", apiKey)
                    .header("Version", "2")
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .bodyValue(request)
                    .retrieve()
                    .toEntity(Object.class)
                    .block();

            if (response != null && response.getStatusCode().is2xxSuccessful()) {
                String cst = response.getHeaders().getFirst("CST");
                String securityToken = response.getHeaders().getFirst("X-SECURITY-TOKEN");
                if (cst != null && securityToken != null) {
                    session.set(new IGSession(cst, securityToken));
                    log.info("IG API authenticated successfully (demo={})", isDemoUrl());
                } else {
                    log.error("IG auth response missing CST or X-SECURITY-TOKEN headers");
                }
            }
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException wcre) {
            lastAuthFailure = Instant.now();
            log.error("IG authentication failed: {} body=[{}] — retrying in {} min",
                    wcre.getMessage(), wcre.getResponseBodyAsString(), AUTH_BACKOFF_SECONDS / 60);
        } catch (Exception e) {
            lastAuthFailure = Instant.now();
            log.error("IG authentication failed: {} — retrying in {} min", e.getMessage(), AUTH_BACKOFF_SECONDS / 60);
        }
    }

    private boolean isDemoUrl() {
        return baseUrl.contains("demo");
    }

    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000)
    public void refreshSession() {
        if (isEnabled() && session.get() != null) {
            log.info("Refreshing IG API session");
            authenticate();
        }
    }

    @Data
    @AllArgsConstructor
    public static class IGSession {
        private final String cst;
        private final String securityToken;
    }

    @Data
    @AllArgsConstructor
    private static class IGAuthRequest {
        private String identifier;
        private String password;
        private boolean encryptedPassword;
    }
}
