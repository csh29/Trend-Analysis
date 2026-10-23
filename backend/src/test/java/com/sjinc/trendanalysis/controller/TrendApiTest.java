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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P-05 트렌드 집계 API. 분석이 끝난 문서·트렌드 항목을 저장소에 바로 넣고(Claude 없이) 집계 → 조회 → 근거 원문을 확인한다.
 * 기준 주는 고정 날짜(2026-10-12 주)라 실행 날짜와 상관없다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:trendapitest")
@AutoConfigureMockMvc
class TrendApiTest {

    private static final LocalDate BASE = LocalDate.of(2026, 10, 12);

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
     * 레더: 전주 1건 → 이번 주 2건(+100%, 급상승), 데님: 이번 주 1건(신규), 분석 실패 문서는 세지 않는다.
     * TXT-T-1~4는 아이템 배열 이전 형식(값에 아이템 번호 없음)이다 — 재분석 전 데이터도 집계되는지 같이 본다.
     * TXT-T-5는 코디(상의 그레이 니트 카디건 + 하의 블랙 미니스커트)다.
     */
    @BeforeEach
    void seed() {
        if (seeded) return;
        seeded = true;
        doneDocument("TXT-T-1", BASE.minusWeeks(1).plusDays(2), "OUTER", "LEATHER", "레더 재킷");
        doneDocument("TXT-T-2", BASE.plusDays(1), "OUTER", "LEATHER", "레더  재킷"); // 공백 차이 — 같은 키워드
        doneDocument("TXT-T-3", BASE.plusDays(3), "OUTER", "LEATHER", "가죽 블루종");
        doneDocument("TXT-T-4", BASE.plusDays(4), "BOTTOM", "DENIM", "와이드 데님");
        outfitDocument("TXT-T-5", BASE.plusDays(5));
        SourceDocument failed = sourceDocumentRepository.save(SourceDocument.builder()
                .docType(SourceDocument.TYPE_TEXT).docId("TXT-T-9").title("실패").body("본문").media("샘플")
                .publishedAt(BASE.plusDays(1)).status(SourceDocument.FAILED)
                .registeredBy("admin").registeredAt(LocalDateTime.now()).build());
        TrendItem item = TrendItem.builder().documentId(failed.getId()).category("OUTER")
                .summary("요약").resultJson("{}").createdAt(LocalDateTime.now()).build();
        item.addValue(TrendItemValue.MATERIAL, "LEATHER", 0);
        trendItemRepository.save(item);
    }

    private void doneDocument(String docId, LocalDate publishedAt, String category, String material, String keyword) {
        SourceDocument doc = sourceDocumentRepository.save(SourceDocument.builder()
                .docType(SourceDocument.TYPE_TEXT).docId(docId).title(docId + " 제목").body("본문").media("샘플 매거진")
                .publishedAt(publishedAt).url("https://example.com/" + docId).status(SourceDocument.DONE)
                .registeredBy("admin").registeredAt(LocalDateTime.now()).build());
        TrendItem item = TrendItem.builder().documentId(doc.getId()).category(category)
                .summary(docId + " 요약").resultJson("{}").createdAt(LocalDateTime.now()).build();
        item.addValue(TrendItemValue.MATERIAL, material, 0);
        item.addValue(TrendItemValue.KEYWORD, keyword, 0);
        trendItemRepository.save(item);
    }

    private void outfitDocument(String docId, LocalDate publishedAt) {
        SourceDocument doc = sourceDocumentRepository.save(SourceDocument.builder()
                .docType(SourceDocument.TYPE_IMAGE).docId(docId).title(docId + " 제목").media("직접 업로드")
                .publishedAt(publishedAt).status(SourceDocument.DONE)
                .registeredBy("admin").registeredAt(LocalDateTime.now()).build());
        TrendItem item = TrendItem.builder().documentId(doc.getId()).category("TOP")
                .summary(docId + " 요약").resultJson("{}").createdAt(LocalDateTime.now()).build();
        item.addValue(TrendItemValue.KEYWORD, "캠퍼스룩", 0);
        item.addValue(TrendItemValue.SEASON, "FALL", 0);
        item.addGarmentValue(TrendItemValue.ITEM, "니트 카디건", 0, 0, "TOP");
        item.addGarmentValue(TrendItemValue.MATERIAL, "KNIT", 1, 0, "TOP");
        item.addGarmentValue(TrendItemValue.COLOR, "GRAY", 1, 0, "TOP");
        item.addGarmentValue(TrendItemValue.ITEM, "미니스커트", 0, 1, "BOTTOM");
        item.addGarmentValue(TrendItemValue.COLOR, "BLACK", 1, 1, "BOTTOM");
        trendItemRepository.save(item);
    }

