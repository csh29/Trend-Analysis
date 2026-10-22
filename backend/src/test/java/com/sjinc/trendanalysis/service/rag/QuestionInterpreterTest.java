package com.sjinc.trendanalysis.service.rag;

import com.sjinc.trendanalysis.domain.ComCd;
import com.sjinc.trendanalysis.service.analysis.TrendCodes;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 질문 해석의 규칙 대체(AI 해석이 실패했을 때) — 알아듣는 말은 공통코드 초안(TrendCodes.SEED)에서 온다. 오늘 = 2026-10-22(목). */
class QuestionInterpreterTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    private static List<ComCd> seed(String group) {
        return TrendCodes.SEED.get(group).stream().map(c -> ComCd.builder().codeGroup(group).codeValue(c[0]).codeName(c[1])
                .attr1(c.length > 2 ? c[2] : null).sortOrder(1).useYn("Y").build()).toList();
    }

    private static QuestionInterpreter.Interpretation interpret(String question) {
        return QuestionInterpreter.interpret(question, TODAY, seed(TrendCodes.CATEGORY), seed(TrendCodes.ITEM_TYPE),
                seed(TrendCodes.TREND_WORD));
    }

    @Test
    void 유행_단어와_카테고리_일상_표현은_공통코드에서_알아본다() {
        QuestionInterpreter.Interpretation q = interpret("요즘 유행하는 재킷은?");
        assertThat(q.trend()).isTrue();
        assertThat(q.category()).isEqualTo("OUTER"); // "재킷"은 아우터 속성
        assertThat(q.period()).isNull();             // 기간 없음 — 집계가 최근 4주를 쓴다

        assertThat(interpret("와이드 팬츠 코디 알려줘").trend()).isFalse();
        assertThat(interpret("많이 입는 바지").category()).isEqualTo("BOTTOM");
    }

    @Test
    void 아이템_종류는_긴_이름이_이기고_그_카테고리를_따른다() {
        QuestionInterpreter.Interpretation q = interpret("데님 재킷 인기 컬러");
        assertThat(q.itemType()).isEqualTo("OUTER_DENIM_JACKET"); // "데님 팬츠"·"데님"보다 "데님 재킷"
        assertThat(q.category()).isEqualTo("OUTER");
        assertThat(interpret("요즘 뜨는 트러커").itemType()).isEqualTo("OUTER_DENIM_JACKET"); // 괄호 안 다른 표기
        assertThat(interpret("기타 아우터 유행").itemType()).isNull(); // 기타 종류는 질문에서 찾지 않는다
    }

    @Test
    void 기간_표현() {
        Map<String, LocalDate[]> cases = Map.of(
                "지난주 키워드는?", new LocalDate[]{LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18)},
                "이번 주 뭐가 떴어?", new LocalDate[]{LocalDate.of(2026, 10, 19), TODAY},
                "지난달 컬러", new LocalDate[]{LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)},
                "이번달 소재", new LocalDate[]{LocalDate.of(2026, 10, 1), TODAY},
                "최근 3개월 아우터", new LocalDate[]{LocalDate.of(2026, 7, 25), TODAY},
                "최근 2주", new LocalDate[]{LocalDate.of(2026, 10, 9), TODAY},
                "올해 유행", new LocalDate[]{LocalDate.of(2026, 1, 1), TODAY},
                "작년 데님", new LocalDate[]{LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)},
                "올가을 아우터 트렌드", new LocalDate[]{LocalDate.of(2026, 4, 23), TODAY});
        cases.forEach((question, range) -> {
            QuestionInterpreter.Period p = QuestionInterpreter.period(question, TODAY);
            assertThat(new LocalDate[]{p.from(), p.to()}).as(question).isEqualTo(range);
            assertThat(p.explicit()).isTrue();
        });
        assertThat(QuestionInterpreter.period("전월 대비 급상승 키워드는?", TODAY)).isNull(); // 비교 표현은 기간이 아니다
        assertThat(QuestionInterpreter.period("요즘 유행", TODAY)).isNull();
    }

    @Test
    void 기본_기간은_오늘까지_4주() {
        QuestionInterpreter.Period p = QuestionInterpreter.recentWeeks(TODAY);
        assertThat(Arrays.asList(p.from(), p.to(), p.label())).containsExactly(LocalDate.of(2026, 9, 25), TODAY, "최근 4주");
    }
}
