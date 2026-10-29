package com.sjinc.trendanalysis.controller;

import com.sjinc.trendanalysis.dto.rag.RagAnswerView;
import com.sjinc.trendanalysis.dto.rag.RagQuestionRequest;
import com.sjinc.trendanalysis.service.rag.RagService;
import com.sjinc.trendanalysis.service.image.ImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 홈의 질문창(P-06 RAG)이 호출하는 REST API. 홈은 프로그램이 아니라서 로그인만 요구한다(@RequiresProgram 없음).
 * 답의 근거는 분석이 끝난 원문 요약이다 — 업로드 화면 권한이 없어도 요약·출처와 이미지 출처의 사진은 보인다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;
    private final com.sjinc.trendanalysis.service.rag.RagLogService ragLogService;
    private final com.sjinc.trendanalysis.service.rag.BriefService briefService;

    /** 질문 한 건에 답한다(요청 시 실시간 — 검색·Claude 답변까지 수 초~수십 초). 앞 대화는 이어 묻기 해석에만 쓴다. */
    @PostMapping("/ask")
    public com.sjinc.trendanalysis.dto.rag.RagAskResponse ask(@RequestBody RagQuestionRequest request, java.security.Principal principal) {
        long started = System.currentTimeMillis();
        com.sjinc.trendanalysis.service.rag.RagService.Answered answered = ragService.answer(request.question(), request.history());
        Long logId = ragLogService.record(principal.getName(), answered.view(), answered.usage(), System.currentTimeMillis() - started);
        return new com.sjinc.trendanalysis.dto.rag.RagAskResponse(answered.view(), logId);
    }

    /** 기획 브리프(PPT용) — 질문 범위를 여러 갈래로 세고 Claude가 8개 구성의 서술을 쓴다(수십 초, Claude 비용이 든다). */
    @PostMapping("/brief")
    public com.sjinc.trendanalysis.dto.rag.BriefView brief(@RequestBody RagQuestionRequest.Brief request) {
        return briefService.build(request.question());
    }

    /** 답 아래 👍👎 — 질문한 사람만. value는 UP·DOWN, 없으면 지운다. */
    @PostMapping("/feedback")
    public void feedback(@RequestBody RagQuestionRequest.Feedback request, java.security.Principal principal) {
        ragLogService.feedback(request.logId(), request.value(), principal.getName());
    }

    /** 이미지 출처 보기(홈 오른쪽 슬라이드 패널). 분석이 끝난 이미지 원문만 열린다. */
    @GetMapping("/sources/{docId}/image")
    public ResponseEntity<Resource> sourceImage(@PathVariable String docId) {
        ImageService.ImageFile file = ragService.sourceImage(docId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.originalName(), StandardCharsets.UTF_8).build().toString())
                .body(file.resource());
    }
}
