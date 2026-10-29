package com.sjinc.trendanalysis.service.rag;

import com.sjinc.trendanalysis.domain.ComCd;
import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.domain.TrendItem;
import com.sjinc.trendanalysis.domain.TrendItemValue;
import com.sjinc.trendanalysis.domain.TrendWeeklyStat;
import com.sjinc.trendanalysis.repository.ComCdRepository;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.repository.TrendItemRepository;
import com.sjinc.trendanalysis.service.analysis.AiCallException;
import com.sjinc.trendanalysis.service.analysis.AiClient;
import com.sjinc.trendanalysis.service.analysis.TokenUsage;
import com.sjinc.trendanalysis.service.analysis.TrendCodes;
import com.sjinc.trendanalysis.service.trend.TrendAggregationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * P-06 도구 경로 — 유행 질문은 모두 Claude가 집계 도구를 골라 불러 답한다(2026-10-29 사용자 결정: 질문 유형마다 칸·규칙을 두지 않는다).
 * Claude는 무엇을 셀지 고르고, 숫자는 늘 같은 집계 코드(TrendFactsService)가 센다.
 *
 * <ul>
 *   <li>반복은 여기서 한다 — AI 서비스 /agent/step은 Claude를 한 번 부르고 응답 블록을 돌려줄 뿐이다. 도구 결과는 다음 단계 messages로 넘긴다.</li>
 *   <li>해석 범위의 집계는 여기서 미리 세어 첫 메시지에 넣는다 — 순위·제외·특징 질문은 Claude 호출 한 번으로 끝나고, 비교·추이만 도구를 더 부른다
 *       (2026-10-29: Claude가 첫 count_trend를 부르느라 한 단계·입력 약 8,000토큰을 더 썼다).</li>
 *   <li>둘째 단계부터 규칙·도구 정의를 캐시한다 — 같은 질문의 단계는 몇 초 간격이라 셋째 단계부터 싸게 읽는다. 첫 단계는 대개 마지막이라 캐시를 만들지 않는다.</li>
 *   <li>도구: count_trend(범위·기간 집계, 축별 상위 top개 + 예시 원문), trend_series(주별 원문 수), final_answer(답 — 형식은 /answer와 같다).</li>
 *   <li>도구 호출 상한 {@code rag.agent.max-tool-calls}(기본 6번, 사용자 결정). 넘으면 지금까지 결과로 답하라고 알린다. 전체 90초 안.</li>
 *   <li>근거 번호 [n]은 도구 결과의 예시 원문에 여기서 붙인다 — 답은 그 번호만 인용할 수 있고, 출처는 그 번호의 원문이다.</li>
 *   <li>실패하면(도구 없이 끝남·시간 초과·AI 오류) empty — RagService가 고정 경로(집계 한 번 + 답변)로 답한다.</li>
 * </ul>
 */
@Slf4j
@Service
public class AgentService {

    static final int MAX_STEPS = 8;              // Claude 호출 상한(도구 6번 + 답 + 여유)
    static final long TIME_LIMIT_MS = 90_000;
    static final int EXAMPLES_PER_CALL = 6;      // count_trend 결과에 싣는 예시 원문
    static final int DEFAULT_TOP = 10;           // count_trend 축별 상위 개수 기본값 — 제외·다음 순위 질문도 다시 부르지 않고 답하게
    static final int SUMMARY_CHARS = 160;
    static final int MAX_SERIES_WEEKS = 12;
    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM/dd");

    private final AiClient aiClient;
    private final TrendFactsService trendFactsService;
    private final ComCdRepository comCdRepository;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final TrendItemRepository trendItemRepository;
    private final String model;
    private final int maxToolCalls;

    public AgentService(AiClient aiClient, TrendFactsService trendFactsService, ComCdRepository comCdRepository,
                        SourceDocumentRepository sourceDocumentRepository, TrendItemRepository trendItemRepository,
                        @Value("${rag.agent.model:${claude.model:claude-sonnet-5}}") String model,
                        @Value("${rag.agent.max-tool-calls:6}") int maxToolCalls) {
        this.aiClient = aiClient;
        this.trendFactsService = trendFactsService;
        this.comCdRepository = comCdRepository;
        this.sourceDocumentRepository = sourceDocumentRepository;
        this.trendItemRepository = trendItemRepository;
        this.model = model;
        this.maxToolCalls = Math.max(1, maxToolCalls);
    }

