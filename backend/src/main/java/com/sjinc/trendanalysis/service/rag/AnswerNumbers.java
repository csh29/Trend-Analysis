package com.sjinc.trendanalysis.service.rag;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 답 글의 수치가 Claude가 읽은 집계 글(집계 줄·도구 결과)에 있는지 대조한다(2026-10-29 진단 F1). 서버는 인용 번호만 검증했고,
 * 답 문장의 "5건"·"+200%"는 Claude가 옮겨 쓴 글이라 틀려도 알 수 없었다.
 * <ul>
 *   <li>보는 수치: N건·N곳·N%·유행점수 N. 부호는 떼고 비교한다("+200%"를 "200% 늘었다"로 써도 같다).</li>
 *   <li>"2건 이하"·"유행점수 2 이상"처럼 기준을 말하는 수치는 집계 값이 아니라 보지 않는다.</li>
 *   <li>단위 없는 수(표 칸의 "5", "3위")와 기간(4주·10/12)은 보지 않는다 — 놓치는 것이 있어 "확인되지 않은 수치"만 알리고 답은 막지 않는다.</li>
 * </ul>
 */
final class AnswerNumbers {

    private static final String NOT_BOUND = "(?!\\s*(?:이하|이상|미만|초과))";
    private static final Pattern UNIT = Pattern.compile("[+-]?(\\d+(?:\\.\\d+)?)\\s*(건|곳|%)" + NOT_BOUND);
    private static final Pattern SCORE = Pattern.compile("유행점수\\s*(\\d+(?:\\.\\d+)?)" + NOT_BOUND);

    private AnswerNumbers() {
    }

    /** 답에 나온 수치 중 근거 글에 없는 것(답에 나온 모양 그대로, 중복 없이 나온 순서). */
    static List<String> unverified(String answer, List<String> evidence) {
        if (answer == null || answer.isBlank()) return List.of();
        Set<String> known = new HashSet<>();
        evidence.forEach(text -> collect(text, known, null));
        Set<String> missing = new LinkedHashSet<>();
        collect(answer, null, (key, shown) -> {
            if (!known.contains(key)) missing.add(shown);
        });
        return List.copyOf(missing);
    }

    private interface Found {
        void accept(String key, String shown);
    }

    private static void collect(String text, Set<String> into, Found found) {
        if (text == null) return;
        Matcher m = UNIT.matcher(text);
        while (m.find()) add(key(m.group(1), m.group(2)), m.group().strip(), into, found);
        Matcher s = SCORE.matcher(text);
        while (s.find()) add(key(s.group(1), "점"), s.group().strip(), into, found);
    }

    private static void add(String key, String shown, Set<String> into, Found found) {
        if (into != null) into.add(key);
        if (found != null) found.accept(key, shown);
    }

    /** 단위 + 정규화한 수("2.0"과 "2"는 같다). */
    private static String key(String number, String unit) {
        return unit + ":" + new BigDecimal(number).stripTrailingZeros().toPlainString();
    }
}
