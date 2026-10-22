package com.sjinc.trendanalysis.controller;

import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.domain.TrendItem;
import com.sjinc.trendanalysis.domain.TrendItemValue;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.repository.TrendItemRepository;
import com.sjinc.trendanalysis.service.analysis.AiCallException;
import com.sjinc.trendanalysis.service.analysis.AiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 유행 질문 = 집계로 답한다(2026-10-22 사용자 정의: 유행 = 많이 입어 대중화된 것). 질문 해석은 작은 모델(가짜 AiClient)이 하고,
 * 실패하면 규칙(공통코드의 유행 단어·카테고리 일상 표현)으로 대신한다. 날짜는 실행 날짜 기준(최근 4주 안·밖)으로 넣는다.
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:ragtrendtest", "claude.api.key=test-key"})
@AutoConfigureMockMvc
class RagTrendApiTest {

    private static final LocalDate TODAY = LocalDate.now();

    @MockBean
    private AiClient aiClient;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;
    @Autowired
    private TrendItemRepository trendItemRepository;

    private static boolean seeded;

    /**
     * 최근 4주: 데님 재킷 3건(매체 A·A·B), 패딩 1건(매체 A), 데님 팬츠(하의) 1건. 직전 4주: 데님 재킷 1건. 분석 실패 원문은 세지 않는다.
     */
    @BeforeEach
    void seed() {
        clearInvocations(aiClient);
        if (seeded) return;
        seeded = true;
        garment("RT-1", TODAY.minusDays(1), "매체A", "OUTER", "OUTER_DENIM_JACKET", SourceDocument.DONE);
        garment("RT-2", TODAY.minusDays(3), "매체A", "OUTER", "OUTER_DENIM_JACKET", SourceDocument.DONE);
        garment("RT-3", TODAY.minusDays(10), "매체B", "OUTER", "OUTER_DENIM_JACKET", SourceDocument.DONE);
        garment("RT-4", TODAY.minusDays(5), "매체A", "OUTER", "OUTER_PADDING", SourceDocument.DONE);
        garment("RT-5", TODAY.minusDays(2), "매체A", "BOTTOM", "BOTTOM_DENIM", SourceDocument.DONE);
        garment("RT-6", TODAY.minusDays(35), "매체A", "OUTER", "OUTER_DENIM_JACKET", SourceDocument.DONE); // 직전 기간
        garment("RT-7", TODAY.minusDays(1), "매체A", "OUTER", "OUTER_PADDING", SourceDocument.FAILED);
    }

    private void garment(String docId, LocalDate published, String media, String category, String type, String status) {
        SourceDocument doc = sourceDocumentRepository.save(SourceDocument.builder()
                .docType(SourceDocument.TYPE_IMAGE).docId(docId).title(docId + ".png").media(media)
                .publishedAt(published).status(status).registeredBy("admin").registeredAt(LocalDateTime.now()).build());
        TrendItem item = TrendItem.builder().documentId(doc.getId()).category(category).sentiment("neutral")
                .summary(docId + " 요약").resultJson("{}").model("m").createdAt(LocalDateTime.now()).build();
        item.addValue(TrendItemValue.KEYWORD, docId, 0);
        item.addGarmentValue(TrendItemValue.ITEM, docId + " 아이템", 0, 0, category);
        item.addGarmentValue(TrendItemValue.ITEM_TYPE, type, 0, 0, category);
        item.addGarmentValue(TrendItemValue.COLOR, "BLACK", 1, 0, category);
        trendItemRepository.save(item);
    }

    private static String question(String text) {
        return "{\"question\":\"" + text + "\"}";
    }

    private static AiClient.AnswerResponse grounded(String docId) {
        return new AiClient.AnswerResponse(true, "데님 재킷이 가장 많다.",
                List.of(new AiClient.AnswerPoint("데님 재킷: 3건", List.of(1))),
                List.of(new AiClient.AnswerSource(1, docId, null, "예시")), null, null, null,
                new AiClient.AnswerPeriod(TODAY.minusDays(27).toString(), TODAY.toString(), "최근 4주", false, false));
    }

