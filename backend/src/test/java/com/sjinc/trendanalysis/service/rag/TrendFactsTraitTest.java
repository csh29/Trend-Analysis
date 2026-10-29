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
 * 특징 질문("니트 특징은?") — 축별 순위만이 아니라 같은 옷의 공통 조합·키워드를 세고, 예시 원문은 최근 순이 아니라 공통 특징을
 * 많이 가진 원문으로 고른다(2026-10-29 사용자 지적: 아무 최근 6건은 의미가 없다).
 * 니트 8건: 그레이·오버사이즈·"레이어드" 4건(5~8일 전), 제각각인 옷 4건(1~4일 전 — 최근 순이면 이것들이 먼저 뽑힌다).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:trendfactstraittest")
class TrendFactsTraitTest {

    private static final LocalDate TODAY = LocalDate.now();

    @Autowired
    private TrendFactsService trendFactsService;
    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;
    @Autowired
    private TrendItemRepository trendItemRepository;

    private static boolean seeded;

    private void knit(String docId, int daysAgo, String color, String silhouette, String... keywords) {
        SourceDocument doc = sourceDocumentRepository.save(SourceDocument.builder()
                .docType(SourceDocument.TYPE_IMAGE).docId(docId).title(docId + ".png").media("매체-" + docId)
                .publishedAt(TODAY.minusDays(daysAgo)).status(SourceDocument.DONE).registeredBy("admin").registeredAt(LocalDateTime.now()).build());
        TrendItem item = TrendItem.builder().documentId(doc.getId()).category("TOP")
                .summary(docId + " 요약").resultJson("{}").createdAt(LocalDateTime.now()).build();
        for (int i = 0; i < keywords.length; i++) item.addValue(TrendItemValue.KEYWORD, keywords[i], i);
        item.addGarmentValue(TrendItemValue.ITEM_TYPE, "TOP_KNIT", 0, 0, "TOP");
        item.addGarmentValue(TrendItemValue.COLOR, color, 1, 0, "TOP");
        item.addGarmentValue(TrendItemValue.SILHOUETTE, silhouette, 2, 0, "TOP");
        trendItemRepository.save(item);
    }

    @BeforeEach
    void setUp() {
        if (seeded) return;
        seeded = true;
        for (int i = 0; i < 4; i++) knit("TR-C" + i, 5 + i, "GRAY", "OVERSIZED", "레이어드", "니트");
        knit("TR-O1", 1, "RED", "CROPPED", "포인트 컬러");
        knit("TR-O2", 2, "YELLOW", "LONG");
        knit("TR-O3", 3, "PURPLE", "SLIM");
        knit("TR-O4", 4, "BLUE", "REGULAR");
    }

    private TrendFactsService.Facts knitFacts(String focus) {
        return trendFactsService.count(new QuestionInterpreter.Interpretation(true, "TOP", "상의", "TOP_KNIT", "니트·스웨터", null,
                List.of(), List.of(), focus, Map.of(), null), TODAY);
    }

    @Test
    void 특징_질문은_공통_조합과_키워드를_센다() {
        TrendFactsService.Facts facts = knitFacts(null);

        assertThat(facts.combos()).containsExactly(new TrendFactsService.NameCount("그레이 · 오버사이즈", 4)); // 1건뿐인 조합은 공통이 아니다
        assertThat(facts.keywords()).containsExactly(new TrendFactsService.NameCount("레이어드", 4)); // 범위 이름("니트")·1건 키워드는 뺀다
        assertThat(facts.lines()).anyMatch(l -> l.equals("[공통 조합 — 같은 옷에서 함께 나온 값, 원문 수] 그레이 · 오버사이즈 4건"))
                .anyMatch(l -> l.startsWith("[키워드 — 범위 원문의 스타일·무드 키워드, 원문 수] 레이어드 4건"));
    }

    @Test
    void 예시_원문은_공통_특징을_많이_가진_원문이_먼저다() {
        // 최근 순이면 TR-O1~O4(1~4일 전)가 먼저였다 — 그레이·오버사이즈 원문 4건이 앞에 온다
        assertThat(knitFacts(null).evidenceDocIds().subList(0, 4)).containsExactlyInAnyOrder("TR-C0", "TR-C1", "TR-C2", "TR-C3");
    }

    @Test
    void 묻는_축이_있으면_조합_키워드를_싣지_않고_그_축의_상위_값_원문을_쓴다() {
        TrendFactsService.Facts facts = knitFacts(TrendItemValue.COLOR);
        assertThat(facts.combos()).isEmpty();
        assertThat(facts.keywords()).isEmpty();
        assertThat(facts.evidenceDocIds()).startsWith("TR-C0", "TR-C1"); // 컬러 1위(그레이)의 최근 원문 2건부터
    }
}
