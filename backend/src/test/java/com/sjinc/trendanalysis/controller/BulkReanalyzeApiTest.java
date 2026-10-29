package com.sjinc.trendanalysis.controller;

import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.service.analysis.AiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 일괄 재분석 — 선택한 원문을 한 번에 대기열에 넣고, 이미 분석 중인 원문은 건너뛴다. 한 번에 200건까지. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:bulkreanalyzeapitest")
@AutoConfigureMockMvc
class BulkReanalyzeApiTest {

    /** 대기열에 들어간 분석이 실제 AI 서비스를 부르지 않게 가짜로 바꿔 끼운다. */
    @MockBean
    private AiClient aiClient;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;

    private long text(String docId, String status) {
        return sourceDocumentRepository.save(SourceDocument.builder()
                .docType(SourceDocument.TYPE_TEXT).docId(docId).title(docId).body("본문").media("매체")
                .publishedAt(LocalDate.now()).status(status)
                .registeredBy("admin").registeredAt(LocalDateTime.now()).build()).getId();
    }

    @Test
    @WithMockUser(username = "admin")
    void 선택한_원문을_한_번에_다시_분석하고_분석_중인_것은_건너뛴다() throws Exception {
        long done = text("BR-1", SourceDocument.DONE);
        long failed = text("BR-2", SourceDocument.FAILED);
        long running = text("BR-3", SourceDocument.PROCESSING);

        mockMvc.perform(post("/api/texts/reanalyze").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("[" + done + "," + failed + "," + running + "]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(2))
                .andExpect(jsonPath("$.skipped.length()").value(1))
                .andExpect(jsonPath("$.skipped[0]").value("BR-3"));
    }

    @Test
    @WithMockUser(username = "admin")
    void 비었거나_너무_많으면_막는다() throws Exception {
        mockMvc.perform(post("/api/texts/reanalyze").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest());
        String many = IntStream.rangeClosed(1, 201).mapToObj(String::valueOf).collect(Collectors.joining(",", "[", "]"));
        mockMvc.perform(post("/api/images/reanalyze").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(many))
                .andExpect(status().isBadRequest());
    }
}
