package com.sjinc.trendanalysis.service.rag;

import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.domain.TrendItem;
import com.sjinc.trendanalysis.domain.TrendItemValue;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.repository.TrendItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수집량 보정(2026-10-29 진단 C2) — 증감은 비중 변화로 고르고, 범위 원문 수가 직전과 크게 다르면 수집량 주의를 붙인다.
 * 최근 4주: 패딩 6건, 레더 재킷 4건(범위 10건). 직전 4주: 패딩 1건, 레더 재킷 2건(범위 3건).
 * 레더 재킷은 건수로는 2 → 4로 늘었지만 비중은 67% → 40%로 줄었다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:trendfactssharetest")
class TrendFactsShareTest {

    private static final LocalDate TODAY = LocalDate.now();

    @Autowired
    private TrendFactsService trendFactsService;
    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;
    @Autowired
    private TrendItemRepository trendItemRepository;

    private static int seq; // 테스트마다 새 인스턴스라 static — 한 번만 넣는다(같은 메모리 DB)

    private void garment(String type, int daysAgo) {
        String docId = "SHR-" + (++seq);
        SourceDocument doc = sourceDocumentRepository.save(SourceDocument.builder()
                .docType(SourceDocument.TYPE_IMAGE).docId(docId).title(docId + ".png").media("매체" + seq)
                .publishedAt(TODAY.minusDays(daysAgo)).status(SourceDocument.DONE).registeredBy("admin").registeredAt(LocalDateTime.now()).build());
        TrendItem item = TrendItem.builder().documentId(doc.getId()).category("OUTER")
                .summary(docId + " 요약").resultJson("{}").createdAt(LocalDateTime.now()).build();
        item.addGarmentValue(TrendItemValue.ITEM_TYPE, type, 0, 0, "OUTER");
        trendItemRepository.save(item);
    }

    @BeforeEach
    void setUp() {
        if (seq > 0) return;
        for (int i = 0; i < 6; i++) garment("OUTER_PADDING", 1);
        for (int i = 0; i < 4; i++) garment("OUTER_LEATHER_JACKET", 2);
        garment("OUTER_PADDING", 40);
        garment("OUTER_LEATHER_JACKET", 40);
        garment("OUTER_LEATHER_JACKET", 41);
    }

    private TrendFactsService.Facts count(String change) {
        return trendFactsService.count(new QuestionInterpreter.Interpretation(true, "OUTER", "아우터", null, null, null,
                List.of(), List.of(), null, Map.of(), change), TODAY);
    }

    @Test
    void 급상승은_건수가_아니라_비중이_늘어난_값이다() {
        TrendFactsService.Facts up = count("UP");
        assertThat(up.dimensions().get(0).rows()).extracting(TrendFactsService.Row::value).containsExactly("OUTER_PADDING");
        TrendFactsService.Row padding = up.dimensions().get(0).rows().get(0);
        assertThat(padding.share()).isEqualTo(60);
        assertThat(padding.prevShare()).isEqualTo(33);
        assertThat(padding.shareDelta()).isEqualTo(27);
        // 레더 재킷은 건수가 늘었어도(2 → 4) 비중이 줄어(67% → 40%) 감소 쪽이다
        assertThat(count("DOWN").dimensions().get(0).rows()).extracting(TrendFactsService.Row::value).containsExactly("OUTER_LEATHER_JACKET");
        assertThat(up.lines()).anyMatch(l -> l.startsWith("[정렬] 직전 대비 비중이 늘어난 값만"))
                .anyMatch(l -> l.contains("6건 · 출처 6곳 · 직전 1건(+500%) · 비중 60%(직전 33%, +27%p)"));
    }

    @Test
    void 수집량이_크게_다르면_주의를_붙인다() {
        TrendFactsService.Facts facts = count(null);
        assertThat(facts.volumeNote()).contains("직전 3건 → 이번 10건");
        assertThat(facts.lines()).noneMatch(l -> l.startsWith("[수집량 주의]")); // 증감 질문이 아니면 싣지 않는다(2026-10-30)
        assertThat(count("UP").lines()).anyMatch(l -> l.startsWith("[수집량 주의]"));

        assertThat(TrendFactsService.volumeNote(129, 17)).isNotNull();
        assertThat(TrendFactsService.volumeNote(20, 18)).isNull();    // 비슷한 양이면 붙이지 않는다
        assertThat(TrendFactsService.volumeNote(30, 12)).isNotNull(); // 2.5배
        assertThat(TrendFactsService.volumeNote(5, 0)).contains("직전 기간 원문이 없어");
        assertThat(TrendFactsService.volumeNote(0, 0)).isNull();
    }

    @Test
    void 주별_추이는_그_주_전체_원문_수를_같이_센다() {
        List<TrendFactsService.WeekCount> weeks = trendFactsService.series(new QuestionInterpreter.Interpretation(true, "OUTER", "아우터",
                null, null, null), TrendItemValue.ITEM_TYPE, "OUTER_PADDING", 8, TODAY);
        assertThat(weeks.stream().mapToInt(TrendFactsService.WeekCount::count).sum()).isEqualTo(7);
        assertThat(weeks.stream().mapToInt(TrendFactsService.WeekCount::total).sum()).isEqualTo(13); // 8주 안 전체 원문
    }
}
