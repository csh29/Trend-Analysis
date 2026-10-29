package com.sjinc.trendanalysis.service.rag;

import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.domain.TrendItem;
import com.sjinc.trendanalysis.domain.TrendItemValue;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.repository.TrendItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 증감 순위는 이번·직전 모두 1건 이하인 값을 뺀다(2026-10-29 진단 C3 — 0→1 "신규"가 급상승으로 끼었다). */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:trendfactssmallsampletest")
class TrendFactsSmallSampleTest {

    private static final LocalDate TODAY = LocalDate.now();

    @Autowired
    private TrendFactsService trendFactsService;
    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;
    @Autowired
    private TrendItemRepository trendItemRepository;

    private static int seq;

    private void garment(String type, int daysAgo) {
        String docId = "SS-" + (++seq);
        SourceDocument doc = sourceDocumentRepository.save(SourceDocument.builder()
                .docType(SourceDocument.TYPE_IMAGE).docId(docId).title(docId + ".png").media("매체" + seq)
                .publishedAt(TODAY.minusDays(daysAgo)).status(SourceDocument.DONE).registeredBy("admin").registeredAt(LocalDateTime.now()).build());
        TrendItem item = TrendItem.builder().documentId(doc.getId()).category("OUTER")
                .summary(docId + " 요약").resultJson("{}").createdAt(LocalDateTime.now()).build();
        item.addGarmentValue(TrendItemValue.ITEM_TYPE, type, 0, 0, "OUTER");
        trendItemRepository.save(item);
    }

    @Test
    void 한_건짜리_신규는_급상승에서_빠진다() {
        for (int i = 0; i < 3; i++) garment("OUTER_PADDING", 1);
        for (int i = 0; i < 2; i++) garment("OUTER_COAT", 2);   // 신규 2건 — 남는다
        garment("OUTER_VEST", 3);                                // 신규 1건 — 빠진다
        garment("OUTER_PADDING", 40);                            // 직전 기간

        TrendFactsService.Facts up = trendFactsService.count(new QuestionInterpreter.Interpretation(true, "OUTER", "아우터", null, null, null,
                List.of(), List.of(), null, Map.of(), "UP"), TODAY);

        assertThat(up.dimensions().get(0).rows()).extracting(TrendFactsService.Row::value).containsExactly("OUTER_COAT");
        assertThat(up.lines()).anyMatch(l -> l.contains("이번·직전 모두 1건 이하인 값은 뺐다"));
    }
}