    /** 도구 경로의 답. facts·period는 첫 count_trend 결과(판단 근거 상자용) — 없으면 null. calls는 조회 기록(로그·화면용). */
    public record Result(AiClient.AnswerResponse response, TrendFactsService.Facts facts, List<String> calls) {
    }

    /** 한 번의 실행 상태 — 근거 번호와 원문, 조회 기록. */
    private static final class Run {
        final Map<Integer, String> docIdByN = new LinkedHashMap<>();
        final Map<String, Integer> nByDocId = new LinkedHashMap<>();
        final Map<Integer, String> summaryByN = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        TrendFactsService.Facts firstFacts;
        TokenUsage usage = TokenUsage.ZERO;
        int toolCalls;
    }

    /**
     * @param scope 질문 해석 범위 — 이 범위를 미리 세어(축별 상위 DEFAULT_TOP개) 첫 메시지에 넣는다. null이면 넣지 않는다.
     */
    public Optional<Result> run(String question, QuestionInterpreter.Interpretation scope, LocalDate today, String apiKey) {
        long started = System.currentTimeMillis();
        Codes codes = codes();
        Run run = new Run();
        List<Map<String, Object>> messages = new ArrayList<>();
        try {
            String first = aiClient.agentTask(today.toString()) + "\n\n<question>\n" + question + "\n</question>";
            if (scope != null) {
                first += "\n\n<prefetched>\n질문 해석 범위로 미리 센 집계(count_trend와 같다 — 이것으로 답할 수 있으면 도구를 더 부르지 않는다):\n"
                        + countText(scope, today, DEFAULT_TOP, run) + "\n</prefetched>";
            }
            messages.add(Map.of("role", "user", "content", first));
            for (int step = 0; step < MAX_STEPS; step++) {
                if (System.currentTimeMillis() - started > TIME_LIMIT_MS) {
                    log.warn("도구 경로 시간 초과({}초) — 고정 경로로 답한다. 조회 {}", TIME_LIMIT_MS / 1000, run.calls);
                    return Optional.empty();
                }
                AiClient.AgentStepResponse response = aiClient.agentStep(codes.request(model, today, messages, step > 0), apiKey);
                run.usage = run.usage.plus(TokenUsage.of(response.usage()));
                List<Map<String, Object>> content = response.content() == null ? List.of() : response.content();
                messages.add(Map.of("role", "assistant", "content", content));
                List<Map<String, Object>> uses = content.stream().filter(b -> "tool_use".equals(b.get("type"))).toList();
                if (uses.isEmpty()) {
                    log.warn("도구 경로가 final_answer 없이 끝났다(stop={}) — 고정 경로로 답한다", response.stopReason());
                    return Optional.empty();
                }
                List<Map<String, Object>> results = new ArrayList<>();
                for (Map<String, Object> use : uses) {
                    String name = String.valueOf(use.get("name"));
                    Map<String, Object> input = input(use.get("input"));
                    if ("final_answer".equals(name)) {
                        log.info("도구 경로 답 — 조회 {}번 {} 근거 원문 {}건 토큰 {} 소요 {}ms", run.toolCalls, run.calls,
                                run.docIdByN.size(), run.usage, System.currentTimeMillis() - started);
                        return Optional.of(new Result(answer(input, run), run.firstFacts, List.copyOf(run.calls)));
                    }
                    results.add(Map.of("type", "tool_result", "tool_use_id", String.valueOf(use.get("id")),
                            "content", execute(name, input, today, codes, run)));
                }
                messages.add(Map.of("role", "user", "content", results));
            }
            log.warn("도구 경로가 {}단계 안에 답하지 못했다 — 고정 경로로 답한다. 조회 {}", MAX_STEPS, run.calls);
        } catch (AiCallException e) {
            log.warn("도구 경로 AI 호출 실패 — 고정 경로로 답한다: {}", e.getMessage());
        } catch (RuntimeException e) { // 응답 모양이 어긋나도 답은 멈추지 않는다
            log.warn("도구 경로 처리 오류 — 고정 경로로 답한다", e);
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> input(Object raw) {
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** 도구 실행 — 결과는 Claude가 읽는 한국어 글. 입력이 잘못됐으면 무엇이 틀렸는지 알려 다시 부르게 한다. */
    String execute(String name, Map<String, Object> input, LocalDate today, Codes codes, Run run) {
        if (run.toolCalls >= maxToolCalls) {
            return "조회 상한(" + maxToolCalls + "번)에 도달했다. 더 조회하지 말고 지금까지 결과로 final_answer를 불러라.";
        }
        run.toolCalls++;
        try {
            return switch (name) {
                case "count_trend" -> countTrend(input, today, codes, run);
                case "trend_series" -> trendSeries(input, today, codes, run);
                default -> "없는 도구다: " + name + ". count_trend·trend_series·final_answer만 쓴다.";
            };
        } catch (IllegalArgumentException e) {
            run.calls.add(name + " 입력 오류");
            return "입력 오류: " + e.getMessage() + " — 고쳐서 다시 불러라.";
        }
    }

    private String countTrend(Map<String, Object> input, LocalDate today, Codes codes, Run run) {
        return countText(codes.scope(input, today, true), today, input.get("top") instanceof Number n ? n.intValue() : DEFAULT_TOP, run);
    }

    /** 범위를 세어 Claude가 읽는 글로 — 집계 줄 + 예시 원문([n]). 첫 집계는 판단 근거 상자가 된다. */
    private String countText(QuestionInterpreter.Interpretation scope, LocalDate today, int top, Run run) {
        TrendFactsService.Facts facts = trendFactsService.count(scope, today, top);
        if (run.firstFacts == null) run.firstFacts = facts;
        QuestionInterpreter.Period p = facts.period();
        run.calls.add("집계 " + facts.scopeLabel() + " · " + p.label());
        StringBuilder out = new StringBuilder("집계 기간: " + p.label() + " (" + p.from() + " ~ " + p.to() + ")"
                + (facts.stale() ? " — 최근 자료가 없어 가장 최근 자료 4주로 셌다" : "") + "\n");
        facts.lines().forEach(line -> out.append(line).append('\n'));
        List<String> examples = facts.evidenceDocIds().stream().limit(EXAMPLES_PER_CALL).toList();
        if (!examples.isEmpty()) {
            out.append("예시 원문(인용할 때 [n]):\n");
            examples(examples, run).forEach(line -> out.append(line).append('\n'));
        }
        return out.toString().strip();
    }

    private String trendSeries(Map<String, Object> input, LocalDate today, Codes codes, Run run) {
        QuestionInterpreter.Interpretation scope = codes.scope(input, today, false);
        int weeks = Math.min(MAX_SERIES_WEEKS, Math.max(2, input.get("weeks") instanceof Number n ? n.intValue() : 6));
        String axis = text(input.get("axis"));
        String value = text(input.get("value"));
        if ((axis == null) != (value == null)) {
            throw new IllegalArgumentException("axis와 value는 함께 주거나 둘 다 빼야 한다");
        }
        if (axis != null && !RagService.FOCUS_AXES.contains(axis)) {
            throw new IllegalArgumentException("axis는 " + RagService.FOCUS_AXES + " 중 하나다: " + axis);
        }
        if (value != null && !codes.knows(axis, value)) {
            throw new IllegalArgumentException(axis + "에 없는 코드다: " + value);
        }
        List<TrendFactsService.WeekCount> series = trendFactsService.series(scope, axis, value, weeks, today);
        String label = (scope.itemTypeName() != null ? scope.itemTypeName() : scope.categoryName() != null ? scope.categoryName() : "전체")
                + (value == null ? "" : " · " + codes.name(axis, value));
        run.calls.add("추이 " + label + " " + weeks + "주");
        return "주별 원문 수(" + label + ", 주는 월요일 시작): " + series.stream()
                .map(w -> w.weekStart().format(MD) + " 주 " + w.count() + "건" + (w.current() ? "(이번 주, 진행 중)" : ""))
                .collect(Collectors.joining(" · "));
    }

    /** 예시 원문에 근거 번호를 붙인다 — 같은 원문은 같은 번호. "[n] 문서ID · 제목 · 매체 · 날짜 — 요약". */
    private List<String> examples(List<String> docIds, Run run) {
        Map<String, SourceDocument> docs = sourceDocumentRepository.findByDocIdIn(docIds).stream()
                .collect(Collectors.toMap(SourceDocument::getDocId, Function.identity()));
        Map<Long, TrendItem> items = trendItemRepository.findByDocumentIdIn(docs.values().stream().map(SourceDocument::getId).toList())
                .stream().collect(Collectors.toMap(TrendItem::getDocumentId, Function.identity()));
        List<String> lines = new ArrayList<>();
        for (String docId : docIds) {
            SourceDocument doc = docs.get(docId);
            if (doc == null) continue;
            int n = run.nByDocId.computeIfAbsent(docId, id -> {
                int next = run.docIdByN.size() + 1;
                run.docIdByN.put(next, id);
                return next;
            });
            TrendItem item = items.get(doc.getId());
            String summary = item == null || item.getSummary() == null ? "" : item.getSummary();
            if (summary.length() > SUMMARY_CHARS) summary = summary.substring(0, SUMMARY_CHARS) + "…";
            run.summaryByN.put(n, summary);
            lines.add("[" + n + "] " + docId + " · " + Objects.toString(doc.getTitle(), "") + " · " + Objects.toString(doc.getMedia(), "")
                    + " · " + TrendAggregationService.statDate(doc) + " — " + summary);
        }
        return lines;
    }

    /** final_answer 입력 → /answer 응답과 같은 형식. 인용 번호는 도구 결과에 붙인 번호만 남긴다(나머지는 RagService가 정리). */
    private AiClient.AnswerResponse answer(Map<String, Object> input, Run run) {
        List<AiClient.AnswerPoint> points = new ArrayList<>();
        if (input.get("points") instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> p = input(o);
                String text = text(p.get("text"));
                if (text == null) continue;
                List<Integer> sources = p.get("sources") instanceof List<?> s
                        ? s.stream().filter(Number.class::isInstance).map(x -> ((Number) x).intValue()).filter(run.docIdByN::containsKey).distinct().toList()
                        : List.of();
                points.add(new AiClient.AnswerPoint(text, sources));
            }
        }
        String body = RagService.blankBody(text(input.get("body")));
        java.util.Set<Integer> cited = new java.util.LinkedHashSet<>();
        points.forEach(p -> cited.addAll(p.sources()));
        if (body != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[(\\d+)]").matcher(body);
            while (m.find()) {
                int n = Integer.parseInt(m.group(1));
                if (run.docIdByN.containsKey(n)) cited.add(n);
            }
        }
        List<AiClient.AnswerSource> sources = cited.stream()
                .map(n -> new AiClient.AnswerSource(n, run.docIdByN.get(n), null, run.summaryByN.getOrDefault(n, ""))).toList();
        boolean grounded = !Boolean.FALSE.equals(input.get("grounded")) && !sources.isEmpty();
        Map<String, Object> usage = Map.of("input_tokens", run.usage.input(), "output_tokens", run.usage.output(),
                "cache_creation_input_tokens", run.usage.cacheCreation(), "cache_read_input_tokens", run.usage.cacheRead());
        return new AiClient.AnswerResponse(grounded, text(input.get("summary")), points, sources, null, usage,
                grounded ? null : "MODEL_DECLINED", null, body);
    }

    private static String text(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o).strip();
    }

    Codes codes() {
        return new Codes(comCdRepository);
    }

    /** 사용 중 공통코드 — 도구 입력을 검증하고(목록 밖 코드는 입력 오류) Claude에게 허용 코드로 보낸다. */
    static final class Codes {
        private final Map<String, List<ComCd>> groups = new LinkedHashMap<>();

        Codes(ComCdRepository repository) {
            for (String group : List.of(TrendCodes.CATEGORY, TrendCodes.ITEM_TYPE, TrendCodes.SEASON, TrendCodes.MATERIAL,
                    TrendCodes.COLOR, TrendCodes.SILHOUETTE, TrendCodes.GENDER, TrendCodes.PATTERN, TrendCodes.DETAIL)) {
                groups.put(group, repository.findByCodeGroupAndUseYnOrderBySortOrderAsc(group, "Y"));
            }
        }

        private List<AiClient.CodeHint> hints(String group) {
            return groups.get(group).stream()
                    .map(c -> new AiClient.CodeHint(c.getCodeValue(), c.getCodeName(), Objects.toString(c.getAttr1(), ""))).toList();
        }

        AiClient.AgentStepRequest request(String model, LocalDate today, List<Map<String, Object>> messages, boolean cache) {
            return new AiClient.AgentStepRequest(model, today.toString(), messages, hints(TrendCodes.CATEGORY), hints(TrendCodes.ITEM_TYPE),
                    hints(TrendCodes.SEASON), hints(TrendCodes.MATERIAL), hints(TrendCodes.COLOR), hints(TrendCodes.SILHOUETTE),
                    hints(TrendCodes.GENDER), hints(TrendCodes.PATTERN), hints(TrendCodes.DETAIL), cache);
        }

        private static String group(String axis) {
            return TrendWeeklyStat.CATEGORY.equals(axis) ? TrendCodes.CATEGORY : TrendCodes.VALUE_TYPE_GROUPS.get(axis);
        }

        boolean knows(String axis, String value) {
            String group = group(axis);
            return group != null && groups.getOrDefault(group, List.of()).stream().anyMatch(c -> c.getCodeValue().equals(value));
        }

        String name(String axis, String value) {
            String group = group(axis);
            return groups.getOrDefault(group, List.of()).stream().filter(c -> c.getCodeValue().equals(value))
                    .map(ComCd::getCodeName).findFirst().orElse(value);
        }

        private String known(String group, Object raw, String field) {
            String value = text(raw);
            if (value == null) return null;
            if (groups.get(group).stream().noneMatch(c -> c.getCodeValue().equals(value))) {
                throw new IllegalArgumentException(field + "에 없는 코드다: " + value);
            }
            return value;
        }

        private List<String> knownList(String group, Object raw, String field) {
            if (!(raw instanceof List<?> list)) return List.of();
            return list.stream().map(o -> known(group, o, field)).filter(Objects::nonNull).distinct().toList();
        }

        /** 도구 입력 → 집계 범위. withPeriod면 period·focus·change도 읽는다(count_trend). */
        QuestionInterpreter.Interpretation scope(Map<String, Object> input, LocalDate today, boolean withPeriod) {
            String category = known(TrendCodes.CATEGORY, input.get("category"), "category");
            String itemType = known(TrendCodes.ITEM_TYPE, input.get("item_type"), "item_type");
            if (itemType != null) category = TrendCodes.categoryOfType(itemType);
            Map<String, List<String>> filters = new LinkedHashMap<>();
            Map.of("materials", TrendItemValue.MATERIAL, "colors", TrendItemValue.COLOR, "silhouettes", TrendItemValue.SILHOUETTE,
                    "patterns", TrendItemValue.PATTERN, "details", TrendItemValue.DETAIL, "genders", TrendItemValue.GENDER).forEach((field, type) -> {
                List<String> values = knownList(TrendCodes.VALUE_TYPE_GROUPS.get(type), input.get(field), field);
                if (!values.isEmpty()) filters.put(type, values);
            });
            List<String> seasons = knownList(TrendCodes.SEASON, input.get("seasons"), "seasons");
            List<String> terms = input.get("terms") instanceof List<?> list
                    ? list.stream().map(AgentService::text).filter(Objects::nonNull).limit(RagService.MAX_TERMS).toList() : List.of();
            String focus = null, change = null;
            QuestionInterpreter.Period period = null;
            if (withPeriod) {
                focus = text(input.get("focus"));
                if (focus != null && !RagService.FOCUS_AXES.contains(focus)) throw new IllegalArgumentException("focus가 축이 아니다: " + focus);
                change = text(input.get("change"));
                if (change != null && !RagService.CHANGES.contains(change)) throw new IllegalArgumentException("change가 잘못됐다: " + change);
                period = period(input.get("period"), today);
            }
            return new QuestionInterpreter.Interpretation(true, category, name(TrendWeeklyStat.CATEGORY, category), itemType,
                    itemType == null ? null : name(TrendItemValue.ITEM_TYPE, itemType), period, terms, seasons, focus, filters, change);
        }

        private static QuestionInterpreter.Period period(Object raw, LocalDate today) {
            if (!(raw instanceof Map<?, ?> map)) return null;
            try {
                LocalDate from = LocalDate.parse(String.valueOf(map.get("from")));
                LocalDate to = LocalDate.parse(String.valueOf(map.get("to")));
                if (from.isAfter(to) || to.isAfter(today)) {
                    throw new IllegalArgumentException("period는 from ≤ to ≤ 오늘(" + today + ")이어야 한다");
                }
                String label = text(map.get("label"));
                return new QuestionInterpreter.Period(from, to, label == null ? from + " ~ " + to : label, true);
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException("period 날짜는 YYYY-MM-DD다");
            }
        }
    }
}
