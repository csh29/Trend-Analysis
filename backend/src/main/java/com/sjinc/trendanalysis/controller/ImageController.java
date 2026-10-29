package com.sjinc.trendanalysis.controller;

import com.sjinc.trendanalysis.dto.analysis.AnalysisView;
import com.sjinc.trendanalysis.dto.image.ImageUploadResult;
import com.sjinc.trendanalysis.dto.image.ImageView;
import com.sjinc.trendanalysis.security.RequiresProgram;
import com.sjinc.trendanalysis.service.analysis.DocumentService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;

/** 이미지 업로드 화면이 호출하는 REST API. "img-upload" 권한이 있어야 한다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/images")
@RequiresProgram("img-upload")
public class ImageController {

    private final ImageService imageService;
    private final DocumentService documentService;

    @GetMapping
    public List<ImageView> getImages() {
        return imageService.getImages();
    }

    /** 여러 장을 한 번에 올린다(multipart의 같은 이름 "files"로 여러 개). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImageUploadResult upload(@RequestParam("files") List<MultipartFile> files, Principal principal) {
        return imageService.upload(files, principal.getName());
    }

    /** 썸네일·원본 보기. inline으로 내려주되 형식은 업로드 때 파일 내용으로 확인한 값을 쓴다. */
    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> getFile(@PathVariable Long id) {
        ImageService.ImageFile file = imageService.getFile(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.originalName(), StandardCharsets.UTF_8).build().toString())
                .body(file.resource());
    }

    @DeleteMapping
    public void delete(@RequestBody List<Long> ids) {
        imageService.delete(ids);
    }

    /** 분석(P-03) 결과 보기 — 정형 결과·출처·상태. */
    @GetMapping("/{id}/analysis")
    public AnalysisView getAnalysis(@PathVariable Long id) {
        return documentService.getImageAnalysis(id);
    }

    /** 선택한 이미지를 한 번에 다시 분석한다(최대 200건, 분석 중인 것은 건너뛴다). */
    @PostMapping("/reanalyze")
    public com.sjinc.trendanalysis.dto.analysis.ReanalyzeResult reanalyzeAll(@RequestBody List<Long> ids) {
        return documentService.reanalyzeImages(ids);
    }

    /** 실패했거나 결과가 마음에 들지 않을 때 다시 분석한다(대기열에 넣고 바로 돌아간다). */
    @PostMapping("/{id}/reanalyze")
    public void reanalyze(@PathVariable Long id) {
        documentService.reanalyzeImage(id);
    }
}
