package com.sjinc.trendanalysis.dto.analysis;

import java.time.LocalDate;
import java.util.List;

/**
 * 분석 결과 조회 화면의 한 줄 — 분석이 끝난 원문 하나. 카테고리·컬러·패턴·실루엣은 조회 조건(카테고리·종류)에 맞은 의류 아이템의 값
 * 이름(중복 없이, 나온 순서), 키워드는 원문 단위. imageUrl은 사진이 없는 텍스트 원문이면 null.
 */
public record AnalysisResultRow(String docId, String docType, String title, String media, LocalDate date, String imageUrl,
                                List<String> categories, List<String> itemTypes, List<String> keywords, List<String> colors,
                                List<String> patterns, List<String> silhouettes) {
}
