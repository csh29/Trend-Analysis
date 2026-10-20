package com.sjinc.trendanalysis.repository;

import com.sjinc.trendanalysis.domain.UploadedImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UploadedImageRepository extends JpaRepository<UploadedImage, Long> {

    /** 이미지 업로드 화면 목록 — 최근에 올린 것부터. */
    List<UploadedImage> findAllByOrderByUploadedAtDescIdDesc();
}
