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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기타 종류(OUTER_ETC)는 목록에 없는 옷이 한데 모인 것이라, 그 안의 아이템명 상위를 같이 세어 넘기는지 본다(2026-10-23).
 * 최근 4주: 기타 아우터 4건(퀼팅 재킷 2 — 공백만 다른 표기 포함, 케이프 1, 사파리 재킷 1), 데님 재킷 1건.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:trendfactsetctest")
class TrendFactsEtcTest {

    private static final LocalDate TODAY = LocalDate.now();

    @Autowired
    private TrendFactsService trendFactsService;
    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;
    @Autowired
    private TrendItemRepository trendItemRepository;

    private void garment(String docId, String type, String name) {
        SourceDocument doc = sourceDocumentRepository.save(SourceDocument.builder()
                .docType(SourceDocument.TYPE_IMAGE).docId(docId).title(docId + ".png").media("매체A")
                .publishedAt(TODAY.minusDays(1)).status(SourceDocument.DONE).registeredBy("admin").registeredAt(LocalDateTime.now()).build());
        TrendItem item = TrendItem.builder().documentId(doc.getId()).category("OUTER")
                .summary(docId + " 요약").resultJson("{}").createdAt(LocalDateTime.now()).build();
        item.addGarmentValue(TrendItemValue.ITEM, name, 0, 0, "OUTER");
        item.addGarmentValue(TrendItemValue.ITEM_TYPE, type, 0, 0, "OUTER");
        trendItemRepository.save(item);
    }

    @Test
    void 기타_종류는_그_안의_아이템명_상위를_같이_센다() {
        garment("ETC-1", "OUTER_ETC", "퀼팅 재킷");
        garment("ETC-2", "OUTER_ETC", " 퀼팅  재킷 ");
        garment("ETC-3", "OUTER_ETC", "케이프");
        garment("ETC-4", "OUTER_ETC", "사파리 재킷");
        garment("ETC-5", "OUTER_DENIM_JACKET", "트러커 재킷");

        TrendFactsService.Facts facts = trendFactsService.count(
                new QuestionInterpreter.Interpretation(true, "OUTER", "아우터", null, null, null), TODAY);

        TrendFactsService.Dimension types = facts.dimensions().get(0);
        TrendFactsService.Row etc = types.rows().get(0);
        assertThat(etc.value()).isEqualTo("OUTER_ETC");
        assertThat(etc.count()).isEqualTo(4);
        // 공백만 다른 표기는 합치고, 원문 수 순 → 같으면 이름 순, 상위 3개
        assertThat(etc.names()).containsExactly(new TrendFactsService.NameCount("퀼팅 재킷", 2),
                new TrendFactsService.NameCount("사파리 재킷", 1), new TrendFactsService.NameCount("케이프", 1));
        assertThat(types.rows().get(1).names()).isEmpty(); // 기타가 아닌 종류는 아이템명을 붙이지 않는다

        List<String> lines = facts.lines();
        // 증감 질문이 아니면 직전·비중은 싣지 않고, 1건짜리 아이템명은 뺀다(2026-10-30)
        assertThat(lines).anyMatch(l -> l.contains("기타 아우터 유행점수 2.5 · 4건 (아이템명: 퀼팅 재킷 2건)"));
        assertThat(lines).noneMatch(l -> l.contains("직전") || l.contains("비중") || l.contains("[수집량 주의]"));
    }
}
