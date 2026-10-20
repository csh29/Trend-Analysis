package com.sjinc.trendanalysis.config;

import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.service.analysis.AiClient;
import com.sjinc.trendanalysis.service.trend.TrendAggregator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** --trend.sample.load=true면 샘플 텍스트를 최근 6주 날짜로 한 번만 넣는다. 분석은 가짜 AiClient라 Claude를 부르지 않는다. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:sampleloadertest", "trend.sample.load=true"})
class SampleDataLoaderTest {

    @MockBean
    private AiClient aiClient;

    @Autowired
    private SampleDataLoader loader;
    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;

    private List<SourceDocument> samples() {
        return sourceDocumentRepository.findAll().stream()
                .filter(doc -> doc.getTitle().startsWith(SampleDataLoader.TITLE_PREFIX)).toList();
    }

    @Test
    void 기동하면_샘플을_최근_6주_날짜로_넣고_다시_돌려도_중복으로_넣지_않는다() throws Exception {
        List<SourceDocument> first = samples();
        LocalDate today = LocalDate.now();
        LocalDate oldest = TrendAggregator.weekStart(today).minusWeeks(5);

        assertThat(first).hasSizeGreaterThanOrEqualTo(30);
        assertThat(first).allSatisfy(doc -> {
            assertThat(doc.getPublishedAt()).isBetween(oldest, today);
            assertThat(doc.getRegisteredBy()).isEqualTo(SampleDataLoader.REGISTERED_BY);
            assertThat(doc.getDocType()).isEqualTo(SourceDocument.TYPE_TEXT);
        });

        loader.run(new DefaultApplicationArguments());

        assertThat(samples()).hasSameSizeAs(first);
    }

    @Test
    void 샘플_분석은_공통코드가_심어진_뒤에_돈다() throws Exception {
        // 분석 스레드가 끝날 때까지 기다린다(가짜 AiClient라 금방 실패로 끝난다)
        for (int i = 0; i < 100 && samples().stream().anyMatch(d -> !SourceDocument.FAILED.equals(d.getStatus())); i++) {
            Thread.sleep(200);
        }
        // 실패 사유가 "공통코드 없음"이면 DataInitializer보다 먼저 돌았다는 뜻이다
        assertThat(samples()).noneSatisfy(doc ->
                assertThat(doc.getFailReason()).contains("공통코드가 없는 그룹"));
    }
}
