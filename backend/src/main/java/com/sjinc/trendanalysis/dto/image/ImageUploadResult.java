package com.sjinc.trendanalysis.dto.image;

import java.util.List;

/**
 * 업로드 결과. 이미 올린 이미지(파일 내용이 같은 것)는 저장하지 않고 duplicates로 알려 준다.
 *
 * @param uploaded   새로 저장한 이미지
 * @param duplicates 건너뛴 파일 — existingName은 먼저 올린 같은 이미지의 파일명(같은 요청 안의 중복이면 그 요청의 앞 파일)
 */
public record ImageUploadResult(List<ImageView> uploaded, List<Duplicate> duplicates) {

    public record Duplicate(String fileName, String existingName) {
    }
}
