package com.sjinc.trendanalysis.service.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AiClient가 실제로 보내는 JSON(가짜 AI 서비스로 받는다). 가짜 AiClient를 쓰는 API 테스트로는 직렬화가 드러나지 않는다 —
 * LocalDate가 [2026,10,22] 배열로 나가 AI 서비스가 422로 거부한 일이 있었다(2026-10-22).
 */
class AiClientWireTest {

    private HttpServer server;
    private final AtomicReference<String> received = new AtomicReference<>();

    private AiClient start(int status, String responseBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/answer", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new AiClient("http://127.0.0.1:" + server.getAddress().getPort(), new ObjectMapper());
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static AiClient.AnswerRequest request() {
        return new AiClient.AnswerRequest("요즘 와이드 팬츠", "claude-sonnet-5", 8, 0.6, "2026-10-19",
                List.of(new AiClient.StatRow("키워드", "와이드 팬츠", 3, 1, 200.0, true, false)),
                LocalDate.of(2026, 10, 22).toString());
    }

    @Test
    void 기준일은_문자열_날짜로_보내고_기간도_문자열로_받는다() throws Exception {
        AiClient client = start(200, """
                {"grounded": true, "answer": "결론", "points": [{"text": "줄", "sources": [1]}],
                 "sources": [{"n": 1, "doc_id": "IMG-1", "score": 0.7, "chunk": "청크"}], "best_score": 0.7,
                 "usage": {"input_tokens": 10}, "reason": null,
                 "period": {"from": null, "to": null, "label": "최근 자료 우선", "explicit": false, "stale": true}}
                """);

        AiClient.AnswerResponse response = client.answer(request(), "k");

        JsonNode sent = new ObjectMapper().readTree(received.get());
        assertThat(sent.path("today").isTextual()).isTrue();
        assertThat(sent.path("today").asText()).isEqualTo("2026-10-22");
        assertThat(sent.path("top_k").asInt()).isEqualTo(8);
        assertThat(sent.path("stats").get(0).path("is_new").isBoolean()).isTrue();
        assertThat(response.period().from()).isNull(); // 근거가 없으면 게시일 범위도 없다
        assertThat(response.period().stale()).isTrue();
        assertThat(response.points().get(0).sources()).containsExactly(1);
        assertThat(response.sources().get(0).docId()).isEqualTo("IMG-1");
    }

    @Test
    void 요청_형식_오류_422는_어느_필드인지_사유에_남긴다() throws Exception {
        AiClient client = start(422, """
                {"detail": [{"type": "date_type", "loc": ["body", "today"], "msg": "Input should be a valid date"}]}
                """);

        assertThatThrownBy(() -> client.answer(request(), "k"))
                .isInstanceOf(AiCallException.class)
                .hasMessageContaining("today")
                .hasMessageContaining("Input should be a valid date");
    }
}
