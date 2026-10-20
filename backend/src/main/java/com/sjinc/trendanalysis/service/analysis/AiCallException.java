package com.sjinc.trendanalysis.service.analysis;

/** AI 서비스(FastAPI) 호출 실패. retryable이면 같은 요청을 다시 보내 볼 만하다(연결 실패·일시 오류·형식 오류). */
public class AiCallException extends RuntimeException {

    private final boolean retryable;
    /** Claude가 응답은 했지만 쓸 수 없던 경우(거절·잘림)에도 과금된 토큰. 응답 전 실패면 0. */
    private final TokenUsage usage;

    public AiCallException(String message, boolean retryable) {
        this(message, retryable, null, TokenUsage.ZERO);
    }

    public AiCallException(String message, boolean retryable, Throwable cause) {
        this(message, retryable, cause, TokenUsage.ZERO);
    }

    public AiCallException(String message, boolean retryable, Throwable cause, TokenUsage usage) {
        super(message, cause);
        this.retryable = retryable;
        this.usage = usage == null ? TokenUsage.ZERO : usage;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public TokenUsage getUsage() {
        return usage;
    }
}
