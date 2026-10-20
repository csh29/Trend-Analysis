package com.sjinc.trendanalysis.service.trend;

import com.sjinc.trendanalysis.domain.TrendWeeklyStat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P-05 주간 집계 계산(순수 클래스 — DB·Spring 없이 테스트한다).
 *
 * <ul>
 *   <li>언급량: 그 주(월~일)에 값이 나온 문서 수. 날짜는 원문 게시일, 없으면 업로드일(2026-10-20 사용자 결정).</li>
 *   <li>전주 대비: (이번 주 - 전주) / 전주 × 100. 전주가 0이면 계산하지 않고 "신규"로 표시한다.</li>
 *   <li>전월 대비: 직전 4주 평균과 비교한다. 4주 전 한 주와 비교하면 그 주 사정에 너무 흔들려서다.</li>
 *   <li>급상승: 전주 대비가 기준(기본 20%) 이상이고 이번 주 언급량이 최소 건수 이상(사용자 결정 2026-10-20).</li>
 * </ul>
 */
public final class TrendAggregator {

    /** 집계 입력 한 건 — 문서 한 건의 값 하나. */
    public record Mention(long documentId, LocalDate date, String category, String valueType, String value) {
    }

    /** 급상승 판정 기준. */
    public record SurgeRule(double minRate, int minCount) {
    }

    /** 집계 결과 한 행(TrendWeeklyStat과 같은 내용). */
    public record Row(LocalDate weekStart, String category, String valueType, String value, int count, int prevCount,
                      Double prevRate, double monthAvg, Double monthRate, boolean surge, boolean isNew) {
    }

    private record Key(String category, String valueType, String value) {
    }

    private static final int MONTH_WEEKS = 4;

    private TrendAggregator() {
    }

    public static LocalDate weekStart(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /**
     * 첫 언급 주부터 lastWeek까지 주마다 집계한다. 이번 주·전주 모두 0인 값은 행을 만들지 않는다.
     * 카테고리 범위는 전체(ALL)와 문서의 카테고리 두 가지다. 카테고리 자체의 언급량은 전체 범위에만 둔다.
     */
    public static List<Row> aggregate(List<Mention> mentions, LocalDate lastWeek, SurgeRule rule) {
        Map<Key, Map<LocalDate, Set<Long>>> docsByWeek = new HashMap<>();
        LocalDate firstWeek = null;
        LocalDate endWeek = weekStart(lastWeek);
        for (Mention m : mentions) {
            LocalDate week = weekStart(m.date());
            firstWeek = firstWeek == null || week.isBefore(firstWeek) ? week : firstWeek;
            endWeek = week.isAfter(endWeek) ? week : endWeek;
            add(docsByWeek, new Key(TrendWeeklyStat.ALL, m.valueType(), m.value()), week, m.documentId());
            if (!TrendWeeklyStat.CATEGORY.equals(m.valueType())) {
                add(docsByWeek, new Key(m.category(), m.valueType(), m.value()), week, m.documentId());
            }
        }
        if (firstWeek == null) {
            return List.of();
        }

        List<Row> rows = new ArrayList<>();
        for (Map.Entry<Key, Map<LocalDate, Set<Long>>> entry : docsByWeek.entrySet()) {
            Key key = entry.getKey();
            Map<LocalDate, Set<Long>> weeks = entry.getValue();
            for (LocalDate week = firstWeek; !week.isAfter(endWeek); week = week.plusWeeks(1)) {
                int count = count(weeks, week);
                int prev = count(weeks, week.minusWeeks(1));
                if (count == 0 && prev == 0) {
                    continue;
                }
                int monthTotal = 0;
                for (int i = 1; i <= MONTH_WEEKS; i++) {
                    monthTotal += count(weeks, week.minusWeeks(i));
                }
                double monthAvg = round((double) monthTotal / MONTH_WEEKS);
                Double prevRate = prev == 0 ? null : rate(count, prev);
                Double monthRate = monthTotal == 0 ? null : rate(count, (double) monthTotal / MONTH_WEEKS);
                boolean surge = prevRate != null && prevRate >= rule.minRate() && count >= rule.minCount();
                rows.add(new Row(week, key.category(), key.valueType(), key.value(), count, prev,
                        prevRate, monthAvg, monthRate, surge, prev == 0 && count > 0));
            }
        }
        rows.sort(Comparator.comparing(Row::weekStart).thenComparing(Row::category)
                .thenComparing(Row::valueType).thenComparing(Comparator.comparingInt(Row::count).reversed())
                .thenComparing(Row::value));
        return rows;
    }

    private static void add(Map<Key, Map<LocalDate, Set<Long>>> docsByWeek, Key key, LocalDate week, long documentId) {
        docsByWeek.computeIfAbsent(key, k -> new HashMap<>()).computeIfAbsent(week, w -> new HashSet<>()).add(documentId);
    }

    private static int count(Map<LocalDate, Set<Long>> weeks, LocalDate week) {
        Set<Long> docs = weeks.get(week);
        return docs == null ? 0 : docs.size();
    }

    private static double rate(int current, double base) {
        return round((current - base) / base * 100);
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