    private void aggregate() throws Exception {
        mockMvc.perform(post("/api/trends/aggregate").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").value(5)); // 실패 문서 제외
    }

    @Test
    @WithMockUser(username = "admin")
    void 집계하면_기준_주의_언급량과_급상승_신규를_돌려준다() throws Exception {
        aggregate();

        mockMvc.perform(get("/api/trends").param("type", "MATERIAL").param("baseWeek", BASE.toString()).param("weeks", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseWeek").value(BASE.toString()))
                .andExpect(jsonPath("$.weeks", hasSize(4)))
                .andExpect(jsonPath("$.rows[0].value").value("LEATHER"))
                .andExpect(jsonPath("$.rows[0].label").value("레더(가죽)")) // 공통코드 이름
                .andExpect(jsonPath("$.rows[0].count").value(2))
                .andExpect(jsonPath("$.rows[0].prevCount").value(1))
                .andExpect(jsonPath("$.rows[0].prevRate").value(100.0))
                .andExpect(jsonPath("$.rows[0].surge").value(true))
                .andExpect(jsonPath("$.rows[1].value").value("DENIM"))
                .andExpect(jsonPath("$.rows[1].isNew").value(true))
                .andExpect(jsonPath("$.rows[1].prevRate").doesNotExist())
                .andExpect(jsonPath("$.surgeCount").value(1))
                .andExpect(jsonPath("$.newCount").value(2)) // 데님 + 코디의 니트
                .andExpect(jsonPath("$.series[0].label").value("레더(가죽)"))
                .andExpect(jsonPath("$.series[0].counts[2]").value(1))
                .andExpect(jsonPath("$.series[0].counts[3]").value(2));
    }

    @Test
    @WithMockUser(username = "admin")
    void 키워드는_공백_차이를_합쳐_세고_카테고리로_거른다() throws Exception {
        aggregate();

        mockMvc.perform(get("/api/trends").param("type", "KEYWORD").param("category", "OUTER")
                        .param("baseWeek", BASE.plusDays(3).toString()))  // 주 중간 날짜도 그 주 월요일로 맞춘다
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseWeek").value(BASE.toString()))
                .andExpect(jsonPath("$.rows[?(@.value == '레더 재킷')].count").value(1))
                .andExpect(jsonPath("$.rows[?(@.value == '와이드 데님')]").isEmpty());
    }

    @Test
    @WithMockUser(username = "admin")
    void 근거_원문은_기간_안의_분석_완료_문서를_최신순으로_준다() throws Exception {
        mockMvc.perform(get("/api/trends/evidence").param("type", "MATERIAL").param("value", "LEATHER")
                        .param("from", BASE.minusWeeks(1).toString()).param("to", BASE.plusDays(6).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))  // 실패 문서 제외
                .andExpect(jsonPath("$[0].docId").value("TXT-T-3"))
                .andExpect(jsonPath("$[0].summary").value("TXT-T-3 요약"))
                .andExpect(jsonPath("$[2].docId").value("TXT-T-1"));

        mockMvc.perform(get("/api/trends/evidence").param("type", "KEYWORD").param("value", "레더 재킷")
                        .param("from", BASE.toString()).param("to", BASE.plusDays(6).toString()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].docId").value("TXT-T-2"));
    }

    @Test
    @WithMockUser(username = "admin")
    void 코디_사진의_속성은_그_아이템의_카테고리로만_센다() throws Exception {
        aggregate();

        // 하의 컬러에는 블랙만 — 상의 카디건의 그레이가 섞이지 않는다
        mockMvc.perform(get("/api/trends").param("type", "COLOR").param("category", "BOTTOM").param("baseWeek", BASE.toString()))
                .andExpect(jsonPath("$.rows[?(@.value == 'BLACK')].count").value(1))
                .andExpect(jsonPath("$.rows[?(@.value == 'GRAY')]").isEmpty());
        // 카테고리 언급량: 한 문서가 상의·하의 모두에 잡힌다
        mockMvc.perform(get("/api/trends").param("type", "CATEGORY").param("baseWeek", BASE.toString()))
                .andExpect(jsonPath("$.rows[?(@.value == 'TOP')].count").value(1))
                .andExpect(jsonPath("$.rows[?(@.value == 'BOTTOM')].count").value(2)); // TXT-T-4 + 코디
        // 시즌은 문서 단위 — 전체 1건, 문서의 아이템 카테고리(상의·하의)마다 1건
        mockMvc.perform(get("/api/trends").param("type", "SEASON").param("baseWeek", BASE.toString()))
                .andExpect(jsonPath("$.rows[?(@.value == 'FALL')].count").value(1))
                .andExpect(jsonPath("$.rows[?(@.value == 'FALL')].label").value("가을"));
        mockMvc.perform(get("/api/trends").param("type", "SEASON").param("category", "BOTTOM").param("baseWeek", BASE.toString()))
                .andExpect(jsonPath("$.rows[?(@.value == 'FALL')].count").value(1));
        // 아이템명은 유형이 아니다(아이템 종류와 뜻이 겹쳐 뺐다)
        mockMvc.perform(get("/api/trends").param("type", "ITEM").param("baseWeek", BASE.toString()))
                .andExpect(status().isBadRequest());
        // 근거 원문도 같은 기준 — 하의 그레이는 없다
        mockMvc.perform(get("/api/trends/evidence").param("type", "COLOR").param("value", "GRAY").param("category", "BOTTOM")
                        .param("from", BASE.toString()).param("to", BASE.plusDays(6).toString()))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/trends/evidence").param("type", "COLOR").param("value", "GRAY").param("category", "TOP")
                        .param("from", BASE.toString()).param("to", BASE.plusDays(6).toString()))
                .andExpect(jsonPath("$[0].docId").value("TXT-T-5"));
    }

    @Test
    @WithMockUser(username = "admin")
    void 잘못된_유형은_400이다() throws Exception {
        mockMvc.perform(get("/api/trends").param("type", "PRICE"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/trends").param("type", "KEYWORD").param("weeks", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "nobody")
    void 권한이_없으면_403이다() throws Exception {
        mockMvc.perform(get("/api/trends")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/trends/aggregate").with(csrf())).andExpect(status().isForbidden());
    }
}
