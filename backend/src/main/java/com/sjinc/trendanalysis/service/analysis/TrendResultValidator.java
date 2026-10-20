package com.sjinc.trendanalysis.service.analysis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P-03 "스키마를 통과해야 저장한다" — AI 서비스가 돌려준 정형 결과를 저장 전에 검증·정리한다. Structured Output이 형식은
 * 맞춰 주지만, 공통코드가 그사이 바뀌었거나 값이 비는 경우는 여기서 걸러야 한다. Spring 없이 테스트되는 순수 클래스다.
 *
 * <p>결과는 의류 아이템 배열(items)이다. 아이템 수 상한은 스키마로 걸 수 없어(배열 길이 제약 미지원) 여기서 자른다.
 */
public final class TrendResultValidator {

    public static final List<String> SENTIMENTS = List.of("positive", "neutral", "negative");
    static final int MAX_ITEMS = 5;
    static final int MAX_ITEM_NAME_LENGTH = 50;
    static final int MAX_KEYWORDS = 8;
    static final int MAX_KEYWORD_LENGTH = 100;
    static final int MAX_SUMMARY_LENGTH = 1000;

    /** 의류 아이템 하나. 다중 값은 중복을 빼고 순서를 지킨다. */
    public record Garment(String category, String item, List<String> materials, List<String> colors,
                          List<String> silhouettes) {
    }

    /** 검증을 통과한 결과. items의 첫 번째가 대표 아이템이다. */
    public record Validated(List<Garment> items, List<String> keywords, List<String> seasons, String sentiment,
                            String summary) {
        public String mainCategory() {
            return items.get(0).category();
        }
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

        List<Garment> items = new ArrayList<>();
        List<?> rawItems = raw.get("items") instanceof List<?> list ? list : null;
        if (rawItems == null) {
            errors.add("items가 배열이 아닙니다.");
        } else if (rawItems.isEmpty()) {
            errors.add("items가 비어 있습니다.");
        } else {
            // 프롬프트가 "눈에 띄는 순서로 최대 5개"를 지시한다 — 넘치면 실패 대신 앞에서부터 자른다
            for (int i = 0; i < Math.min(rawItems.size(), MAX_ITEMS); i++) {
                Garment garment = garment(rawItems.get(i), i, codes, errors);
                if (garment != null) {
                    items.add(garment);
                }
            }
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

        // 시즌은 판단할 수 없으면 비어도 된다(사계절 옷만 있는 사진 등)
        List<String> seasons = codeValues(raw.get("seasons"), "seasons", TrendCodes.SEASON, codes, errors);

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
        return new Outcome(new Validated(List.copyOf(items), List.copyOf(keywords), seasons, sentiment, summary), List.of());
    }

    private static Garment garment(Object raw, int index, TrendCodeBook codes, List<String> errors) {
        String prefix = "items[" + index + "].";
        if (!(raw instanceof Map<?, ?> map)) {
            errors.add(prefix.substring(0, prefix.length() - 1) + "이 객체가 아닙니다.");
            return null;
        }
        String category = text(map.get("category"));
        if (!codes.contains(TrendCodes.CATEGORY, category)) {
            errors.add(prefix + "category가 공통코드에 없습니다: " + category);
        }
        String name = text(map.get("item")).trim();
        if (name.isEmpty()) {
            errors.add(prefix + "item이 비어 있습니다.");
        } else if (name.length() > MAX_ITEM_NAME_LENGTH) {
            name = name.substring(0, MAX_ITEM_NAME_LENGTH);
        }
        return new Garment(category, name,
                codeValues(map.get("materials"), prefix + "materials", TrendCodes.MATERIAL, codes, errors),
                codeValues(map.get("colors"), prefix + "colors", TrendCodes.COLOR, codes, errors),
                onePerAxis(codeValues(map.get("silhouettes"), prefix + "silhouettes", TrendCodes.SILHOUETTE, codes, errors)));
    }

    /** 실루엣은 축(핏·기장·형태)마다 처음 나온 값 하나만 — 실패로 돌리면 재시도 비용만 들어서 걸러 낸다. */
    static List<String> onePerAxis(List<String> silhouettes) {
        Set<String> usedAxes = new java.util.HashSet<>();
        List<String> kept = new ArrayList<>();
        for (String code : silhouettes) {
            String axis = TrendCodes.SILHOUETTE_AXES.get(code);
            if (axis == null || usedAxes.add(axis)) {
                kept.add(code);
            }
        }
        return List.copyOf(kept);
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
