package com.sjinc.trendanalysis.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sjinc.trendanalysis.dto.rag.RagAnswerView;
import com.sjinc.trendanalysis.dto.rag.RagQuestionRequest;
import com.sjinc.trendanalysis.service.rag.RagService;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P-06 챗봇 평가 — 대표 질문 세트(ai/evals/rag_questions.json)를 챗봇과 같은 처리(RagService)로 돌려 답을 파일로 남기고 끝낸다.
 * --rag.eval.questions=질문 파일로 띄울 때만 돈다(HTTP·로그인을 거치지 않는다). 채점은 ai/evals/judge.py가 한다.
 *
 * <p>도구 사용 구조로 바꾸기 전후를 같은 세트로 비교하려고 만들었다(2026-10-23 사용자 결정). 이어 묻기 질문(followup_of)은 앞 질문의
 * 답(다시 쓴 질문·해석)을 화면처럼 앞 대화로 넣는다. 실제 Claude를 부른다(질문당 해석 1회 + 답변 1회).
 */
@Component
@ConditionalOnProperty(name = "rag.eval.questions")
@Order(Ordered.LOWEST_PRECEDENCE) // 공통코드 시드·기동 집계 뒤에
@RequiredArgsConstructor
@Slf4j
public class RagEvalRunner implements ApplicationRunner {

    private final RagService ragService;
    private final ObjectMapper objectMapper;
    private final ApplicationContext context;

    @Value("${rag.eval.questions}")
    private String questionsPath;
    @Value("${rag.eval.out:rag-eval-answers.json}")
    private String outPath;

    @Override
    public void run(ApplicationArguments args) throws IOException {
        JsonNode root = objectMapper.readTree(new File(questionsPath));
        Map<String, Turned> answered = new HashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode q : root.path("questions")) {
            String id = q.path("id").asText();
            String text = q.path("question").asText();
            Turned previous = answered.get(q.path("followup_of").asText(""));
            List<RagQuestionRequest.Turn> history = previous == null ? List.of() : List.of(previous.turn());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("question", text);
            long started = System.currentTimeMillis();
            try {
                RagAnswerView view = ragService.ask(text, history);
                row.put("answer", view);
                answered.put(id, new Turned(text, view));
            } catch (RuntimeException e) {
                row.put("error", e.getMessage()); // 한 문항이 실패해도 나머지는 돈다
            }
            row.put("elapsedMs", System.currentTimeMillis() - started);
            rows.add(row);
            log.info("평가 {} {}ms {}", id, row.get("elapsedMs"), row.containsKey("error") ? "오류: " + row.get("error") : "완료");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("questions", questionsPath);
        out.put("ranAt", LocalDateTime.now().toString());
        out.put("results", rows);
        objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(new File(outPath), out);
        log.info("평가 답 {}건을 {}에 저장했습니다. 앱을 끝냅니다.", rows.size(), new File(outPath).getAbsolutePath());
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    /** 이어 묻기용 — 화면처럼 다시 쓴 질문과 해석(콕 집은 기간만)을 앞 대화로 넘긴다. */
    private record Turned(String question, RagAnswerView view) {
        RagQuestionRequest.Turn turn() {
            RagAnswerView.Interpretation i = view.interpretation();
            if (i == null) {
                return new RagQuestionRequest.Turn(question, false, null, null, null, null, null);
            }
            RagAnswerView.Period p = i.period();
            return new RagQuestionRequest.Turn(i.resolvedQuestion() == null ? question : i.resolvedQuestion(), i.trend(),
                    i.category(), i.itemType(), p == null ? null : p.label(), p == null ? null : p.from().toString(),
                    p == null ? null : p.to().toString());
        }
    }
}
