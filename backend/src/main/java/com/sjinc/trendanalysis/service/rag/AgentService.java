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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 *   <li>실패하면(도구 없이 끝남·시간 초과·AI 오류) 답 없이 쓴 토큰만 돌려준다 — RagService가 고정 경로(집계 한 번 + 답변)로 답한다.</li>
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
    static final int MAX_SERIES_WEEKS = 26;      // "최근 3개월·반년 추이"를 한 번에(진단 B3 — 12주로는 3개월을 덮지 못했다)
    static final int MAX_SERIES_VALUES = 5;
    static final int MAX_FACT_BOXES = 3;         // 판단 근거 상자 최대 개수(비교 질문은 대상·기간마다 하나)
    static final int SEARCH_CHUNKS = 10;         // search_documents가 받아 오는 후보 청크 수
    static final int SEARCH_DOCS = 4;            // 그중 결과에 싣는 원문 수(문서 단위로 묶어서)
    static final int SEARCH_CHARS = 300;         // 원문마다 싣는 청크 글 길이
    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM/dd");

    private final AiClient aiClient;
    private final TrendFactsService trendFactsService;
    private final ComCdRepository comCdRepository;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final TrendItemRepository trendItemRepository;
    private final String model;
    private final int maxToolCalls;
    private final SearchSettings searchSettings;

    public AgentService(AiClient aiClient, TrendFactsService trendFactsService, ComCdRepository comCdRepository,
                        SourceDocumentRepository sourceDocumentRepository, TrendItemRepository trendItemRepository,
                        @Value("${rag.agent.model:${claude.model:claude-sonnet-5}}") String model,
                        @Value("${rag.agent.max-tool-calls:6}") int maxToolCalls,
                        SearchSettings searchSettings) {
        this.searchSettings = searchSettings;
        this.aiClient = aiClient;
        this.trendFactsService = trendFactsService;
        this.comCdRepository = comCdRepository;
        this.sourceDocumentRepository = sourceDocumentRepository;
        this.trendItemRepository = trendItemRepository;
        this.model = model;
        this.maxToolCalls = Math.max(1, maxToolCalls);
    }

    /**
     * 도구 경로의 답. facts는 판단 근거 상자에 그릴 집계(답이 인용한 원문이 나온 count_trend 결과, 없으면 미리 센 집계) — 없으면 빈 목록.
     * calls는 조회 기록(로그·화면용). toolResults는 Claude가 읽은 집계 글 전부(미리 센 집계 + 도구 결과) — 답의 수치를 이것과 대조한다.
     */
    public record Result(AiClient.AnswerResponse response, List<TrendFactsService.Facts> facts, List<String> calls, List<String> toolResults,
                         com.sjinc.trendanalysis.dto.rag.RagAnswerView.Chart chart) {
    }

    /** 한 번의 실행 — 답(result, 실패면 null)과 쓴 토큰. 실패해도 토큰은 과금되므로 질문 로그에 더한다(2026-10-29 진단). */
    public record Attempt(Result result, TokenUsage usage) {
    }

    /** count_trend 한 번(미리 센 집계 포함)의 집계와 그 결과에 실린 예시 원문 번호 — 답이 인용한 집계를 고를 때 쓴다. */
    private record Counted(TrendFactsService.Facts facts, Set<Integer> ns, boolean prefetched) {
    }

    /** 한 번의 실행 상태 — 근거 번호와 원문, 조회 기록. */
    private static final class Run {
        final Map<Integer, String> docIdByN = new LinkedHashMap<>();
        final Map<String, Integer> nByDocId = new LinkedHashMap<>();
        final Map<Integer, String> summaryByN = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        final List<String> toolResults = new ArrayList<>();
        final List<Counted> counts = new ArrayList<>();
        com.sjinc.trendanalysis.dto.rag.RagAnswerView.Chart chart; // 마지막 trend_series 결과로 만든 추이 차트
        TokenUsage usage = TokenUsage.ZERO;
        int toolCalls;
    }

    /**
     * @param scope 질문 해석 범위 — 이 범위를 미리 세어(축별 상위 DEFAULT_TOP개) 첫 메시지에 넣는다. null이면 넣지 않는다.
     */
    public Attempt run(String question, QuestionInterpreter.Interpretation scope, LocalDate today, String apiKey) {
        Run run = new Run();
        return new Attempt(answerWithTools(question, scope, today, apiKey, run), run.usage);
    }

    private Result answerWithTools(String question, QuestionInterpreter.Interpretation scope, LocalDate today, String apiKey, Run run) {
        long started = System.currentTimeMillis();
        Codes codes = codes();
        List<Map<String, Object>> messages = new ArrayList<>();
        try {
            String first = aiClient.agentTask(today.toString()) + "\n\n<question>\n" + question + "\n</question>";
            if (scope != null) {
                first += "\n\n<prefetched>\n질문 해석 범위로 미리 센 집계(count_trend와 같다 — 이것으로 답할 수 있으면 도구를 더 부르지 않는다):\n"
                        + countText(scope, today, DEFAULT_TOP, run, true) + "\n</prefetched>";
            }
            messages.add(Map.of("role", "user", "content", first));
            for (int step = 0; step < MAX_STEPS; step++) {
                if (System.currentTimeMillis() - started > TIME_LIMIT_MS) {
                    log.warn("도구 경로 시간 초과({}초) — 고정 경로로 답한다. 조회 {}", TIME_LIMIT_MS / 1000, run.calls);
                    return null;
                }
                AiClient.AgentStepResponse response = aiClient.agentStep(codes.request(model, today, messages, step > 0), apiKey);
                run.usage = run.usage.plus(TokenUsage.of(response.usage()));
                List<Map<String, Object>> content = response.content() == null ? List.of() : response.content();
                messages.add(Map.of("role", "assistant", "content", content));
                List<Map<String, Object>> uses = content.stream().filter(b -> "tool_use".equals(b.get("type"))).toList();
                if (uses.isEmpty()) {
                    log.warn("도구 경로가 final_answer 없이 끝났다(stop={}) — 고정 경로로 답한다", response.stopReason());
                    return null;
                }
                List<Map<String, Object>> results = new ArrayList<>();
                for (Map<String, Object> use : uses) {
                    String name = String.valueOf(use.get("name"));
                    Map<String, Object> input = input(use.get("input"));
                    if ("final_answer".equals(name)) {
                        log.info("도구 경로 답 — 조회 {}번 {} 근거 원문 {}건 토큰 {} 소요 {}ms", run.toolCalls, run.calls,
                                run.docIdByN.size(), run.usage, System.currentTimeMillis() - started);
                        AiClient.AnswerResponse answer = answer(input, run);
                        return new Result(answer, usedFacts(run, answer), List.copyOf(run.calls), List.copyOf(run.toolResults), run.chart);
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
        return null;
    }

    /**
     * 판단 근거 상자에 그릴 집계 — Claude가 직접 부른 count_trend 중 답이 인용한 원문이 나온 것(부른 순서, 같은 범위·기간은 한 번, 최대
     * MAX_FACT_BOXES개). 없으면(미리 센 집계로만 답함) 첫 집계. 첫 집계만 그리면 비교·다른 기간 답 아래에 해석 범위("전체") 막대가 나와
     * 답과 어긋났다(2026-10-29 진단 B1).
     */
    private static List<TrendFactsService.Facts> usedFacts(Run run, AiClient.AnswerResponse answer) {
        Set<Integer> cited = answer.sources() == null ? Set.of()
                : answer.sources().stream().map(AiClient.AnswerSource::n).collect(Collectors.toSet());
        List<TrendFactsService.Facts> used = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Counted c : run.counts) {
            if (c.prefetched() || c.ns().stream().noneMatch(cited::contains)) continue;
            TrendFactsService.Facts f = c.facts();
            if (seen.add(f.scopeLabel() + "|" + f.period().from() + "|" + f.period().to() + "|" + f.change()) && used.size() < MAX_FACT_BOXES) {
                used.add(f);
            }
        }
        if (used.isEmpty() && !run.counts.isEmpty()) used.add(run.counts.get(0).facts());
        return used;
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
                case "search_documents" -> searchDocuments(input, codes, run);
                default -> "없는 도구다: " + name + ". count_trend·trend_series·search_documents·final_answer만 쓴다.";
            };
        } catch (IllegalArgumentException e) {
            run.calls.add(name + " 입력 오류");
            return "입력 오류: " + e.getMessage() + " — 고쳐서 다시 불러라.";
        }
    }

    private String countTrend(Map<String, Object> input, LocalDate today, Codes codes, Run run) {
        return countText(codes.scope(input, today, true), today, input.get("top") instanceof Number n ? n.intValue() : DEFAULT_TOP, run, false);
    }

    /** 범위를 세어 Claude가 읽는 글로 — 집계 줄 + 예시 원문([n]). 집계와 그 예시 번호는 판단 근거 상자를 고를 때 쓴다(usedFacts). */
    private String countText(QuestionInterpreter.Interpretation scope, LocalDate today, int top, Run run, boolean prefetched) {
        TrendFactsService.Facts facts = trendFactsService.count(scope, today, top);
        Set<Integer> ns = new HashSet<>();
        run.counts.add(new Counted(facts, ns, prefetched));
        QuestionInterpreter.Period p = facts.period();
        run.calls.add("집계 " + facts.scopeLabel() + " · " + p.label());
        StringBuilder out = new StringBuilder("집계 기간: " + p.label() + " (" + p.from() + " ~ " + p.to() + ")"
                + (facts.stale() ? " — 최근 자료가 없어 가장 최근 자료 4주로 셌다" : "") + "\n");
        facts.lines().forEach(line -> out.append(line).append('\n'));
        List<String> examples = facts.evidenceDocIds().stream().limit(EXAMPLES_PER_CALL).toList();
        if (!examples.isEmpty()) {
            out.append("예시 원문(인용할 때 [n]):\n");
            examples(examples, run, ns).forEach(line -> out.append(line).append('\n'));
        }
        run.toolResults.add(out.toString().strip());
        return out.toString().strip();
    }

    private String trendSeries(Map<String, Object> input, LocalDate today, Codes codes, Run run) {
        QuestionInterpreter.Interpretation scope = codes.scope(input, today, false);
        int weeks = Math.min(MAX_SERIES_WEEKS, Math.max(2, input.get("weeks") instanceof Number n ? n.intValue() : 6));
        String axis = text(input.get("axis"));
        // 값 여러 개를 한 번에 — 상위 5개 추이를 보려고 도구를 다섯 번 부르면 호출 상한(6)에 닿았다(2026-10-29 진단 B3)
        List<String> values = new ArrayList<>();
        String single = text(input.get("value"));
        if (single != null) values.add(single);
        if (input.get("values") instanceof List<?> list) {
            list.stream().map(AgentService::text).filter(v -> v != null && !values.contains(v)).forEach(values::add);
        }
        if ((axis == null) != values.isEmpty()) {
            throw new IllegalArgumentException("axis와 value(또는 values)는 함께 주거나 둘 다 빼야 한다");
        }
        if (axis != null && !RagService.FOCUS_AXES.contains(axis)) {
            throw new IllegalArgumentException("axis는 " + RagService.FOCUS_AXES + " 중 하나다: " + axis);
        }
        if (values.size() > MAX_SERIES_VALUES) {
            throw new IllegalArgumentException("values는 " + MAX_SERIES_VALUES + "개까지다");
        }
        for (String v : values) {
            if (!codes.knows(axis, v)) throw new IllegalArgumentException(axis + "에 없는 코드다: " + v);
        }
        String scopeName = scope.itemTypeName() != null ? scope.itemTypeName() : scope.categoryName() != null ? scope.categoryName() : "전체";
        run.calls.add("추이 " + scopeName + (values.isEmpty() ? "" : " · " + values) + " " + weeks + "주");
        List<String> lines = new ArrayList<>();
        List<TrendFactsService.WeekCount> first = null;
        List<com.sjinc.trendanalysis.dto.rag.RagAnswerView.ChartSeries> chartSeries = new ArrayList<>();
        for (String v : values.isEmpty() ? java.util.Collections.<String>singletonList(null) : values) {
            List<TrendFactsService.WeekCount> series = trendFactsService.series(scope, axis, v, weeks, today);
            if (first == null) first = series;
            String name = v == null ? scopeName : shortLabel(codes.name(axis, v));
            lines.add("- " + scopeName + (v == null ? "" : " · " + codes.name(axis, v)) + ": " + series.stream()
                    .map(w -> w.weekStart().format(MD) + " " + w.count() + "건").collect(Collectors.joining(" · ")));
            // 차트는 그 주 전체 원문 중 비중(%) — 건수로 그리면 수집량이 늘어난 주에 모든 선이 함께 올라간다(진단 C2)
            chartSeries.add(new com.sjinc.trendanalysis.dto.rag.RagAnswerView.ChartSeries(name, series.stream()
                    .map(w -> w.total() == 0 ? 0 : (int) Math.round(w.count() * 100.0 / w.total())).toList()));
        }
        // 차트 값은 서버가 센 값 그대로다 — Claude가 숫자를 옮기지 않는다(진단 E1). 마지막 추이 조회가 차트가 된다
        run.chart = new com.sjinc.trendanalysis.dto.rag.RagAnswerView.Chart(scopeName + " 주별 비중(그 주 전체 분석 원문 중 %)",
                first.stream().map(w -> w.weekStart().format(MD)).toList(), chartSeries,
                "주별 전체 분석 원문: " + first.stream().map(w -> w.weekStart().format(MD) + " " + w.total() + "건").collect(Collectors.joining(" · "))
                        + " — 마지막 주는 진행 중");
        // 주마다 전체 원문 수(수집량)를 한 번만 준다 — 수집량이 늘어난 주는 건수만 보면 모든 값이 늘어난 것처럼 보였다(2026-10-29 진단 C2)
        String text = "주별 원문 수(주는 월요일 시작, 마지막 주는 진행 중):\n"
                + "- 그 주 전체 분석 원문: " + first.stream().map(w -> w.weekStart().format(MD) + " " + w.total() + "건")
                .collect(Collectors.joining(" · ")) + "\n" + String.join("\n", lines);
        run.toolResults.add(text);
        return text;
    }

    /**
     * 문서 검색 — 질문과 비슷한 원문 청크(기준 유사도 이상, 분석 완료 원문만)에 근거 번호를 붙여 준다. 집계는 "무엇이 많은지"만 알려 줘
     * "와이드 팬츠가 유행하는 이유는?" 같은 이유·배경·사례 질문은 근거 글이 없어 답하지 못했다(2026-10-29 진단 B2). 수는 세지 않는다.
     * 검색 실패는 도구 결과로 알리고 답은 계속한다(집계만으로도 답할 수 있다).
     */
    private String searchDocuments(Map<String, Object> input, Codes codes, Run run) {
        String query = text(input.get("query"));
        if (query == null) throw new IllegalArgumentException("query가 비었다");
        String category = codes.known(TrendCodes.CATEGORY, input.get("category"), "category");
        SearchSettings.Mode mode = searchSettings.current();
        String scoreName = mode.rerank() ? "관련도" : "유사도";
        List<AiClient.SearchHit> hits;
        try {
            hits = aiClient.search(query, SEARCH_CHUNKS, category, mode.rerank());
        } catch (AiCallException e) {
            run.calls.add("검색 실패");
            return "검색에 실패했다(" + e.getMessage() + ") — 집계로만 답하고, 이유·사례는 자료가 부족하다고 밝혀라.";
        }
        Map<String, AiClient.SearchHit> best = new LinkedHashMap<>(); // 문서마다 가장 관련 있는 청크(관련 순으로 온다)
        hits.stream().filter(h -> h.score() >= mode.minScore()).forEach(h -> best.putIfAbsent(h.docId(), h));
        Map<String, SourceDocument> docs = sourceDocumentRepository.findByDocIdIn(best.keySet()).stream()
                .filter(d -> SourceDocument.DONE.equals(d.getStatus()))
                .collect(Collectors.toMap(SourceDocument::getDocId, Function.identity()));
        run.calls.add("검색 " + query);
        List<String> lines = new ArrayList<>();
        for (AiClient.SearchHit h : best.values()) {
            SourceDocument doc = docs.get(h.docId());
            if (doc == null) continue;
            int n = run.nByDocId.computeIfAbsent(h.docId(), id -> {
                int next = run.docIdByN.size() + 1;
                run.docIdByN.put(next, id);
                return next;
            });
            String chunk = h.chunk().replaceAll("\\s+", " ").strip();
            if (chunk.length() > SEARCH_CHARS) chunk = chunk.substring(0, SEARCH_CHARS) + "…";
            run.summaryByN.putIfAbsent(n, chunk);
            lines.add("[" + n + "] " + h.docId() + " · " + Objects.toString(doc.getTitle(), "") + " · " + Objects.toString(doc.getMedia(), "")
                    + " · " + TrendAggregationService.statDate(doc) + " · " + scoreName + " " + String.format("%.2f", h.score()) + " — " + chunk);
            if (lines.size() >= SEARCH_DOCS) break;
        }
        String text = lines.isEmpty()
                ? "기준 " + scoreName + "(" + mode.minScore() + ") 이상인 원문이 없다 — 이유·사례의 근거를 찾지 못했다. 지어내지 말고 자료가 부족하다고 밝혀라."
                : "검색 결과(질문과 비슷한 원문 — 사례·이유·설명의 근거, 수를 세는 자료가 아니다):\n" + String.join("\n", lines);
        run.toolResults.add(text);
        return text;
    }

    /** 예시 원문에 근거 번호를 붙인다 — 같은 원문은 같은 번호. "[n] 문서ID · 제목 · 매체 · 날짜 — 요약". 붙인 번호는 ns에 모은다. */
    private List<String> examples(List<String> docIds, Run run, Set<Integer> ns) {
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
            ns.add(n);
            TrendItem item = items.get(doc.getId());
            String summary = item == null || item.getSummary() == null ? "" : item.getSummary();
            if (summary.length() > SUMMARY_CHARS) summary = summary.substring(0, SUMMARY_CHARS) + "…";
            run.summaryByN.put(n, summary);
            lines.add("[" + n + "] " + docId + " · " + Objects.toString(doc.getTitle(), "") + " · " + Objects.toString(doc.getMedia(), "")
                    + " · " + TrendAggregationService.statDate(doc) + " — " + summary);
        }
        return lines;
    }

    /**
     * final_answer 입력 → /answer 응답과 같은 형식. 출처는 도구 결과에 붙인 번호만이다. 줄·본문의 번호는 지우지 않고 그대로 넘긴다 —
     * 여기서 지우면 지어낸 번호만 단 줄이 "처음부터 인용 없는 줄"이 되어 RagService가 남겼다(2026-10-29 진단 F2). 정리는 RagService가 한다.
     */
    private AiClient.AnswerResponse answer(Map<String, Object> input, Run run) {
        List<AiClient.AnswerPoint> points = new ArrayList<>();
        if (input.get("points") instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> p = input(o);
                String text = text(p.get("text"));
                if (text == null) continue;
                List<Integer> sources = p.get("sources") instanceof List<?> s
                        ? s.stream().filter(Number.class::isInstance).map(x -> ((Number) x).intValue()).distinct().toList()
                        : List.of();
                points.add(new AiClient.AnswerPoint(text, sources));
            }
        }
        String body = RagService.blankBody(text(input.get("body")));
        Set<Integer> cited = new java.util.LinkedHashSet<>();
        points.forEach(p -> p.sources().stream().filter(run.docIdByN::containsKey).forEach(cited::add));
        if (body != null) {
            RagService.citations(body).stream().filter(run.docIdByN::containsKey).forEach(cited::add);
        }
        List<AiClient.AnswerSource> sources = cited.stream()
                .map(n -> new AiClient.AnswerSource(n, run.docIdByN.get(n), null, run.summaryByN.getOrDefault(n, ""))).toList();
        boolean grounded = !Boolean.FALSE.equals(input.get("grounded")) && !sources.isEmpty();
        Map<String, Object> usage = Map.of("input_tokens", run.usage.input(), "output_tokens", run.usage.output(),
                "cache_creation_input_tokens", run.usage.cacheCreation(), "cache_read_input_tokens", run.usage.cacheRead());
        return new AiClient.AnswerResponse(grounded, text(input.get("summary")), points, sources, null, usage,
                grounded ? null : "MODEL_DECLINED", null, body);
    }

    /** 코드 이름의 괄호 힌트를 뗀다 — 차트 범례가 길어지지 않게("패딩(숏패딩·푸퍼)" → "패딩"). */
    private static String shortLabel(String name) {
        int i = name.indexOf('(');
        return i > 0 ? name.substring(0, i).strip() : name;
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
