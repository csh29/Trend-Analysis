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
            TrendCodes.SEASON, List.of(code(TrendCodes.SEASON, "FALL", "가을"), code(TrendCodes.SEASON, "WINTER", "겨울")),
            TrendCodes.GENDER, List.of(code(TrendCodes.GENDER, "WOMEN", "여성복"), code(TrendCodes.GENDER, "MEN", "남성복")),
            TrendCodes.PATTERN, List.of(code(TrendCodes.PATTERN, "SOLID", "무지(단색)"), code(TrendCodes.PATTERN, "CHECK", "체크")),
            TrendCodes.DETAIL, List.of(code(TrendCodes.DETAIL, "NECK_HALF", "하프넥"), code(TrendCodes.DETAIL, "CLOSE_ASYM", "사선 여밈"),
                    code(TrendCodes.DETAIL, "SLEEVE_CUFF", "단추 커프스"), code(TrendCodes.DETAIL, "TEX_RIB", "골지")),
            TrendCodes.ITEM_TYPE, List.of(code(TrendCodes.ITEM_TYPE, "OUTER_ETC", "기타 아우터"),
                    code(TrendCodes.ITEM_TYPE, "OUTER_PADDING", "패딩"), code(TrendCodes.ITEM_TYPE, "BOTTOM_ETC", "기타 하의"),
                    code(TrendCodes.ITEM_TYPE, "BOTTOM_DENIM", "데님 팬츠"), code(TrendCodes.ITEM_TYPE, "ETC", "기타"))));

    private static Map<String, Object> item(String category, String name, List<String> materials, List<String> colors) {
        Map<String, Object> item = new HashMap<>();
        item.put("category", category);
        item.put("item", name);
        item.put("type", "ETC".equals(category) ? "ETC" : category + "_ETC"); // 아이템 종류는 그 카테고리 것
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
        assertThat(v.items().get(0).genders()).isEmpty(); // 성별 칸이 없는 예전 결과는 빈 값(실패 아님)
        assertThat(v.items().get(0).patterns()).isEmpty(); // 패턴 칸이 없는 예전 결과도 빈 값
    }

    @Test
    void 디테일은_세_개까지_남기고_공통코드_밖_값은_거부한다() {
        Map<String, Object> raw = valid();
        Map<String, Object> knit = item("OUTER", "하프넥 니트 재킷", List.of(), List.of());
        knit.put("details", List.of("NECK_HALF", "CLOSE_ASYM", "SLEEVE_CUFF", "TEX_RIB"));
        raw.put("items", List.of(knit));
        assertThat(TrendResultValidator.validate(raw, codes).validated().items().get(0).details())
                .containsExactly("NECK_HALF", "CLOSE_ASYM", "SLEEVE_CUFF");

        knit.put("details", List.of("BOW_TIE"));
        assertThat(TrendResultValidator.validate(raw, codes).errors()).anyMatch(e -> e.contains("details"));
    }

    @Test
    void 패턴은_하나만_남기고_공통코드_밖_값은_거부한다() {
        Map<String, Object> raw = valid();
        Map<String, Object> outer = item("OUTER", "체크 코트", List.of(), List.of());
        outer.put("patterns", List.of("CHECK", "SOLID"));
        raw.put("items", List.of(outer));
        assertThat(TrendResultValidator.validate(raw, codes).validated().items().get(0).patterns()).containsExactly("CHECK");

        outer.put("patterns", List.of("TIE_DYE"));
        assertThat(TrendResultValidator.validate(raw, codes).errors()).anyMatch(e -> e.contains("patterns"));
    }

    @Test
    void 대상_라인은_하나만_남기고_공통코드_밖_값은_거부한다() {
        Map<String, Object> raw = valid();
        Map<String, Object> outer = item("OUTER", "레더 재킷", List.of(), List.of());
        outer.put("genders", List.of("WOMEN", "MEN"));
        raw.put("items", List.of(outer));
        assertThat(TrendResultValidator.validate(raw, codes).validated().items().get(0).genders()).containsExactly("WOMEN");

        outer.put("genders", List.of("KIDS"));
        assertThat(TrendResultValidator.validate(raw, codes).errors()).anyMatch(e -> e.contains("genders"));
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
    void 아이템이_없으면_기타_하나로_채우고_이름이_비면_거부한다() {
        Map<String, Object> empty = valid();
        empty.put("items", List.of()); // 의류가 없는 자료 — 프롬프트 대신 서버가 기타 아이템으로 채운다
        TrendResultValidator.Validated filled = TrendResultValidator.validate(empty, codes).validated();
        assertThat(filled.items()).hasSize(1);
        assertThat(filled.items().get(0).category()).isEqualTo("ETC");
        assertThat(filled.items().get(0).type()).isEqualTo("ETC");

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
        Map<String, String> axes = Map.of("OVERSIZED", "FIT", "REGULAR", "FIT", "SLIM", "FIT", "CROPPED", "LENGTH",
                "BOXY", "SHAPE", "WIDE", "SHAPE"); // 공통코드 실루엣 속성
        assertThat(TrendResultValidator.onePerAxis(List.of("OVERSIZED", "REGULAR", "CROPPED", "BOXY", "WIDE"), axes))
                .containsExactly("OVERSIZED", "CROPPED", "BOXY");
        // 축이 정해지지 않은 코드(공통코드에 새로 넣은 것)는 그대로 둔다
        assertThat(TrendResultValidator.onePerAxis(List.of("REGULAR", "NEW_CODE", "SLIM"), axes))
                .containsExactly("REGULAR", "NEW_CODE");
    }

    @Test
    void 카테고리에_쓸_수_없는_실루엣은_버린다() {
        // 공통코드 실루엣 속성의 카테고리 목록 — 하의는 OVERSIZED 대신 WIDE, 기타는 실루엣 없음
        Map<String, java.util.Set<String>> allowed = Map.of("OVERSIZED", java.util.Set.of("OUTER", "TOP"),
                "WIDE", java.util.Set.of("BOTTOM"));
        assertThat(TrendResultValidator.forCategory(List.of("OVERSIZED", "WIDE", "NEW_CODE"), "BOTTOM", allowed))
                .containsExactly("WIDE", "NEW_CODE"); // 카테고리를 적지 않은 코드는 그대로
        assertThat(TrendResultValidator.forCategory(List.of("OVERSIZED", "WIDE"), "ETC", allowed)).isEmpty();
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
    void 빈_키워드_빈_요약은_거부한다() {
        Map<String, Object> raw = valid();
        raw.put("keywords", List.of(" "));
        raw.put("summary", "");

        TrendResultValidator.Outcome outcome = TrendResultValidator.validate(raw, codes);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.errors()).hasSize(2);
    }

    @Test
    void 키워드는_8개까지만_남긴다() {
        Map<String, Object> raw = valid();
        raw.put("keywords", List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"));

        assertThat(TrendResultValidator.validate(raw, codes).validated().keywords()).hasSize(TrendResultValidator.MAX_KEYWORDS);
    }

    @Test
    void 종류와_카테고리가_어긋나면_종류의_카테고리를_따른다() {
        TrendResultValidator.Outcome ok = TrendResultValidator.validate(valid(), codes);
        assertThat(ok.validated().items().get(0).type()).isEqualTo("OUTER_ETC");

        Map<String, Object> mismatch = valid();
        @SuppressWarnings("unchecked")
        Map<String, Object> outer = (Map<String, Object>) ((List<?>) mismatch.get("items")).get(0);
        outer.put("type", "BOTTOM_DENIM"); // 아우터에 데님 팬츠 — 재시도 대신 종류(더 구체적)에 맞춘다
        TrendResultValidator.Garment fixed = TrendResultValidator.validate(mismatch, codes).validated().items().get(0);
        assertThat(fixed.category()).isEqualTo("BOTTOM");
        assertThat(fixed.type()).isEqualTo("BOTTOM_DENIM");

        outer.put("type", "OUTER_UNKNOWN");
        assertThat(TrendResultValidator.validate(mismatch, codes).errors()).anyMatch(e -> e.contains("공통코드에 없습니다"));
    }

    @Test
    void 결과가_없으면_거부한다() {
        assertThat(TrendResultValidator.validate(null, codes).ok()).isFalse();
    }
}
