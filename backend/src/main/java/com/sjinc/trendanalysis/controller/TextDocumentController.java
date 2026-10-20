package com.sjinc.trendanalysis.controller;

import com.sjinc.trendanalysis.dto.analysis.AnalysisView;
import com.sjinc.trendanalysis.dto.analysis.TextDocumentRequest;
import com.sjinc.trendanalysis.dto.analysis.TextDocumentView;
import com.sjinc.trendanalysis.security.RequiresProgram;
import com.sjinc.trendanalysis.service.analysis.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/** 텍스트 등록 화면이 호출하는 REST API. "text-upload" 권한이 있어야 한다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/texts")
@RequiresProgram("text-upload")
public class TextDocumentController {

    private final DocumentService documentService;

    @GetMapping
    public List<TextDocumentView> getTexts() {
        return documentService.getTexts();
    }

    /** 등록하면 분석(P-03·P-04) 대기열에 넣고 바로 돌아간다. */
    @PostMapping
    public TextDocumentView register(@RequestBody TextDocumentRequest request, Principal principal) {
        return documentService.registerText(request, principal.getName());
    }

    @DeleteMapping
    public void delete(@RequestBody List<Long> ids) {
        documentService.deleteTexts(ids);
    }

    @GetMapping("/{id}/analysis")
    public AnalysisView getAnalysis(@PathVariable Long id) {
        return documentService.getTextAnalysis(id);
    }

    @PostMapping("/{id}/reanalyze")
    public void reanalyze(@PathVariable Long id) {
        documentService.reanalyzeText(id);
    }
}
