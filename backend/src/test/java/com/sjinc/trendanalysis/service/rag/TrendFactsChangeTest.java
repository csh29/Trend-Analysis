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
 * 증감 질문(급상승·감소)은 원문 수 순이 아니라 직전 기간 대비 증감 순으로 고르고, 증감률을 서버가 계산해 붙이는지 본다(2026-10-28).
 * 최근 4주: 패딩 4건, 레더 재킷 3건, 트렌치 0건. 직전 4주: 패딩 4건, 레더 재킷 1건, 트렌치 2건.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:trendfactschangetest")
class TrendFactsChangeTest {

    private static final LocalDate TODAY = LocalDate.now();

    @Autowired
    private TrendFactsService trendFactsService;
    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;
    @Autowired
    private TrendItemRepository trendItemRepository;

    private static int seq; // 테스트마다 새 인스턴스라 static — 한 번만 넣는다(같은 메모리 DB)

    private void garment(String type, int daysAgo) {
        String docId = "CHG-" + (++seq);
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
        for (int i = 0; i < 4; i++) garment("OUTER_PADDING", 1);
        for (int i = 0; i < 4; i++) garment("OUTER_PADDING", 40);
        for (int i = 0; i < 3; i++) garment("OUTER_LEATHER_JACKET", 2);
        garment("OUTER_LEATHER_JACKET", 40);
        garment("OUTER_TRENCH", 40);
        garment("OUTER_TRENCH", 41);
    }

    private TrendFactsService.Facts count(String change) {
        return trendFactsService.count(new QuestionInterpreter.Interpretation(true, "OUTER", "아우터", null, null, null,
                List.of(), List.of(), null, Map.of(), change), TODAY);
    }

    @Test
    void 증감률은_서버가_계산한다() {
        assertThat(TrendFactsService.rate(3, 1)).isEqualTo("+200%");
        assertThat(TrendFactsService.rate(1, 3)).isEqualTo("-67%");
        assertThat(TrendFactsService.rate(4, 4)).isEqualTo("0%");
        assertThat(TrendFactsService.rate(2, 0)).isEqualTo("신규");
        assertThat(TrendFactsService.rate(0, 0)).isEqualTo("-");
    }

    @Test
    void 보통_질문은_원문_수_순이다() {
        assertThat(count(null).dimensions().get(0).rows()).extracting(TrendFactsService.Row::value)
                .containsExactly("OUTER_PADDING", "OUTER_LEATHER_JACKET");
    }

    @Test
    void 유행점수는_같은_출처의_반복을_반만_센다() {
        assertThat(TrendFactsService.trendScore(10, 1)).isEqualTo(5.5); // 한 매체가 10번
        assertThat(TrendFactsService.trendScore(5, 5)).isEqualTo(5.0);  // 5곳이 한 번씩
        assertThat(TrendFactsService.trendScore(10, 5)).isEqualTo(7.5); // 5곳이 두 번씩
        assertThat(TrendFactsService.sourceKey(SourceDocument.builder().media("직접 업로드").build()))
                .startsWith("doc:"); // 직접 업로드는 원문마다 다른 출처
        assertThat(TrendFactsService.sourceKey(SourceDocument.builder().media("보그").build())).isEqualTo("보그");
    }

    @Test
    void 급상승_질문은_늘어난_값만_증가_폭_순이다() {
        TrendFactsService.Facts facts = count("UP");
        assertThat(facts.dimensions().get(0).rows()).extracting(TrendFactsService.Row::value).containsExactly("OUTER_LEATHER_JACKET");
        assertThat(facts.lines()).anyMatch(l -> l.startsWith("[정렬] 직전 대비 늘어난 값만"))
                .anyMatch(l -> l.contains("3건 · 매체 3곳 · 직전 1건(+200%)"));
    }

    @Test
    void 감소_질문은_이번_기간에_사라진_값도_넣는다() {
        TrendFactsService.Facts facts = count("DOWN");
        assertThat(facts.dimensions().get(0).rows()).extracting(TrendFactsService.Row::value).containsExactly("OUTER_TRENCH");
        assertThat(facts.lines()).anyMatch(l -> l.contains("0건 · 매체 0곳 · 직전 2건(-100%)"));
        assertThat(facts.evidenceDocIds()).isNotEmpty(); // 상위 값에 이번 기간 원문이 없어도 범위의 최근 원문을 근거로 준다
    }
}
