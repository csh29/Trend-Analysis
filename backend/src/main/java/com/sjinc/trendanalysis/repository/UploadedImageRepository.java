package com.sjinc.trendanalysis.repository;

import com.sjinc.trendanalysis.domain.UploadedImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface UploadedImageRepository extends JpaRepository<UploadedImage, Long> {

    /** 이미지 업로드 화면 목록 — 따로 올린 이미지만(텍스트 원문 첨부는 텍스트 등록 화면에서 본다), 최근에 올린 것부터. */
    List<UploadedImage> findByDocumentIdIsNullOrderByUploadedAtDescIdDesc();

    /** 원문 한 건에 첨부한 이미지(순서대로). */
    List<UploadedImage> findByDocumentIdOrderBySortOrderAscIdAsc(Long documentId);

    List<UploadedImage> findByDocumentIdIn(Collection<Long> documentIds);

    /** 업로드 중복 확인. */
    List<UploadedImage> findByContentHashIn(Collection<String> contentHashes);

    /** 해시 칸이 생기기 전에 올린 이미지. */
    List<UploadedImage> findByContentHashIsNull();
}
