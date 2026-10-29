package com.sjinc.trendanalysis.dto.analysis;

import java.util.List;

/** 일괄 재분석 결과 — requested는 대기열에 넣은 건수, skipped는 이미 분석 중이라 건너뛴 원문 ID. */
public record ReanalyzeResult(int requested, List<String> skipped) {
}
