package com.sjinc.trendanalysis.service.analysis;

import com.sjinc.trendanalysis.domain.ComCd;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrendResultValidatorTest {

    private static ComCd code(String group, String value, String name) {
        return ComCd.builder().codeGroup(group).codeValue(value).codeName(name).sortOrder(1).useYn("Y").build();
    }

    private final TrendCodeBook codes = new TrendCodeBook(Map.of(
            TrendCodes.CATEGORY, List.of(code(TrendCodes.CATEGORY, "OUTER", "아우터"), code(TrendCodes.CATEGORY, "ETC", "기타")),
            TrendCodes.MATERIAL, List.of(code(TrendCodes.MATERIAL, "LEATHER", "레더(가죽)")),
            TrendCodes.COLOR, List.of(code(TrendCodes.COLOR, "BROWN", "브라운")),
            TrendCodes.SILHOUETTE, List.of(code(TrendCodes.SILHOUETTE, "OVERSIZED", "오버사이즈"))));

    private static Map<String, Object> valid() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("category", "OUTER");
        raw.put("keywords", List.of(" 레더 재킷 ", "뉴트로", "레더 재킷"));
        raw.put("materials", List.of("LEATHER", "LEATHER"));
        raw.put("colors", List.of("BROWN"));
        raw.put("silhouettes", List.of());
        raw.put("sentiment", "positive");
        raw.put("summary", "레더 아우터가 주목받는다.");
        return raw;
    }

    @Test
    void 통과하면_값을_정리해서_돌려준다() {
        TrendResultValidator.Outcome outcome = TrendResultValidator.validate(valid(), codes);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.validated().keywords()).containsExactly("레더 재킷", "뉴트로"); // 공백 제거·중복 제거
        assertThat(outcome.validated().materials()).containsExactly("LEATHER");
        assertThat(outcome.validated().silhouettes()).isEmpty(); // 빈 배열은 허용(자료에서 확인되지 않음)
    }

    @Test
    void 공통코드에_없는_값은_거부한다() {
        Map<String, Object> raw = valid();
        raw.put("category", "SHOES");
        raw.put("colors", List.of("갈색"));

        TrendResultValidator.Outcome outcome = TrendResultValidator.validate(raw, codes);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.errors()).anyMatch(e -> e.contains("category")).anyMatch(e -> e.contains("갈색"));
    }

    @Test
    void 필수값이_비거나_형식이_틀리면_거부한다() {
        Map<String, Object> raw = valid();
        raw.put("keywords", List.of("  "));
        raw.put("summary", "");
        raw.put("sentiment", "happy");
        raw.put("materials", "LEATHER");

        TrendResultValidator.Outcome outcome = TrendResultValidator.validate(raw, codes);

        assertThat(outcome.errors()).anyMatch(e -> e.contains("keywords")).anyMatch(e -> e.contains("summary"))
                .anyMatch(e -> e.contains("sentiment")).anyMatch(e -> e.contains("materials가 배열이 아닙니다"));
    }

    @Test
    void 키워드는_8개까지만_남긴다() {
        Map<String, Object> raw = valid();
        raw.put("keywords", List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"));

        assertThat(TrendResultValidator.validate(raw, codes).validated().keywords()).hasSize(8);
    }

    @Test
    void 결과가_없으면_거부한다() {
        assertThat(TrendResultValidator.validate(null, codes).ok()).isFalse();
    }
}
