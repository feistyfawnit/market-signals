package com.trading.rsi.controller;

import com.trading.rsi.repository.InstrumentRepository;
import com.trading.rsi.service.IGAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApplicationEventPublisher eventPublisher;

    @MockitoBean
    private InstrumentRepository instrumentRepository;

    @MockitoBean
    private IGAuthService igAuthService;

    @BeforeEach
    void setUp() {
        when(instrumentRepository.findAll()).thenReturn(Collections.emptyList());
        when(instrumentRepository.findByEnabledTrue()).thenReturn(Collections.emptyList());
    }

    @Test
    void notify_dispatchesSignalEvent() throws Exception {
        mockMvc.perform(post("/api/test/notify"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("Test notification dispatched — check Telegram"));
    }

    @Test
    void lowerThresholds_returnsUpdatedCount() throws Exception {
        mockMvc.perform(post("/api/test/lower-thresholds")
                .param("oversold", "25")
                .param("overbought", "75"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.updated").value(0));
    }

    @Test
    void resetThresholds_returnsStatus() throws Exception {
        mockMvc.perform(post("/api/test/reset-thresholds"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").exists());
    }
}
