package com.sjinc.trendanalysis.service.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 공통코드 속성으로 적은 리랭커 기준 읽기 — 잘못 적으면 기준이 사라지지 않게 기본값을 쓴다. */
class SearchSettingsTest {

    @Test
    void 속성의_숫자를_리랭커_기준으로_읽는다() {
        assertThat(SearchSettings.rerankMinScore(" 0.25 ")).isEqualTo(0.25);
    }

    @Test
    void 숫자가_아니거나_0_1_밖이면_기본값() {
        assertThat(SearchSettings.rerankMinScore(null)).isEqualTo(0.10);
        assertThat(SearchSettings.rerankMinScore("")).isEqualTo(0.10);
        assertThat(SearchSettings.rerankMinScore("높게")).isEqualTo(0.10);
        assertThat(SearchSettings.rerankMinScore("0")).isEqualTo(0.10);  // 0이면 무관한 원문까지 근거가 된다
        assertThat(SearchSettings.rerankMinScore("1.5")).isEqualTo(0.10);
    }
}
