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

    /** 카테고리가 맞는 것이 없을 때 LLM이 쓰는 코드(프롬프트 규칙과 같아야 한다). */
    public static final String CATEGORY_ETC = "ETC";

    /** 다중 값 종류 → 공통코드 그룹. */
    public static final Map<String, String> VALUE_TYPE_GROUPS = Map.of(
            TrendItemValue.MATERIAL, MATERIAL,
            TrendItemValue.COLOR, COLOR,
            TrendItemValue.SILHOUETTE, SILHOUETTE,
            TrendItemValue.SEASON, SEASON);

    /**
     * 기본 데이터 초안(DataInitializer가 그룹이 없을 때만 넣는다). 이름의 괄호는 LLM이 같은 뜻의 다른 표기를 이 코드로 옮기게 하는 힌트다.
     * 카테고리는 의류만 둔다(신발·가방·액세서리 제외, 2026-10-20 사용자 결정). 이미 넣은 DB는 공통코드 관리에서 사용 안 함으로 바꾼다.
     */
    public static final Map<String, List<String[]>> SEED = Map.of(
            CATEGORY, List.of(
                    new String[]{"OUTER", "아우터"}, new String[]{"TOP", "상의"}, new String[]{"BOTTOM", "하의"},
                    new String[]{"DRESS", "원피스"}, new String[]{"SETUP", "셋업·수트"}, new String[]{"ETC", "기타"}),
            MATERIAL, List.of(
                    new String[]{"COTTON", "면(코튼)"}, new String[]{"LINEN", "린넨(마)"}, new String[]{"WOOL", "울"},
                    new String[]{"CASHMERE", "캐시미어"}, new String[]{"LEATHER", "레더(가죽)"}, new String[]{"SUEDE", "스웨이드"},
                    new String[]{"DENIM", "데님"}, new String[]{"SILK", "실크"}, new String[]{"KNIT", "니트"},
                    new String[]{"FUR", "퍼(모피·페이크퍼)"}, new String[]{"FLEECE", "플리스"}, new String[]{"TWEED", "트위드"},
                    new String[]{"CORDUROY", "코듀로이(골덴)"}, new String[]{"NYLON", "나일론"},
                    new String[]{"POLYESTER", "폴리에스터"}, new String[]{"SATIN", "새틴"}),
            COLOR, List.of(
                    new String[]{"BLACK", "블랙"}, new String[]{"WHITE", "화이트"}, new String[]{"IVORY", "아이보리"},
                    new String[]{"GRAY", "그레이"}, new String[]{"BEIGE", "베이지"}, new String[]{"BROWN", "브라운"},
                    new String[]{"KHAKI", "카키"}, new String[]{"NAVY", "네이비"}, new String[]{"BLUE", "블루"},
                    new String[]{"SKYBLUE", "스카이블루"}, new String[]{"GREEN", "그린"}, new String[]{"RED", "레드"},
                    new String[]{"BURGUNDY", "버건디"}, new String[]{"PINK", "핑크"}, new String[]{"PURPLE", "퍼플"},
                    new String[]{"YELLOW", "옐로우"}, new String[]{"ORANGE", "오렌지"}, new String[]{"MULTI", "멀티컬러(패턴)"}),
            SILHOUETTE, List.of(
                    new String[]{"OVERSIZED", "오버사이즈"}, new String[]{"REGULAR", "레귤러"}, new String[]{"SLIM", "슬림"},
                    new String[]{"BOXY", "박시"}, new String[]{"WIDE", "와이드"}, new String[]{"CROPPED", "크롭"},
                    new String[]{"LONG", "롱"}, new String[]{"A_LINE", "A라인"}, new String[]{"H_LINE", "H라인"},
                    new String[]{"FLARE", "플레어"}),
            // 여러 개를 고른다(간절기 = 봄·가을). SS/FW는 이 값들을 묶어 본다
            SEASON, List.of(
                    new String[]{"SPRING", "봄"}, new String[]{"SUMMER", "여름"}, new String[]{"FALL", "가을"},
                    new String[]{"WINTER", "겨울"}));

    private TrendCodes() {
    }
}
