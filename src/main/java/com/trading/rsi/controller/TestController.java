package com.trading.rsi.controller;

import com.trading.rsi.domain.Instrument;
import com.trading.rsi.domain.SignalLog;
import com.trading.rsi.event.SignalEvent;
import com.trading.rsi.model.RsiSignal;
import com.trading.rsi.repository.InstrumentRepository;
import com.trading.rsi.service.IGAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class TestController {

    private final ApplicationEventPublisher eventPublisher;
    private final InstrumentRepository instrumentRepository;
    private final IGAuthService igAuthService;

    @PostMapping("/notify")
    public ResponseEntity<Map<String, String>> triggerTestNotification() {
        Map<String, BigDecimal> rsiValues = new LinkedHashMap<>();
        rsiValues.put("15m", BigDecimal.valueOf(24.3));
        rsiValues.put("1h", BigDecimal.valueOf(26.7));
        rsiValues.put("4h", BigDecimal.valueOf(22.1));

        RsiSignal signal = Objects.requireNonNull(RsiSignal.builder()
                .symbol("SOLUSDT")
                .instrumentName("Solana [TEST]")
                .signalType(SignalLog.SignalType.OVERSOLD)
                .currentPrice(BigDecimal.valueOf(142.50))
                .rsiValues(rsiValues)
                .timeframesAligned(3)
                .totalTimeframes(3)
                .signalStrength(BigDecimal.valueOf(7.2))
                .build());

        eventPublisher.publishEvent(new SignalEvent(this, signal));
        return ResponseEntity.ok(Map.of("status", "Test notification dispatched — check Telegram"));
    }

    @PostMapping("/lower-thresholds")
    public ResponseEntity<Map<String, Object>> lowerThresholds(
            @RequestParam(defaultValue = "50") int oversold,
            @RequestParam(defaultValue = "50") int overbought) {
        List<Instrument> instruments = instrumentRepository.findByEnabledTrue();
        instruments.forEach(i -> {
            i.setOversoldThreshold(oversold);
            i.setOverboughtThreshold(overbought);
        });
        instrumentRepository.saveAll(instruments);
        return ResponseEntity.ok(Map.of(
                "updated", instruments.size(),
                "oversold", oversold,
                "overbought", overbought,
                "warning", "REAL signal notifications will fire on next poll — they will NOT be labelled [TEST]. Use POST /api/test/notify for a labelled test instead.",
                "next", "POST /api/test/reset-thresholds to restore 30/70 after testing"
        ));
    }

    @PostMapping("/reset-thresholds")
    public ResponseEntity<Map<String, Object>> resetThresholds() {
        List<Instrument> instruments = instrumentRepository.findAll();
        instruments.forEach(i -> {
            i.setOversoldThreshold(30);
            i.setOverboughtThreshold(70);
        });
        instrumentRepository.saveAll(instruments);
        return ResponseEntity.ok(Map.of("status", "Thresholds reset to 30/70 for all instruments"));
    }

    @GetMapping("/ig/search")
    public ResponseEntity<?> searchIGEpic(@RequestParam String term) {
        if (!igAuthService.isEnabled()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "IG API not enabled",
                    "fix", "Set IG_ENABLED=true and IG credentials in .env"
            ));
        }
        IGAuthService.IGSession session = igAuthService.getSession();
        if (session == null) {
            return ResponseEntity.status(503).body(Map.of("error", "IG session unavailable — check credentials"));
        }
        try {
            Object result = igAuthService.getClient().get()
                    .uri("/markets?searchTerm={term}", term)
                    .header("X-IG-API-KEY", igAuthService.getApiKey())
                    .header("CST", session.getCst())
                    .header("X-SECURITY-TOKEN", session.getSecurityToken())
                    .header("Version", "1")
                    .retrieve()
                    .bodyToMono(Object.class)
                    .block();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
