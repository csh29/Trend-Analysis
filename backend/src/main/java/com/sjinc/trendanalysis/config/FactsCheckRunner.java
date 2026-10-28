package com.sjinc.trendanalysis.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sjinc.trendanalysis.service.rag.QuestionInterpreter;
import com.sjinc.trendanalysis.service.rag.TrendFactsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 챗봇 유행 집계 검증 — 해석 결과(범위·속성·시즌·질문 단어·기간)를 직접 주고 판단 근거 집계(TrendFactsService)만 파일로 남기고 끝낸다.
 * --rag.facts-check.cases=조건 파일로 띄울 때만 돈다. Claude를 부르지 않는다. 같은 조건을 SQL로 센 값과 맞대는 것은
 * ai/evals/facts_check.py가 한다(2026-10-26 — 집계는 계산이라 틀리면 버그다).
 */
@Component
@ConditionalOnProperty(name = "rag.facts-check.cases")
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class FactsCheckRunner implements ApplicationRunner {

    private final TrendFactsService trendFactsService;
    private final ObjectMapper objectMapper;
    private final ApplicationContext context;

    @Value("${rag.facts-check.cases}")
    private String casesPath;
    @Value("${rag.facts-check.out:facts-check.json}")
    private String outPath;

    @Override
    public void run(ApplicationArguments args) throws IOException {
        JsonNode root = objectMapper.readTree(new File(casesPath));
        LocalDate today = root.hasNonNull("today") ? LocalDate.parse(root.get("today").asText()) : LocalDate.now();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode c : root.path("cases")) {
            QuestionInterpreter.Period period = c.hasNonNull("from") ? new QuestionInterpreter.Period(
                    LocalDate.parse(c.get("from").asText()), LocalDate.parse(c.get("to").asText()), "지정 기간", true) : null;
            Map<String, List<String>> filters = new LinkedHashMap<>();
            c.path("filters").fields().forEachRemaining(e -> filters.put(e.getKey(), strings(e.getValue())));
            QuestionInterpreter.Interpretation q = new QuestionInterpreter.Interpretation(true, text(c, "category"), text(c, "category"),
                    text(c, "itemType"), text(c, "itemType"), period, strings(c.path("terms")), strings(c.path("seasons")),
                    text(c, "focus"), filters);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.path("id").asText());
            row.put("facts", trendFactsService.count(q, today));
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("today", today.toString());
        out.put("results", rows);
        objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(new File(outPath), out);
        log.info("집계 검증 {}건을 {}에 저장했습니다. 앱을 끝냅니다.", rows.size(), new File(outPath).getAbsolutePath());
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    private static String text(JsonNode c, String field) {
        return c.hasNonNull(field) ? c.get(field).asText() : null;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asText()));
        return values;
    }
}
