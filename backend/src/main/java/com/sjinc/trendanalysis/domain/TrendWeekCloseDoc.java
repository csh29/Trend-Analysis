package com.sjinc.trendanalysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 마감한 주에 센 원문(마감 때 고정). 원문 하나는 한 주에만 들어간다. 집계는 이 원문을 다시 세지 않고,
 * 근거 원문 화면은 마감된 주를 이 목록으로 보여 준다. 원문을 지워도 이 행은 남긴다(마감 당시 기록) — 근거 원문에서만 빠진다.
 */
@Entity
@Table(name = "trend_week_close_docs",
        uniqueConstraints = @UniqueConstraint(columnNames = "document_id"),
        indexes = @Index(columnList = "week_start"))
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class TrendWeekCloseDoc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    /** SourceDocument.id. */
    @Column(name = "document_id", nullable = false)
    private Long documentId;
}
