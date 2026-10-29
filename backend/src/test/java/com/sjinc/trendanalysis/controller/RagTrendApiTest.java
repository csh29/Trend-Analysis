package com.sjinc.trendanalysis.controller;

import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.domain.TrendItem;
import com.sjinc.trendanalysis.domain.TrendItemValue;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.repository.TrendItemRepository;
import com.sjinc.trendanalysis.service.analysis.AiCallException;
import com.sjinc.trendanalysis.service.analysis.AiClient;
import com.sjinc.trendanalysis.service.rag.RagService;
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
import java.util.Map;

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
    @Autowired
    private RagService ragService;

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
        TrendItem item = TrendItem.builder().documentId(doc.getId()).category(category)
                .summary(docId + " 요약").resultJson("{}").createdAt(LocalDateTime.now()).build();
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

    /** 아우터 유행 질문 해석 — 유행 질문은 모두 도구 경로로 간다. */
    private static AiClient.InterpretResponse trendOuter() {
        return new AiClient.InterpretResponse(true, "OUTER", null, null, null, true, null);
    }

    @Test
    @WithMockUser(username = "admin")
    void 기획_브리프는_집계를_여러_갈래로_세고_서술은_Claude가_쓴다() throws Exception {
        when(aiClient.interpret(any(), eq("test-key"))).thenReturn(trendOuter());
        when(aiClient.brief(any(), eq("test-key"))).thenReturn(new AiClient.BriefResponse(
                Map.of("title", "아우터 브리프"), Map.of("input_tokens", 1000, "output_tokens", 500)));

        mockMvc.perform(post("/api/rag/brief").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(question("아우터 기획 브리프 만들어줘")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brief.title").value("아우터 브리프"))
                .andExpect(jsonPath("$.docCount").value(4))
                .andExpect(jsonPath("$.sourceMix").isArray())
                .andExpect(jsonPath("$.itemsByCategory.length()").value(1)); // 범위가 아우터 하나면 그 카테고리만

        ArgumentCaptor<AiClient.BriefRequest> sent = ArgumentCaptor.forClass(AiClient.BriefRequest.class);
        verify(aiClient).brief(sent.capture(), eq("test-key"));
        assertThat(sent.getValue().data()).anyMatch(l -> l.startsWith("[출처 분포]")); // 숫자는 집계가 준다
    }

    @Test
    @WithMockUser(username = "admin")
    void 유행_질문은_도구로_집계를_불러_인용한_원문으로_답한다() throws Exception {
        when(aiClient.interpret(any(), eq("test-key"))).thenReturn(trendOuter());
        when(aiClient.agentTask(any())).thenReturn("도구로 조회해 답하라.");
        when(aiClient.agentStep(any(), eq("test-key"))).thenReturn(
                new AiClient.AgentStepResponse("tool_use", List.of(Map.of("type", "tool_use", "id", "t1", "name", "count_trend",
                        "input", Map.of("category", "OUTER", "focus", "ITEM_TYPE"))), Map.of("input_tokens", 100, "output_tokens", 20)),
                new AiClient.AgentStepResponse("tool_use", List.of(Map.of("type", "tool_use", "id", "t2", "name", "final_answer",
                        "input", Map.of("summary", "최근 4주 아우터는 데님 재킷이 가장 많다", "grounded", true, "body", "",
                                "points", List.of(Map.of("text", "데님 재킷 3건(매체 2곳)", "sources", List.of(1, 9)))))),
                        Map.of("input_tokens", 300, "output_tokens", 50)));

        mockMvc.perform(post("/api/rag/ask").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(question("데님 재킷이랑 패딩 중 뭐가 더 많아?")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.interpretation.by").value("AGENT"))
                .andExpect(jsonPath("$.points[0].sources.length()").value(1)) // 도구 결과에 없는 [9]는 빠진다
                .andExpect(jsonPath("$.sources.length()").value(1))
                .andExpect(jsonPath("$.facts.docCount").value(4))          // 판단 근거는 답이 인용한 원문이 나온 집계
                .andExpect(jsonPath("$.unverifiedNumbers").isEmpty());     // "3건"·"2곳"은 도구 결과에 있다

        ArgumentCaptor<AiClient.AgentStepRequest> steps = ArgumentCaptor.forClass(AiClient.AgentStepRequest.class);
        verify(aiClient, org.mockito.Mockito.times(2)).agentStep(steps.capture(), eq("test-key"));
        // 둘째 단계 messages의 마지막은 도구 결과 — 집계 줄과 [1] 예시 원문이 실린다
        String toolResult = String.valueOf(steps.getAllValues().get(1).messages().get(2));
        assertThat(toolResult).contains("[아이템 종류]").contains("[1] RT-");
        // 첫 메시지에 해석 범위로 미리 센 집계가 실린다 — 순위 질문은 도구를 더 부르지 않고 답할 수 있다
        assertThat(String.valueOf(steps.getAllValues().get(0).messages().get(0))).contains("<prefetched>").contains("[1] RT-");
        assertThat(steps.getAllValues().get(0).cache()).isFalse(); // 캐시는 둘째 단계부터
        assertThat(steps.getAllValues().get(1).cache()).isTrue();
        verify(aiClient, org.mockito.Mockito.never()).answer(any(), any()); // 지금 경로는 부르지 않는다
    }

    /** 도구 경로 한 단계 응답 — count_trend 또는 final_answer 하나. */
    private static AiClient.AgentStepResponse toolUse(String id, String name, Map<String, Object> input) {
        return new AiClient.AgentStepResponse("tool_use", List.of(Map.of("type", "tool_use", "id", id, "name", name, "input", input)),
                Map.of("input_tokens", 100, "output_tokens", 20));
    }

    @Test
    @WithMockUser(username = "admin")
    void 비교_답은_인용한_집계마다_판단_근거를_그리고_지어낸_인용_줄과_수치를_가린다() throws Exception {
        // 번호: 미리 센 아우터 집계 RT-1·RT-2·RT-4 → [1][2][3], 데님 재킷 집계에서 RT-3 → [4]
        when(aiClient.interpret(any(), eq("test-key"))).thenReturn(trendOuter());
        when(aiClient.agentTask(any())).thenReturn("도구로 조회해 답하라.");
        when(aiClient.agentStep(any(), eq("test-key"))).thenReturn(
                toolUse("t1", "count_trend", Map.of("item_type", "OUTER_DENIM_JACKET")),
                toolUse("t2", "count_trend", Map.of("item_type", "OUTER_PADDING")),
                toolUse("t3", "final_answer", Map.of("summary", "최근 4주 데님 재킷이 패딩보다 많다(전체 9건)", "grounded", true, "body", "",
                        "points", List.of(Map.of("text", "데님 재킷: 3건", "sources", List.of(4)),
                                Map.of("text", "패딩: 1건", "sources", List.of(3)),
                                Map.of("text", "지어낸 줄", "sources", List.of(9))))));

        mockMvc.perform(post("/api/rag/ask").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(question("데님 재킷이랑 패딩 비교해줘")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.points.length()").value(2))                  // [9]만 인용한 줄은 빠진다
                .andExpect(jsonPath("$.facts.scopeLabel").value("아우터 · 데님 재킷(트러커)")) // 미리 센 "아우터"가 아니라 답이 쓴 집계
                .andExpect(jsonPath("$.facts.docCount").value(3))
                .andExpect(jsonPath("$.moreFacts[0].scopeLabel").value("아우터 · 패딩(숏패딩·푸퍼·패딩 점퍼)"))
                .andExpect(jsonPath("$.moreFacts[0].period.label").value("최근 4주"))
                .andExpect(jsonPath("$.unverifiedNumbers[0]").value("9건"))     // 어느 집계에도 없는 수치
                .andExpect(jsonPath("$.unverifiedNumbers.length()").value(1));
    }

    @Test
    void 도구_경로가_실패해도_쓴_토큰을_질문_토큰에_더한다() {
        when(aiClient.interpret(any(), eq("test-key"))).thenReturn(trendOuter());
        when(aiClient.agentTask(any())).thenReturn("도구로 조회해 답하라.");
        when(aiClient.agentStep(any(), eq("test-key"))).thenReturn(new AiClient.AgentStepResponse("end_turn",
                List.of(Map.of("type", "text", "text", "도구 없이 끝남")), Map.of("input_tokens", 100, "output_tokens", 10)));
        when(aiClient.answer(any(), any())).thenReturn(grounded("RT-1"));

        RagService.Answered answered = ragService.answer("요즘 아우터 뭐 입어?", List.of());

        assertThat(answered.view().grounded()).isTrue();          // 고정 경로로 답했다
        assertThat(answered.usage().input()).isGreaterThanOrEqualTo(100); // 실패한 도구 경로 토큰이 빠지지 않는다
        assertThat(answered.usage().output()).isGreaterThanOrEqualTo(10);
    }

    @Test
    @WithMockUser(username = "admin")
    void 도구_경로가_실패하면_지금_경로로_답한다() throws Exception {
        when(aiClient.interpret(any(), eq("test-key"))).thenReturn(trendOuter());
        when(aiClient.agentTask(any())).thenReturn("도구로 조회해 답하라.");
        when(aiClient.agentStep(any(), any())).thenThrow(new AiCallException("AI 서비스 오류", true));
        when(aiClient.answer(any(), any())).thenReturn(grounded("RT-1"));

        mockMvc.perform(post("/api/rag/ask").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(question("아우터 6주 추이 어때?")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.interpretation.by").value("AI"));
        verify(aiClient).answer(any(), eq("test-key"));
    }

    @Test
    @WithMockUser(username = "admin")
    void 유행_질문은_범위_원문을_세서_집계와_예시_원문으로_답한다() throws Exception {
        when(aiClient.interpret(any(), eq("test-key"))).thenReturn(
                new AiClient.InterpretResponse(true, "OUTER", null, null, null, true, null));
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
        assertThat(request.trendFacts()).anyMatch(l -> l.startsWith("대상: 아우터 · 원문 4건 · 출처 2곳 (직전 기간 원문 1건, +300%)"))
                .anyMatch(l -> l.startsWith("[아이템 종류] 데님 재킷(트러커) 유행점수 2.5 · 3건 · 출처 2곳 · 직전 1건(+200%) · 비중 75%(직전 100%, -25%p) / 패딩"));
        // 예시 원문: 상위 종류마다 최근 2건 — 데님 재킷(RT-1·RT-2), 패딩(RT-4). 하의·실패·직전 기간 원문은 빠진다
        assertThat(request.evidenceDocIds()).containsExactly("RT-1", "RT-2", "RT-4");
    }

    @Test
    @WithMockUser(username = "admin")
    void 종류를_콕_집으면_그_종류만_센다() throws Exception {
        when(aiClient.interpret(any(), any())).thenReturn(new AiClient.InterpretResponse(true, "TOP", "OUTER_PADDING", null, null, true, null));
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
                new AiClient.AnswerPeriod("2020-01-01", "2020-12-31", "2020년", true, false), null, true, null));

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
    void 이어_묻기는_앞_대화를_걸러_해석에_넘기고_다시_쓴_질문으로_답한다() throws Exception {
        when(aiClient.interpret(any(), any())).thenReturn(
                new AiClient.InterpretResponse(true, "BOTTOM", null, null, null, true, "요즘 유행하는 하의는?"));
        when(aiClient.answer(any(), any())).thenReturn(grounded("RT-5"));
        // 앞 대화 4건 — 최근 3건만 넘기고, 공통코드에 없는 값·잘못된 날짜는 버린다
        String body = """
                {"question": "그럼 하의는?", "history": [
                  {"question": "아주 옛날 질문", "trend": true, "category": "OUTER"},
                  {"question": "요즘 유행하는 신발은?", "trend": true, "category": "SHOES"},
                  {"question": "지난달 유행 아우터는?", "trend": true, "category": "OUTER", "periodLabel": "지난달", "periodFrom": "어제", "periodTo": "2026-09-30"},
                  {"question": "요즘 유행하는 아우터는?", "trend": true, "category": "OUTER", "itemType": "OUTER_PADDING", "periodLabel": "지난달(9월)", "periodFrom": "2026-09-01", "periodTo": "2026-09-30"}]}
                """;

        mockMvc.perform(post("/api/rag/ask").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question").value("그럼 하의는?")) // 화면에는 사용자가 쓴 질문
                .andExpect(jsonPath("$.interpretation.resolvedQuestion").value("요즘 유행하는 하의는?"))
                .andExpect(jsonPath("$.interpretation.category").value("BOTTOM"));

        ArgumentCaptor<AiClient.InterpretRequest> interpreted = ArgumentCaptor.forClass(AiClient.InterpretRequest.class);
        verify(aiClient).interpret(interpreted.capture(), eq("test-key"));
        List<AiClient.InterpretTurn> turns = interpreted.getValue().history();
        assertThat(turns).extracting(AiClient.InterpretTurn::question)
                .containsExactly("요즘 유행하는 신발은?", "지난달 유행 아우터는?", "요즘 유행하는 아우터는?");
        assertThat(turns.get(0).category()).isEmpty();      // 공통코드에 없는 카테고리
        assertThat(turns.get(1).periodLabel()).isEmpty();   // 잘못된 날짜면 기간을 통째로 뺀다
        assertThat(turns.get(2).itemType()).isEqualTo("OUTER_PADDING");
        assertThat(turns.get(2).periodFrom()).isEqualTo("2026-09-01");

        ArgumentCaptor<AiClient.AnswerRequest> answered = ArgumentCaptor.forClass(AiClient.AnswerRequest.class);
        verify(aiClient).answer(answered.capture(), eq("test-key"));
        assertThat(answered.getValue().question()).isEqualTo("요즘 유행하는 하의는?"); // 검색·답변은 다시 쓴 질문으로
    }

    @Test
    @WithMockUser(username = "admin")
    void 패션_질문이_아니면_집계도_답변도_하지_않는다() throws Exception {
        // "유행"이 들어 있어도 패션이 아니면(밈) 옷 원문을 세서 답하면 안 된다
        when(aiClient.interpret(any(), any())).thenReturn(new AiClient.InterpretResponse(true, null, null, null, null, false, null));

        mockMvc.perform(post("/api/rag/ask").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(question("요즘 유행하는 밈은?")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(false))
                .andExpect(jsonPath("$.reason").value("OFF_TOPIC"))
                .andExpect(jsonPath("$.interpretation.fashion").value(false))
                .andExpect(jsonPath("$.facts").doesNotExist())
                .andExpect(jsonPath("$.sources").isEmpty());
        verify(aiClient, never()).answer(any(), any());
    }

    @Test
    @WithMockUser(username = "admin")
    void 공통코드에_없는_해석_값은_버린다() throws Exception {
        when(aiClient.interpret(any(), any())).thenReturn(new AiClient.InterpretResponse(true, "SHOES", "SHOES_SNEAKER", null, null, null, null));
        when(aiClient.answer(any(), any())).thenReturn(grounded("RT-1"));

        mockMvc.perform(post("/api/rag/ask").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(question("요즘 유행?")))
                .andExpect(jsonPath("$.interpretation.category").doesNotExist())
                .andExpect(jsonPath("$.facts.scopeLabel").value("전체"))
                .andExpect(jsonPath("$.facts.docCount").value(5));
    }
}
