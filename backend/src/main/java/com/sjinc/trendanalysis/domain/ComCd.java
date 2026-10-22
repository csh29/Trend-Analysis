package com.sjinc.trendanalysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 화면마다 하드코딩되던 select 옵션(역할, 메뉴 그룹 등)을 공통코드 관리 화면에서 관리하기 위한 코드 한 줄. */
@Entity
@Table(name = "COM_CD_DTL")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComCd {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "code_id")
    private Long id;

    /** 코드를 묶는 그룹(예: "ROLE"). 화면에서는 이 값으로 select를 채운다. */
    @Column(name = "code_group", nullable = false)
    private String codeGroup;

    /** select의 option value로 쓰이는 실제 코드값(예: "ADMIN"). */
    @Column(name = "code_value", nullable = false)
    private String codeValue;

    /** select에 보여줄 라벨. */
    @Column(name = "code_name", nullable = false)
    private String codeName;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    /** 사용여부. "Y" 또는 "N" — "N"인 코드는 select에서 제외된다. */
    @Column(name = "use_yn", nullable = false)
    private String useYn;

    @Column(name = "remark")
    private String remark;

    /**
     * 코드 속성 — 코드마다 프로그램이 읽는 값(비고는 사람이 읽는 설명). 그룹마다 뜻이 다르다:
     * TREND_SILHOUETTE = 축(FIT·LENGTH·SHAPE), TREND_MATERIAL = IMAGE(사진으로 구분되는 소재), TREND_CATEGORY = 일상 표현
     * 쉼표 목록(재킷,바지…), TREND_VALUE_TYPE = RAG(답변에 참고 집계로 보냄). 그룹의 비고에 무엇을 쓰는지 적는다.
     */
    @Column(name = "attr1", length = 500)
    private String attr1;

    /** 속성을 쉼표로 나눈 값(앞뒤 공백 제거, 빈 값 제외). */
    public java.util.List<String> attrValues() {
        if (attr1 == null || attr1.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(attr1.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
    }

    /** 속성이 비어 있을 때만 기본값을 넣는다(DataInitializer — 화면에서 고친 값은 덮어쓰지 않는다). 넣었으면 true. */
    public boolean fillAttr1IfBlank(String value) {
        if (value == null || (attr1 != null && !attr1.isBlank())) {
            return false;
        }
        attr1 = value;
        return true;
    }
}
