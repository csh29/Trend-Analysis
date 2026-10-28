package com.sjinc.trendanalysis.service.analysis;

import com.sjinc.trendanalysis.domain.TrendItemValue;

import java.util.List;
import java.util.Map;

/**
 * 트렌드 항목 표준 코드(공통코드 그룹). 카테고리·소재·컬러·실루엣 값은 이 그룹들의 코드값만 저장한다 — 가죽/레더 같은
 * 표기 흔들림을 막기 위해서다(프로세스 정의서 10번 슬라이드). 값 추가·수정은 공통코드 관리 화면에서 한다.
 */
public final class TrendCodes {

    public static final String CATEGORY = "TREND_CATEGORY";
    public static final String MATERIAL = "TREND_MATERIAL";
    public static final String COLOR = "TREND_COLOR";
    public static final String SILHOUETTE = "TREND_SILHOUETTE";
    public static final String SEASON = "TREND_SEASON";
    /**
     * 옷의 대상 라인(여성복·남성복·공용) — 입은 사람의 성별이 아니라 어느 라인의 옷인지다. 기획·MD는 라인별로 본다(2026-10-26 사용자 요청).
     * 의류 아이템마다 0~1개, 판단할 수 없으면 비운다. 판단 기준은 ai/prompts/structure/common.md.
     */
    public static final String GENDER = "TREND_GENDER";
    /**
     * 아이템 종류(패딩·블루종·데님 팬츠…) — "유행 = 많이 입는 것"을 세려면 아이템명(LLM 자유 문장)으로는 "데님 트러커 재킷"과
     * "트러커 재킷"이 갈라져서, 집계용으로 정한 목록에서 고르게 한다(2026-10-22 사용자 결정). 코드값은 "카테고리_종류"(OUTER_PADDING)라
     * 앞부분으로 어느 카테고리 것인지 안다 — 검증이 아이템 카테고리와 맞는지 본다. 기타 카테고리는 "ETC" 하나.
     */
    public static final String ITEM_TYPE = "TREND_ITEM";

    /** 카테고리가 맞는 것이 없을 때 LLM이 쓰는 코드(프롬프트 규칙과 같아야 한다). */
    public static final String CATEGORY_ETC = "ETC";

    /** 값 종류(키워드·아이템 종류·소재…) 이름 — 트렌드 분석 화면 유형 목록. */
    public static final String VALUE_TYPE = "TREND_VALUE_TYPE";
    /** 유행을 묻는 말(유행·인기·뜨는…) — 질문 해석의 규칙 대체용(AI 해석이 실패했을 때). */
    public static final String TREND_WORD = "RAG_TREND_WORD";

    /** 코드 속성(ComCd.attr1) 값. */
    public static final String ATTR_IMAGE = "IMAGE";

    /** 아이템 종류 코드가 속한 카테고리(코드값 앞부분). "ETC"는 기타 카테고리. */
    public static String categoryOfType(String typeCode) {
        if (typeCode == null || typeCode.isBlank()) {
            return "";
        }
        int cut = typeCode.indexOf('_');
        return cut < 0 ? typeCode : typeCode.substring(0, cut);
    }

    /** 값 종류 → 공통코드 그룹. */
    public static final Map<String, String> VALUE_TYPE_GROUPS = Map.of(
            TrendItemValue.ITEM_TYPE, ITEM_TYPE,
            TrendItemValue.MATERIAL, MATERIAL,
            TrendItemValue.COLOR, COLOR,
            TrendItemValue.SILHOUETTE, SILHOUETTE,
            TrendItemValue.SEASON, SEASON,
            TrendItemValue.GENDER, GENDER);

