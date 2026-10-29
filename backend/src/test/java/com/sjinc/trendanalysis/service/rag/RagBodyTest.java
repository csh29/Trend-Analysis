package com.sjinc.trendanalysis.service.rag;

import com.sjinc.trendanalysis.dto.rag.RagAnswerView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 자유 형식 본문 정리 — 따옴표만 온 본문은 비운다(2026-10-29 화면에 ""가 보였다). 지어낸 번호만 인용한 줄은 뺀다(진단 F2). */
class RagBodyTest {

    private static RagAnswerView.Source source(int n) {
        return new RagAnswerView.Source(n, "D-" + n, "IMAGE", "제목", "매체", null, null, null, null, "청크");
    }

    @Test
    void 따옴표만_있는_본문은_비운다() {
        assertThat(RagService.blankBody("\"\"")).isNull();
        assertThat(RagService.blankBody(" '' ")).isNull();
        assertThat(RagService.blankBody(null)).isNull();
        assertThat(RagService.body("\"\"", List.of())).isNull();
        assertThat(RagService.blankBody("| 축 | 핵심 |")).isEqualTo("| 축 | 핵심 |");
    }

    @Test
    void 없는_번호만_인용한_줄은_빼고_섞인_줄은_번호만_지운다() {
        String body = """
                | 아이템 | 특징 | 근거 |
                |---|---|---|
                | 패딩 | 블랙이 많다 | [1] |
                | 레더 재킷 | 지어낸 줄 | [9] |
                | 데님 재킷 | 섞인 줄 | [2][7] |
                자료가 적어 참고로만 본다.""";

        String cleaned = RagService.body(body, List.of(source(1), source(2)));

        assertThat(cleaned).contains("| 패딩 | 블랙이 많다 | [1] |")
                .doesNotContain("지어낸 줄")                    // [9]만 인용 — 근거 없는 문장이 남지 않는다
                .contains("| 데님 재킷 | 섞인 줄 | [2] |")      // 있는 번호는 남고 없는 번호만 지운다
                .contains("| 아이템 | 특징 | 근거 |")            // 번호 없는 표 머리·한계 문장은 둔다
                .contains("자료가 적어 참고로만 본다.");
        assertThat(RagService.body("지어낸 문단 [5]", List.of(source(1)))).isNull(); // 남는 줄이 없으면 본문 없음
    }
}
