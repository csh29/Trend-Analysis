package com.sjinc.trendanalysis.dto.trend;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 주 마감 현황(트렌드 분석 화면). 마감은 오래된 주부터 이어지므로 lastClosedWeek까지는 전부 마감, openFrom부터 열린 주다.
 *
 * @param lastClosedWeek 마지막 마감 주(월요일), 마감이 없으면 null
 * @param openFrom       첫 열린 주(월요일), 마감이 없으면 null(전부 열림)
 */
public record TrendCloseView(LocalDate lastClosedWeek, LocalDate openFrom, List<Close> closes, List<Log> logs) {

    public record Close(LocalDate weekStart, int docCount, int rowCount, LocalDateTime closedAt, String closedBy) {
    }

    /** action: CLOSE(마감)·CANCEL(마감 취소). */
    public record Log(LocalDate weekStart, String action, String actor, String reason, LocalDateTime actedAt) {
    }
}
