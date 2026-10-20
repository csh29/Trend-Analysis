package com.sjinc.trendanalysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * P-05 트렌드 집계 결과(프로세스 정의서 9번 슬라이드 "키워드 집계") — 기준 주 × 카테고리 × 값 한 행.
 * 집계 배치(TrendAggregationService)가 통째로 다시 만든다. 화면·급상승 판정은 이 표만 읽는다.
 */
@Entity
@Table(name = "trend_weekly_stats",
        uniqueConstraints = @UniqueConstraint(columnNames = {"week_start", "category", "value_type", "item_value"}),
        indexes = @Index(columnList = "category, value_type, week_start"))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrendWeeklyStat {

    /** 카테고리 전체 합계 행의 category 값. */
    public static final String ALL = "ALL";
    /** 카테고리 자체의 언급량(value_type). 다른 종류는 TrendItemValue의 상수를 쓴다. */
    public static final String CATEGORY = "CATEGORY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 기준 주의 월요일. */
    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    /** 카테고리 코드, 전체면 ALL. */
    @Column(nullable = false, length = 30)
    private String category;

    /** KEYWORD·MATERIAL·COLOR·SILHOUETTE·CATEGORY. */
    @Column(name = "value_type", nullable = false, length = 20)
    private String valueType;

    @Column(name = "item_value", nullable = false, length = 200)
    private String value;

    /** 언급량 — 그 주에 이 값이 나온 문서 수(한 문서에서 여러 번 나와도 1). */
    @Column(name = "mention_count", nullable = false)
    private int mentionCount;

    @Column(name = "prev_week_count", nullable = false)
    private int prevWeekCount;

    /** 전주 대비 증감률(%). 전주가 0이면 계산할 수 없어 null(신규). */
    @Column(name = "prev_week_rate")
    private Double prevWeekRate;

    /** 직전 4주 평균 언급량. */
    @Column(name = "month_avg", nullable = false)
    private double monthAvg;

    /** 전월(직전 4주 평균) 대비 증감률(%). 평균이 0이면 null. */
    @Column(name = "month_rate")
    private Double monthRate;

    @Column(name = "surge_yn", nullable = false, length = 1)
    private String surgeYn;

    /** 전주 0건에서 새로 나온 값. */
    @Column(name = "new_yn", nullable = false, length = 1)
    private String newYn;

    @Column(name = "aggregated_at", nullable = false)
    private LocalDateTime aggregatedAt;
}
