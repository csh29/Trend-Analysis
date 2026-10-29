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
 * 속성 조건으로 거르기 — "와이드 팬츠"는 하의 중 실루엣이 와이드인 옷이다. 글자 비교(질문 단어)로는 "와이드 데님 팬츠"가 빠졌다(2026-10-26).
 * 최근 4주: 와이드 데님 팬츠(블랙), 와이드 슬랙스(베이지), 슬림 데님 팬츠, 와이드 아우터(하의 아님) 각 1건.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:trendfactsfiltertest")
class TrendFactsFilterTest {

    private static final LocalDate TODAY = LocalDate.now();

    @Autowired
    private TrendFactsService trendFactsService;
    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;
    @Autowired
    private TrendItemRepository trendItemRepository;

    private void doc(String docId, String category, String type, String name, String silhouette, String color, String gender) {
        SourceDocument doc = sourceDocumentRepository.save(SourceDocument.builder()
                .docType(SourceDocument.TYPE_IMAGE).docId(docId).title(docId + ".png").media("매체-" + docId)
                .publishedAt(TODAY.minusDays(3)).status(SourceDocument.DONE).registeredBy("admin").registeredAt(LocalDateTime.now()).build());
        TrendItem item = TrendItem.builder().documentId(doc.getId()).category(category)
                .summary(docId + " 요약").resultJson("{}").createdAt(LocalDateTime.now()).build();
        item.addGarmentValue(TrendItemValue.ITEM, name, 0, 0, category);
        item.addGarmentValue(TrendItemValue.ITEM_TYPE, type, 0, 0, category);
        item.addGarmentValue(TrendItemValue.SILHOUETTE, silhouette, 0, 0, category);
        item.addGarmentValue(TrendItemValue.COLOR, color, 0, 0, category);
        if (gender != null) item.addGarmentValue(TrendItemValue.GENDER, gender, 0, 0, category);
        trendItemRepository.save(item);
    }

    @Test
    void 속성_조건으로_그_값을_가진_옷만_센다() {
        doc("FI-1", "BOTTOM", "BOTTOM_DENIM", "와이드 데님 팬츠", "WIDE", "BLACK", "WOMEN");
        doc("FI-2", "BOTTOM", "BOTTOM_SLACKS", "와이드 슬랙스", "WIDE", "BEIGE", "MEN");
        doc("FI-3", "BOTTOM", "BOTTOM_DENIM", "슬림 데님 팬츠", "SLIM", "BLUE", null);
        doc("FI-4", "OUTER", "OUTER_COAT", "와이드 코트", "WIDE", "BLACK", "WOMEN");

        // "와이드 팬츠 유행해?" → 하의 + 실루엣 와이드
        TrendFactsService.Facts wide = trendFactsService.count(new QuestionInterpreter.Interpretation(true, "BOTTOM", "하의",
                null, null, null, List.of(), List.of(), TrendItemValue.ITEM_TYPE,
                Map.of(TrendItemValue.SILHOUETTE, List.of("WIDE"))), TODAY);

        assertThat(wide.docCount()).isEqualTo(2); // 와이드 데님 팬츠·와이드 슬랙스 — 슬림 팬츠·와이드 코트는 빠진다
        assertThat(wide.scopeLabel()).isEqualTo("하의 · 와이드");
        assertThat(wide.dimensions().get(0).rows()).extracting(TrendFactsService.Row::value)
                .containsExactlyInAnyOrder("BOTTOM_DENIM", "BOTTOM_SLACKS");
        assertThat(wide.evidenceDocIds()).containsExactlyInAnyOrder("FI-1", "FI-2");

        // 종류끼리는 모두 맞아야 한다(AND) — 와이드이면서 블랙인 하의
        TrendFactsService.Facts wideBlack = trendFactsService.count(new QuestionInterpreter.Interpretation(true, "BOTTOM", "하의",
                null, null, null, List.of(), List.of(), null,
                Map.of(TrendItemValue.SILHOUETTE, List.of("WIDE"), TrendItemValue.COLOR, List.of("BLACK"))), TODAY);
        assertThat(wideBlack.docCount()).isEqualTo(1);

        // 대상 라인도 속성 조건이다 — "여성 하의" → 하의 + 여성복(2026-10-26)
        TrendFactsService.Facts women = trendFactsService.count(new QuestionInterpreter.Interpretation(true, "BOTTOM", "하의",
                null, null, null, List.of(), List.of(), null, Map.of(TrendItemValue.GENDER, List.of("WOMEN"))), TODAY);
        assertThat(women.docCount()).isEqualTo(1);
        assertThat(women.scopeLabel()).isEqualTo("하의 · 여성복");
        // 라인 값이 없는 하의(FI-3)는 조건으로 거를 수 없어 빠졌다고 알린다(진단 C4). 실루엣은 모두 값이 있어 알리지 않는다
        assertThat(women.coverage()).containsExactly("[조건 정보] 범위 원문 3건 중 라인(여성·남성) 값이 있는 원문 2건만 조건으로 거를 수 있었다 — 값이 없는 1건은 세지 않았다");
        assertThat(women.lines()).contains(women.coverage().get(0));
        assertThat(wide.coverage()).isEmpty();

        // 조건이 없으면 하의 전부
        TrendFactsService.Facts bottoms = trendFactsService.count(new QuestionInterpreter.Interpretation(true, "BOTTOM", "하의",
                null, null, null), TODAY);
        assertThat(bottoms.docCount()).isEqualTo(3);
        assertThat(bottoms.scopeLabel()).isEqualTo("하의");
    }
}
