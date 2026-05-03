package com.trading.rsi.service;

import com.trading.rsi.repository.FilterEventCountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Persists filter suppression counts so they survive log rotation.
 * Wraps the repository call in try/catch — a counter blip must never break a hot path.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FilterEventCounterService {

    private final FilterEventCountRepository filterEventCountRepository;

    public void record(String filterName, String symbol) {
        try {
            filterEventCountRepository.increment(filterName, symbol, LocalDate.now(ZoneOffset.UTC));
        } catch (Exception e) {
            log.warn("Failed to increment filter_event_counts for {}:{} — {}", filterName, symbol, e.getMessage());
        }
    }
}
