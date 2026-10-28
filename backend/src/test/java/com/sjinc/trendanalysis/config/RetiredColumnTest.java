package com.sjinc.trendanalysis.config;

import com.sjinc.trendanalysis.domain.ComCd;
import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.domain.TrendItem;
import com.sjinc.trendanalysis.repository.ComCdRepository;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.service.analysis.TrendCodes;
import com.sjinc.trendanalysis.repository.TrendItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 엔티티에서 뺀 컬럼(trend_items.model·sentiment, 2026-10-23)이 기존 파일 DB에 NOT NULL로 남아 있으면 새 분석 결과 저장이 깨진다 —
 * 기동 초기화가 지우는지 본다. 예전 DB처럼 컬럼을 만들어 두고 초기화를 다시 돌린다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:retiredcolumntest")
class RetiredColumnTest {

    @Autowired
    private DataInitializer dataInitializer;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;
    @Autowired
    private TrendItemRepository trendItemRepository;
    @Autowired
    private ComCdRepository comCdRepository;

    private int retiredColumns() {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'TREND_ITEMS' AND COLUMN_NAME IN ('MODEL', 'SENTIMENT')", Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    void 예전_DB의_모델_감성_컬럼을_기동_때_지운다() {
        jdbcTemplate.execute("ALTER TABLE trend_items ADD COLUMN model VARCHAR(255) DEFAULT 'claude-sonnet-5' NOT NULL");
        jdbcTemplate.execute("ALTER TABLE trend_items ADD COLUMN sentiment VARCHAR(20) DEFAULT 'neutral' NOT NULL");
        assertThat(retiredColumns()).isEqualTo(2);

        dataInitializer.run();
        dataInitializer.run(); // 다시 돌아도(컬럼이 없어도) 실패하지 않는다

        assertThat(retiredColumns()).isZero();
        SourceDocument doc = sourceDocumentRepository.save(SourceDocument.builder().docType(SourceDocument.TYPE_TEXT)
                .docId("TXT-RC-1").title("t").media("m").publishedAt(LocalDate.now()).status(SourceDocument.DONE)
                .registeredBy("admin").registeredAt(LocalDateTime.now()).build());
        trendItemRepository.save(TrendItem.builder().documentId(doc.getId()).category("OUTER")
                .summary("s").resultJson("{}").createdAt(LocalDateTime.now()).build());
        assertThat(trendItemRepository.findByDocumentId(doc.getId())).isPresent();
    }

    @Test
    void 예전_DB의_아이템명_유형_코드를_기동_때_지운다() {
        comCdRepository.save(ComCd.builder().codeGroup(TrendCodes.VALUE_TYPE).codeValue("ITEM").codeName("아이템명")
                .sortOrder(3).useYn("Y").build());

        dataInitializer.run();

        assertThat(comCdRepository.findByCodeGroupOrderBySortOrderAsc(TrendCodes.VALUE_TYPE))
                .extracting(ComCd::getCodeValue).doesNotContain("ITEM").contains("ITEM_TYPE");
    }

    @Test
    void 예전_DB에_나중에_더한_소재_코드를_넣는다() {
        comCdRepository.findByCodeGroupOrderBySortOrderAsc(TrendCodes.MATERIAL).stream()
                .filter(c -> c.getCodeValue().equals("SHEARLING")).forEach(comCdRepository::delete);

        dataInitializer.run();
        dataInitializer.run(); // 이미 있으면 다시 넣지 않는다

        List<ComCd> shearling = comCdRepository.findByCodeGroupOrderBySortOrderAsc(TrendCodes.MATERIAL).stream()
                .filter(c -> c.getCodeValue().equals("SHEARLING")).toList();
        assertThat(shearling).hasSize(1);
        assertThat(shearling.get(0).getCodeName()).isEqualTo("무스탕(시어링·양털 가죽)");
        assertThat(shearling.get(0).getAttr1()).isEqualTo(TrendCodes.ATTR_IMAGE);
    }

    @Test
    void 예전_DB에_정답셋에서_나온_코드를_넣는다() {
        java.util.Map<String, List<String>> added = java.util.Map.of(TrendCodes.MATERIAL, List.of("RAYON", "ACRYLIC", "VELVET"),
                TrendCodes.SILHOUETTE, List.of("BOOTCUT"), TrendCodes.ITEM_TYPE, List.of("BOTTOM_GURKHA"));
        added.forEach((group, values) -> comCdRepository.findByCodeGroupOrderBySortOrderAsc(group).stream()
                .filter(c -> values.contains(c.getCodeValue())).forEach(comCdRepository::delete));

        dataInitializer.run();

        added.forEach((group, values) -> assertThat(comCdRepository.findByCodeGroupOrderBySortOrderAsc(group))
                .extracting(ComCd::getCodeValue).containsAll(values));
        ComCd bootcut = comCdRepository.findByCodeGroupOrderBySortOrderAsc(TrendCodes.SILHOUETTE).stream()
                .filter(c -> c.getCodeValue().equals("BOOTCUT")).findFirst().orElseThrow();
        assertThat(bootcut.getAttr1()).isEqualTo("SHAPE,BOTTOM"); // 축·카테고리 속성도 같이 들어간다
    }

    @Test
    void 예전_DB의_컬러_데님을_지운다() {
        comCdRepository.save(ComCd.builder().codeGroup(TrendCodes.COLOR).codeValue("DENIM").codeName("데님(인디고·청 워싱)")
                .sortOrder(99).useYn("Y").build());

        dataInitializer.run();

        assertThat(comCdRepository.findByCodeGroupOrderBySortOrderAsc(TrendCodes.COLOR))
                .extracting(ComCd::getCodeValue).doesNotContain("DENIM").contains("BLUE", "NAVY");
    }
}
