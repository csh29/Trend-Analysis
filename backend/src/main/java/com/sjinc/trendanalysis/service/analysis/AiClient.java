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
    public record AllowedCodes(List<CodeOption> categories, List<CodeOption> itemTypes, List<CodeOption> materials,
                               List<CodeOption> colors, List<CodeOption> silhouettes, List<CodeOption> seasons) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ImageInput(String data, String mediaType) {
    }

    /** 원문 1건 = 본문(text, TEXT만) + 이미지 0~5장. IMAGE는 본문 없이 이미지 한 장인 원문이다. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StructureRequest(String docId, String docType, String model, String title, String text,
                                   List<ImageInput> images, AllowedCodes allowed) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StructureResponse(String docId, Map<String, Object> result, Map<String, Object> usage) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record IndexRequest(String docId, String docType, String text, Map<String, Object> structured,
                               Map<String, String> names, Map<String, String> meta) {
    }

    /** P-06 주간 집계 한 줄 — 근거가 아니라 답변에 덧붙이는 참고 수치. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StatRow(String typeLabel, String label, int count, int prevCount, Double prevRate, boolean surge,
                          boolean isNew) {
    }

    /**
     * today는 최신성 가중치·기간 해석의 기준일 — 서버 날짜를 "2026-10-22" 문자열로 보낸다. 날짜는 이 클라이언트에서 모두 문자열로 주고받는다:
     * RestClient 기본 JSON 설정은 LocalDate를 [2026,10,22] 배열로 써서 AI 서비스가 422로 거부했다(2026-10-22).
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AnswerRequest(String question, String model, int topK, double minScore, String statsWeek,
                                List<StatRow> stats, String today, String mode, String category, AnswerPeriod period,
                                List<String> trendFacts, List<String> evidenceDocIds) {

        public static final String SEARCH = "SEARCH";
        public static final String TREND = "TREND";
    }

    /** 질문 해석 힌트 — 코드값·이름·일상 표현(카테고리 속성). */
    public record CodeHint(String value, String name, String words) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record InterpretRequest(String question, String model, String today, List<CodeHint> categories,
                                   List<CodeHint> itemTypes, List<InterpretTurn> history) {
    }

    /** 챗봇의 앞 질문과 그 해석(이어 묻기용). 기간은 콕 집은 질문만, 날짜는 문자열. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record InterpretTurn(String question, boolean trend, String category, String itemType, String periodLabel,
                                String periodFrom, String periodTo) {
    }

    /**
     * 작은 모델의 질문 해석. period는 질문에 기간이 있을 때만(없으면 null).
     * fashion이 false면 패션·트렌드 질문이 아니다 — null(예전 AI 서비스)은 패션 질문으로 본다.
     * question은 이어 묻기를 앞 대화로 채워 혼자서도 뜻이 통하게 다시 쓴 질문(이어 묻기가 아니면 원래 질문, 예전 AI 서비스는 null).
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record InterpretResponse(boolean trend, String category, String itemType, AnswerPeriod period,
                                    Map<String, Object> usage, Boolean fashion, String question) {

        public boolean offTopic() {
            return Boolean.FALSE.equals(fashion);
        }
    }

    /**
     * 근거의 기간(yyyy-MM-dd). explicit이면 질문이 정한 기간(그 범위로 잘랐다), 아니면 근거로 쓴 원문의 게시일 범위(최근 자료
     * 우선으로 골랐다 — 근거가 없으면 null). stale=가장 최근 근거도 8주가 넘었다.
     */
    public record AnswerPeriod(String from, String to, String label, boolean explicit, boolean stale) {
    }

    /** n은 답변 본문의 인용 번호([1]), chunk는 그 문서에서 가장 비슷했던 청크. score는 유행 질문(예시 원문)이면 null. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AnswerSource(int n, String docId, Double score, String chunk) {
    }

    /** 답의 한 줄과 그 줄의 근거 번호. */
    public record AnswerPoint(String text, List<Integer> sources) {
    }

    /**
     * answer는 결론 한 줄, points는 글머리 줄들. reason: 근거 없음이면 NO_EVIDENCE(기준 유사도 미달 — Claude를 부르지 않았다),
     * MODEL_DECLINED(근거가 질문과 맞지 않음).
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AnswerResponse(boolean grounded, String answer, List<AnswerPoint> points, List<AnswerSource> sources,
                                 Double bestScore, Map<String, Object> usage, String reason, AnswerPeriod period) {
    }

    public StructureResponse structure(StructureRequest request, String apiKey) {
        return call(() -> restClient.post().uri("/structure")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Claude-Api-Key", apiKey)
                .body(request)
                .retrieve()
                .body(StructureResponse.class));
    }

    /** P-06 RAG 답변. 검색·근거 판정은 AI 서비스가 한다. */
    public AnswerResponse answer(AnswerRequest request, String apiKey) {
        return call(() -> restClient.post().uri("/answer")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Claude-Api-Key", apiKey)
                .body(request)
                .retrieve()
                .body(AnswerResponse.class));
    }

    /** P-06 질문 해석(작은 모델). 실패하면 RagService가 규칙으로 대신한다. */
    public InterpretResponse interpret(InterpretRequest request, String apiKey) {
        return call(() -> restClient.post().uri("/interpret")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Claude-Api-Key", apiKey)
                .body(request)
                .retrieve()
                .body(InterpretResponse.class));
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

    /**
     * FastAPI 오류 본문 {"detail": {"message", "retryable"}}를 읽는다. 요청 형식 검사(422)에서 막히면 detail이 목록이라
     * 어느 필드인지(loc·msg)를 사유에 붙인다 — "AI 서비스 오류(422)"만으로는 원인을 알 수 없었다. 형식이 다르면 5xx만 재시도한다.
     */
    private AiCallException toException(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        try {
            JsonNode detail = objectMapper.readTree(e.getResponseBodyAsString()).path("detail");
            if (detail.isArray() && !detail.isEmpty()) {
                List<String> problems = new java.util.ArrayList<>();
                detail.forEach(d -> problems.add(d.path("loc").toString() + " " + d.path("msg").asText()));
                return new AiCallException("AI 서비스가 요청 형식을 거부했습니다(" + status + "): " + String.join(" / ", problems), false, e);
            }
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
