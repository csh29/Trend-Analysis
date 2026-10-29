package com.sjinc.trendanalysis.service.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 답의 수치를 Claude가 읽은 집계 글과 대조한다(2026-10-29 진단 F1). */
class AnswerNumbersTest {

    private static final List<String> EVIDENCE = List.of(
            "대상: 아우터 · 원문 12건 · 출처 5곳 (직전 기간 원문 4건, +200%)",
            "[아이템 종류] 패딩 유행점수 4.5 · 6건 · 출처 3곳 · 직전 2건(+200%) / 레더 재킷 유행점수 2 · 2건 · 출처 2곳 · 직전 0건(신규)");

    @Test
    void 집계에_있는_수치는_통과한다() {
        String answer = "최근 4주 아우터는 패딩이 6건으로 가장 많다. 직전 2건에서 200% 늘었다. 출처 3곳, 유행점수 4.5다.";
        assertThat(AnswerNumbers.unverified(answer, EVIDENCE)).isEmpty();
    }

    @Test
    void 집계에_없는_수치를_찾는다() {
        String answer = "패딩 7건, +150% 늘었다. 레더 재킷은 출처 2곳이다. 유행점수 3이다.";
        assertThat(AnswerNumbers.unverified(answer, EVIDENCE)).containsExactly("7건", "+150%", "유행점수 3");
    }

    @Test
    void 기준을_말하는_수치와_단위_없는_수는_보지_않는다() {
        String answer = "직전·최근이 모두 2건 이하라 비율이 크게 나온다. 유행점수 2 이상만 핵심이다. 상위 3위까지, 최근 4주 기준.";
        assertThat(AnswerNumbers.unverified(answer, EVIDENCE)).isEmpty();
        assertThat(AnswerNumbers.unverified("5건 미만이다", EVIDENCE)).isEmpty();
    }

    @Test
    void 소수점_표기가_달라도_같은_수로_본다() {
        assertThat(AnswerNumbers.unverified("유행점수 4.50", EVIDENCE)).isEmpty();
        assertThat(AnswerNumbers.unverified("", EVIDENCE)).isEmpty();
    }
}
