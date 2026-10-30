package com.sjinc.trendanalysis.controller;

import com.sjinc.trendanalysis.config.DataInitializer;
import com.sjinc.trendanalysis.domain.Program;
import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.domain.UserProgramPermission;
import com.sjinc.trendanalysis.repository.ProgramRepository;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.repository.UserProgramPermissionRepository;
import com.sjinc.trendanalysis.repository.UserRepository;
import com.sjinc.trendanalysis.service.analysis.AiCallException;
import com.sjinc.trendanalysis.service.analysis.AiClient;
import com.sjinc.trendanalysis.service.rag.SearchSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P-06 RAG 질문 API(홈 질문창). 검색·답변은 가짜 AiClient가 돌려주고, 여기서는 출처를 원문으로 되돌리는 규칙을 본다. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:ragapitest", "claude.api.key=test-key",
        "image.upload.dir=target/test-uploads-rag"})
@AutoConfigureMockMvc
class RagApiTest {

    @MockBean
    private AiClient aiClient;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;
    @Autowired
    private ProgramRepository programRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserProgramPermissionRepository permissionRepository;
    @Autowired
    private DataInitializer dataInitializer;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static boolean seeded;

    @BeforeEach
    void seed() {
        if (seeded) return;
        seeded = true;
        document("TXT-R-1", SourceDocument.DONE);
        document("TXT-R-2", SourceDocument.PROCESSING); // 재분석 중 — 출처로 내보내지 않는다
    }

    private void document(String docId, String status) {
        sourceDocumentRepository.save(SourceDocument.builder()
                .docType(SourceDocument.TYPE_TEXT).docId(docId).title(docId + " 제목").body("본문").media("샘플 매거진")
                .publishedAt(LocalDate.of(2026, 10, 14)).url("https://example.com/" + docId).status(status)
                .registeredBy("admin").registeredAt(LocalDateTime.now()).build());
    }

    private static String question(String text) {
        return "{\"question\":\"" + text + "\"}";
    }

