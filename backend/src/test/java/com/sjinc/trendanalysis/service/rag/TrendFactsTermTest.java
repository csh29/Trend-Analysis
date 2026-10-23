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
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 질문 단어(코드로 못 옮긴 말)를 키워드·아이템명에서 세는지 본다(2026-10-23). "무스탕 유행해?"가 "아우터 유행"으로 넓어져 답을 못 했다.
 * 최근 4주: 무스탕 언급 3건(키워드 "무스탕 칼라" 1, 아이템명 "무스탕 재킷" 1, 하의인데 키워드 "무스탕" 1 — 범위 무관), 무관 1건.
 * 직전 기간: 무스탕 1건.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:trendfactstermtest")
class TrendFactsTermTest {

    private static final LocalDate TODAY = LocalDate.now();

    @Autowired
    private TrendFactsService trendFactsService;
    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;
    @Autowired
    private TrendItemRepository trendItemRepository;

    private void doc(String docId, int daysAgo, String media, String category, String type, String name, String... keywords) {
        SourceDocument doc = sourceDocumentRepository.save(SourceDocument.builder()
                .docType(SourceDocument.TYPE_IMAGE).docId(docId).title(docId + ".png").media(media)
                .publishedAt(TODAY.minusDays(daysAgo)).status(SourceDocument.DONE).registeredBy("admin").registeredAt(LocalDateTime.now()).build());
        TrendItem item = TrendItem.builder().documentId(doc.getId()).category(category)
                .summary(docId + " 요약").resultJson("{}").createdAt(LocalDateTime.now()).build();
        for (int i = 0; i < keywords.length; i++) {
            item.addValue(TrendItemValue.KEYWORD, keywords[i], i);
        }
        item.addGarmentValue(TrendItemValue.ITEM, name, 0, 0, category);
        item.addGarmentValue(TrendItemValue.ITEM_TYPE, type, 0, 0, category);
        trendItemRepository.save(item);
    }

    @Test
    void 질문_단어는_키워드와_아이템명에서_범위_없이_센다() {
        doc("TM-1", 1, "매체A", "OUTER", "OUTER_BLOUSON", "레더 블루종", "레더 재킷", "무스탕 칼라");
        doc("TM-2", 2, "매체B", "OUTER", "OUTER_ETC", "무스탕  재킷", "겨울 아우터");
        doc("TM-3", 3, "매체B", "BOTTOM", "BOTTOM_ETC", "퍼 스커트", "무스탕");
        doc("TM-4", 4, "매체A", "OUTER", "OUTER_PADDING", "숏패딩", "패딩");
        doc("TM-5", 40, "매체A", "OUTER", "OUTER_ETC", "무스탕 재킷", "무스탕");

        TrendFactsService.Facts facts = trendFactsService.count(new QuestionInterpreter.Interpretation(true, "OUTER", "아우터",
                null, null, null, List.of("무스탕")), TODAY);

        assertThat(facts.docCount()).isEqualTo(3); // 범위(아우터) 원문 수는 그대로
        TrendFactsService.TermRow term = facts.terms().get(0);
        assertThat(term.term()).isEqualTo("무스탕");
        assertThat(term.count()).isEqualTo(3); // 하의 원문도 센다
        assertThat(term.mediaCount()).isEqualTo(2);
        assertThat(term.prevCount()).isEqualTo(1);
        // 나온 표현(공백 정리) — 원문 수 같으면 이름 순
        assertThat(term.expressions()).extracting(TrendFactsService.NameCount::name)
                .containsExactly("무스탕", "무스탕 재킷", "무스탕 칼라");
        // 질문 단어가 나온 최근 원문이 예시 원문 앞에 온다
        assertThat(facts.evidenceDocIds()).startsWith("TM-1", "TM-2");
        assertThat(facts.lines()).anyMatch(l -> l.startsWith("[질문 단어 — 키워드·아이템명에 나온 원문, 범위 무관] 무스탕 3건 · 매체 2곳 · 직전 1건 (나온 표현: "));
    }

    @Test
    void 질문_단어는_빈_값_긴_값_중복을_거르고_세_개까지() {
        assertThat(RagService.terms(Arrays.asList(" 무스탕 ", null, "", "무스탕", "가".repeat(21), "발마칸  코트", "고프코어", "카고")))
                .containsExactly("무스탕", "발마칸 코트", "고프코어");
        assertThat(RagService.terms(null)).isEmpty();
        assertThat(new QuestionInterpreter.Interpretation(true, null, null, null, null, null).terms()).isEmpty();
    }
}
