package com.sjinc.trendanalysis.service.trend;

import com.sjinc.trendanalysis.domain.TrendWeeklyStat;
import com.sjinc.trendanalysis.service.trend.TrendAggregator.Mention;
import com.sjinc.trendanalysis.service.trend.TrendAggregator.Row;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TrendAggregatorTest {

    /** 2026-10-12(월) 주를 기준 주로 쓴다. */
    private static final LocalDate BASE = LocalDate.of(2026, 10, 12);
    private static final TrendAggregator.SurgeRule RULE = new TrendAggregator.SurgeRule(20, 1);

    private long nextDoc = 1;

    /** week 주에 문서 count건이 value를 언급. 문서마다 id가 다르다. */
    private void mentions(List<Mention> list, LocalDate week, String category, String type, String value, int count) {
        for (int i = 0; i < count; i++) {
            list.add(new Mention(nextDoc++, week.plusDays(i % 7), category, type, value));
        }
    }

    private static Optional<Row> row(List<Row> rows, LocalDate week, String category, String type, String value) {
        return rows.stream().filter(r -> r.weekStart().equals(week) && r.category().equals(category)
                && r.valueType().equals(type) && r.value().equals(value)).findFirst();
    }

    @Test
    void 주는_월요일부터_센다() {
        assertThat(TrendAggregator.weekStart(LocalDate.of(2026, 10, 18))).isEqualTo(BASE); // 일요일
        assertThat(TrendAggregator.weekStart(LocalDate.of(2026, 10, 12))).isEqualTo(BASE); // 월요일
        assertThat(TrendAggregator.weekStart(LocalDate.of(2026, 10, 11))).isEqualTo(BASE.minusWeeks(1));
    }

    @Test
    void 마감된_주는_다시_만들지_않고_확정_언급량으로_전주와_4주_평균을_낸다() {
        // 마감된 주(BASE 이전)의 원문이 남아 있어도 세지 않는다 — 확정값(closed)만 쓴다
        List<Mention> list = new ArrayList<>();
        mentions(list, BASE, "OUTER", "MATERIAL", "LEATHER", 3);
        List<TrendAggregator.WeekCount> closed = List.of(
                new TrendAggregator.WeekCount(BASE.minusWeeks(1), TrendWeeklyStat.ALL, "MATERIAL", "LEATHER", 2),
                new TrendAggregator.WeekCount(BASE.minusWeeks(2), TrendWeeklyStat.ALL, "MATERIAL", "LEATHER", 2),
                new TrendAggregator.WeekCount(BASE.minusWeeks(1), TrendWeeklyStat.ALL, "COLOR", "BLACK", 4));

        List<Row> rows = TrendAggregator.aggregate(list, closed, BASE, BASE, RULE);

        assertThat(rows).allMatch(r -> !r.weekStart().isBefore(BASE)); // 마감된 주의 행은 만들지 않는다
        Row leather = row(rows, BASE, TrendWeeklyStat.ALL, "MATERIAL", "LEATHER").orElseThrow();
        assertThat(leather.prevCount()).isEqualTo(2);
        assertThat(leather.prevRate()).isEqualTo(50.0);
        assertThat(leather.monthAvg()).isEqualTo(1.0); // (2 + 2 + 0 + 0) / 4
        // 마감된 주에만 있던 값은 열린 주에서 사라진 것으로(-100%) 나온다
        assertThat(row(rows, BASE, TrendWeeklyStat.ALL, "COLOR", "BLACK").orElseThrow().prevRate()).isEqualTo(-100.0);
    }

    @Test
    void 전주_대비_20퍼센트_이상이면_급상승이다() {
        List<Mention> list = new ArrayList<>();
        mentions(list, BASE.minusWeeks(1), "OUTER", "MATERIAL", "LEATHER", 5);
        mentions(list, BASE, "OUTER", "MATERIAL", "LEATHER", 6);       // +20% — 경계값 포함
        mentions(list, BASE.minusWeeks(1), "BOTTOM", "MATERIAL", "DENIM", 5);
        mentions(list, BASE, "BOTTOM", "MATERIAL", "DENIM", 5);        // 0%

        List<Row> rows = TrendAggregator.aggregate(list, BASE, RULE);

        Row leather = row(rows, BASE, TrendWeeklyStat.ALL, "MATERIAL", "LEATHER").orElseThrow();
        assertThat(leather.count()).isEqualTo(6);
        assertThat(leather.prevCount()).isEqualTo(5);
        assertThat(leather.prevRate()).isEqualTo(20.0);
        assertThat(leather.surge()).isTrue();
        Row denim = row(rows, BASE, TrendWeeklyStat.ALL, "MATERIAL", "DENIM").orElseThrow();
        assertThat(denim.prevRate()).isEqualTo(0.0);
        assertThat(denim.surge()).isFalse();
    }

    @Test
    void 전주가_0이면_증감률_없이_신규로_표시하고_급상승은_아니다() {
        List<Mention> list = new ArrayList<>();
        mentions(list, BASE, "OUTER", "KEYWORD", "숏패딩", 3);

        Row row = row(TrendAggregator.aggregate(list, BASE, RULE), BASE, TrendWeeklyStat.ALL, "KEYWORD", "숏패딩").orElseThrow();

        assertThat(row.prevRate()).isNull();
        assertThat(row.isNew()).isTrue();
        assertThat(row.surge()).isFalse();
    }

    @Test
    void 이번_주에_사라진_값도_감소로_남는다() {
        List<Mention> list = new ArrayList<>();
        mentions(list, BASE.minusWeeks(1), "TOP", "MATERIAL", "LINEN", 2);

        Row row = row(TrendAggregator.aggregate(list, BASE, RULE), BASE, TrendWeeklyStat.ALL, "MATERIAL", "LINEN").orElseThrow();

        assertThat(row.count()).isZero();
        assertThat(row.prevRate()).isEqualTo(-100.0);
    }

    @Test
    void 전월_대비는_직전_4주_평균과_비교한다() {
        List<Mention> list = new ArrayList<>();
        mentions(list, BASE.minusWeeks(4), "OUTER", "MATERIAL", "LEATHER", 1);
        mentions(list, BASE.minusWeeks(3), "OUTER", "MATERIAL", "LEATHER", 1);
        mentions(list, BASE.minusWeeks(2), "OUTER", "MATERIAL", "LEATHER", 2);
        mentions(list, BASE.minusWeeks(1), "OUTER", "MATERIAL", "LEATHER", 4);
        mentions(list, BASE.minusWeeks(5), "OUTER", "MATERIAL", "LEATHER", 9); // 4주 밖 — 평균에 들어가지 않는다
        mentions(list, BASE, "OUTER", "MATERIAL", "LEATHER", 4);

        Row row = row(TrendAggregator.aggregate(list, BASE, RULE), BASE, TrendWeeklyStat.ALL, "MATERIAL", "LEATHER").orElseThrow();

        assertThat(row.monthAvg()).isEqualTo(2.0);   // (1+1+2+4)/4
        assertThat(row.monthRate()).isEqualTo(100.0);
    }

    @Test
    void 한_문서에서_여러_번_나와도_한_건이고_카테고리별로도_센다() {
        List<Mention> list = new ArrayList<>();
        list.add(new Mention(1, BASE, "OUTER", "COLOR", "BROWN"));
        list.add(new Mention(1, BASE, "OUTER", "COLOR", "BROWN")); // 같은 문서에서 또 나옴
        list.add(new Mention(2, BASE.plusDays(1), "TOP", "COLOR", "BROWN"));
        list.add(new Mention(1, BASE, "OUTER", TrendWeeklyStat.CATEGORY, "OUTER"));

        List<Row> rows = TrendAggregator.aggregate(list, BASE, RULE);

        assertThat(row(rows, BASE, TrendWeeklyStat.ALL, "COLOR", "BROWN").orElseThrow().count()).isEqualTo(2);
        assertThat(row(rows, BASE, "OUTER", "COLOR", "BROWN").orElseThrow().count()).isEqualTo(1);
        assertThat(row(rows, BASE, "TOP", "COLOR", "BROWN").orElseThrow().count()).isEqualTo(1);
        // 카테고리 자체의 언급량은 전체 범위에만 있다
        assertThat(row(rows, BASE, TrendWeeklyStat.ALL, TrendWeeklyStat.CATEGORY, "OUTER")).isPresent();
        assertThat(row(rows, BASE, "OUTER", TrendWeeklyStat.CATEGORY, "OUTER")).isEmpty();
    }

    @Test
    void 최소_건수에_못_미치면_급상승이_아니다() {
        List<Mention> list = new ArrayList<>();
        mentions(list, BASE.minusWeeks(1), "OUTER", "KEYWORD", "레더 재킷", 1);
        mentions(list, BASE, "OUTER", "KEYWORD", "레더 재킷", 2); // +100%

        Row row = row(TrendAggregator.aggregate(list, BASE, new TrendAggregator.SurgeRule(20, 3)),
                BASE, TrendWeeklyStat.ALL, "KEYWORD", "레더 재킷").orElseThrow();

        assertThat(row.prevRate()).isEqualTo(100.0);
        assertThat(row.surge()).isFalse();
    }

    @Test
    void 언급이_없으면_빈_결과다() {
        assertThat(TrendAggregator.aggregate(List.of(), BASE, RULE)).isEmpty();
    }
}
