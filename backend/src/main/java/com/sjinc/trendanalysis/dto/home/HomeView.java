package com.sjinc.trendanalysis.dto.home;

import java.time.LocalDate;
import java.util.List;

/**
 * 홈 대시보드 카드 두 장의 수치. 트렌드 수치는 집계가 있는 가장 최근 주(statsWeek) 기준이고, 집계가 없으면 statsWeek가 null이다.
 *
 * @param keywordCount 기준 주에 한 번이라도 나온 키워드 종류 수
 * @param pendingCount 분석 대기·처리 중 문서 수
 */
public record HomeView(LocalDate statsWeek, boolean currentWeek, int surgeKeywordCount, int newKeywordCount,
                       int keywordCount,
                       List<Bar> topKeywords, long doneCount, long pendingCount, long failedCount, List<Bar> topCategories) {

    /** 가로 막대 한 줄(fragments/rank-bars). 목록은 건수 내림차순 — 첫 행이 100% 기준. */
    public record Bar(String label, int count, boolean surge) {
    }
}
