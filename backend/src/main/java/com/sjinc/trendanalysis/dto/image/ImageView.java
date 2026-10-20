package com.sjinc.trendanalysis.dto.image;

import com.sjinc.trendanalysis.domain.UploadedImage;
import com.sjinc.trendanalysis.dto.analysis.AnalysisSummary;

import java.time.LocalDateTime;

/**
 * 이미지 업로드 화면 그리드의 한 행. 저장 파일명(storedName)은 내보내지 않는다 — 파일은 id로만 연다.
 * analysis는 분석(P-03) 상태·요약이며, 분석 문서가 아직 없으면 null이다.
 */
public record ImageView(Long id, String originalName, String contentType, long fileSize,
                       String uploadedBy, LocalDateTime uploadedAt, String url, AnalysisSummary analysis) {

    public static ImageView from(UploadedImage image, AnalysisSummary analysis) {
        return new ImageView(image.getId(), image.getOriginalName(), image.getContentType(), image.getFileSize(),
                image.getUploadedBy(), image.getUploadedAt(), "/api/images/" + image.getId() + "/file", analysis);
    }
}
