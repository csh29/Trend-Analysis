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
 * F/W 같은 시즌 질문은 원문의 시즌 값으로 거르고, 컬러마다 그 컬러로 나온 아이템 종류를 같이 세는지 본다(2026-10-23).
 * "26FW 핵심 컬러 5개 + 컬러별 아이템"이 검색 경로에서 근거 없음으로 끝났다.
 * 기간 안: 가을·겨울 원문 3건(블랙 레더 재킷, 블랙 슬랙스+브라운 코트, 브라운 레더 재킷), 여름 원문 1건(화이트 린넨 셔츠), 시즌 없는 원문 1건(블랙 티셔츠).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:trendfactsseasontest")
class TrendFactsSeasonTest {

    private static final LocalDate TODAY = LocalDate.now();

    @Autowired
    private TrendFactsService trendFactsService;
    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;
    @Autowired
    private TrendItemRepository trendItemRepository;

    /** garments: {카테고리, 종류, 컬러} 묶음. */
    private void doc(String docId, List<String> seasons, String[]... garments) {
        SourceDocument doc = sourceDocumentRepository.save(SourceDocument.builder()
                .docType(SourceDocument.TYPE_IMAGE).docId(docId).title(docId + ".png").media("매체-" + docId)
                .publishedAt(TODAY.minusDays(10)).status(SourceDocument.DONE).registeredBy("admin").registeredAt(LocalDateTime.now()).build());
        TrendItem item = TrendItem.builder().documentId(doc.getId()).category(garments[0][0])
                .summary(docId + " 요약").resultJson("{}").createdAt(LocalDateTime.now()).build();
        for (int i = 0; i < seasons.size(); i++) {
            item.addValue(TrendItemValue.SEASON, seasons.get(i), i);
        }
        for (int no = 0; no < garments.length; no++) {
            String[] g = garments[no];
            item.addGarmentValue(TrendItemValue.ITEM, g[1] + " 아이템", 0, no, g[0]);
            item.addGarmentValue(TrendItemValue.ITEM_TYPE, g[1], 0, no, g[0]);
            item.addGarmentValue(TrendItemValue.COLOR, g[2], 0, no, g[0]);
        }
        trendItemRepository.save(item);
    }

    @Test
    void 시즌을_정하면_그_시즌_원문만_세고_컬러마다_아이템_종류를_같이_센다() {
        doc("SE-1", List.of("FALL", "WINTER"), new String[]{"OUTER", "OUTER_LEATHER_JACKET", "BLACK"});
        doc("SE-2", List.of("FALL"), new String[]{"BOTTOM", "BOTTOM_SLACKS", "BLACK"}, new String[]{"OUTER", "OUTER_COAT", "BROWN"});
        doc("SE-3", List.of("WINTER"), new String[]{"OUTER", "OUTER_LEATHER_JACKET", "BROWN"});
        doc("SE-4", List.of("SUMMER"), new String[]{"TOP", "TOP_SHIRT", "WHITE"});
        doc("SE-5", List.of(), new String[]{"TOP", "TOP_TSHIRT", "BLACK"});
        QuestionInterpreter.Period sixMonths = new QuestionInterpreter.Period(TODAY.minusDays(182), TODAY, "최근 6개월(시즌 표현)", true);

        TrendFactsService.Facts fw = trendFactsService.count(new QuestionInterpreter.Interpretation(true, null, null, null, null,
                sixMonths, List.of(), List.of("FALL", "WINTER")), TODAY);

        assertThat(fw.docCount()).isEqualTo(3); // 여름·시즌 없는 원문은 빠진다
        assertThat(fw.scopeLabel()).isEqualTo("전체 · 가을·겨울 시즌");
        TrendFactsService.Dimension colors = fw.dimensions().stream()
                .filter(d -> d.valueType().equals(TrendItemValue.COLOR)).findFirst().orElseThrow();
        assertThat(colors.rows()).extracting(TrendFactsService.Row::value).containsExactly("BLACK", "BROWN");
        // 같은 의류 아이템의 종류 — 블랙은 레더 재킷·슬랙스, 브라운은 코트·레더 재킷(괄호 힌트는 뗀다)
        assertThat(colors.rows().get(0).items()).extracting(TrendFactsService.NameCount::name)
                .containsExactlyInAnyOrder("레더 재킷", "슬랙스");
        assertThat(colors.rows().get(1).items()).extracting(TrendFactsService.NameCount::name)
                .containsExactlyInAnyOrder("코트", "레더 재킷");
        assertThat(fw.lines()).anyMatch(l -> l.startsWith("[컬러] 블랙 유행점수 2 · 2건") && l.contains("[주요 아이템: "));

        // 해석이 기간을 빠뜨려도 시즌을 정했으면 시즌 기간(6개월)으로 센다
        TrendFactsService.Facts noPeriod = trendFactsService.count(new QuestionInterpreter.Interpretation(true, null, null, null, null,
                null, List.of(), List.of("FALL", "WINTER")), TODAY);
        assertThat(noPeriod.period().label()).isEqualTo("최근 6개월(시즌 표현)");
        assertThat(noPeriod.docCount()).isEqualTo(3);

        // 묻는 축이 있으면 그 축이 첫 축(막대·예시 원문) — "요즘 유행하는 컬러는?"
        TrendFactsService.Facts colorFirst = trendFactsService.count(new QuestionInterpreter.Interpretation(true, null, null, null,
                null, sixMonths, List.of(), List.of("FALL", "WINTER"), TrendItemValue.COLOR), TODAY);
        assertThat(colorFirst.dimensions().get(0).valueType()).isEqualTo(TrendItemValue.COLOR);
        // Claude에게도 묻는 축만 보낸다 — 다른 축 줄은 답에 쓰이지 않았다(2026-10-26)
        assertThat(colorFirst.lines()).anyMatch(l -> l.startsWith("[컬러]")).noneMatch(l -> l.startsWith("[아이템 종류]"));
        // 묻는 축이 없으면 모든 축
        assertThat(fw.lines()).anyMatch(l -> l.startsWith("[아이템 종류]"));
        // 예시 원문은 상위 컬러(블랙 SE-2·SE-1, 브라운 SE-3·SE-2)의 최근 원문 — 같은 날이면 나중에 넣은 것부터
        assertThat(colorFirst.evidenceDocIds()).containsExactlyInAnyOrder("SE-1", "SE-2", "SE-3");
        // 범위에서 빠진 축(종류를 콕 집었는데 종류 축)을 고르면 무시한다
        TrendFactsService.Facts typeScoped = trendFactsService.count(new QuestionInterpreter.Interpretation(true, "OUTER", "아우터",
                "OUTER_LEATHER_JACKET", "레더 재킷", sixMonths, List.of(), List.of(), TrendItemValue.ITEM_TYPE), TODAY);
        assertThat(typeScoped.dimensions()).extracting(TrendFactsService.Dimension::valueType).doesNotContain(TrendItemValue.ITEM_TYPE);

        // 기간을 정하고 시즌을 정하지 않으면 다 센다
        TrendFactsService.Facts all = trendFactsService.count(new QuestionInterpreter.Interpretation(true, null, null, null, null,
                sixMonths), TODAY);
        assertThat(all.docCount()).isEqualTo(5);
        assertThat(all.scopeLabel()).isEqualTo("전체");
        assertThat(all.coverage()).noneMatch(l -> l.startsWith("[시즌]"));
    }

