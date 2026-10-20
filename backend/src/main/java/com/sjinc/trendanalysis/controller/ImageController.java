package com.sjinc.trendanalysis.controller;

import com.sjinc.trendanalysis.dto.image.ImageView;
import com.sjinc.trendanalysis.security.RequiresProgram;
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

    @GetMapping
    public List<ImageView> getImages() {
        return imageService.getImages();
    }

    /** 여러 장을 한 번에 올린다(multipart의 같은 이름 "files"로 여러 개). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<ImageView> upload(@RequestParam("files") List<MultipartFile> files, Principal principal) {
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
}
