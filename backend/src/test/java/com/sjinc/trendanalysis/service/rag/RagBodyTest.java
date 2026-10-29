package com.sjinc.trendanalysis.service.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 자유 형식 본문 정리 — 따옴표만 온 본문은 비운다(2026-10-29 화면에 ""가 보였다). */
class RagBodyTest {

    @Test
    void 따옴표만_있는_본문은_비운다() {
        assertThat(RagService.blankBody("\"\"")).isNull();
        assertThat(RagService.blankBody(" '' ")).isNull();
        assertThat(RagService.blankBody(null)).isNull();
        assertThat(RagService.body("\"\"", List.of())).isNull();
        assertThat(RagService.blankBody("| 축 | 핵심 |")).isEqualTo("| 축 | 핵심 |");
    }
}
