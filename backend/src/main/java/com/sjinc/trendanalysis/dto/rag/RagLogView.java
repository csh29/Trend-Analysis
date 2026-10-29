package com.sjinc.trendanalysis.dto.rag;

import com.sjinc.trendanalysis.domain.RagQueryLog;

import java.time.LocalDateTime;

/** 챗봇 질문 로그 화면의 한 줄. */
public record RagLogView(Long id, LocalDateTime askedAt, String username, String question, String resolvedQuestion, String route,
                         boolean grounded, String reason, String answerText, String sourceDocIds, long inputTokens,
                         long outputTokens, long elapsedMs, String feedback) {

    public static RagLogView of(RagQueryLog l) {
        return new RagLogView(l.getId(), l.getAskedAt(), l.getUsername(), l.getQuestion(), l.getResolvedQuestion(), l.getRoute(),
                l.isGrounded(), l.getReason(), l.getAnswerText(), l.getSourceDocIds(), l.getInputTokens(), l.getOutputTokens(),
                l.getElapsedMs(), l.getFeedback());
    }
}
