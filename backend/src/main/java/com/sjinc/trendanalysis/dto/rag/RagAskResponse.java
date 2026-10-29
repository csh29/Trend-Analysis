package com.sjinc.trendanalysis.dto.rag;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** 챗봇 답 + 질문 로그 ID(👍👎를 보낼 때 쓴다 — 로그 저장이 실패했으면 null). JSON은 답 필드에 logId가 붙은 모양이다. */
public record RagAskResponse(@JsonUnwrapped RagAnswerView view, Long logId) {
}
