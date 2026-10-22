package com.sjinc.trendanalysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 이미지 한 장 — 이미지 업로드 화면에서 따로 올린 것이거나 텍스트 원문에 첨부한 것(documentId). 파일 자체는 디스크(image.upload.dir)에 storedName으로 두고 여기엔 메타정보만 둔다.
 * 원본 파일명은 표시용일 뿐 저장 경로에 쓰지 않는다 — 사용자가 준 이름을 경로에 쓰면 경로 조작·덮어쓰기가 된다.
 */
@Entity
@Table(name = "uploaded_images", indexes = {@Index(columnList = "content_hash"), @Index(columnList = "document_id")})
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

    /**
     * 파일 내용의 SHA-256(16진수). 같은 이미지를 두 번 올리면 같은 문서가 두 번 집계되고 분석 토큰도 두 번 든다 — 업로드 때 이 값으로 거른다.
     * 이 칸이 생기기 전에 올린 이미지는 기동 때 채운다(ImageService.fillMissingHashes). 그 전 중복이 남아 있을 수 있어 유일 제약은 두지 않는다.
     */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    /**
     * 본문이 있는 원문(기사·게시물)에 딸린 이미지면 그 원문 문서 id(SourceDocument.id). 원문 1건 = 본문 + 이미지 N장
     * (2026-10-21 사용자 결정) — 본문과 사진을 한 번에 분석해 같은 트렌드가 여러 번 세지지 않게 한다.
     * 이미지 업로드 화면에서 따로 올린 이미지(본문 없는 원문)는 null이고, 그 원문은 SourceDocument.imageId로 이 행을 가리킨다.
     */
    @Column(name = "document_id")
    private Long documentId;

    /** 원문 안 이미지 순서(0부터). 따로 올린 이미지는 null. */
    @Column(name = "sort_order")
    private Integer sortOrder;

    public void assignContentHash(String contentHash) {
        this.contentHash = contentHash;
    }
}
