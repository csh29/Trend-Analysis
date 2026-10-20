package com.sjinc.trendanalysis.service.analysis;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TokenUsageTest {

    @Test
    void AI_서비스_usage를_읽고_재시도분을_더한다() {
        TokenUsage first = TokenUsage.of(Map.of("input_tokens", 1200, "output_tokens", 300,
                "cache_creation_input_tokens", 0, "cache_read_input_tokens", 50, "elapsed_ms", 4100));
        TokenUsage second = TokenUsage.of(Map.of("input_tokens", 1000, "output_tokens", 200));

        TokenUsage sum = first.plus(second);

        assertThat(sum).isEqualTo(new TokenUsage(2200, 500, 0, 50));
        assertThat(sum.total()).isEqualTo(2750);
    }

    @Test
    void usage가_없으면_0이다() {
        assertThat(TokenUsage.of(null)).isEqualTo(TokenUsage.ZERO);
        assertThat(new AiCallException("연결 실패", true).getUsage()).isEqualTo(TokenUsage.ZERO);
    }
}
