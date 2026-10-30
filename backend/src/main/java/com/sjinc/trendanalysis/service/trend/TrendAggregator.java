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

    /** 마감된 주의 확정 언급량 한 건(그 주의 집계 행) — 다시 세지 않고 열린 주의 전주·4주 평균에만 쓴다. */
    public record WeekCount(LocalDate weekStart, String category, String valueType, String value, int count) {
    }

    /**
     * 첫 언급 주부터 lastWeek까지 주마다 집계한다. 이번 주·전주 모두 0인 값은 행을 만들지 않는다.
     * 카테고리 범위는 전체(ALL)와 문서의 카테고리 두 가지다. 카테고리 자체의 언급량은 전체 범위에만 둔다.
     */
    public static List<Row> aggregate(List<Mention> mentions, LocalDate lastWeek, SurgeRule rule) {
        return aggregate(mentions, List.of(), null, lastWeek, rule);
    }

    /**
     * 주 마감(2026-10-30) — fromWeek(첫 열린 주)부터의 행만 만든다. mentions는 열린 주 원문만(날짜는 집계 주),
     * closed는 마감된 주의 확정 언급량(fromWeek 이전 4주면 충분하다). fromWeek가 null이면 마감이 없는 것이다.
     */
    public static List<Row> aggregate(List<Mention> mentions, List<WeekCount> closed, LocalDate fromWeek,
                                      LocalDate lastWeek, SurgeRule rule) {
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
        // 주별 문서 수 — 마감된 주는 확정값을 그대로 쓴다(원문을 다시 세지 않는다)
        Map<Key, Map<LocalDate, Integer>> counts = new HashMap<>();
        docsByWeek.forEach((key, weeks) -> weeks.forEach((week, docs) ->
                counts.computeIfAbsent(key, k -> new HashMap<>()).put(week, docs.size())));
        for (WeekCount c : closed) {
            counts.computeIfAbsent(new Key(c.category(), c.valueType(), c.value()), k -> new HashMap<>()).put(c.weekStart(), c.count());
            firstWeek = firstWeek == null || c.weekStart().isBefore(firstWeek) ? c.weekStart() : firstWeek;
        }
        if (firstWeek == null) {
            return List.of();
        }
        if (fromWeek != null && fromWeek.isAfter(firstWeek)) {
            firstWeek = fromWeek;
        }

        List<Row> rows = new ArrayList<>();
        for (Map.Entry<Key, Map<LocalDate, Integer>> entry : counts.entrySet()) {
            Key key = entry.getKey();
            Map<LocalDate, Integer> weeks = entry.getValue();
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

    private static int count(Map<LocalDate, Integer> weeks, LocalDate week) {
        return weeks.getOrDefault(week, 0);
    }

    private static double rate(int current, double base) {
        return round((current - base) / base * 100);
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
