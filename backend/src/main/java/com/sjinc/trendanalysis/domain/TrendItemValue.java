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

/** 트렌드 항목의 다중 값 한 개(키워드·소재·컬러·실루엣). 한 행씩 둬야 P-05가 값별 문서 수를 바로 센다. */
@Entity
@Table(name = "trend_item_values", indexes = @Index(columnList = "value_type, item_value"))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrendItemValue {

    public static final String KEYWORD = "KEYWORD";
    public static final String MATERIAL = "MATERIAL";
    public static final String COLOR = "COLOR";
    public static final String SILHOUETTE = "SILHOUETTE";

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
}