    @Test
    void 지금_시즌은_그달의_계절과_계절_마지막_달이면_다음_계절이다() {
        assertThat(TrendFactsService.currentSeasons(LocalDate.of(2026, 10, 30))).containsExactly("FALL");
        assertThat(TrendFactsService.currentSeasons(LocalDate.of(2026, 11, 1))).containsExactly("FALL", "WINTER");
        assertThat(TrendFactsService.currentSeasons(LocalDate.of(2026, 12, 15))).containsExactly("WINTER"); // S/S 런웨이 기사가 섞이지 않게
        assertThat(TrendFactsService.currentSeasons(LocalDate.of(2027, 1, 10))).containsExactly("WINTER");
        assertThat(TrendFactsService.currentSeasons(LocalDate.of(2027, 2, 10))).containsExactly("WINTER", "SPRING");
        assertThat(TrendFactsService.currentSeasons(LocalDate.of(2027, 7, 10))).containsExactly("SUMMER");
    }

    /**
     * 기간·시즌을 정하지 않은 질문("요즘 유행하는 원피스는?")은 지금 시즌 원문만 센다 — 최근 4주에는 다음 시즌 런웨이 기사가 섞인다(2026-10-30).
     * 지금 시즌 원문이 MIN_SEASON_DOCS보다 적으면 시즌 구분 없이 센다.
     */
    @Test
    void 기간과_시즌이_없으면_지금_시즌으로_세고_적으면_시즌_없이_센다() {
        List<String> now = TrendFactsService.currentSeasons(TODAY);
        String off = now.contains("SUMMER") ? "WINTER" : "SUMMER"; // 지금 시즌이 아닌 계절
        for (int i = 1; i <= TrendFactsService.MIN_SEASON_DOCS; i++) {
            doc("NOW-" + i, now, new String[]{"DRESS", "DRESS_DRESS", "BLACK"});
        }
        doc("OFF-1", List.of(off), new String[]{"DRESS", "DRESS_JUMPSUIT", "WHITE"});
        doc("OFF-2", List.of(), new String[]{"DRESS", "DRESS_JUMPSUIT", "WHITE"});

        TrendFactsService.Facts dresses = trendFactsService.count(new QuestionInterpreter.Interpretation(true, "DRESS", "원피스",
                null, null, null), TODAY);
        assertThat(dresses.docCount()).isEqualTo(TrendFactsService.MIN_SEASON_DOCS);
        assertThat(dresses.period().label()).isEqualTo("최근 4주"); // 시즌 기간(6개월)이 아니라 최근 4주 그대로
        assertThat(dresses.scopeLabel()).startsWith("원피스 · ").endsWith(" 시즌");
        assertThat(dresses.coverage()).anyMatch(l -> l.startsWith("[시즌] 질문에 시즌이 없어 지금 시즌(") && l.endsWith("원문 2건은 뺐다"));
        assertThat(dresses.dimensions().get(0).rows()).extracting(TrendFactsService.Row::value).containsExactly("DRESS_DRESS");

        // 지금 시즌 원문이 적으면(셋업 1건) 시즌 구분 없이 센다
        doc("NOW-SETUP", now, new String[]{"SETUP", "SETUP_SUIT", "BROWN"});
        doc("OFF-SETUP", List.of(off), new String[]{"SETUP", "SETUP_TRACKSUIT", "BLACK"});
        TrendFactsService.Facts setups = trendFactsService.count(new QuestionInterpreter.Interpretation(true, "SETUP", "셋업",
                null, null, null), TODAY);
        assertThat(setups.docCount()).isEqualTo(2);
        assertThat(setups.scopeLabel()).isEqualTo("셋업");
        assertThat(setups.lines()).anyMatch(l -> l.startsWith("[시즌] 지금 시즌(") && l.contains("원문이 1건뿐이라 시즌 구분 없이 셌다"));
    }
}
