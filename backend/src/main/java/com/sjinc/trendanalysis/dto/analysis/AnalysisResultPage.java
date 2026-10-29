package com.sjinc.trendanalysis.dto.analysis;

import java.util.List;

/** 분석 결과 조회 한 페이지 — page는 0부터, total은 조건에 맞는 전체 원문 수. */
public record AnalysisResultPage(List<AnalysisResultRow> rows, int page, int size, long total) {
}
