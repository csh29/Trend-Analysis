package com.sjinc.trendanalysis.service.rag;

import com.sjinc.trendanalysis.domain.ComCd;
import com.sjinc.trendanalysis.service.analysis.TrendCodes;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 질문 해석의 <b>규칙 대체</b> — 평소에는 AI(작은 모델, AI 서비스 /interpret)가 해석하고, 그 호출이 실패하면 이 규칙을 쓴다
 * (2026-10-22 사용자 결정 A). 규칙은 미리 정한 말만 알아듣는다. 알아듣는 말은 공통코드에 있다(유행 질문 단어 RAG_TREND_WORD,
 * 카테고리 일상 표현 = TREND_CATEGORY 속성, 아이템 종류 이름 = TREND_ITEM 코드명).
 *
 * <ul>
 *   <li><b>유행 질문</b>("유행·트렌드·인기·뜨는·많이 입는…"): 유행 = 많이 입어 대중화된 것(2026-10-22 사용자 정의)이라
 *       문장 유사도가 아니라 집계(원문 수·매체 수)로 답한다(RagService). 기간이 없으면 최근 4주.</li>
 *   <li><b>그 밖의 질문</b>: 문장 유사도 검색. 기간이 없으면 자르지 않고 최근 자료를 앞세운다(AI 서비스, 반감기 4주).</li>
 *   <li>카테고리·아이템 종류는 공통코드 이름(괄호 안 다른 표기 포함)과 일상 표현(재킷·바지…)으로 찾는다. 둘 다 있으면 종류가 이긴다.</li>
 * </ul>
 */
public final class QuestionInterpreter {

    static final int TREND_DAYS = 28;
    static final int SEASON_DAYS = 183;

    private static final Pattern RECENT_N = Pattern.compile("(?:최근|지난)\\s*(\\d{1,3})\\s*(일|주|개월|달)");
    private static final Pattern SEASON = Pattern.compile(
            "올\\s*(봄|여름|가을|겨울)|이번\\s*(봄|여름|가을|겨울)|\\d{2}\\s*(SS|FW|S/S|F/W)|(SS|FW)\\s*시즌", Pattern.CASE_INSENSITIVE);

    /**
     * @param trend    유행을 묻는가
     * @param category 카테고리 코드(없으면 null)
     * @param itemType 아이템 종류 코드(없으면 null). 있으면 category는 그 종류의 카테고리다
     * @param period   질문에 기간이 있으면 그 기간, 없으면 null
     * @param terms    카테고리·종류 코드로 옮기지 못한 패션 단어(AI 해석만 — 규칙 해석은 비어 있다). 유행 집계가 키워드·아이템명에서 센다
     * @param seasons  질문이 가리키는 시즌 코드(F/W → FALL·WINTER, AI 해석만). 유행 집계가 원문의 시즌 값으로 거른다
     * @param focus    유행 질문이 묻는 축(SILHOUETTE·COLOR…, AI 해석만 — 없으면 null). 판단 근거의 첫 축·예시 원문이 이 축이 된다
     * @param filters  대상을 좁히는 속성 값(값 종류 MATERIAL·COLOR·SILHOUETTE → 코드들, AI 해석만). 유행 집계가 아이템의 그 값으로 거른다
     *                 — "와이드 팬츠"를 글자로 찾으면 "와이드 데님 팬츠"가 빠졌다(2026-10-26)
     * @param change   증감을 묻는 유행 질문이면 방향(UP 급상승·DOWN 감소·BOTH 변화, AI 해석만 — 없으면 null). 유행 집계가 많은 순 대신
     *                 직전 기간 대비 증감 순으로 고르고 증감률을 붙인다(2026-10-28 사용자 요청)
     */
    public record Interpretation(boolean trend, String category, String categoryName, String itemType, String itemTypeName,
                                 Period period, List<String> terms, List<String> seasons, String focus,
                                 Map<String, List<String>> filters, String change) {

        public Interpretation {
            terms = terms == null ? List.of() : List.copyOf(terms);
            seasons = seasons == null ? List.of() : List.copyOf(seasons);
            filters = filters == null ? Map.of() : Map.copyOf(filters);
        }

        public Interpretation(boolean trend, String category, String categoryName, String itemType, String itemTypeName,
                              Period period, List<String> terms, List<String> seasons, String focus,
                              Map<String, List<String>> filters) {
            this(trend, category, categoryName, itemType, itemTypeName, period, terms, seasons, focus, filters, null);
        }

        public Interpretation(boolean trend, String category, String categoryName, String itemType, String itemTypeName,
                              Period period, List<String> terms, List<String> seasons, String focus) {
            this(trend, category, categoryName, itemType, itemTypeName, period, terms, seasons, focus, Map.of(), null);
        }

        public Interpretation(boolean trend, String category, String categoryName, String itemType, String itemTypeName,
                              Period period, List<String> terms, List<String> seasons) {
            this(trend, category, categoryName, itemType, itemTypeName, period, terms, seasons, null, Map.of(), null);
        }

        public Interpretation(boolean trend, String category, String categoryName, String itemType, String itemTypeName,
                              Period period, List<String> terms) {
            this(trend, category, categoryName, itemType, itemTypeName, period, terms, List.of(), null, Map.of(), null);
        }

        public Interpretation(boolean trend, String category, String categoryName, String itemType, String itemTypeName,
                              Period period) {
            this(trend, category, categoryName, itemType, itemTypeName, period, List.of(), List.of(), null, Map.of(), null);
        }
    }

