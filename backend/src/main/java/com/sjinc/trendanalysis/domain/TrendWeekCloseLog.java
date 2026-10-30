package com.sjinc.trendanalysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 주 마감·마감 취소 이력(누가·언제·왜). 지우지 않는다. */
@Entity
@Table(name = "trend_week_close_logs")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class TrendWeekCloseLog {

    public static final String CLOSE = "CLOSE";
    public static final String CANCEL = "CANCEL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    /** CLOSE·CANCEL. */
    @Column(nullable = false, length = 10)
    private String action;

    /** 사용자 ID, 자동 마감이면 SYSTEM. */
    @Column(nullable = false, length = 100)
    private String actor;

    /** 마감 취소 사유(마감은 비어 있다). */
    @Column(length = 500)
    private String reason;

    @Column(name = "acted_at", nullable = false)
    private LocalDateTime actedAt;
}
