package com.sjinc.trendanalysis.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 트렌드 항목(P-03 정형화 결과) — 원문 문서 한 건당 하나. 키워드·소재·컬러·실루엣은 TrendItemValue에 한 행씩 둔다.
 * 언급량은 여기 두지 않는다 — P-05 집계가 값별 문서 수로 센다(프로세스 정의서 10번 슬라이드).
 */
@Entity
@Table(name = "trend_items")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrendItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "trend_item_id")
    private Long id;

    @Column(name = "document_id", nullable = false, unique = true)
    private Long documentId;

    @Column(nullable = false, length = 30)
    private String category;

    @Column(nullable = false, length = 20)
    private String sentiment;

    @Column(nullable = false, length = 1000)
    private String summary;

    /** 문서 한 건의 정형 JSON 전체(출처 포함, 프로세스 정의서 10번 슬라이드 형식). */
    @Lob
    @Column(name = "result_json", nullable = false)
    private String resultJson;

    @Column(nullable = false)
    private String model;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder.Default
    @OneToMany(mappedBy = "trendItem", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("valueType asc, sortOrder asc")
    private List<TrendItemValue> values = new ArrayList<>();

    public void addValue(String valueType, String value, int sortOrder) {
        values.add(TrendItemValue.builder().trendItem(this).valueType(valueType).value(value).sortOrder(sortOrder).build());
    }

    public List<String> valuesOf(String valueType) {
        return values.stream().filter(v -> v.getValueType().equals(valueType)).map(TrendItemValue::getValue).toList();
    }
}
