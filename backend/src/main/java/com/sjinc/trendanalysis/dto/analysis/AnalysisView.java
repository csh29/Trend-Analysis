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
                           String media, LocalDate publishedAt, String url, Result result) {

    public record Code(String value, String name) {
    }

    /** 의류 아이템 하나. */
    public record Item(Code category, String name, List<Code> materials, List<Code> colors, List<Code> silhouettes) {
    }

    public record Result(List<Item> items, List<String> keywords, List<Code> seasons, String sentiment, String summary,
                         String model, String json) {
    }
}