    /**
     * 기본 데이터 초안 {코드값, 이름, 속성(없으면 생략)}. DataInitializer가 그룹이 없을 때 넣고, 이미 있는 그룹은 속성이 빈 코드에만
     * 속성을 채운다. 이름의 괄호는 LLM이 같은 뜻의 다른 표기를 이 코드로 옮기게 하는 힌트다.
     * 카테고리는 의류만 둔다(신발·가방·액세서리 제외, 2026-10-20 사용자 결정). 이미 넣은 DB는 공통코드 관리에서 사용 안 함으로 바꾼다.
     * <ul>
     *   <li>소재 속성 IMAGE: 사진의 질감으로 구분되는 소재 — 이미지만 있는 원문은 이것만 고를 수 있다(2026-10-20 사용자 결정. 면·울 등은
     *       프롬프트로 막아도 추측해 넣어서 허용 값에서 뺀다).</li>
     *   <li>실루엣 속성: 축(FIT·LENGTH·SHAPE). 축마다 하나만 남긴다(검증) — 오버사이즈·레귤러를 함께 내는 경우가 있었다. 속성이 없는
     *       코드는 축 제한이 없다. 판단 기준은 ai/prompts/structure/silhouette.md.</li>
     *   <li>카테고리 속성: 질문에서 카테고리를 알아보는 일상 표현(쉼표).</li>
     * </ul>
     */
    public static final Map<String, List<String[]>> SEED = Map.of(
            CATEGORY, List.of(
                    new String[]{"OUTER", "아우터", "겉옷,재킷,자켓,점퍼,잠바"}, new String[]{"TOP", "상의", "윗옷,탑"},
                    new String[]{"BOTTOM", "하의", "바지,팬츠,치마"}, new String[]{"DRESS", "원피스", "드레스"},
                    new String[]{"SETUP", "셋업·수트", "정장"}, new String[]{"ETC", "기타"}),
            MATERIAL, List.of(
                    new String[]{"COTTON", "면(코튼)"}, new String[]{"LINEN", "린넨(마)"}, new String[]{"WOOL", "울"},
                    new String[]{"CASHMERE", "캐시미어"}, new String[]{"LEATHER", "레더(가죽)", ATTR_IMAGE}, new String[]{"SUEDE", "스웨이드", ATTR_IMAGE},
                    new String[]{"DENIM", "데님", ATTR_IMAGE}, new String[]{"SILK", "실크"}, new String[]{"KNIT", "니트", ATTR_IMAGE},
                    new String[]{"FUR", "퍼(모피·페이크퍼)", ATTR_IMAGE},
                    new String[]{"SHEARLING", "무스탕(시어링·양털 가죽)", ATTR_IMAGE}, new String[]{"FLEECE", "플리스", ATTR_IMAGE}, new String[]{"TWEED", "트위드", ATTR_IMAGE},
                    new String[]{"CORDUROY", "코듀로이(골덴)", ATTR_IMAGE}, new String[]{"NYLON", "나일론"},
                    new String[]{"POLYESTER", "폴리에스터"}, new String[]{"SATIN", "새틴", ATTR_IMAGE},
                    // 2026-10-28 정답셋(사람이 단 라벨)에 나와 추가 — 사진으로는 가릴 수 없어 IMAGE가 아니다
                    new String[]{"RAYON", "레이온"}, new String[]{"ACRYLIC", "아크릴"}),
            COLOR, List.of(
                    new String[]{"BLACK", "블랙"}, new String[]{"WHITE", "화이트"}, new String[]{"IVORY", "아이보리"},
                    new String[]{"GRAY", "그레이"}, new String[]{"BEIGE", "베이지"}, new String[]{"BROWN", "브라운"},
                    new String[]{"KHAKI", "카키"}, new String[]{"NAVY", "네이비"}, new String[]{"BLUE", "블루"},
                    new String[]{"SKYBLUE", "스카이블루"}, new String[]{"GREEN", "그린"}, new String[]{"RED", "레드"},
                    new String[]{"BURGUNDY", "버건디"}, new String[]{"PINK", "핑크"}, new String[]{"PURPLE", "퍼플"},
                    // 데님 워싱은 따로 두지 않는다 — 연청·중청 BLUE, 진청·생지 NAVY, 블랙진 BLACK(2026-10-28 사용자 결정)
                    new String[]{"YELLOW", "옐로우"}, new String[]{"ORANGE", "오렌지"}, new String[]{"MULTI", "멀티컬러(패턴)"}),
            SILHOUETTE, List.of(
                    // 속성 = 축,이 실루엣을 쓸 수 있는 카테고리(없으면 모든 카테고리). 기타(ETC)는 어느 실루엣도 쓰지 않는다
                    new String[]{"OVERSIZED", "오버사이즈", "FIT,OUTER,TOP,DRESS,SETUP"}, // 하의는 통이 넓으면 WIDE
                    new String[]{"REGULAR", "레귤러", "FIT,OUTER,TOP,BOTTOM,DRESS,SETUP"},
                    new String[]{"SLIM", "슬림", "FIT,OUTER,TOP,BOTTOM,DRESS,SETUP"},
                    new String[]{"BOXY", "박시", "SHAPE,OUTER,TOP,DRESS,SETUP"}, new String[]{"WIDE", "와이드", "SHAPE,BOTTOM,SETUP"},
                    new String[]{"CROPPED", "크롭", "LENGTH,OUTER,TOP,BOTTOM"}, new String[]{"LONG", "롱", "LENGTH,OUTER,TOP,BOTTOM,DRESS"},
                    new String[]{"A_LINE", "A라인", "SHAPE,OUTER,BOTTOM,DRESS"}, new String[]{"H_LINE", "H라인", "SHAPE,OUTER,BOTTOM,DRESS"},
                    new String[]{"FLARE", "플레어", "SHAPE,BOTTOM,DRESS"},
                    new String[]{"BOOTCUT", "부츠컷", "SHAPE,BOTTOM"}), // 2026-10-28 정답셋에 나와 추가 — 무릎 아래가 살짝 퍼지는 바지
            GENDER, List.of(
                    new String[]{"WOMEN", "여성복(우먼즈)"}, new String[]{"MEN", "남성복(맨즈)"}, new String[]{"UNISEX", "공용(유니섹스)"}),
            // 여러 개를 고른다(간절기 = 봄·가을). SS/FW는 이 값들을 묶어 본다
            SEASON, List.of(
                    new String[]{"SPRING", "봄"}, new String[]{"SUMMER", "여름"}, new String[]{"FALL", "가을"},
                    new String[]{"WINTER", "겨울"}),
            // 핏·기장(오버사이즈·크롭·와이드)은 실루엣에서 따로 다루므로 종류에 넣지 않는다
            ITEM_TYPE, List.of(
                    new String[]{"OUTER_PADDING", "패딩(숏패딩·푸퍼·패딩 점퍼)"}, new String[]{"OUTER_COAT", "코트(울 코트·롱 코트)"},
                    new String[]{"OUTER_TRENCH", "트렌치코트"}, new String[]{"OUTER_BLAZER", "블레이저(테일러드 재킷)"},
                    new String[]{"OUTER_BLOUSON", "블루종(봄버·항공 점퍼·스타디움 재킷)"}, new String[]{"OUTER_DENIM_JACKET", "데님 재킷(트러커)"},
                    new String[]{"OUTER_LEATHER_JACKET", "레더 재킷(라이더·레더 블루종·가죽 봄버)"}, new String[]{"OUTER_HOOD_ZIPUP", "후드 집업"},
                    new String[]{"OUTER_FLEECE", "플리스(후리스)"}, new String[]{"OUTER_PARKA", "파카(아노락·바람막이·후드 파카)"},
                    new String[]{"OUTER_TRACK_JACKET", "트랙 재킷(저지)"}, new String[]{"OUTER_WORK_JACKET", "워크 재킷(초어·셔츠 재킷)"},
                    new String[]{"OUTER_VEST", "베스트(조끼·패딩 조끼)"}, new String[]{"OUTER_ETC", "기타 아우터"},
                    new String[]{"TOP_TSHIRT", "티셔츠"}, new String[]{"TOP_SHIRT", "셔츠·블라우스"},
                    new String[]{"TOP_KNIT", "니트·스웨터"}, new String[]{"TOP_CARDIGAN", "카디건"},
                    new String[]{"TOP_SWEATSHIRT", "맨투맨"}, new String[]{"TOP_HOODIE", "후드티"},
                    new String[]{"TOP_SLEEVELESS", "민소매·탑(나시·캐미솔)"}, new String[]{"TOP_POLO", "폴로"},
                    new String[]{"TOP_ETC", "기타 상의"},
                    new String[]{"BOTTOM_DENIM", "데님 팬츠(청바지)"}, new String[]{"BOTTOM_SLACKS", "슬랙스"},
                    new String[]{"BOTTOM_CHINO", "면바지(치노)"}, new String[]{"BOTTOM_CARGO", "카고 팬츠"},
                    new String[]{"BOTTOM_TRACK", "트랙·조거 팬츠"}, new String[]{"BOTTOM_SHORTS", "반바지"},
                    new String[]{"BOTTOM_SKIRT", "스커트"}, new String[]{"BOTTOM_LEGGINGS", "레깅스"},
                    new String[]{"BOTTOM_ETC", "기타 하의"},
                    new String[]{"DRESS_DRESS", "원피스"}, new String[]{"DRESS_JUMPSUIT", "점프수트"},
                    new String[]{"SETUP_SUIT", "수트(테일러드 재킷+같은 원단 하의)"},
                    new String[]{"SETUP_TRACKSUIT", "트레이닝 셋업(트랙 재킷+트랙 팬츠)"},
                    new String[]{"ETC", "기타"}),
            // 트렌드 분석 화면 유형 목록(정렬순서대로). 코드값은 집계 값 종류라 바꾸지 않는다.
            // 아이템명(ITEM)은 없다 — 아이템 종류와 뜻이 겹쳐 헷갈린다(2026-10-23 사용자 결정)
            VALUE_TYPE, List.of(
                    new String[]{TrendItemValue.KEYWORD, "키워드"}, new String[]{TrendItemValue.ITEM_TYPE, "아이템 종류"},
                    new String[]{TrendItemValue.MATERIAL, "소재"},
                    new String[]{TrendItemValue.COLOR, "컬러"}, new String[]{TrendItemValue.SILHOUETTE, "실루엣"},
                    new String[]{TrendItemValue.SEASON, "시즌"}, new String[]{"CATEGORY", "카테고리"}),
            // 유행을 묻는 말 — AI 해석이 실패했을 때 규칙으로 알아본다(질문에 이 말이 들어 있으면 유행 질문)
            TREND_WORD, List.of(
                    new String[]{"TREND", "유행"}, new String[]{"TREND2", "트렌드"}, new String[]{"POPULAR", "인기"},
                    new String[]{"RISING", "뜨는"}, new String[]{"RISING2", "떠오르"}, new String[]{"HOT", "핫한"},
                    new String[]{"MAINSTREAM", "대세"}, new String[]{"WORN", "많이 입"}, new String[]{"SEEN", "많이 보이"},
                    new String[]{"SELLING", "잘 나가"}));

    private TrendCodes() {
    }
}
