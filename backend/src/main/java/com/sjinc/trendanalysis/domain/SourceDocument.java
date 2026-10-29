package com.sjinc.trendanalysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 원문 문서(프로세스 정의서 "원문 문서") 한 건 — 분석(P-03 정형화·P-04 색인)의 단위. 이미지 한 장 또는 텍스트 한 건이다.
 * 출처(media·publishedAt)는 필수다 — 비면 정형화 결과를 저장하지 않는다(RAG 답변의 근거 표시가 이 값을 쓴다).
 */
@Entity
// 챗봇 집계는 상태·게시일 기간으로, 분석 결과 조회는 상태·게시일 순으로 읽는다
@Table(name = "source_documents", indexes = @jakarta.persistence.Index(columnList = "status, published_at"))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceDocument {

    public static final String TYPE_IMAGE = "IMAGE";
    public static final String TYPE_TEXT = "TEXT";

    public static final String PENDING = "PENDING";
    public static final String PROCESSING = "PROCESSING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "document_id")
    private Long id;

    /** 화면·색인·정형 JSON에 쓰는 문서 ID(예: IMG-20261020-000001). 저장 후 id로 만든다. */
    @Column(name = "doc_id", unique = true)
    private String docId;

    @Column(name = "doc_type", nullable = false, length = 10)
    private String docType;

    private String title;

    /** 텍스트 문서 본문. 이미지 문서는 비어 있다. */
    @Lob
    @Column(name = "body")
    private String body;

    /** 이미지 문서의 UploadedImage id. */
    @Column(name = "image_id")
    private Long imageId;

    @Column(nullable = false)
    private String media;

    @Column(name = "published_at", nullable = false)
    private LocalDate publishedAt;

    private String url;

    @Column(nullable = false, length = 20)
    private String status;

    /** 이번 분석에서 정형화를 시도한 횟수(재분석하면 0부터). */
    @Builder.Default
    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "fail_reason", length = 1000)
    private String failReason;

    @Column(name = "registered_by", nullable = false)
    private String registeredBy;

    @Column(name = "registered_at", nullable = false)
    private LocalDateTime registeredAt;

    @Column(name = "analyzed_at")
    private LocalDateTime analyzedAt;

    public boolean isImage() {
        return TYPE_IMAGE.equals(docType);
    }

    public void assignDocId(String docId) {
        this.docId = docId;
    }

    public void markPending() {
        this.status = PENDING;
        this.attempts = 0;
        this.failReason = null;
    }

    public void markProcessing(int attempts) {
        this.status = PROCESSING;
        this.attempts = attempts;
    }

    public void markDone() {
        this.status = DONE;
        this.failReason = null;
        this.analyzedAt = LocalDateTime.now();
    }

    public void markFailed(String reason) {
        this.status = FAILED;
        this.failReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 1000));
        this.analyzedAt = LocalDateTime.now();
    }
}
