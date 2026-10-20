package com.sjinc.trendanalysis.service.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Python AI 서비스(ai/main.py, FastAPI) 호출. Claude API 키는 Spring의 claude.api.key 하나를 출처로 두고 요청마다
 * X-Claude-Api-Key 헤더로 넘긴다 — AI 서비스는 키를 저장하지 않는다. 키를 로그·예외 메시지에 넣지 않는다.
 */
@Component
public class AiClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public AiClient(@Value("${ai.server.url:http://127.0.0.1:8001}") String baseUrl, ObjectMapper objectMapper) {
        // java.net.http 기반 — HttpURLConnection(SimpleClientHttpRequestFactory)은 401 응답의 본문을 버려서
        // AI 서비스가 보낸 실패 사유("키가 올바르지 않습니다")가 화면까지 오지 않았다.
        // HTTP/1.1 고정: 기본값(HTTP/2)은 평문 http에서 업그레이드를 시도하고, uvicorn이 그 요청의 본문을 받지 못해 422가 났다.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build());
        factory.setReadTimeout(Duration.ofMinutes(3)); // 이미지 정형화는 Claude 응답까지 수십 초 걸릴 수 있다
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CodeOption(String value, String name) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AllowedCodes(List<CodeOption> categories, List<CodeOption> materials, List<CodeOption> colors,
                               List<CodeOption> silhouettes) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StructureRequest(String docId, String docType, String model, String title, String text,
                                   String imageBase64, String imageMediaType, AllowedCodes allowed) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StructureResponse(String docId, Map<String, Object> result, Map<String, Object> usage) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record IndexRequest(String docId, String docType, String text, Map<String, Object> structured,
                               Map<String, String> names, Map<String, String> meta) {
    }

    public StructureResponse structure(StructureRequest request, String apiKey) {
        return call(() -> restClient.post().uri("/structure")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Claude-Api-Key", apiKey)
                .body(request)
                .retrieve()
                .body(StructureResponse.class));
    }

    /** P-04 색인. 넣은 청크 수. */
    public int index(IndexRequest request) {
        JsonNode body = call(() -> restClient.post().uri("/index")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class));
        return body == null ? 0 : body.path("chunks").asInt();
    }

    public void deleteIndex(String docId) {
        call(() -> restClient.delete().uri("/index/{docId}", docId).retrieve().toBodilessEntity());
    }

    private <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            throw toException(e);
        } catch (ResourceAccessException e) {
            throw new AiCallException("AI 서비스(" + baseUrl + ")에 연결하지 못했습니다. ai 폴더의 start-ai.cmd로 서비스를 띄웠는지 확인하세요.", true, e);
        }
    }

    /** FastAPI 오류 본문 {"detail": {"message", "retryable"}}를 읽는다. 형식이 다르면 5xx만 재시도한다. */
    private AiCallException toException(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        try {
            JsonNode detail = objectMapper.readTree(e.getResponseBodyAsString()).path("detail");
            if (detail.has("message")) {
                // 거절·잘림처럼 Claude가 응답한 뒤 실패하면 소모한 토큰(usage)도 같이 온다
                TokenUsage usage = detail.has("usage")
                        ? TokenUsage.of(objectMapper.convertValue(detail.path("usage"), new TypeReference<Map<String, Object>>() { }))
                        : TokenUsage.ZERO;
                return new AiCallException(detail.path("message").asText(), detail.path("retryable").asBoolean(status >= 500), e, usage);
            }
        } catch (Exception ignored) {
            // 본문이 JSON이 아니면 아래 일반 메시지로
        }
        return new AiCallException("AI 서비스 오류(" + status + ")", status >= 500, e);
    }
}
