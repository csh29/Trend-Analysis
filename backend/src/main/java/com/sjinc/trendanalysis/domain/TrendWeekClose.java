package com.sjinc.trendanalysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * P-05 주 마감(2026-10-30 사용자 요청 — ERP 마감처럼). 행이 있으면 그 주는 마감이다: 집계를 다시 계산하지 않고,
 * 그 주에 센 원문은 TrendWeekCloseDoc에 고정한다. 마감은 오래된 주부터 차례로, 취소는 가장 최근 마감 주부터 한다
 * (마감된 주는 늘 맨 앞에서부터 이어진다 — 열린 주는 마지막 마감 주 다음 주부터).
 */
@Entity
@Table(name = "trend_week_closes")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class TrendWeekClose {

    /** 자동 마감한 사람 이름. */
    public static final String SYSTEM = "SYSTEM";

    /** 마감한 주의 월요일. */
    @Id
    @Column(name = "week_start")
    private LocalDate weekStart;

    /** 그 주에 센 원문 수(마감 때 고정한 원문). */
    @Column(name = "doc_count", nullable = false)
    private int docCount;

    /** 그 주의 집계 행 수. */
    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(name = "closed_at", nullable = false)
    private LocalDateTime closedAt;

    /** 마감한 사용자 ID, 자동 마감이면 SYSTEM. */
    @Column(name = "closed_by", nullable = false, length = 100)
    private String closedBy;
}
