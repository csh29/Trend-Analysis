package com.sjinc.trendanalysis.service.analysis;

import com.sjinc.trendanalysis.domain.ComCd;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 이미지 소재 허용 목록 — 사진으로 구분되는 소재만 요청·검증에 쓴다. 코드는 초안(TrendCodes.SEED)에서 만든다. */
class TrendCodeBookTest {

    private static TrendCodeBook seedBook() {
        Map<String, List<ComCd>> groups = new HashMap<>();
        TrendCodes.SEED.forEach((group, codes) -> groups.put(group, Arrays.stream(codes.toArray(new String[0][]))
                .map(c -> ComCd.builder().codeGroup(group).codeValue(c[0]).codeName(c[1]).attr1(c.length > 2 ? c[2] : null)
                        .sortOrder(1).useYn("Y").build())
                .toList()));
        return new TrendCodeBook(groups);
    }

    private static final java.util.Set<String> IMAGE = java.util.Set.of(
            "LEATHER", "SUEDE", "DENIM", "KNIT", "FUR", "FLEECE", "TWEED", "CORDUROY", "SATIN");

    @Test
    void 이미지_허용_소재는_공통코드_속성_IMAGE로_정한다() {
        assertThat(seedBook().imageMaterials()).isEqualTo(IMAGE); // 2026-10-20 사용자가 승인한 목록
    }

    @Test
    void 실루엣_축은_공통코드_속성으로_정한다() {
        assertThat(seedBook().silhouetteAxes()).containsEntry("OVERSIZED", "FIT").containsEntry("CROPPED", "LENGTH")
                .containsEntry("BOXY", "SHAPE").hasSize(10);
    }

    @Test
    void 이미지용_코드_묶음에는_사진으로_구분되는_소재만_남는다() {
        TrendCodeBook image = seedBook().restrict(TrendCodes.MATERIAL, seedBook().imageMaterials());

        assertThat(image.codes(TrendCodes.MATERIAL)).extracting(ComCd::getCodeValue)
                .containsExactlyInAnyOrderElementsOf(IMAGE)
                .doesNotContain("COTTON", "WOOL", "POLYESTER", "LINEN", "NYLON");
        // 다른 그룹은 그대로
        assertThat(image.codes(TrendCodes.COLOR)).hasSameSizeAs(seedBook().codes(TrendCodes.COLOR));
    }

    @Test
    void 이미지_결과에_면이_오면_검증에서_거부한다() {
        TrendCodeBook image = seedBook().restrict(TrendCodes.MATERIAL, seedBook().imageMaterials());
        Map<String, Object> garment = new HashMap<>(Map.of("category", "TOP", "item", "티셔츠", "type", "TOP_TSHIRT",
                "materials", List.of("COTTON"), "colors", List.of("WHITE"), "silhouettes", List.of()));
        Map<String, Object> raw = new HashMap<>(Map.of("items", List.of(garment), "keywords", List.of("티셔츠"),
                "seasons", List.of(), "sentiment", "neutral", "summary", "흰 티셔츠"));

        assertThat(TrendResultValidator.validate(raw, image).errors())
                .anyMatch(e -> e.contains("materials") && e.contains("COTTON"));
        // 텍스트(제한 없음)에서는 통과
        assertThat(TrendResultValidator.validate(raw, seedBook()).ok()).isTrue();
    }

    @Test
    void 아이템_종류_초안은_모두_있는_카테고리_앞부분을_쓴다() {
        List<String> categories = TrendCodes.SEED.get(TrendCodes.CATEGORY).stream().map(c -> c[0]).toList();
        assertThat(TrendCodes.SEED.get(TrendCodes.ITEM_TYPE)).allSatisfy(c ->
                assertThat(categories).contains(TrendCodes.categoryOfType(c[0])));
        assertThat(TrendCodes.categoryOfType("OUTER_DENIM_JACKET")).isEqualTo("OUTER");
        assertThat(TrendCodes.categoryOfType("ETC")).isEqualTo("ETC");
    }

}
