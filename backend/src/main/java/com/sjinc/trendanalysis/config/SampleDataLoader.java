package com.sjinc.trendanalysis.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjinc.trendanalysis.dto.analysis.TextDocumentRequest;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.service.analysis.DocumentService;
import com.sjinc.trendanalysis.service.trend.TrendAggregator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;

/**
 * P-05 확인용 샘플 텍스트(6주 분량, sample/trend-sample-texts.json)를 등록한다. 기본은 꺼져 있고
 * --trend.sample.load=true로 띄울 때만 돈다. 제목이 "[샘플]"로 시작하는 문서가 이미 있으면 다시 넣지 않는다.
 *
 * <p>날짜는 기동한 날 기준 "몇 주 전 무슨 요일"로 만든다 — 언제 넣어도 최근 6주에 흐름(레더·니트 상승, 숏패딩 신규,
 * 데님 유지, 린넨 하락)이 보이게. 등록하면 일반 텍스트처럼 실제 Claude 분석을 거친다(토큰 사용).
 */
@Component
@ConditionalOnProperty(name = "trend.sample.load", havingValue = "true")
@Order(Ordered.LOWEST_PRECEDENCE) // DataInitializer가 표준 코드를 심은 뒤에 — 먼저 돌면 분석이 "공통코드 없음"으로 실패한다
@RequiredArgsConstructor
@Slf4j
public class SampleDataLoader implements ApplicationRunner {

    static final String RESOURCE = "sample/trend-sample-texts.json";
    static final String TITLE_PREFIX = "[샘플]";
    static final String REGISTERED_BY = "sample-loader";

    record SampleText(int weeksAgo, int day, String media, String title, String body) {
    }

    private final SourceDocumentRepository sourceDocumentRepository;
    private final DocumentService documentService;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (sourceDocumentRepository.existsByTitleStartingWith(TITLE_PREFIX)) {
            log.info("샘플 텍스트가 이미 있어 건너뜁니다(제목이 {}로 시작하는 문서).", TITLE_PREFIX);
            return;
        }
        List<SampleText> samples;
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            samples = objectMapper.readValue(in, new TypeReference<>() { });
        }
        LocalDate today = LocalDate.now();
        LocalDate monday = TrendAggregator.weekStart(today);
        for (SampleText sample : samples) {
            LocalDate date = monday.minusWeeks(sample.weeksAgo()).plusDays(sample.day());
            documentService.registerText(new TextDocumentRequest(sample.title(), sample.body(), sample.media(),
                    date.isAfter(today) ? today : date, null), REGISTERED_BY);
        }
        log.info("샘플 텍스트 {}건을 등록했습니다. 분석이 끝나면 트렌드 분석 화면에서 \"집계\"를 누르세요.", samples.size());
    }
}
