package com.sjinc.trendanalysis.service.analysis;

import com.sjinc.trendanalysis.domain.ComCd;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrendResultValidatorTest {

    private static ComCd code(String group, String value, String name) {
        return ComCd.builder().codeGroup(group).codeValue(value).codeName(name).sortOrder(1).useYn("Y").build();
    }

    private final TrendCodeBook codes = new TrendCodeBook(Map.of(
            TrendCodes.CATEGORY, List.of(code(TrendCodes.CATEGORY, "OUTER", "아우터"), code(TrendCodes.CATEGORY, "BOTTOM", "하의"),
                    code(TrendCodes.CATEGORY, "ETC", "기타")),
            TrendCodes.MATERIAL, List.of(code(TrendCodes.MATERIAL, "LEATHER", "레더(가죽)")),
            TrendCodes.COLOR, List.of(code(TrendCodes.COLOR, "BROWN", "브라운"), code(TrendCodes.COLOR, "BLACK", "블랙")),
            TrendCodes.SILHOUETTE, List.of(code(TrendCodes.SILHOUETTE, "OVERSIZED", "오버사이즈")),
            TrendCodes.SEASON, List.of(code(TrendCodes.SEASON, "FALL", "가을"), code(TrendCodes.SEASON, "WINTER", "겨울"))));

    private static Map<String, Object> item(String category, String name, List<String> materials, List<String> colors) {
        Map<String, Object> item = new HashMap<>();
        item.put("category", category);
        item.put("item", name);
        item.put("materials", materials);
        item.put("colors", colors);
        item.put("silhouettes", List.of());
        return item;
    }

    private static Map<String, Object> valid() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("items", List.of(
                item("OUTER", " 레더 재킷 ", List.of("LEATHER", "LEATHER"), List.of("BROWN")),
                item("BOTTOM", "미니스커트", List.of(), List.of("BLACK"))));
        raw.put("keywords", List.of(" 레더 재킷 ", "뉴트로", "레더 재킷"));
        raw.put("seasons", List.of("FALL", "WINTER", "FALL"));
        raw.put("sentiment", "positive");
        raw.put("summary", "레더 아우터가 주목받는다.");
        return raw;
    }

    @Test
    void 통과하면_아이템별로_값을_정리해서_돌려준다() {
        TrendResultValidator.Outcome outcome = TrendResultValidator.validate(valid(), codes);

        assertThat(outcome.ok()).isTrue();
        TrendResultValidator.Validated v = outcome.validated();
        assertThat(v.items()).hasSize(2);
        assertThat(v.mainCategory()).isEqualTo("OUTER");
        assertThat(v.items().get(0).item()).isEqualTo("레더 재킷");
        assertThat(v.items().get(0).materials()).containsExactly("LEATHER"); // 아이템 안의 중복 제거
        assertThat(v.items().get(1).colors()).containsExactly("BLACK");      // 속성은 그 아이템 것만
        assertThat(v.keywords()).containsExactly("레더 재킷", "뉴트로");
        assertThat(v.seasons()).containsExactly("FALL", "WINTER");
    }

    @Test
    void 시즌은_비어도_되지만_공통코드_밖_값은_거부한다() {
        Map<String, Object> empty = valid();
        empty.put("seasons", List.of());
        assertThat(TrendResultValidator.validate(empty, codes).ok()).isTrue(); // 판단할 수 없으면 비운다

        Map<String, Object> wrong = valid();
        wrong.put("seasons", List.of("AUTUMN"));
        assertThat(TrendResultValidator.validate(wrong, codes).errors()).anyMatch(e -> e.startsWith("seasons"));

        Map<String, Object> missing = valid();
        missing.remove("seasons");
        assertThat(TrendResultValidator.validate(missing, codes).ok()).isFalse();
    }

    @Test
    void 아이템의_공통코드_밖_값은_거부한다() {
        Map<String, Object> raw = valid();
        raw.put("items", List.of(item("SHOES", "부츠", List.of(), List.of("RED"))));

        TrendResultValidator.Outcome outcome = TrendResultValidator.validate(raw, codes);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.errors()).anyMatch(e -> e.contains("items[0].category"))
                .anyMatch(e -> e.contains("items[0].colors"));
    }

    @Test
    void 아이템이_없거나_이름이_비면_거부한다() {
        Map<String, Object> empty = valid();
        empty.put("items", List.of());
        assertThat(TrendResultValidator.validate(empty, codes).errors()).contains("items가 비어 있습니다.");

        Map<String, Object> noName = valid();
        noName.put("items", List.of(item("OUTER", "  ", List.of(), List.of())));
        assertThat(TrendResultValidator.validate(noName, codes).errors()).anyMatch(e -> e.contains("items[0].item"));

        Map<String, Object> oldShape = valid();
        oldShape.remove("items");
        oldShape.put("category", "OUTER"); // 아이템 배열 이전 형식
        assertThat(TrendResultValidator.validate(oldShape, codes).ok()).isFalse();
    }

    @Test
    void 실루엣은_축마다_처음_값_하나만_남긴다() {
        // 프롬프트 지시에도 오버사이즈·레귤러(둘 다 핏)를 함께 내던 실제 사례
        assertThat(TrendResultValidator.onePerAxis(List.of("OVERSIZED", "REGULAR", "CROPPED", "BOXY", "WIDE")))
                .containsExactly("OVERSIZED", "CROPPED", "BOXY");
        // 축이 정해지지 않은 코드(공통코드에 새로 넣은 것)는 그대로 둔다
        assertThat(TrendResultValidator.onePerAxis(List.of("REGULAR", "NEW_CODE", "SLIM")))
                .containsExactly("REGULAR", "NEW_CODE");
    }

    @Test
    void 아이템은_5개까지만_남긴다() {
        Map<String, Object> raw = valid();
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            many.add(item("OUTER", "아이템" + i, List.of(), List.of()));
        }
        raw.put("items", many);

        TrendResultValidator.Validated v = TrendResultValidator.validate(raw, codes).validated();

        assertThat(v.items()).hasSize(TrendResultValidator.MAX_ITEMS);
        assertThat(v.items().get(4).item()).isEqualTo("아이템4"); // 눈에 띄는 순서(앞에서부터)
    }

    @Test
    void 빈_키워드_잘못된_감성_빈_요약은_거부한다() {
        Map<String, Object> raw = valid();
        raw.put("keywords", List.of(" "));
        raw.put("sentiment", "happy");
        raw.put("summary", "");

        TrendResultValidator.Outcome outcome = TrendResultValidator.validate(raw, codes);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.errors()).hasSize(3);
    }

    @Test
    void 키워드는_8개까지만_남긴다() {
        Map<String, Object> raw = valid();
        raw.put("keywords", List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"));

        assertThat(TrendResultValidator.validate(raw, codes).validated().keywords()).hasSize(TrendResultValidator.MAX_KEYWORDS);
    }

    @Test
    void 결과가_없으면_거부한다() {
        assertThat(TrendResultValidator.validate(null, codes).ok()).isFalse();
    }
}
