package com.sjinc.trendanalysis.dto.analysis;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 분석 결과 보기(이미지 업로드·텍스트 등록 화면의 결과 모달). 코드값과 이름을 함께 내려 화면이 공통코드를 다시 읽지 않게 한다.
 * 분석 전이거나 실패했으면 result는 null이다.
 */
public record AnalysisView(Long documentId, String docId, String docType, String title, String status,
                           String failReason, int attempts, LocalDateTime analyzedAt,
                           String media, LocalDate publishedAt, String url, List<Image> images, Result result) {

    /** 텍스트 원문에 첨부한 이미지(순서대로). url은 그 원문 화면 권한으로 여는 주소다. 이미지만 있는 원문은 비어 있다. */
    public record Image(Long id, String name, String url) {
    }

    public record Code(String value, String name) {
    }

    /** 의류 아이템 하나. type은 아이템 종류(종류 도입 전 결과는 null — 재분석하면 생긴다). genders는 대상 라인(0~1개). */
    public record Item(Code category, String name, Code type, List<Code> materials, List<Code> colors, List<Code> silhouettes,
                       List<Code> genders) {
    }

    public record Result(List<Item> items, List<String> keywords, List<Code> seasons, String summary,
                         String json) {
    }
}
