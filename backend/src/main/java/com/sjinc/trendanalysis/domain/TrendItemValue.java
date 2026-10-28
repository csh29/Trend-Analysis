package com.sjinc.trendanalysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 트렌드 항목의 다중 값 한 개. 한 행씩 둬야 P-05가 값별 문서 수를 바로 센다.
 * 키워드는 문서 단위(garmentNo·category 없음), 아이템명(ITEM)·소재·컬러·실루엣은 의류 아이템 단위다 —
 * 같은 garmentNo끼리 한 아이템이고 category는 그 아이템의 카테고리다(코디 사진의 상의·하의 속성이 섞이지 않게).
 */
@Entity
@Table(name = "trend_item_values", indexes = @Index(columnList = "value_type, item_value"))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrendItemValue {

    public static final String KEYWORD = "KEYWORD";
    /** 시즌(공통코드 TREND_SEASON). 키워드처럼 문서 단위다. */
    public static final String SEASON = "SEASON";
    /** 의류 아이템 이름(예: "니트 카디건"). LLM이 쓴 문자열이다. */
    public static final String ITEM = "ITEM";
    /** 아이템 종류(공통코드 TREND_ITEM, 예: OUTER_PADDING). 유행 집계는 이것으로 센다 — 아이템명은 표기가 흔들린다. */
    public static final String ITEM_TYPE = "ITEM_TYPE";
    public static final String MATERIAL = "MATERIAL";
    public static final String COLOR = "COLOR";
    public static final String SILHOUETTE = "SILHOUETTE";
    /** 옷의 대상 라인(공통코드 TREND_GENDER — 여성복·남성복·공용). 의류 아이템 단위, 판단할 수 없으면 없다(2026-10-26 사용자 요청). */
    public static final String GENDER = "GENDER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trend_item_id", nullable = false)
    private TrendItem trendItem;

    @Column(name = "value_type", nullable = false, length = 20)
    private String valueType;

    /** 소재·컬러·실루엣은 공통코드 값, 키워드는 LLM이 뽑은 문자열. 컬럼명을 value로 두면 H2 예약어라 SQL이 깨진다. */
    @Column(name = "item_value", nullable = false, length = 200)
    private String value;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** 몇 번째 의류 아이템의 값인지(0부터). 키워드면 null. 아이템 배열 도입 전 결과도 null이다. */
    @Column(name = "garment_no")
    private Integer garmentNo;

    /** 그 아이템의 카테고리 코드. 키워드면 null. */
    @Column(length = 30)
    private String category;
}
