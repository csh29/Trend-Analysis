package com.sjinc.trendanalysis.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 정기 배치(P-05 트렌드 집계, 일 1회). 시각은 trend.aggregate.cron으로 바꾼다. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
