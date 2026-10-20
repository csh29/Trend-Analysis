package com.sjinc.trendanalysis.dto.image;

import com.sjinc.trendanalysis.domain.UploadedImage;

import java.time.LocalDateTime;

/** 이미지 업로드 화면 그리드의 한 행. 저장 파일명(storedName)은 내보내지 않는다 — 파일은 id로만 연다. */
public record ImageView(Long id, String originalName, String contentType, long fileSize,
                       String uploadedBy, LocalDateTime uploadedAt, String url) {

    public static ImageView from(UploadedImage image) {
        return new ImageView(image.getId(), image.getOriginalName(), image.getContentType(), image.getFileSize(),
                image.getUploadedBy(), image.getUploadedAt(), "/api/images/" + image.getId() + "/file");
    }
}