    /** 게시일 기간(양 끝 포함). explicit=질문이 정한 기간. */
    public record Period(LocalDate from, LocalDate to, String label, boolean explicit) {
    }

    private QuestionInterpreter() {
    }

    /**
     * @param trendWords 유행 질문 단어(공통코드 RAG_TREND_WORD — 코드명이 그 말). 질문에 하나라도 있으면 유행 질문
     */
    public static Interpretation interpret(String question, LocalDate today, List<ComCd> categories, List<ComCd> itemTypes,
                                           List<ComCd> trendWords) {
        String compact = question.replace(" ", "");
        boolean trend = trendWords.stream().map(w -> w.getCodeName().replace(" ", ""))
                .anyMatch(w -> !w.isEmpty() && compact.contains(w));

        String itemType = longestMatch(compact, aliases(itemTypes, true));
        String category = itemType != null ? TrendCodes.categoryOfType(itemType) : longestMatch(compact, categoryAliases(categories));
        return new Interpretation(trend, category, name(categories, category), itemType, name(itemTypes, itemType),
                period(question, today));
    }

    /** 질문의 기간. 없으면 null — 유행 질문이면 TrendFactsService가 최근 4주를 쓴다. */
    static Period period(String question, LocalDate today) {
        String q = question.replace(" ", "");
        Matcher m = RECENT_N.matcher(question);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            String unit = m.group(2);
            int days = switch (unit) {
                case "일" -> n;
                case "주" -> n * 7;
                default -> n * 30;
            };
            days = Math.max(1, Math.min(days, 3 * 365));
            return new Period(today.minusDays(days - 1L), today, "최근 " + n + unit, true);
        }
        if (q.contains("지난주") || q.contains("저번주")) {
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return new Period(monday.minusWeeks(1), monday.minusDays(1), "지난주", true);
        }
        if (q.contains("이번주") || q.contains("금주")) {
            return new Period(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today, "이번 주", true);
        }
        if (q.contains("지난달") || q.contains("저번달")) { // "전월 대비"는 비교 표현이라 기간으로 보지 않는다
            LocalDate start = today.withDayOfMonth(1).minusMonths(1);
            return new Period(start, start.with(TemporalAdjusters.lastDayOfMonth()), "지난달(" + start.getMonthValue() + "월)", true);
        }
        if (q.contains("이번달") || q.contains("이달") || q.contains("금월")) {
            return new Period(today.withDayOfMonth(1), today, "이번 달(" + today.getMonthValue() + "월)", true);
        }
        if (q.contains("작년") || q.contains("지난해")) {
            int year = today.getYear() - 1;
            return new Period(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), "작년(" + year + "년)", true);
        }
        if (q.contains("올해") || q.contains("금년")) {
            return new Period(LocalDate.of(today.getYear(), 1, 1), today, "올해(" + today.getYear() + "년)", true);
        }
        // 시즌 기사는 몇 달 앞서 나온다 — 시즌 표현은 최근 6개월
        if (SEASON.matcher(question).find()) {
            return seasonWindow(today);
        }
        return null;
    }

    /** 시즌 표현의 기간 — 시즌 기사는 몇 달 앞서 나와 오늘까지 6개월을 본다. */
    public static Period seasonWindow(LocalDate today) {
        return new Period(today.minusDays(SEASON_DAYS - 1L), today, "최근 6개월(시즌 표현)", true);
    }

    /** 기간이 없는 유행 질문의 기본 기간 — 오늘까지 4주(집계의 전월 대비와 같은 길이). */
    public static Period recentWeeks(LocalDate end) {
        return new Period(end.minusDays(TREND_DAYS - 1L), end, "최근 4주", false);
    }

    /** 코드 이름과 괄호 안 다른 표기("패딩(숏패딩·푸퍼)" → 패딩, 숏패딩, 푸퍼)를 별칭으로. 기타(…_ETC)는 질문에서 찾지 않는다. */
    private static Map<String, String> aliases(List<ComCd> codes, boolean skipEtc) {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (ComCd code : codes) {
            if (skipEtc && (code.getCodeValue().endsWith("_ETC") || code.getCodeValue().equals(TrendCodes.CATEGORY_ETC))) {
                continue;
            }
            for (String word : code.getCodeName().split("[()·,/]")) {
                String alias = word.replace(" ", "").trim();
                if (alias.length() >= 2) {
                    aliases.putIfAbsent(alias, code.getCodeValue());
                }
            }
        }
        return aliases;
    }

    /** 카테고리 이름 + 속성(일상 표현 쉼표 목록 — 재킷·바지…). */
    private static Map<String, String> categoryAliases(List<ComCd> categories) {
        Map<String, String> aliases = aliases(categories, true);
        categories.forEach(c -> c.attrValues().forEach(w -> aliases.putIfAbsent(w.replace(" ", ""), c.getCodeValue())));
        return aliases;
    }

    /** 가장 긴 별칭이 이긴다("데님 재킷"이 "데님"보다 먼저). */
    private static String longestMatch(String compact, Map<String, String> aliases) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(aliases.entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());
        for (Map.Entry<String, String> e : entries) {
            if (compact.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String name(List<ComCd> codes, String value) {
        if (value == null) {
            return null;
        }
        return codes.stream().filter(c -> c.getCodeValue().equals(value)).map(ComCd::getCodeName).findFirst().orElse(value);
    }
}
