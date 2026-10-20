package com.sjinc.trendanalysis.service.analysis;

import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** claude.api.key가 없으면 AI 서비스를 부르지 않고 바로 실패 사유를 남긴다(설정 누락을 화면에서 알 수 있게). */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:nokeytest", "claude.api.key="})
class AnalysisServiceNoKeyTest {

    @MockBean
    private AiClient aiClient;
    @Autowired
    private AnalysisService analysisService;
    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;

    @Test
    void 키가_없으면_호출하지_않고_실패한다() {
        SourceDocument doc = sourceDocumentRepository.save(SourceDocument.builder()
                .docType(SourceDocument.TYPE_TEXT).title("t").body("본문").media("직접 등록").publishedAt(LocalDate.now())
                .status(SourceDocument.PENDING).registeredBy("admin").registeredAt(LocalDateTime.now()).build());

        analysisService.analyze(doc.getId());

        SourceDocument failed = sourceDocumentRepository.findById(doc.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(SourceDocument.FAILED);
        assertThat(failed.getFailReason()).contains("claude.api.key");
        verify(aiClient, never()).structure(any(), any());
    }
}
