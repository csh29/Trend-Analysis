package com.sjinc.trendanalysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 챗봇 질문·답 한 건(2026-10-29 사용자 요청 — 실제로 무엇을 묻는지, 답이 맞았는지 보려고). 관리 화면 "챗봇 질문 로그"에서 본다.
 * route: AGENT(도구 경로)·TREND(고정 집계 경로)·SEARCH(검색)·OFF_TOPIC(패션 외). feedback: UP·DOWN(답 아래 👍👎, 없으면 null).
 * 보관 기간은 rag.log.retention-days(기본 180일) — 지난 로그는 매일 지운다.
 */
@Entity
@Table(name = "rag_query_logs", indexes = @Index(columnList = "asked_at"))
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RagQueryLog {

    public static final String UP = "UP";
    public static final String DOWN = "DOWN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long id;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(name = "asked_at", nullable = false)
    private LocalDateTime askedAt;

    @Column(nullable = false, length = 600)
    private String question;

    /** 이어 묻기면 해석이 다시 쓴 질문(아니면 null). */
    @Column(name = "resolved_question", length = 600)
    private String resolvedQuestion;

    @Column(nullable = false, length = 20)
    private String route;

    private boolean grounded;

    /** 근거 없음·범위 밖 사유(NO_EVIDENCE·OFF_TOPIC…). */
    @Column(length = 40)
    private String reason;

    /** 화면에 보인 답 — 결론 줄 + 글머리 줄(또는 자유 형식 본문). */
    @Lob
    @Column(name = "answer_text")
    private String answerText;

    /** 출처 원문 ID(쉼표). */
    @Column(name = "source_doc_ids", length = 1000)
    private String sourceDocIds;

    @Column(name = "input_tokens")
    private long inputTokens;

    @Column(name = "output_tokens")
    private long outputTokens;

    @Column(name = "elapsed_ms")
    private long elapsedMs;

    @Column(length = 10)
    private String feedback;

    @Column(name = "feedback_at")
    private LocalDateTime feedbackAt;

    public void giveFeedback(String value) {
        this.feedback = value;
        this.feedbackAt = value == null ? null : LocalDateTime.now();
    }
}
