package com.trading.rsi.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(name = "filter_event_counts",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_filter_day", columnNames = {"filter_name", "symbol", "day"})
    },
    indexes = {
        @Index(name = "idx_filter_day", columnList = "day")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FilterEventCount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "filter_name", nullable = false, length = 30)
    private String filterName;

    @Column(nullable = false, length = 30)
    private String symbol;

    @Column(nullable = false)
    private LocalDate day;

    @Column(nullable = false)
    private long count;
}
