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

/**
 * 출처 수는 유행점수와 같은 기준으로 센다(2026-10-29 진단 C1) — 매체명 그대로 세면 직접 업로드가 모두 한 곳이 되어
 * "유행점수는 높은데 1개 매체"로 어긋났다. 최근 4주 패딩: 직접 업로드 3건 + 보그 2건.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:trendfactssourcetest")
class TrendFactsSourceTest {

    private static final LocalDate TODAY = LocalDate.now();

    @Autowired
    private TrendFactsService trendFactsService;
    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;
    @Autowired
    private TrendItemRepository trendItemRepository;

    private void padding(String docId, String media) {
        SourceDocument doc = sourceDocumentRepository.save(SourceDocument.builder()
                .docType(SourceDocument.TYPE_IMAGE).docId(docId).title(docId + ".png").media(media)
                .publishedAt(TODAY.minusDays(1)).status(SourceDocument.DONE).registeredBy("admin").registeredAt(LocalDateTime.now()).build());
        TrendItem item = TrendItem.builder().documentId(doc.getId()).category("OUTER")
                .summary(docId + " 요약").resultJson("{}").createdAt(LocalDateTime.now()).build();
        item.addGarmentValue(TrendItemValue.ITEM_TYPE, "OUTER_PADDING", 0, 0, "OUTER");
        trendItemRepository.save(item);
    }

    @Test
    void 직접_업로드는_원문마다_다른_출처로_센다() {
        padding("SRC-1", "직접 업로드");
        padding("SRC-2", "직접 업로드");
        padding("SRC-3", "직접 업로드");
        padding("SRC-4", "보그");
        padding("SRC-5", "보그");

        TrendFactsService.Facts facts = trendFactsService.count(new QuestionInterpreter.Interpretation(true, "OUTER", "아우터",
                null, null, null, List.of(), List.of(), null, Map.of(), null), TODAY);

        assertThat(facts.mediaCount()).isEqualTo(4); // 업로드 3곳 + 보그 1곳
        TrendFactsService.Row padding = facts.dimensions().get(0).rows().get(0);
        assertThat(padding.mediaCount()).isEqualTo(4);
        assertThat(padding.score()).isEqualTo(TrendFactsService.trendScore(5, 4)); // 출처 수와 점수가 같은 기준
        assertThat(facts.lines()).anyMatch(l -> l.contains("유행점수 4.5 · 5건")); // 증감 질문이 아니면 출처 수는 싣지 않는다
    }
}