    @Test
    @WithMockUser(username = "admin")
    void 유행_질문은_범위_원문을_세서_집계와_예시_원문으로_답한다() throws Exception {
        when(aiClient.interpret(any(), eq("test-key"))).thenReturn(
                new AiClient.InterpretResponse(true, "OUTER", null, null, null));
        when(aiClient.answer(any(), any())).thenReturn(grounded("RT-1"));

        mockMvc.perform(post("/api/rag/ask").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(question("요즘 사람들 겉옷 뭐 입어?")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.interpretation.by").value("AI"))
                .andExpect(jsonPath("$.interpretation.categoryName").value("아우터"))
                .andExpect(jsonPath("$.period.label").value("최근 4주"))
                .andExpect(jsonPath("$.facts.docCount").value(4))
                .andExpect(jsonPath("$.facts.mediaCount").value(2))
                .andExpect(jsonPath("$.facts.dimensions[0].label").value("아이템 종류"))
                .andExpect(jsonPath("$.facts.dimensions[0].rows[0].label").value("데님 재킷(트러커)"))
                .andExpect(jsonPath("$.facts.dimensions[0].rows[0].count").value(3))
                .andExpect(jsonPath("$.facts.dimensions[0].rows[0].mediaCount").value(2))
                .andExpect(jsonPath("$.facts.dimensions[0].rows[0].prevCount").value(1))
                .andExpect(jsonPath("$.sources[0].score").doesNotExist()); // 유사도로 고른 게 아니다

        ArgumentCaptor<AiClient.AnswerRequest> sent = ArgumentCaptor.forClass(AiClient.AnswerRequest.class);
        verify(aiClient).answer(sent.capture(), eq("test-key"));
        AiClient.AnswerRequest request = sent.getValue();
        assertThat(request.mode()).isEqualTo(AiClient.AnswerRequest.TREND);
        assertThat(request.category()).isEqualTo("OUTER");
        assertThat(request.trendFacts()).anyMatch(l -> l.startsWith("대상: 아우터 · 원문 4건 · 매체 2곳 (직전 기간 원문 1건)"))
                .anyMatch(l -> l.startsWith("[아이템 종류] 데님 재킷(트러커) 3건 · 매체 2곳 · 직전 1건 / 패딩"));
        // 예시 원문: 상위 종류마다 최근 2건 — 데님 재킷(RT-1·RT-2), 패딩(RT-4). 하의·실패·직전 기간 원문은 빠진다
        assertThat(request.evidenceDocIds()).containsExactly("RT-1", "RT-2", "RT-4");
    }

    @Test
    @WithMockUser(username = "admin")
    void 종류를_콕_집으면_그_종류만_센다() throws Exception {
        when(aiClient.interpret(any(), any())).thenReturn(new AiClient.InterpretResponse(true, "TOP", "OUTER_PADDING", null, null));
        when(aiClient.answer(any(), any())).thenReturn(grounded("RT-4"));

        mockMvc.perform(post("/api/rag/ask").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(question("패딩 유행 색은?")))
                .andExpect(jsonPath("$.interpretation.category").value("OUTER")) // 종류의 카테고리가 이긴다
                .andExpect(jsonPath("$.facts.scopeLabel").value("아우터 · 패딩(숏패딩·푸퍼·패딩 점퍼)"))
                .andExpect(jsonPath("$.facts.docCount").value(1))
                // 종류를 정했으니 종류 축은 없고, 소재는 비어 있어 빠진다 — 첫 축은 컬러
                .andExpect(jsonPath("$.facts.dimensions[0].label").value("컬러"));
    }

    @Test
    @WithMockUser(username = "admin")
    void 기간에_원문이_없으면_AI를_부르지_않고_근거_없음() throws Exception {
        when(aiClient.interpret(any(), any())).thenReturn(new AiClient.InterpretResponse(true, "OUTER", null,
                new AiClient.AnswerPeriod("2020-01-01", "2020-12-31", "2020년", true, false), null));

        mockMvc.perform(post("/api/rag/ask").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(question("2020년 유행 아우터")))
                .andExpect(jsonPath("$.grounded").value(false))
                .andExpect(jsonPath("$.reason").value("NO_EVIDENCE"))
                .andExpect(jsonPath("$.answer").value("근거 자료 없음 — 2020년(2020-01-01 ~ 2020-12-31)에 아우터 원문이 없습니다."));
        verify(aiClient, never()).answer(any(), any());
    }

    @Test
    @WithMockUser(username = "admin")
    void 해석_AI가_실패하면_공통코드_규칙으로_해석한다() throws Exception {
        when(aiClient.interpret(any(), any())).thenThrow(new AiCallException("AI 서비스 연결 실패", true));
        when(aiClient.answer(any(), any())).thenReturn(grounded("RT-5"));

        // "유행"은 공통코드 RAG_TREND_WORD, "바지"는 TREND_CATEGORY(하의)의 속성
        mockMvc.perform(post("/api/rag/ask").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(question("요즘 유행하는 바지는?")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interpretation.by").value("RULE"))
                .andExpect(jsonPath("$.interpretation.trend").value(true))
                .andExpect(jsonPath("$.interpretation.category").value("BOTTOM"))
                .andExpect(jsonPath("$.facts.docCount").value(1));
    }

    @Test
    @WithMockUser(username = "admin")
    void 공통코드에_없는_해석_값은_버린다() throws Exception {
        when(aiClient.interpret(any(), any())).thenReturn(new AiClient.InterpretResponse(true, "SHOES", "SHOES_SNEAKER", null, null));
        when(aiClient.answer(any(), any())).thenReturn(grounded("RT-1"));

        mockMvc.perform(post("/api/rag/ask").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(question("요즘 유행?")))
                .andExpect(jsonPath("$.interpretation.category").doesNotExist())
                .andExpect(jsonPath("$.facts.scopeLabel").value("전체"))
                .andExpect(jsonPath("$.facts.docCount").value(5));
    }
}
