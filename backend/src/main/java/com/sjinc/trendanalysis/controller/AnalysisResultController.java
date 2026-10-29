package com.sjinc.trendanalysis.controller;

import com.sjinc.trendanalysis.dto.analysis.AnalysisResultRow;
import com.sjinc.trendanalysis.security.RequiresProgram;
import com.sjinc.trendanalysis.service.analysis.AnalysisResultService;
import com.sjinc.trendanalysis.service.image.ImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 분석 결과 조회 화면이 호출하는 REST API. "analysis-result" 권한이 있어야 한다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/analysis-results")
@RequiresProgram("analysis-result")
public class AnalysisResultController {

    private final AnalysisResultService analysisResultService;

    /** 조회 조건: 카테고리·아이템 종류 코드(없으면 전체). */
    @GetMapping
    public List<AnalysisResultRow> search(@RequestParam(required = false) String category,
                                          @RequestParam(required = false) String itemType) {
        return analysisResultService.search(category, itemType);
    }

    /** 행의 사진 — 이미지 업로드 권한 없이도 이 화면에서 보이게 따로 연다(분석이 끝난 원문만). */
    @GetMapping("/{docId}/image")
    public ResponseEntity<Resource> image(@PathVariable String docId) {
        ImageService.ImageFile file = analysisResultService.image(docId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.originalName(), StandardCharsets.UTF_8).build().toString())
                .body(file.resource());
    }
}
