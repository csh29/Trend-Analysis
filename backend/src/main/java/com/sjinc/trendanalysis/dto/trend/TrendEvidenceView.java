package com.sjinc.trendanalysis.dto.trend;

import java.time.LocalDate;

/** 키워드(값)별 근거 원문 한 건. statDate는 집계에 쓴 날짜(게시일, 없으면 업로드일). */
public record TrendEvidenceView(String docId, String docType, String title, String summary, String media,
                                LocalDate statDate, String url) {
}
