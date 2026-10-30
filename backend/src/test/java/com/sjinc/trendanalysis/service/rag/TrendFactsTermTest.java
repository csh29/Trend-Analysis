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
        assertThat(facts.lines()).anyMatch(l -> l.startsWith("[질문 단어 — 키워드·아이템명에 나온 원문, 범위 무관] 무스탕 3건 (나온 표현: ")); // 증감 질문이 아니면 출처·직전은 뺀다
    }

    /** 옷 여러 벌짜리 원문 — garments는 {카테고리, 종류, 이름, 컬러, 실루엣} (컬러·실루엣은 빈 문자열이면 없음). */
    private void outfit(String docId, int daysAgo, String media, List<String> keywords, String[]... garments) {
        SourceDocument doc = sourceDocumentRepository.save(SourceDocument.builder()
                .docType(SourceDocument.TYPE_IMAGE).docId(docId).title(docId + ".png").media(media)
                .publishedAt(TODAY.minusDays(daysAgo)).status(SourceDocument.DONE).registeredBy("admin").registeredAt(LocalDateTime.now()).build());
        TrendItem item = TrendItem.builder().documentId(doc.getId()).category(garments[0][0])
                .summary(docId + " 요약").resultJson("{}").createdAt(LocalDateTime.now()).build();
        for (int i = 0; i < keywords.size(); i++) {
            item.addValue(TrendItemValue.KEYWORD, keywords.get(i), i);
        }
        for (int no = 0; no < garments.length; no++) {
            String[] g = garments[no];
            item.addGarmentValue(TrendItemValue.ITEM, g[2], 0, no, g[0]);
            item.addGarmentValue(TrendItemValue.ITEM_TYPE, g[1], 0, no, g[0]);
            if (!g[3].isEmpty()) item.addGarmentValue(TrendItemValue.COLOR, g[3], 0, no, g[0]);
            if (!g[4].isEmpty()) item.addGarmentValue(TrendItemValue.SILHOUETTE, g[4], 0, no, g[0]);
        }
        trendItemRepository.save(item);
    }

    @Test
    void 질문_단어_대상_옷의_포인트를_센다() {
        // 앞 테스트 원문(TM-1 키워드 "레더 재킷")과 섞이지 않게 "가죽 재킷"으로 센다.
        // 2026-10-26: "레더 재킷 9건"만으로는 어떤 레더 재킷이 뜨는지 몰랐다 — 대상 옷의 컬러·실루엣, 함께 나온 옷, 키워드를 센다
        outfit("PT-1", 1, "매체A", List.of("그런지", "가죽 재킷"),
                new String[]{"OUTER", "OUTER_LEATHER_JACKET", "블랙 가죽 라이더 재킷", "BLACK", "CROPPED"},
                new String[]{"BOTTOM", "BOTTOM_DENIM", "와이드 데님 팬츠", "BLUE", "WIDE"});
        outfit("PT-2", 2, "매체B", List.of("그런지"),
                new String[]{"OUTER", "OUTER_LEATHER_JACKET", "가죽 재킷", "BLACK", ""});
        outfit("PT-3", 40, "매체A", List.of(),
                new String[]{"OUTER", "OUTER_LEATHER_JACKET", "가죽재킷", "BLACK", ""});

        TrendFactsService.Facts facts = trendFactsService.count(new QuestionInterpreter.Interpretation(true, null, null,
                null, null, null, List.of("가죽 재킷", "와이드 팬츠")), TODAY);

        TrendFactsService.TermRow leather = facts.terms().get(0);
        assertThat(leather.count()).isEqualTo(2);
        assertThat(leather.prevCount()).isEqualTo(1); // 붙여 쓴 "가죽재킷"도 걸린다
        TrendFactsService.TermPoint color = leather.points().get(0);
        assertThat(color.label()).isEqualTo("컬러");
        assertThat(color.values()).containsExactly(new TrendFactsService.ValueCount("블랙", 2, 1));
        assertThat(leather.points()).extracting(TrendFactsService.TermPoint::label).containsExactly("컬러", "실루엣");
        assertThat(leather.together()).containsExactly(new TrendFactsService.NameCount("데님 팬츠", 1));
        assertThat(leather.keywords()).containsExactly(new TrendFactsService.NameCount("그런지", 2)); // 질문 단어 자체인 키워드는 뺀다

        // 낱말이 모두 들어 있으면 걸린다 — "와이드 팬츠" → "와이드 데님 팬츠"
        TrendFactsService.TermRow wide = facts.terms().get(1);
        assertThat(wide.count()).isEqualTo(1);
        assertThat(wide.together()).containsExactly(new TrendFactsService.NameCount("레더 재킷", 1));
        assertThat(facts.lines()).contains("  - 가죽 재킷 옷의 컬러: 블랙 2건");
    }

    @Test
    void 질문_단어는_빈_값_긴_값_중복을_거르고_세_개까지() {
        assertThat(RagService.terms(Arrays.asList(" 무스탕 ", null, "", "무스탕", "가".repeat(21), "발마칸  코트", "고프코어", "카고")))
                .containsExactly("무스탕", "발마칸 코트", "고프코어");
        assertThat(RagService.terms(null)).isEmpty();
        assertThat(new QuestionInterpreter.Interpretation(true, null, null, null, null, null).terms()).isEmpty();
    }
}
