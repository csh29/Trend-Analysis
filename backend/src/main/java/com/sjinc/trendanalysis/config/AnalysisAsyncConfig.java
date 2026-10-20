package com.sjinc.trendanalysis.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 분석(P-03·P-04) 전용 스레드. 동시에 2건만 돌린다 — 이미지 50장을 한꺼번에 올려도 Claude 요청 한도(429)에
 * 몰리지 않게 하고, 내장형 Chroma에 쓰는 요청도 그만큼만 겹친다. 나머지는 대기열에서 차례를 기다린다.
 */
@Configuration
@EnableAsync
public class AnalysisAsyncConfig {

    public static final String EXECUTOR = "analysisExecutor";

    @Bean(name = EXECUTOR)
    public Executor analysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10_000);
        executor.setThreadNamePrefix("analysis-");
        executor.initialize();
        return executor;
    }
}
