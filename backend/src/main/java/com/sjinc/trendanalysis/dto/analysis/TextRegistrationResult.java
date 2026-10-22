package com.sjinc.trendanalysis.dto.analysis;

import com.sjinc.trendanalysis.dto.image.ImageUploadResult;

import java.util.List;

/** 텍스트 원문 등록 결과. skippedImages는 이미 올린 이미지와 같아 첨부하지 않은 파일이다. */
public record TextRegistrationResult(TextDocumentView document, List<ImageUploadResult.Duplicate> skippedImages) {
}
