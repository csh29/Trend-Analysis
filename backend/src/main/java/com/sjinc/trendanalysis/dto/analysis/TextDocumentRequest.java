package com.sjinc.trendanalysis.dto.analysis;

import java.time.LocalDate;

/** 텍스트 등록 화면의 "등록" 요청. 매체명·게시일을 비우면 "직접 등록"·오늘로 채운다(테스트 단계 결정, 2026-10-20). */
public record TextDocumentRequest(String title, String body, String media, LocalDate publishedAt, String url) {
}