    @Test
    @WithMockUser(username = "admin")
    void 답변의_출처는_분석_완료된_원문만_돌려준다() throws Exception {
        when(aiClient.answer(any(), eq("test-key"))).thenReturn(new AiClient.AnswerResponse(true, "와이드 팬츠가 많다.",
                List.of(new AiClient.AnswerPoint("트랙 팬츠: 블랙 와이드가 반복된다.", List.of(1, 2)),
                        new AiClient.AnswerPoint("레더 카고 팬츠도 보인다.", List.of(2)), // 빠진 출처만 인용 — 줄째 뺀다
                        new AiClient.AnswerPoint("키워드 와이드 팬츠가 급상승이다.", List.of())),
                List.of(new AiClient.AnswerSource(1, "TXT-R-1", 0.71, "와이드 팬츠가 유행"),
                        new AiClient.AnswerSource(2, "TXT-R-2", 0.65, "다른 청크")),
                0.71, Map.of("input_tokens", 100, "output_tokens", 20), null,
                new AiClient.AnswerPeriod("2026-10-14", "2026-10-20", "최근 자료 우선", false, false)));

        mockMvc.perform(post("/api/rag/ask").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(question(" 와이드 팬츠 코디? ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question").value("와이드 팬츠 코디?"))
                // 해석 AI(가짜 AiClient)가 답하지 않으면 규칙으로 해석한다 — 유행 질문이 아니라 문장 검색
                .andExpect(jsonPath("$.interpretation.by").value("RULE"))
                .andExpect(jsonPath("$.interpretation.trend").value(false))
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.sources", hasSize(1)))
                .andExpect(jsonPath("$.sources[0].n").value(1))
                .andExpect(jsonPath("$.sources[0].title").value("TXT-R-1 제목"))
                .andExpect(jsonPath("$.sources[0].media").value("샘플 매거진"))
                .andExpect(jsonPath("$.sources[0].statDate").value("2026-10-14"))
                .andExpect(jsonPath("$.sources[0].url").value("https://example.com/TXT-R-1"))
                .andExpect(jsonPath("$.minScore").value(0.1)) // 리랭킹이 기본으로 켜져 있다(공통코드 RAG_SEARCH) — 기준은 리랭커 점수
                .andExpect(jsonPath("$.reranked").value(true))
                // 결론 한 줄 + 글머리 줄. 재분석 중이라 빠진 출처(2번)는 줄의 인용 번호에서도 빼고, 그 출처만 인용한 줄은 뺀다(확인할 수 없는 문장)
                .andExpect(jsonPath("$.answer").value("와이드 팬츠가 많다."))
                .andExpect(jsonPath("$.points", hasSize(2)))
                .andExpect(jsonPath("$.points[0].text").value("트랙 팬츠: 블랙 와이드가 반복된다."))
                .andExpect(jsonPath("$.points[0].sources", hasSize(1)))
                .andExpect(jsonPath("$.points[0].sources[0]").value(1))
                .andExpect(jsonPath("$.points[1].sources", hasSize(0)))
                .andExpect(jsonPath("$.period.label").value("최근 자료 우선"))
                .andExpect(jsonPath("$.period.from").value("2026-10-14"))
                .andExpect(jsonPath("$.period.explicit").value(false))
                .andExpect(jsonPath("$.period.stale").value(false));

        ArgumentCaptor<AiClient.AnswerRequest> sent = ArgumentCaptor.forClass(AiClient.AnswerRequest.class);
        verify(aiClient).answer(sent.capture(), eq("test-key"));
        assertThat(sent.getValue().minScore()).isEqualTo(0.10);
        assertThat(sent.getValue().rerank()).isTrue();
        assertThat(sent.getValue().model()).isEqualTo("claude-sonnet-5");
        assertThat(sent.getValue().today()).isEqualTo(LocalDate.now().toString()); // 최신성·기간의 기준일 — "2026-10-22" 꼴 문자열
    }

    @Test
    @WithMockUser(username = "admin")
    void 리랭킹을_끄면_유사도_기준으로_묻는다() throws Exception {
        when(aiClient.answer(any(), any())).thenReturn(new AiClient.AnswerResponse(false, null, List.of(), List.of(), 0.41, null, "NO_EVIDENCE",
                new AiClient.AnswerPeriod("2026-10-12", "2026-10-18", "지난주", true, false)));
        jdbcTemplate.update("UPDATE COM_CD_DTL SET use_yn = 'N' WHERE code_group = ? AND code_value = ?", SearchSettings.GROUP, SearchSettings.RERANK);
        try {
            mockMvc.perform(post("/api/rag/ask").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(question("레더 재킷 코디")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.minScore").value(0.6))
                    .andExpect(jsonPath("$.reranked").value(false));
            ArgumentCaptor<AiClient.AnswerRequest> sent = ArgumentCaptor.forClass(AiClient.AnswerRequest.class);
            verify(aiClient, org.mockito.Mockito.atLeastOnce()).answer(sent.capture(), any());
            assertThat(sent.getValue().rerank()).isFalse();
            assertThat(sent.getValue().minScore()).isEqualTo(0.60); // 설정 rag.min-score
        } finally {
            jdbcTemplate.update("UPDATE COM_CD_DTL SET use_yn = 'Y' WHERE code_group = ? AND code_value = ?", SearchSettings.GROUP, SearchSettings.RERANK);
        }
    }

    @Test
    @WithMockUser(username = "admin")
    void 근거가_기준_미달이면_근거_자료_없음() throws Exception {
        when(aiClient.answer(any(), any())).thenReturn(new AiClient.AnswerResponse(false, null, List.of(), List.of(), 0.41, null, "NO_EVIDENCE",
                new AiClient.AnswerPeriod("2026-10-12", "2026-10-18", "지난주", true, false)));

        mockMvc.perform(post("/api/rag/ask").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(question("오늘 날씨")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(false))
                .andExpect(jsonPath("$.reason").value("NO_EVIDENCE"))
                .andExpect(jsonPath("$.answer").value("근거 자료 없음 — 지난주(2026-10-12 ~ 2026-10-18)에 질문과 충분히 비슷한 원문이 없습니다."))
                .andExpect(jsonPath("$.bestScore").value(0.41))
                .andExpect(jsonPath("$.sources", hasSize(0)))
                .andExpect(jsonPath("$.points", hasSize(0)));
    }

    @Test
    @WithMockUser(username = "admin")
    void 인용한_원문이_모두_없으면_답을_내보내지_않는다() throws Exception {
        when(aiClient.answer(any(), any())).thenReturn(new AiClient.AnswerResponse(true, "답",
                List.of(new AiClient.AnswerPoint("줄", List.of(1))),
                List.of(new AiClient.AnswerSource(1, "TXT-R-2", 0.7, "청크")), 0.7, null, null, null));

        mockMvc.perform(post("/api/rag/ask").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(question("질문")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(false))
                .andExpect(jsonPath("$.reason").value("SOURCE_MISSING"));
    }

    @Test
    @WithMockUser(username = "admin")
    void 빈_질문은_AI를_부르지_않고_400() throws Exception {
        mockMvc.perform(post("/api/rag/ask").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(question("   ")))
                .andExpect(status().isBadRequest());
        verify(aiClient, never()).answer(any(), any());
    }

    @Test
    @WithMockUser(username = "admin")
    void AI_서비스_실패는_502와_일반_안내_서버_정보는_숨긴다() throws Exception {
        // 실제 연결 실패 사유에는 AI 서비스 주소와 실행 스크립트가 들어 있다 — 화면(챗봇)에 그대로 보이면 안 된다
        when(aiClient.answer(any(), any())).thenThrow(new AiCallException(
                "AI 서비스(http://127.0.0.1:8001)에 연결하지 못했습니다. ai 폴더의 start-ai.cmd로 서비스를 띄웠는지 확인하세요.", true));
        mockMvc.perform(post("/api/rag/ask").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(question("질문")))
                .andExpect(status().isBadGateway())
                .andExpect(content().string("AI 서비스가 잠시 응답하지 않습니다. 잠시 후 다시 시도해 주세요."));

        // 다시 해도 안 되는 실패(키 오류 등)도 설정 이름을 숨긴다
        org.mockito.Mockito.doThrow(new AiCallException("Claude API 키가 올바르지 않습니다(claude.api.key).", false))
                .when(aiClient).answer(any(), any());
        mockMvc.perform(post("/api/rag/ask").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(question("질문")))
                .andExpect(status().isBadGateway())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("claude.api.key"))));
    }

    @Test
    @WithMockUser(username = "nobody")
    void 홈_질문창이라_프로그램_권한_없이_로그인만으로_묻는다() throws Exception {
        when(aiClient.answer(any(), any())).thenReturn(new AiClient.AnswerResponse(false, null, List.of(), List.of(), 0.3, null, "NO_EVIDENCE", null));

        mockMvc.perform(post("/api/rag/ask").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(question("질문")))
                .andExpect(status().isOk());
    }

    /** 이미지 업로드로 이미지 원문을 만들고 분석 완료로 바꾼다(분석은 가짜 AiClient라 실패하므로 상태를 직접 맞춘다). */
    private String doneImageDocument() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R', 7, 7};
        String body = mockMvc.perform(multipart("/api/images").file(new MockMultipartFile("files", "look.png", "image/png", png))
                        .with(csrf()).with(user("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String docId = JsonPath.read(body, "$.uploaded[0].analysis.docId");
        // 업로드 직후 분석 스레드가 상태를 바꾸므로, 끝난 뒤 완료로 맞춘다
        for (int i = 0; i < 50 && !SourceDocument.FAILED.equals(sourceDocumentRepository.findByDocId(docId).orElseThrow().getStatus()); i++) {
            Thread.sleep(100);
        }
        SourceDocument doc = sourceDocumentRepository.findByDocId(docId).orElseThrow();
        doc.markDone();
        sourceDocumentRepository.save(doc);
        return docId;
    }

    @Test
    @WithMockUser(username = "nobody")
    void 이미지_출처는_패널_주소로_주고_로그인만으로_사진을_연다() throws Exception {
        String docId = doneImageDocument();
        when(aiClient.answer(any(), any())).thenReturn(new AiClient.AnswerResponse(true, "결론",
                List.of(new AiClient.AnswerPoint("줄", List.of(1, 2))),
                List.of(new AiClient.AnswerSource(1, docId, 0.7, "사진 요약"),
                        new AiClient.AnswerSource(2, "TXT-R-1", 0.65, "텍스트")), 0.7, null, null, null));

        mockMvc.perform(post("/api/rag/ask").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(question("질문")))
                .andExpect(status().isOk())
                // 이미지 원문: 새 탭 링크 대신 패널 주소, 텍스트 원문: 원문 링크(새 탭)
                .andExpect(jsonPath("$.sources[0].imageUrl").value("/api/rag/sources/" + docId + "/image"))
                .andExpect(jsonPath("$.sources[0].url").doesNotExist())
                .andExpect(jsonPath("$.sources[1].url").value("https://example.com/TXT-R-1"))
                .andExpect(jsonPath("$.sources[1].imageUrl").doesNotExist());

        // 이미지 업로드 권한이 없는 사용자(nobody)도 홈 출처 사진은 연다
        mockMvc.perform(get("/api/rag/sources/" + docId + "/image"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"));
    }

    @Test
    @WithMockUser(username = "nobody")
    void 텍스트_원문이나_분석_전_원문은_사진으로_열지_않는다() throws Exception {
        mockMvc.perform(get("/api/rag/sources/TXT-R-1/image")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/rag/sources/NO-SUCH/image")).andExpect(status().isBadRequest());
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        mockMvc.perform(post("/api/rag/ask").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(question("질문")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 잠깐_등록됐던_RAG_검색_프로그램은_기동_때_지운다() throws Exception {
        Program program = programRepository.save(Program.builder().programId("rag-search").programNm("RAG 검색")
                .url("/program/rag-search").sortOrder(4).useYn("Y").menuGroup(Program.GROUP_TREND)
                .searchYn("N").newYn("N").saveYn("N").deleteYn("N").resetYn("Y").build());
        userRepository.findByUsername("admin").ifPresent(admin -> permissionRepository.save(UserProgramPermission.builder()
                .user(admin).program(program).searchYn("Y").newYn("Y").saveYn("Y").deleteYn("Y").resetYn("Y")
                .etc1Yn("Y").etc2Yn("Y").etc3Yn("Y").etc4Yn("Y").etc5Yn("Y").build()));

        dataInitializer.run();

        assertThat(programRepository.existsById("rag-search")).isFalse();
    }
}
