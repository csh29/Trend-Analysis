package com.sjinc.trendanalysis.controller;

import com.sjinc.trendanalysis.dto.rag.RagLogView;
import com.sjinc.trendanalysis.security.RequiresProgram;
import com.sjinc.trendanalysis.service.rag.RagLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 챗봇 질문 로그 화면이 호출하는 REST API. "rag-log" 권한이 있어야 한다(질문에 사내 기획 정보가 들어갈 수 있다). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rag-logs")
@RequiresProgram("rag-log")
public class RagLogController {

    private final RagLogService ragLogService;

    /** 조회 조건: 기간(질문한 날, 기본 최근 30일)·피드백(UP·DOWN·NONE)·경로(AGENT·TREND·SEARCH·OFF_TOPIC). */
    @GetMapping
    public List<RagLogView> search(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                   @RequestParam(required = false) String feedback,
                                   @RequestParam(required = false) String route) {
        return ragLogService.search(from, to, feedback, route);
    }
}
