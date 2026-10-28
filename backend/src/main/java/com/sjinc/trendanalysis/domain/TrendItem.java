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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 트렌드 항목(P-03 정형화 결과) — 원문 문서 한 건당 하나. 키워드와 의류 아이템(이름·소재·컬러·실루엣)은 TrendItemValue에 한 행씩 둔다.
 * category는 대표(첫 번째) 아이템의 카테고리다 — 목록 표시용. 아이템별 카테고리는 garments()로 본다.
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

    @Column(nullable = false, length = 1000)
    private String summary;

    /** 문서 한 건의 정형 JSON 전체(출처 포함, 프로세스 정의서 10번 슬라이드 형식). */
    @Lob
    @Column(name = "result_json", nullable = false)
    private String resultJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder.Default
    @OneToMany(mappedBy = "trendItem", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("valueType asc, sortOrder asc")
    private List<TrendItemValue> values = new ArrayList<>();

    /** 문서 단위 값(키워드). */
    public void addValue(String valueType, String value, int sortOrder) {
        addGarmentValue(valueType, value, sortOrder, null, null);
    }

    /** 의류 아이템 단위 값(아이템명·소재·컬러·실루엣). */
    public void addGarmentValue(String valueType, String value, int sortOrder, Integer garmentNo, String garmentCategory) {
        values.add(TrendItemValue.builder().trendItem(this).valueType(valueType).value(value).sortOrder(sortOrder)
                .garmentNo(garmentNo).category(garmentCategory).build());
    }

    public List<String> valuesOf(String valueType) {
        return values.stream().filter(v -> v.getValueType().equals(valueType)).map(TrendItemValue::getValue).toList();
    }

    /**
     * 의류 아이템 하나 — 같은 garmentNo의 값을 모은 것. type은 아이템 종류 코드(종류 도입 전 결과는 null).
     * genders는 대상 라인(0~1개, 성별 도입 전 결과와 판단할 수 없는 옷은 비어 있다).
     */
    public record Garment(int no, String category, String name, String type, List<String> materials, List<String> colors,
                          List<String> silhouettes, List<String> genders) {
    }

    /**
     * 의류 아이템 목록(순서대로). 아이템 배열 도입 전 결과(garmentNo 없음)는 문서 카테고리로 아이템 하나를 만든다 —
     * 재분석 전 데이터도 화면·집계가 깨지지 않게.
     */
    public List<Garment> garments() {
        Map<Integer, List<TrendItemValue>> byNo = new TreeMap<>();
        List<TrendItemValue> legacy = new ArrayList<>();
        for (TrendItemValue v : values) {
            if (TrendItemValue.KEYWORD.equals(v.getValueType()) || TrendItemValue.SEASON.equals(v.getValueType())) {
                continue; // 문서 단위 값
            }
            if (v.getGarmentNo() == null) {
                legacy.add(v);
            } else {
                byNo.computeIfAbsent(v.getGarmentNo(), n -> new ArrayList<>()).add(v);
            }
        }
        List<Garment> result = new ArrayList<>();
        byNo.forEach((no, list) -> result.add(garment(no, list, null)));
        if (byNo.isEmpty() && category != null) {
            result.add(garment(0, legacy, category));
        }
        return result;
    }

    /** 아이템 카테고리(중복 없이, 나온 순서). */
    public List<String> garmentCategories() {
        return garments().stream().map(Garment::category).filter(c -> c != null && !c.isBlank()).distinct().toList();
    }

    private static Garment garment(int no, List<TrendItemValue> list, String fallbackCategory) {
        List<TrendItemValue> sorted = list.stream().sorted(Comparator.comparingInt(TrendItemValue::getSortOrder)).toList();
        String garmentCategory = sorted.stream().map(TrendItemValue::getCategory).filter(c -> c != null).findFirst()
                .orElse(fallbackCategory);
        String name = sorted.stream().filter(v -> TrendItemValue.ITEM.equals(v.getValueType())).map(TrendItemValue::getValue)
                .findFirst().orElse("");
        String type = sorted.stream().filter(v -> TrendItemValue.ITEM_TYPE.equals(v.getValueType())).map(TrendItemValue::getValue)
                .findFirst().orElse(null);
        return new Garment(no, garmentCategory, name, type, of(sorted, TrendItemValue.MATERIAL), of(sorted, TrendItemValue.COLOR),
                of(sorted, TrendItemValue.SILHOUETTE), of(sorted, TrendItemValue.GENDER));
    }

    private static List<String> of(List<TrendItemValue> list, String type) {
        return list.stream().filter(v -> type.equals(v.getValueType())).map(TrendItemValue::getValue).toList();
    }
}
