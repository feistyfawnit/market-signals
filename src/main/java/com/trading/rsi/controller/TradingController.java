package com.trading.rsi.controller;

import com.trading.rsi.service.IGTradingService;
import com.trading.rsi.service.TelegramConfirmationService;
import com.trading.rsi.service.TelegramNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/trading")
@RequiredArgsConstructor
public class TradingController {

    private final IGTradingService tradingService;
    private final TelegramNotificationService telegramNotificationService;
    private final TelegramConfirmationService telegramConfirmationService;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "autoExecutionEnabled", tradingService.isAutoExecutionEnabled(),
                "killSwitchActive", tradingService.isKillSwitchActive()
        ));
    }

    @PostMapping("/kill-switch/activate")
    public ResponseEntity<Map<String, String>> activateKillSwitch() {
        tradingService.activateKillSwitch();
        return ResponseEntity.ok(Map.of("status", "KILL SWITCH ACTIVATED — all auto-trading stopped"));
    }

    @PostMapping("/kill-switch/deactivate")
    public ResponseEntity<Map<String, String>> deactivateKillSwitch() {
        tradingService.deactivateKillSwitch();
        return ResponseEntity.ok(Map.of("status", "Kill switch deactivated"));
    }

    @PostMapping("/test-telegram")
    public ResponseEntity<Map<String, String>> testTelegram() {
        if (!telegramNotificationService.isEnabled()) {
            return ResponseEntity.ok(Map.of("status", "Telegram not enabled"));
        }
        telegramNotificationService.send("🧪 Test notification", "Market signals Telegram is working correctly.");
        return ResponseEntity.ok(Map.of("status", "Test notification sent"));
    }

    @PostMapping("/test-confirm")
    public ResponseEntity<Map<String, String>> testConfirm(
            @RequestParam(defaultValue = "SOLUSDT") String symbol,
            @RequestParam(defaultValue = "BUY") String direction,
            @RequestParam(defaultValue = "94.50") BigDecimal price) {
        if (!telegramNotificationService.isEnabled()) {
            return ResponseEntity.ok(Map.of("status", "Telegram not enabled"));
        }
        telegramConfirmationService.sendTestKeyboard(symbol, direction, price);
        return ResponseEntity.ok(Map.of("status", "Confirmation keyboard sent — check Telegram, respond within 2 min"));
    }
}
