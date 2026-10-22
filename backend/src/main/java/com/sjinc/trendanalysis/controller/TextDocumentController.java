package com.sjinc.trendanalysis.controller;

import com.sjinc.trendanalysis.dto.analysis.AnalysisView;
import com.sjinc.trendanalysis.dto.analysis.TextDocumentRequest;
import com.sjinc.trendanalysis.dto.analysis.TextDocumentView;
import com.sjinc.trendanalysis.dto.analysis.TextRegistrationResult;
import com.sjinc.trendanalysis.security.RequiresProgram;
import com.sjinc.trendanalysis.service.analysis.DocumentService;
import com.sjinc.trendanalysis.service.analysis.TextRegistrationService;
import com.sjinc.trendanalysis.service.image.ImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;

/** 텍스트 등록 화면이 호출하는 REST API. "text-upload" 권한이 있어야 한다. 원문 1건 = 본문 + 첨부 이미지 0~5장. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/texts")
@RequiresProgram("text-upload")
public class TextDocumentController {

    private final DocumentService documentService;
    private final TextRegistrationService textRegistrationService;
    private final ImageService imageService;

    @GetMapping
    public List<TextDocumentView> getTexts() {
        return documentService.getTexts();
    }

    /** 본문만 등록(JSON). 등록하면 분석(P-03·P-04) 대기열에 넣고 바로 돌아간다. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public TextDocumentView register(@RequestBody TextDocumentRequest request, Principal principal) {
        return documentService.registerText(request, principal.getName());
    }

    /** 본문 + 이미지 등록(multipart: "request" = JSON, "files" = 이미지 여러 장). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TextRegistrationResult registerWithImages(@RequestPart("request") TextDocumentRequest request,
                                                     @RequestPart(value = "files", required = false) List<MultipartFile> files,
                                                     Principal principal) {
        return textRegistrationService.register(request, files, principal.getName());
    }

    /** 원문과 분석 결과·색인·첨부 이미지를 함께 지운다. */
    @DeleteMapping
    public void delete(@RequestBody List<Long> ids) {
        textRegistrationService.delete(ids);
    }

    @GetMapping("/{id}/analysis")
    public AnalysisView getAnalysis(@PathVariable Long id) {
        return documentService.getTextAnalysis(id);
    }

    @PostMapping("/{id}/reanalyze")
    public void reanalyze(@PathVariable Long id) {
        documentService.reanalyzeText(id);
    }

    /** 첨부 이미지 보기 — 이미지 업로드 권한 없이 텍스트 등록 권한으로 연다. 그 원문의 이미지만 열린다. */
    @GetMapping("/{id}/images/{imageId}/file")
    public ResponseEntity<Resource> getImage(@PathVariable Long id, @PathVariable Long imageId) {
        ImageService.ImageFile file = imageService.getAttachmentFile(id, imageId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.originalName(), StandardCharsets.UTF_8).build().toString())
                .body(file.resource());
    }
}
