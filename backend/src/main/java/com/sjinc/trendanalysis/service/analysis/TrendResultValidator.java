package com.sjinc.trendanalysis.service.analysis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P-03 "스키마를 통과해야 저장한다" — AI 서비스가 돌려준 정형 결과를 저장 전에 검증·정리한다. Structured Output이 형식은
 * 맞춰 주지만, 공통코드가 그사이 바뀌었거나 값이 비는 경우는 여기서 걸러야 한다. Spring 없이 테스트되는 순수 클래스다.
 */
public final class TrendResultValidator {

    public static final List<String> SENTIMENTS = List.of("positive", "neutral", "negative");
    static final int MAX_KEYWORDS = 8;
    static final int MAX_KEYWORD_LENGTH = 100;
    static final int MAX_SUMMARY_LENGTH = 1000;

    /** 검증을 통과한 결과. 다중 값은 중복을 빼고 순서를 지킨다. */
    public record Validated(String category, List<String> keywords, List<String> materials, List<String> colors,
                            List<String> silhouettes, String sentiment, String summary) {
    }

    /** 통과하면 validated, 아니면 errors(사람이 읽는 사유). */
    public record Outcome(Validated validated, List<String> errors) {
        public boolean ok() {
            return validated != null;
        }
    }

    private TrendResultValidator() {
    }

    public static Outcome validate(Map<String, Object> raw, TrendCodeBook codes) {
        List<String> errors = new ArrayList<>();
        if (raw == null) {
            return new Outcome(null, List.of("결과가 비어 있습니다."));
        }

        String category = text(raw.get("category"));
        if (!codes.contains(TrendCodes.CATEGORY, category)) {
            errors.add("category가 공통코드에 없습니다: " + category);
        }

        List<String> keywords = new ArrayList<>();
        for (String keyword : strings(raw.get("keywords"), "keywords", errors)) {
            String trimmed = keyword.trim();
            if (!trimmed.isEmpty() && trimmed.length() <= MAX_KEYWORD_LENGTH && !keywords.contains(trimmed)) {
                keywords.add(trimmed);
            }
        }
        if (keywords.isEmpty()) {
            errors.add("keywords가 비어 있습니다.");
        }
        if (keywords.size() > MAX_KEYWORDS) {
            keywords = keywords.subList(0, MAX_KEYWORDS);
        }

        List<String> materials = codeValues(raw.get("materials"), "materials", TrendCodes.MATERIAL, codes, errors);
        List<String> colors = codeValues(raw.get("colors"), "colors", TrendCodes.COLOR, codes, errors);
        List<String> silhouettes = codeValues(raw.get("silhouettes"), "silhouettes", TrendCodes.SILHOUETTE, codes, errors);

        String sentiment = text(raw.get("sentiment"));
        if (!SENTIMENTS.contains(sentiment)) {
            errors.add("sentiment가 허용 값이 아닙니다: " + sentiment);
        }

        String summary = text(raw.get("summary")).trim();
        if (summary.isEmpty()) {
            errors.add("summary가 비어 있습니다.");
        } else if (summary.length() > MAX_SUMMARY_LENGTH) {
            summary = summary.substring(0, MAX_SUMMARY_LENGTH);
        }

        if (!errors.isEmpty()) {
            return new Outcome(null, errors);
        }
        return new Outcome(new Validated(category, List.copyOf(keywords), materials, colors, silhouettes, sentiment, summary),
                List.of());
    }

    private static List<String> codeValues(Object raw, String field, String group, TrendCodeBook codes, List<String> errors) {
        Set<String> values = new LinkedHashSet<>();
        for (String value : strings(raw, field, errors)) {
            if (codes.contains(group, value)) {
                values.add(value);
            } else {
                errors.add(field + "에 공통코드에 없는 값이 있습니다: " + value);
            }
        }
        return List.copyOf(values);
    }

    private static List<String> strings(Object raw, String field, List<String> errors) {
        if (raw == null) {
            errors.add(field + "가 없습니다.");
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            errors.add(field + "가 배열이 아닙니다.");
            return List.of();
        }
        return list.stream().filter(v -> v != null).map(String::valueOf).toList();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
