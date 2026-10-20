package com.sjinc.trendanalysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 이미지 업로드 화면에서 올린 이미지 한 장. 파일 자체는 디스크(image.upload.dir)에 storedName으로 두고 여기엔 메타정보만 둔다.
 * 원본 파일명은 표시용일 뿐 저장 경로에 쓰지 않는다 — 사용자가 준 이름을 경로에 쓰면 경로 조작·덮어쓰기가 된다.
 */
@Entity
@Table(name = "uploaded_images")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UploadedImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "image_id")
    private Long id;

    /** 사용자가 올린 파일명(화면 표시·다운로드 이름). */
    @Column(name = "original_name", nullable = false)
    private String originalName;

    /** 디스크에 저장한 이름(UUID + 확장자). */
    @Column(name = "stored_name", nullable = false, unique = true)
    private String storedName;

    /** 파일 내용으로 확인한 이미지 형식의 MIME 타입(브라우저가 보낸 값이 아니다). */
    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "uploaded_by", nullable = false)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;
}
