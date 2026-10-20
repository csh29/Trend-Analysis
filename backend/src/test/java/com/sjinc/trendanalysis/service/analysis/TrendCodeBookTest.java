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
                .map(c -> ComCd.builder().codeGroup(group).codeValue(c[0]).codeName(c[1]).sortOrder(1).useYn("Y").build())
                .toList()));
        return new TrendCodeBook(groups);
    }

    @Test
    void 이미지_허용_소재는_모두_초안_코드에_있다() {
        List<String> seed = TrendCodes.SEED.get(TrendCodes.MATERIAL).stream().map(c -> c[0]).toList();
        assertThat(seed).containsAll(TrendCodes.IMAGE_MATERIALS); // 오타가 있으면 그 소재는 조용히 빠진다
    }

    @Test
    void 이미지용_코드_묶음에는_사진으로_구분되는_소재만_남는다() {
        TrendCodeBook image = seedBook().restrict(TrendCodes.MATERIAL, TrendCodes.IMAGE_MATERIALS);

        assertThat(image.codes(TrendCodes.MATERIAL)).extracting(ComCd::getCodeValue)
                .containsExactlyInAnyOrderElementsOf(TrendCodes.IMAGE_MATERIALS)
                .doesNotContain("COTTON", "WOOL", "POLYESTER", "LINEN", "NYLON");
        // 다른 그룹은 그대로
        assertThat(image.codes(TrendCodes.COLOR)).hasSameSizeAs(seedBook().codes(TrendCodes.COLOR));
    }

    @Test
    void 이미지_결과에_면이_오면_검증에서_거부한다() {
        TrendCodeBook image = seedBook().restrict(TrendCodes.MATERIAL, TrendCodes.IMAGE_MATERIALS);
        Map<String, Object> garment = new HashMap<>(Map.of("category", "TOP", "item", "티셔츠",
                "materials", List.of("COTTON"), "colors", List.of("WHITE"), "silhouettes", List.of()));
        Map<String, Object> raw = new HashMap<>(Map.of("items", List.of(garment), "keywords", List.of("티셔츠"),
                "seasons", List.of(), "sentiment", "neutral", "summary", "흰 티셔츠"));

        assertThat(TrendResultValidator.validate(raw, image).errors())
                .anyMatch(e -> e.contains("materials") && e.contains("COTTON"));
        // 텍스트(제한 없음)에서는 통과
        assertThat(TrendResultValidator.validate(raw, seedBook()).ok()).isTrue();
    }

    @Test
    void 이미지_허용_목록은_데님과_레더를_포함한다() {
        assertThat(TrendCodes.IMAGE_MATERIALS.stream().collect(Collectors.toSet())).contains("DENIM", "LEATHER");
    }
}
