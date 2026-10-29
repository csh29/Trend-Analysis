package com.sjinc.trendanalysis.controller;

import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.domain.TrendItem;
import com.sjinc.trendanalysis.domain.TrendItemValue;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.repository.TrendItemRepository;
import com.sjinc.trendanalysis.service.analysis.AiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 분석 결과 조회 API — 분석이 끝난 원문만, 조회 조건(카테고리·종류)에 맞는 의류 아이템의 값만 보인다.
 * AR-1: 아우터 블랙 레더 재킷(오버사이즈) + 하의 블루 데님 팬츠(와이드, 스트라이프). AR-2: 아우터 브라운 코트. AR-9: 분석 실패.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:analysisresultapitest")
@AutoConfigureMockMvc
class AnalysisResultApiTest {

    @MockBean
    private AiClient aiClient;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;
    @Autowired
    private TrendItemRepository trendItemRepository;

    private static boolean seeded;

    @BeforeEach
    void seed() {
        if (seeded) return;
        seeded = true;
        TrendItem first = item(document("AR-1", 2, SourceDocument.DONE));
        first.addValue(TrendItemValue.KEYWORD, "올블랙", 0);
        first.addGarmentValue(TrendItemValue.ITEM_TYPE, "OUTER_LEATHER_JACKET", 1, 0, "OUTER");
        first.addGarmentValue(TrendItemValue.COLOR, "BLACK", 2, 0, "OUTER");
        first.addGarmentValue(TrendItemValue.SILHOUETTE, "OVERSIZED", 3, 0, "OUTER");
        first.addGarmentValue(TrendItemValue.ITEM_TYPE, "BOTTOM_DENIM", 4, 1, "BOTTOM");
        first.addGarmentValue(TrendItemValue.COLOR, "BLUE", 5, 1, "BOTTOM");
        first.addGarmentValue(TrendItemValue.PATTERN, "STRIPE", 6, 1, "BOTTOM");
        first.addGarmentValue(TrendItemValue.SILHOUETTE, "WIDE", 7, 1, "BOTTOM");
        trendItemRepository.save(first);
        TrendItem second = item(document("AR-2", 1, SourceDocument.DONE));
        second.addGarmentValue(TrendItemValue.ITEM_TYPE, "OUTER_COAT", 0, 0, "OUTER");
        second.addGarmentValue(TrendItemValue.COLOR, "BROWN", 1, 0, "OUTER");
        trendItemRepository.save(second);
        TrendItem failed = item(document("AR-9", 1, SourceDocument.FAILED));
        failed.addGarmentValue(TrendItemValue.ITEM_TYPE, "OUTER_COAT", 0, 0, "OUTER");
        trendItemRepository.save(failed);
    }

    private SourceDocument document(String docId, int daysAgo, String status) {
        return sourceDocumentRepository.save(SourceDocument.builder()
                .docType(SourceDocument.TYPE_TEXT).docId(docId).title(docId + " 제목").body("본문").media("매체")
                .publishedAt(LocalDate.now().minusDays(daysAgo)).status(status)
                .registeredBy("admin").registeredAt(LocalDateTime.now()).build());
    }

    private static TrendItem item(SourceDocument doc) {
        return TrendItem.builder().documentId(doc.getId()).category("OUTER")
                .summary(doc.getDocId() + " 요약").resultJson("{}").createdAt(LocalDateTime.now()).build();
    }

    @Test
    @WithMockUser(username = "admin")
    void 분석이_끝난_원문을_최근_것부터_보인다() throws Exception {
        mockMvc.perform(get("/api/analysis-results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].docId").value("AR-2"))
                .andExpect(jsonPath("$.rows[1].docId").value("AR-1"))
                .andExpect(jsonPath("$.rows[1].categories", hasSize(2)))
                .andExpect(jsonPath("$.rows[1].keywords[0]").value("올블랙"))
                .andExpect(jsonPath("$.rows[1].imageUrl").doesNotExist()) // 사진 없는 텍스트 원문
                .andExpect(jsonPath("$.total").value(2));
        // 페이지는 DB가 나눈다 — 한 건씩이면 둘째 페이지가 AR-1
        mockMvc.perform(get("/api/analysis-results").param("size", "1").param("page", "1"))
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].docId").value("AR-1"))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    @WithMockUser(username = "admin")
    void 조회_조건에_맞는_아이템의_값만_보인다() throws Exception {
        mockMvc.perform(get("/api/analysis-results").param("category", "BOTTOM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].docId").value("AR-1"))
                .andExpect(jsonPath("$.rows[0].categories[0]").value("하의"))
                .andExpect(jsonPath("$.rows[0].colors", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].colors[0]").value("블루"))
                .andExpect(jsonPath("$.rows[0].patterns[0]").value("스트라이프(줄무늬)"))
                .andExpect(jsonPath("$.rows[0].silhouettes[0]").value("와이드"));
        mockMvc.perform(get("/api/analysis-results").param("itemType", "OUTER_COAT"))
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].docId").value("AR-2"));
    }

    @Test
    @WithMockUser(username = "no-permission-user")
    void 권한이_없으면_막는다() throws Exception {
        mockMvc.perform(get("/api/analysis-results")).andExpect(status().isForbidden());
    }
}
