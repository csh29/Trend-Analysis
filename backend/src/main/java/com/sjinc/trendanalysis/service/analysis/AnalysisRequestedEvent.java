package com.sjinc.trendanalysis.service.analysis;

import java.util.List;

/**
 * 원문 문서를 분석 대기열에 넣었다는 이벤트. 등록(DocumentService)과 분석(AnalysisService)을 이벤트로 떼어
 * 업로드 요청이 Claude 응답을 기다리지 않게 하고, 이미지 서비스 ↔ 분석 서비스 사이의 순환 의존을 피한다.
 */
public record AnalysisRequestedEvent(List<Long> documentIds) {
}
