package com.trading.rsi.controller;

import com.trading.rsi.service.AlertCsvService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final AlertCsvService alertCsvService;

    @PostMapping("/backfill-csv")
    public ResponseEntity<Map<String, Object>> triggerBackfillCsv() {
        log.info("Admin: manual CSV outcome backfill triggered");
        alertCsvService.backfillOutcomePrices();
        return ResponseEntity.ok(Map.of("status", "backfill complete"));
    }
}
