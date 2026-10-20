package com.sjinc.trendanalysis.service.analysis;

import java.util.Map;

/** Claude 토큰 사용량(AI 서비스 응답의 usage). 로그용 — 재시도 합계·기동 후 누적을 낸다. */
public record TokenUsage(long input, long output, long cacheCreation, long cacheRead) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0, 0);

    /** usage 맵이 없거나 필드가 빠져 있으면 0으로 본다(Claude 응답 전에 실패한 호출). */
    public static TokenUsage of(Map<String, Object> usage) {
        if (usage == null) {
            return ZERO;
        }
        return new TokenUsage(number(usage, "input_tokens"), number(usage, "output_tokens"),
                number(usage, "cache_creation_input_tokens"), number(usage, "cache_read_input_tokens"));
    }

    private static long number(Map<String, Object> usage, String key) {
        return usage.get(key) instanceof Number n ? n.longValue() : 0;
    }

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(input + other.input, output + other.output,
                cacheCreation + other.cacheCreation, cacheRead + other.cacheRead);
    }

    public long total() {
        return input + output + cacheCreation + cacheRead;
    }

    @Override
    public String toString() {
        return "in=" + input + " out=" + output + " (캐시 생성 " + cacheCreation + "·읽기 " + cacheRead + ")";
    }
}
