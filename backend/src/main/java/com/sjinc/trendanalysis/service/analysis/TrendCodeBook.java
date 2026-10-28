package com.sjinc.trendanalysis.service.analysis;

import com.sjinc.trendanalysis.domain.ComCd;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 분석 한 건에 쓰는 표준 코드 묶음(사용중 코드만). 분석할 때마다 공통코드를 다시 읽으므로 공통코드 관리 화면에서
 * 값을 바꾸면 재기동 없이 다음 분석부터 적용된다.
 */
public record TrendCodeBook(Map<String, List<ComCd>> groups) {

    public static TrendCodeBook of(Function<String, List<ComCd>> loader) {
        Map<String, List<ComCd>> groups = new LinkedHashMap<>();
        for (String group : List.of(TrendCodes.CATEGORY, TrendCodes.ITEM_TYPE, TrendCodes.MATERIAL, TrendCodes.COLOR,
                TrendCodes.SILHOUETTE, TrendCodes.SEASON, TrendCodes.GENDER, TrendCodes.PATTERN, TrendCodes.DETAIL)) {
            groups.put(group, loader.apply(group));
        }
        return new TrendCodeBook(groups);
    }

    /** 한 그룹을 주어진 코드값만 남긴 코드 묶음(이미지 소재 허용 목록 등). 순서는 공통코드 정렬순서를 지킨다. */
    public TrendCodeBook restrict(String group, Set<String> allowed) {
        Map<String, List<ComCd>> restricted = new LinkedHashMap<>(groups);
        restricted.put(group, codes(group).stream().filter(code -> allowed.contains(code.getCodeValue())).toList());
        return new TrendCodeBook(restricted);
    }

    public List<ComCd> codes(String group) {
        return groups.getOrDefault(group, List.of());
    }

    public boolean contains(String group, String value) {
        return codes(group).stream().anyMatch(code -> code.getCodeValue().equals(value));
    }

    /** 코드값 → 이름. 지금 공통코드에 없는 값(사용 중지 등)은 코드값 그대로. */
    public String name(String group, String value) {
        return codes(group).stream().filter(code -> code.getCodeValue().equals(value))
                .map(ComCd::getCodeName).findFirst().orElse(value);
    }

    /** 색인 청크에 코드값 대신 이름을 쓰기 위한 전체 코드값 → 이름 표. */
    public Map<String, String> allNames() {
        Map<String, String> names = new LinkedHashMap<>();
        groups.values().forEach(codes -> codes.forEach(code -> names.putIfAbsent(code.getCodeValue(), code.getCodeName())));
        return names;
    }

    /** 이미지만 있는 원문이 고를 수 있는 소재 — 속성 IMAGE(사진의 질감으로 구분되는 것). */
    public Set<String> imageMaterials() {
        return codes(TrendCodes.MATERIAL).stream().filter(c -> c.attrValues().contains(TrendCodes.ATTR_IMAGE))
                .map(ComCd::getCodeValue).collect(java.util.stream.Collectors.toSet());
    }

    /** 실루엣 코드 → 축(속성). 속성이 없는 코드는 빠진다(축 제한 없음). */
    public Map<String, String> silhouetteAxes() {
        Map<String, String> axes = new LinkedHashMap<>();
        codes(TrendCodes.SILHOUETTE).forEach(c -> c.attrValues().stream().findFirst().ifPresent(a -> axes.put(c.getCodeValue(), a)));
        return axes;
    }

    /**
     * 실루엣 코드 → 쓸 수 있는 카테고리(속성의 축 뒤 값들). 카테고리를 적지 않은 코드는 빠진다(모든 카테고리 허용).
     * 기타(ETC)처럼 어느 목록에도 없는 카테고리는 카테고리를 적은 실루엣을 쓸 수 없다.
     */
    public Map<String, Set<String>> silhouetteCategories() {
        Map<String, Set<String>> allowed = new LinkedHashMap<>();
        codes(TrendCodes.SILHOUETTE).forEach(c -> {
            List<String> values = c.attrValues();
            if (values.size() > 1) {
                allowed.put(c.getCodeValue(), Set.copyOf(values.subList(1, values.size())));
            }
        });
        return allowed;
    }

    /** 비어 있는 그룹 — 하나라도 비면 정형화할 수 없다(LLM이 고를 값이 없다). */
    public List<String> emptyGroups() {
        return groups.entrySet().stream().filter(e -> e.getValue().isEmpty()).map(Map.Entry::getKey).toList();
    }
}
