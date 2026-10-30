package com.sjinc.trendanalysis.dto.trend;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 트렌드 분석 화면 한 번 조회분. rows는 기준 주의 값별 집계, series는 기간 안 언급량 상위 값의 주별 추이(차트).
 *
 * @param weeks 기간의 주(월요일) 목록 — series.counts와 순서가 같다
 * @param closed 기준 주가 마감됐는지(마감된 주의 수치는 다시 바뀌지 않는다)
 * @param lastClosedWeek 마지막 마감 주(월요일), 마감이 없으면 null
 */
public record TrendView(LocalDate baseWeek, List<LocalDate> weeks, List<Row> rows, List<Series> series,
                        int surgeCount, int newCount, LocalDateTime aggregatedAt, boolean closed, LocalDate lastClosedWeek) {

    public record Row(String value, String label, int count, int prevCount, Double prevRate, double monthAvg,
                      Double monthRate, boolean surge, boolean isNew) {
    }

    public record Series(String value, String label, List<Integer> counts) {
    }
}
