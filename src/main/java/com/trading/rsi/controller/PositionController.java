package com.trading.rsi.controller;

import com.trading.rsi.service.PositionOutcomeService;
import com.trading.rsi.service.PositionReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/positions")
@RequiredArgsConstructor
public class PositionController {

    private final PositionOutcomeService positionOutcomeService;
    private final PositionReportService positionReportService;

    @GetMapping("/pnl-summary")
    public Map<String, Object> getPnlSummary() {
        return positionOutcomeService.getPnlSummary();
    }

    @GetMapping(value = "/pnl-report", produces = MediaType.TEXT_PLAIN_VALUE)
    public String getPnlReport() {
        return positionReportService.generateReport();
    }

    @GetMapping(value = "/pnl-report/csv", produces = "text/csv")
    public String getPnlCsv() {
        return positionReportService.generateCsv();
    }

    @PostMapping("/recalculate")
    public Map<String, Object> recalculateClosedPositions() {
        int updated = positionOutcomeService.recalculateClosedPositions();
        return Map.of("status", "ok", "recalculated", updated);
    }
}
