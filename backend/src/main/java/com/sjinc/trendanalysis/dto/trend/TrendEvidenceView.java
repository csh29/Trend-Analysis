package com.sjinc.trendanalysis.dto.trend;

import java.time.LocalDate;

/**
 * 키워드(값)별 근거 원문 한 건. statDate는 집계 날짜(게시일, 없으면 업로드일), statWeek는 실제로 센 주(월요일) —
 * 마감된 주의 날짜로 늦게 들어온 원문은 첫 열린 주에 세므로 statDate의 주와 다를 수 있다.
 */
public record TrendEvidenceView(String docId, String docType, String title, String summary, String media,
                                LocalDate statDate, LocalDate statWeek, String url) {
}
