package com.sjinc.trendanalysis.service.analysis;

import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.domain.TrendItem;
import com.sjinc.trendanalysis.domain.TrendItemValue;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.repository.TrendItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P-03·P-04 흐름 — AI 서비스(AiClient)를 가짜로 바꿔 끼우고 analyze를 직접 부른다(분석 스레드와 겹치지 않게
 * 문서는 저장소에 바로 넣는다). 공통코드는 DataInitializer가 넣은 초안을 쓴다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:analysistest",
        "claude.api.key=test-key",
        "claude.model=claude-sonnet-5",
        "ai.analysis.max-attempts=3"
})
class AnalysisServiceTest {

    @MockBean
    private AiClient aiClient;

    @Autowired
    private AnalysisService analysisService;
    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;
    @Autowired
    private TrendItemRepository trendItemRepository;

    private SourceDocument textDocument() {
        SourceDocument doc = sourceDocumentRepository.save(SourceDocument.builder()
                .docType(SourceDocument.TYPE_TEXT).title("가을 아우터").body("레더 재킷이 다시 유행한다.")
                .media("보그코리아").publishedAt(LocalDate.of(2026, 10, 1)).url("https://example.com/a")
                .status(SourceDocument.PENDING).registeredBy("admin").registeredAt(LocalDateTime.now()).build());
        doc.assignDocId("TXT-TEST-" + doc.getId());
        return sourceDocumentRepository.save(doc);
    }

    private static AiClient.StructureResponse response(Map<String, Object> result) {
        return new AiClient.StructureResponse("x", result, Map.of());
    }

    private static Map<String, Object> validResult() {
        Map<String, Object> result = new HashMap<>();
        result.put("category", "OUTER");
        result.put("keywords", List.of("레더 재킷"));
        result.put("materials", List.of("LEATHER"));
        result.put("colors", List.of("BROWN"));
        result.put("silhouettes", List.of());
        result.put("sentiment", "positive");
        result.put("summary", "레더 아우터가 주목받는다.");
        return result;
    }

    @Test
    void 성공하면_트렌드_항목을_저장하고_색인한다() {
        SourceDocument doc = textDocument();
        when(aiClient.structure(any(), eq("test-key"))).thenReturn(response(validResult()));

        analysisService.analyze(doc.getId());

        SourceDocument done = sourceDocumentRepository.findById(doc.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(SourceDocument.DONE);
        TrendItem item = trendItemRepository.findByDocumentId(doc.getId()).orElseThrow();
        assertThat(item.getCategory()).isEqualTo("OUTER");
        assertThat(item.valuesOf(TrendItemValue.MATERIAL)).containsExactly("LEATHER");
        // 정형 JSON에는 원문 문서의 출처가 붙는다(LLM이 만들지 않는다)
        assertThat(item.getResultJson()).contains("\"media\" : \"보그코리아\"").contains("\"doc_id\" : \"" + done.getDocId() + "\"");
        verify(aiClient).index(any());
    }

    @Test
    void 스키마_검증에_실패하면_다시_시도한다() {
        SourceDocument doc = textDocument();
        Map<String, Object> wrong = validResult();
        wrong.put("category", "UNKNOWN");
        when(aiClient.structure(any(), any())).thenReturn(response(wrong), response(validResult()));

        analysisService.analyze(doc.getId());

        SourceDocument done = sourceDocumentRepository.findById(doc.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(SourceDocument.DONE);
        assertThat(done.getAttempts()).isEqualTo(2);
    }

    @Test
    void 끝까지_검증에_실패하면_사유를_남기고_저장하지_않는다() {
        SourceDocument doc = textDocument();
        Map<String, Object> wrong = validResult();
        wrong.put("keywords", List.of());
        when(aiClient.structure(any(), any())).thenReturn(response(wrong));

        analysisService.analyze(doc.getId());

        SourceDocument failed = sourceDocumentRepository.findById(doc.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(SourceDocument.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(3);
        assertThat(failed.getFailReason()).contains("스키마 검증 실패");
        assertThat(trendItemRepository.findByDocumentId(doc.getId())).isEmpty();
        verify(aiClient, never()).index(any());
    }

    @Test
    void 재시도할_수_없는_오류면_바로_실패한다() {
        SourceDocument doc = textDocument();
        when(aiClient.structure(any(), any())).thenThrow(new AiCallException("Claude API 키가 올바르지 않습니다.", false));

        analysisService.analyze(doc.getId());

        SourceDocument failed = sourceDocumentRepository.findById(doc.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(SourceDocument.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(1);
        verify(aiClient, times(1)).structure(any(), any());
    }

    @Test
    void 색인에_실패하면_실패로_표시해_재분석하게_한다() {
        SourceDocument doc = textDocument();
        when(aiClient.structure(any(), any())).thenReturn(response(validResult()));
        when(aiClient.index(any())).thenThrow(new AiCallException("AI 서비스에 연결하지 못했습니다.", true));

        analysisService.analyze(doc.getId());

        SourceDocument failed = sourceDocumentRepository.findById(doc.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(SourceDocument.FAILED);
        assertThat(failed.getFailReason()).startsWith("색인(P-04) 실패");
    }

    @Test
    void 공통코드_허용값을_AI_서비스로_넘긴다() {
        SourceDocument doc = textDocument();
        when(aiClient.structure(any(), any())).thenAnswer(invocation -> {
            AiClient.StructureRequest request = invocation.getArgument(0);
            assertThat(request.model()).isEqualTo("claude-sonnet-5");
            assertThat(request.allowed().materials()).anyMatch(o -> o.value().equals("LEATHER") && o.name().contains("가죽"));
            assertThat(request.text()).contains("레더 재킷");
            return response(validResult());
        });

        analysisService.analyze(doc.getId());

        assertThat(sourceDocumentRepository.findById(doc.getId()).orElseThrow().getStatus()).isEqualTo(SourceDocument.DONE);
    }
}
