package com.sjinc.trendanalysis.controller;

import com.sjinc.trendanalysis.dto.rag.RagAnswerView;
import com.sjinc.trendanalysis.dto.rag.RagQuestionRequest;
import com.sjinc.trendanalysis.service.rag.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 홈의 질문창(P-06 RAG)이 호출하는 REST API. 홈은 프로그램이 아니라서 로그인만 요구한다(@RequiresProgram 없음).
 * 답의 근거는 분석이 끝난 원문 요약이다 — 업로드 화면 권한이 없어도 요약·출처는 보인다(원문 이미지는 권한이 있어야 열린다).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;

    /** 질문 한 건에 답한다(요청 시 실시간 — 검색·Claude 답변까지 수 초~수십 초). */
    @PostMapping("/ask")
    public RagAnswerView ask(@RequestBody RagQuestionRequest request) {
        return ragService.ask(request.question());
    }
}
