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
 * 매체 조건(2026-10-30 — "엘르코리아 패션 특징은?"이 검색으로 가 근거 없음이 됐다. 매체명은 색인 글에 없다).
 * filters의 MEDIA는 옷 속성이 아니라 원문의 매체명으로 거른다. 최근 패딩: 엘르코리아 2건(블랙) + 보그코리아 1건(브라운) + 직접 업로드 1건.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:trendfactsmediatest")
class TrendFactsMediaTest {

    private static final LocalDate TODAY = LocalDate.now();

    @Autowired
    private TrendFactsService trendFactsService;
    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;
    @Autowired
    private TrendItemRepository trendItemRepository;

    private void padding(String docId, String media, String color) {
        SourceDocument doc = sourceDocumentRepository.save(SourceDocument.builder()
                .docType(SourceDocument.TYPE_TEXT).docId(docId).title(docId).body("본문").media(media)
                .publishedAt(TODAY.minusDays(1)).status(SourceDocument.DONE).registeredBy("admin").registeredAt(LocalDateTime.now()).build());
        TrendItem item = TrendItem.builder().documentId(doc.getId()).category("OUTER")
                .summary(docId + " 요약").resultJson("{}").createdAt(LocalDateTime.now()).build();
        item.addGarmentValue(TrendItemValue.ITEM_TYPE, "OUTER_PADDING", 0, 0, "OUTER");
        item.addGarmentValue(TrendItemValue.COLOR, color, 1, 0, "OUTER");
        trendItemRepository.save(item);
    }

    @Test
    void 매체_조건이면_그_매체_원문만_센다() {
        padding("MD-1", "엘르코리아", "BLACK");
        padding("MD-2", "엘르코리아", "BLACK");
        padding("MD-3", "보그코리아", "BROWN");
        padding("MD-4", "직접 업로드", "BROWN");

        TrendFactsService.Facts facts = trendFactsService.count(new QuestionInterpreter.Interpretation(true, "OUTER", "아우터",
                null, null, null, List.of(), List.of(), null, Map.of(TrendFactsService.MEDIA, List.of("엘르코리아")), null), TODAY);

        assertThat(facts.docCount()).isEqualTo(2);
        assertThat(facts.scopeLabel()).contains("엘르코리아 원문");
        assertThat(facts.coverage()).noneMatch(l -> l.startsWith("[조건 정보]")); // 매체는 옷 속성이 아니라 속성 값 없는 원문 수를 세지 않는다
        assertThat(facts.dimensions()).anyMatch(d -> d.rows().stream().anyMatch(r -> r.value().equals("BLACK") && r.count() == 2))
                .noneMatch(d -> d.rows().stream().anyMatch(r -> r.value().equals("BROWN")));
        assertThat(facts.evidenceDocIds()).allMatch(id -> id.equals("MD-1") || id.equals("MD-2"));
    }

    @Test
    void 매체_줄임말은_뒤의_코리아를_뗀_이름이다() {
        assertThat(RagService.mediaAliases("엘르코리아")).containsExactly("엘르코리아", "엘르");
        assertThat(RagService.mediaAliases("GQ Korea")).containsExactly("gqkorea", "gq");
        assertThat(RagService.mediaAliases("W Korea")).containsExactly("wkorea"); // 한 글자 줄임말은 다른 말에 걸린다
    }
}
