package com.sjinc.trendanalysis.service.analysis;

import com.sjinc.trendanalysis.domain.ComCd;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 분석 한 건에 쓰는 표준 코드 묶음(사용중 코드만). 분석할 때마다 공통코드를 다시 읽으므로 공통코드 관리 화면에서
 * 값을 바꾸면 재기동 없이 다음 분석부터 적용된다.
 */
public record TrendCodeBook(Map<String, List<ComCd>> groups) {

    public static TrendCodeBook of(Function<String, List<ComCd>> loader) {
        Map<String, List<ComCd>> groups = new LinkedHashMap<>();
        for (String group : List.of(TrendCodes.CATEGORY, TrendCodes.MATERIAL, TrendCodes.COLOR, TrendCodes.SILHOUETTE)) {
            groups.put(group, loader.apply(group));
        }
        return new TrendCodeBook(groups);
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

    /** 비어 있는 그룹 — 하나라도 비면 정형화할 수 없다(LLM이 고를 값이 없다). */
    public List<String> emptyGroups() {
        return groups.entrySet().stream().filter(e -> e.getValue().isEmpty()).map(Map.Entry::getKey).toList();
    }
}
