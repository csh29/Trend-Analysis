package com.sjinc.trendanalysis.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 기동 때 기존 DB의 공통코드를 새 기본값으로 맞추는 규칙 — 화면에서 사람이 고친 값은 건드리지 않는다. */
class ComCdTest {

    private static ComCd code(String name, String attr) {
        return ComCd.builder().codeGroup("TREND_ITEM").codeValue("OUTER_BLOUSON").codeName(name).attr1(attr).sortOrder(1).useYn("Y").build();
    }

    @Test
    void 이름이_예전_기본값_그대로일_때만_새_이름으로_바꾼다() {
        ComCd untouched = code("블루종(봄버·항공 점퍼)", null);
        assertThat(untouched.renameIfStill("블루종(봄버·항공 점퍼)", "블루종(봄버·항공 점퍼·스타디움 재킷)")).isTrue();
        assertThat(untouched.getCodeName()).isEqualTo("블루종(봄버·항공 점퍼·스타디움 재킷)");

        ComCd edited = code("블루종(봄버)", null); // 화면에서 고친 이름
        assertThat(edited.renameIfStill("블루종(봄버·항공 점퍼)", "블루종(봄버·항공 점퍼·스타디움 재킷)")).isFalse();
        assertThat(edited.getCodeName()).isEqualTo("블루종(봄버)");
    }
}
