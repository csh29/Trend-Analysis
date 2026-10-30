package com.sjinc.trendanalysis.controller;

import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.domain.TrendItem;
import com.sjinc.trendanalysis.domain.TrendItemValue;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.repository.TrendItemRepository;
import com.sjinc.trendanalysis.service.analysis.AiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 트렌드 주 마감(2026-10-30 사용자 요청 — ERP 마감처럼). 마감 → 늦게 온 원문은 첫 열린 주에 → 마감 취소(사유 필수) → 그 주부터 다시 집계.
 * 한 흐름을 한 테스트로 본다(마감 상태가 테스트끼리 섞이지 않게 DB도 따로 쓴다). 기준 주는 고정 날짜라 실행 날짜와 상관없다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:trendclosetest")
@AutoConfigureMockMvc
class TrendCloseApiTest {

    private static final LocalDate BASE = LocalDate.of(2026, 10, 12);
    private static final LocalDate PREV = BASE.minusWeeks(1);
    private static final LocalDate NEXT = BASE.plusWeeks(1);

    @MockBean
    private AiClient aiClient;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;
    @Autowired
    private TrendItemRepository trendItemRepository;

    private void leatherDocument(String docId, LocalDate publishedAt) {
        SourceDocument doc = sourceDocumentRepository.save(SourceDocument.builder()
                .docType(SourceDocument.TYPE_TEXT).docId(docId).title(docId + " 제목").body("본문").media("샘플 매거진")
                .publishedAt(publishedAt).status(SourceDocument.DONE)
                .registeredBy("admin").registeredAt(LocalDateTime.now()).build());
        TrendItem item = TrendItem.builder().documentId(doc.getId()).category("OUTER")
                .summary(docId + " 요약").resultJson("{}").createdAt(LocalDateTime.now()).build();
        item.addValue(TrendItemValue.MATERIAL, "LEATHER", 0);
        trendItemRepository.save(item);
    }

    private ResultActions leather(LocalDate week) throws Exception {
        return mockMvc.perform(get("/api/trends").param("type", "MATERIAL").param("baseWeek", week.toString()).param("weeks", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].value").value("LEATHER"));
    }

    @Test
    @WithMockUser(username = "admin")
    void 마감한_주는_다시_세지_않고_늦게_온_원문은_첫_열린_주에_센다() throws Exception {
        leatherDocument("TXT-C-1", PREV.plusDays(1));
        leatherDocument("TXT-C-2", BASE.plusDays(2));
        mockMvc.perform(post("/api/trends/aggregate").with(csrf())).andExpect(status().isOk());

        // BASE 주까지 마감 — 앞의 열린 주(PREV)도 같이, 오래된 주부터
        mockMvc.perform(post("/api/trends/closes").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weekStart\":\"" + BASE.plusDays(3) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0]").value(PREV.toString()))
                .andExpect(jsonPath("$[1]").value(BASE.toString()));
        mockMvc.perform(get("/api/trends/closes"))
                .andExpect(jsonPath("$.lastClosedWeek").value(BASE.toString()))
                .andExpect(jsonPath("$.openFrom").value(NEXT.toString()))
                .andExpect(jsonPath("$.closes[0].docCount").value(1))
                .andExpect(jsonPath("$.logs[0].action").value("CLOSE"))
                .andExpect(jsonPath("$.logs[0].actor").value("admin"));
        // 이미 마감한 주·이번 주는 마감할 수 없다
        mockMvc.perform(post("/api/trends/closes").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weekStart\":\"" + PREV + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/trends/closes").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weekStart\":\"" + LocalDate.now() + "\"}"))
                .andExpect(status().isBadRequest());

        // 마감된 주를 보며 집계를 누르면 거부한다(열린 주를 보면 집계한다)
        mockMvc.perform(post("/api/trends/aggregate").param("weekStart", BASE.toString()).with(csrf()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/trends/aggregate").param("weekStart", NEXT.toString()).with(csrf()))
                .andExpect(status().isOk());

        // 마감된 PREV 날짜로 늦게 온 원문 — PREV·BASE 수치는 그대로, 첫 열린 주(NEXT)에 센다
        leatherDocument("TXT-C-3", PREV.plusDays(4));
        mockMvc.perform(post("/api/trends/aggregate").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").value(1)); // 열린 원문만 센다
        leather(BASE).andExpect(jsonPath("$.closed").value(true))
                .andExpect(jsonPath("$.rows[0].count").value(1));
        leather(NEXT).andExpect(jsonPath("$.closed").value(false))
                .andExpect(jsonPath("$.rows[0].count").value(1))
                .andExpect(jsonPath("$.rows[0].prevCount").value(1)); // 전주는 마감 확정값
        mockMvc.perform(get("/api/trends/evidence").param("type", "MATERIAL").param("value", "LEATHER")
                        .param("from", NEXT.toString()).param("to", NEXT.plusDays(6).toString()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].docId").value("TXT-C-3"))
                .andExpect(jsonPath("$[0].statDate").value(PREV.plusDays(4).toString()))
                .andExpect(jsonPath("$[0].statWeek").value(NEXT.toString()));
        mockMvc.perform(get("/api/trends/evidence").param("type", "MATERIAL").param("value", "LEATHER")
                        .param("from", PREV.toString()).param("to", PREV.plusDays(6).toString()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].docId").value("TXT-C-1")); // 마감 때 고정한 원문만

        // 마감 취소 — 사유 필수, 가장 최근 마감 주(BASE)부터. 취소한 주는 다시 세고 늦게 온 원문도 그 주로 온다
        mockMvc.perform(post("/api/trends/closes/cancel").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/trends/closes/cancel").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"원문 누락 보정\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(BASE.toString()));
        leather(BASE).andExpect(jsonPath("$.closed").value(false))
                .andExpect(jsonPath("$.rows[0].count").value(2));
        mockMvc.perform(get("/api/trends/closes"))
                .andExpect(jsonPath("$.lastClosedWeek").value(PREV.toString()))
                .andExpect(jsonPath("$.logs[0].action").value("CANCEL"))
                .andExpect(jsonPath("$.logs[0].reason").value("원문 누락 보정"));
    }

    @Test
    @WithMockUser(username = "nobody")
    void 권한이_없으면_마감할_수_없다() throws Exception {
        mockMvc.perform(post("/api/trends/closes").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weekStart\":\"" + BASE + "\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/trends/closes/cancel").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
    }
}
