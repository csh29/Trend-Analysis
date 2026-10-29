package com.sjinc.trendanalysis.service.rag;

import com.sjinc.trendanalysis.domain.ComCd;
import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.domain.TrendItemValue;
import com.sjinc.trendanalysis.domain.TrendWeeklyStat;
import com.sjinc.trendanalysis.dto.rag.RagAnswerView;
import com.sjinc.trendanalysis.dto.rag.RagQuestionRequest;
import com.sjinc.trendanalysis.dto.trend.TrendView;
import com.sjinc.trendanalysis.repository.ComCdRepository;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.service.analysis.AiCallException;
import com.sjinc.trendanalysis.service.analysis.AiClient;
import com.sjinc.trendanalysis.service.analysis.TokenUsage;
import com.sjinc.trendanalysis.service.analysis.TrendCodes;
import com.sjinc.trendanalysis.service.image.ImageService;
import com.sjinc.trendanalysis.service.trend.TrendAggregationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * P-06 RAG 검색·답변. 질문을 해석해(작은 모델, 실패하면 규칙) 두 갈래로 보낸다.
 * <ul>
 *   <li>유행 질문: 유행 = 많이 입어 대중화된 것(2026-10-22 사용자 정의). 도구 경로(AgentService — Claude가 집계 도구를 골라 부른다)로
 *       답하고, 꺼졌거나 실패하면 고정 경로(해석 범위를 한 번 세고 그 집계와 예시 원문으로 답한다)로 답한다.</li>
 *   <li>그 밖의 질문: AI 서비스가 문장 유사도로 근거를 찾는다(카테고리·기간으로 거름). 집계 수치는 붙이지 않는다.</li>
 * </ul>
 * 패션·트렌드 질문이 아니면(해석의 fashion=false) 어느 쪽으로도 보내지 않고 OFF_TOPIC으로 답한다.
 * 근거는 원문 문서(제목·매체·날짜·링크)로 되돌리고, 삭제됐거나 분석 완료가 아니면 출처에서 뺀다.
 */
@Service
@Slf4j
public class RagService {

    public static final int MAX_QUESTION_LENGTH = 500;
    static final int MAX_TERMS = 3;
    /** 증감 질문의 방향 — UP 급상승, DOWN 감소, BOTH 방향 없는 변화. */
    static final java.util.Set<String> CHANGES = java.util.Set.of("UP", "DOWN", "BOTH");
    /** 해석이 고를 수 있는 묻는 축(집계 값 종류) — 그 밖의 값은 버린다. */
    static final java.util.Set<String> FOCUS_AXES = java.util.Set.of(TrendItemValue.ITEM_TYPE, TrendWeeklyStat.CATEGORY,
            TrendItemValue.MATERIAL, TrendItemValue.COLOR, TrendItemValue.SILHOUETTE, TrendItemValue.PATTERN, TrendItemValue.DETAIL);
    static final int MAX_TERM_LENGTH = 20;
    /** 이어 묻기에 넘기는 앞 대화 건수. 이어 묻기는 거의 바로 앞 질문을 가리킨다 — 많이 넣으면 엉뚱한 앞 질문에 이어 붙인다. */
    static final int MAX_HISTORY = 3;
    /** 패션·트렌드 질문이 아니면 이 말만 하고 집계·검색·답변을 하지 않는다(2026-10-22 사용자 결정). */
    static final String OFF_TOPIC_ANSWER = "패션·트렌드 관련 질문에만 답할 수 있어요. 예) 요즘 유행하는 아우터는? · 데님 재킷 코디는 어떻게 해?";

    private final AiClient aiClient;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final ImageService imageService;
    private final TrendFactsService trendFactsService;
    private final ComCdRepository comCdRepository;
    private final AgentService agentService;
    private final boolean agentEnabled;
    private final String apiKey;
    private final String model;
    private final String interpretModel;
    private final double minScore;
    private final int topK;

    /**
     * @param minScore 근거로 인정하는 최소 유사도(1 - 코사인 거리). 0.60은 2026-10-21 이미지 32건으로 잰 임시값 —
     *                 관련 질문 1위 근거가 0.64~0.78, 데이터에 없는 패션 주제가 0.48~0.59, 패션과 무관한 질문이 0.29~0.37이었다.
     *                 데이터가 쌓이면 다시 잰다(ai /search로 점수 분포 확인).
     */
    public RagService(AiClient aiClient, SourceDocumentRepository sourceDocumentRepository, ImageService imageService,
                      TrendFactsService trendFactsService, ComCdRepository comCdRepository, AgentService agentService,
                      @Value("${rag.agent.enabled:true}") boolean agentEnabled,
                      @Value("${claude.api.key:}") String apiKey,
                      @Value("${claude.model:claude-sonnet-5}") String model,
                      @Value("${claude.interpret-model:claude-haiku-5-5}") String interpretModel,
                      @Value("${rag.min-score:0.60}") double minScore,
                      @Value("${rag.top-k:8}") int topK) {
        this.aiClient = aiClient;
        this.sourceDocumentRepository = sourceDocumentRepository;
        this.imageService = imageService;
        this.trendFactsService = trendFactsService;
        this.comCdRepository = comCdRepository;
        this.agentService = agentService;
        this.agentEnabled = agentEnabled;
        this.apiKey = apiKey;
        this.model = model;
        this.interpretModel = interpretModel;
        this.minScore = minScore;
        this.topK = topK;
    }

    public RagAnswerView ask(String question) {
        return ask(question, List.of());
    }

    /**
     * @param history 챗봇의 앞 질문·해석(오래된 것 → 최근 순). 이어 묻기("그럼 하의는?")를 알아듣는 데만 쓰고, 검색·답변은
     *                해석이 앞 대화로 채워 다시 쓴 질문으로 한다. 화면이 보낸 값이라 건수·길이·코드를 여기서 거른다.
     */
    public RagAnswerView ask(String question, List<RagQuestionRequest.Turn> history) {
        String text = question == null ? "" : question.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("질문을 입력하세요.");
        }
        if (text.length() > MAX_QUESTION_LENGTH) {
            throw new IllegalArgumentException("질문은 " + MAX_QUESTION_LENGTH + "자 이하로 쓰세요.");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiCallException("claude.api.key가 설정되지 않았습니다. application.properties에 키를 넣으세요.", false);
        }
        LocalDate today = LocalDate.now();
        Interpreted interpreted = interpret(text, today, history);
        if (!interpreted.fashion()) {
            log.info("RAG 질문 {}자 — 패션 질문이 아니라 답하지 않음", text.length());
            return new RagAnswerView(text, false, OFF_TOPIC_ANSWER, List.of(), "OFF_TOPIC", List.of(), null, minScore,
                    null, interpreted.view(), null);
        }
        if (agentEnabled && interpreted.question().trend()) {
            Optional<AgentService.Result> result = agentService.run(interpreted.query(), interpreted.question(), today, apiKey);
            if (result.isPresent()) {
                return agentView(text, interpreted, result.get());
            }
        }
        return interpreted.question().trend() ? askTrend(text, interpreted, today) : askSearch(text, interpreted, today);
    }

    /** 도구 경로 답 — 판단 근거 상자는 첫 집계(count_trend) 결과. 인용·출처 정리는 고정 경로와 같다(view). */
    private RagAnswerView agentView(String text, Interpreted interpreted, AgentService.Result result) {
        TrendFactsService.Facts facts = result.facts();
        RagAnswerView.Period period = facts == null ? null : new RagAnswerView.Period(facts.period().from(), facts.period().to(),
                facts.period().label(), facts.period().explicit(), facts.stale());
        Interpreted byAgent = new Interpreted(interpreted.question(), interpreted.fashion(), "AGENT", interpreted.original(),
                interpreted.query());
        return view(text, result.response(), period, byAgent, facts == null ? null : factsView(facts));
    }

    /**
     * 해석 결과와 누가 해석했는지(AI·RULE). fashion은 AI 해석만 false가 될 수 있다(규칙으로는 주제를 가리지 않는다).
     * query는 검색·답변에 쓰는 질문 — 이어 묻기면 앞 대화로 채워 다시 쓴 질문, 아니면(규칙 해석 포함) 원래 질문.
     */
    private record Interpreted(QuestionInterpreter.Interpretation question, boolean fashion, String by, String original,
                               String query) {
        RagAnswerView.Interpretation view() {
            QuestionInterpreter.Period p = question.period();
            return new RagAnswerView.Interpretation(fashion, question.trend(), question.category(), question.categoryName(),
                    question.itemType(), question.itemTypeName(), by, query.equals(original) ? null : query,
                    p == null ? null : new RagAnswerView.Period(p.from(), p.to(), p.label(), true, false));
        }
    }

    /**
     * 질문 해석 — 작은 모델(claude.interpret-model)에게 맡기고(2026-10-22 사용자 결정 A), 호출이 실패하면 규칙(QuestionInterpreter)으로
     * 대신한다. 답변이 멈추지 않게 하는 것이 먼저다. 결과 중 공통코드에 없는 값(카테고리·종류)만 버리고 나머지 해석은 쓴다.
     */
    private Interpreted interpret(String text, LocalDate today, List<RagQuestionRequest.Turn> history) {
        List<ComCd> categories = comCdRepository.findByCodeGroupAndUseYnOrderBySortOrderAsc(TrendCodes.CATEGORY, "Y");
        List<ComCd> itemTypes = comCdRepository.findByCodeGroupAndUseYnOrderBySortOrderAsc(TrendCodes.ITEM_TYPE, "Y");
        List<ComCd> seasons = comCdRepository.findByCodeGroupAndUseYnOrderBySortOrderAsc(TrendCodes.SEASON, "Y");
        // 대상을 좁히는 속성 값 후보(값 종류 → 사용 중 코드)
        Map<String, List<ComCd>> attributes = new LinkedHashMap<>();
        for (String type : List.of(TrendItemValue.MATERIAL, TrendItemValue.COLOR, TrendItemValue.SILHOUETTE, TrendItemValue.GENDER,
                TrendItemValue.PATTERN, TrendItemValue.DETAIL)) {
            attributes.put(type, comCdRepository.findByCodeGroupAndUseYnOrderBySortOrderAsc(TrendCodes.VALUE_TYPE_GROUPS.get(type), "Y"));
        }
        List<AiClient.InterpretTurn> turns = turns(history, categories, itemTypes);
        try {
            AiClient.InterpretResponse r = aiClient.interpret(new AiClient.InterpretRequest(text, interpretModel, today.toString(),
                    // 일상 표현이 없으면 null이 아니라 ""로 보낸다(예전 AI 서비스는 null이면 422로 거부했다)
                    categories.stream().map(c -> new AiClient.CodeHint(c.getCodeValue(), c.getCodeName(), Objects.toString(c.getAttr1(), ""))).toList(),
                    itemTypes.stream().map(t -> new AiClient.CodeHint(t.getCodeValue(), t.getCodeName(), "")).toList(), turns,
                    hints(seasons), hints(attributes.get(TrendItemValue.MATERIAL)), hints(attributes.get(TrendItemValue.COLOR)),
                    hints(attributes.get(TrendItemValue.SILHOUETTE)), hints(attributes.get(TrendItemValue.GENDER)),
                    hints(attributes.get(TrendItemValue.PATTERN)), hints(attributes.get(TrendItemValue.DETAIL))), apiKey);
            String category = known(categories, r.category());
            String itemType = known(itemTypes, r.itemType());
            if (itemType != null) {
                category = TrendCodes.categoryOfType(itemType);
            }
            QuestionInterpreter.Period period = r.period() == null ? null : new QuestionInterpreter.Period(
                    LocalDate.parse(r.period().from()), LocalDate.parse(r.period().to()), r.period().label(), true);
            String query = r.question() == null || r.question().isBlank() || r.question().length() > MAX_QUESTION_LENGTH
                    ? text : r.question().trim();
            List<String> terms = terms(r.terms());
            // 공통코드에 있는 시즌만, 코드 순서대로
            List<String> pickedSeasons = r.seasons() == null ? List.of()
                    : seasons.stream().map(ComCd::getCodeValue).filter(r.seasons()::contains).toList();
            String focus = r.focus() != null && FOCUS_AXES.contains(r.focus()) ? r.focus() : null; // Set.of는 null을 물으면 예외
            String change = r.trend() && r.change() != null && CHANGES.contains(r.change()) ? r.change() : null;
            // 공통코드에 있는 값만, 코드 순서대로. 고른 값이 없는 종류는 빼 둔다
            Map<String, List<String>> filters = new LinkedHashMap<>();
            putKnown(filters, TrendItemValue.MATERIAL, attributes.get(TrendItemValue.MATERIAL), r.materials());
            putKnown(filters, TrendItemValue.COLOR, attributes.get(TrendItemValue.COLOR), r.colors());
            putKnown(filters, TrendItemValue.SILHOUETTE, attributes.get(TrendItemValue.SILHOUETTE), r.silhouettes());
            putKnown(filters, TrendItemValue.PATTERN, attributes.get(TrendItemValue.PATTERN), r.patterns()); // "체크 셔츠" → 체크
            putKnown(filters, TrendItemValue.DETAIL, attributes.get(TrendItemValue.DETAIL), r.details()); // "하프넥 니트" → 하프넥
            putKnown(filters, TrendItemValue.GENDER, attributes.get(TrendItemValue.GENDER), r.genders()); // "여성 아우터" → 여성복
            log.info("질문 해석(AI) 앞 대화 {}건 다시 씀={} 패션={} 유행={} 카테고리={} 종류={} 속성={} 축={} 증감={} 단어={} 시즌={} 기간={} 토큰 {}",
                    turns.size(), !query.equals(text), !r.offTopic(), r.trend(), category, itemType, filters, focus, change, terms, pickedSeasons,
                    period == null ? "-" : period.label(), TokenUsage.of(r.usage()));
            return new Interpreted(new QuestionInterpreter.Interpretation(r.trend(), category, name(categories, category),
                    itemType, name(itemTypes, itemType), period, terms, pickedSeasons, focus, filters, change), !r.offTopic(), "AI", text, query);
        } catch (RuntimeException e) {
            log.warn("질문 해석(AI) 실패 — 규칙으로 대신합니다: {}", e.getMessage());
            List<ComCd> trendWords = comCdRepository.findByCodeGroupAndUseYnOrderBySortOrderAsc(TrendCodes.TREND_WORD, "Y");
            // 규칙은 주제를 가리지 않고 이어 묻기도 모른다 — 검색 질문은 기준 유사도가 무관한 질문을 따로 막는다
            return new Interpreted(QuestionInterpreter.interpret(text, today, categories, itemTypes, trendWords), true, "RULE",
                    text, text);
        }
    }

    private static List<AiClient.CodeHint> hints(List<ComCd> codes) {
        return codes.stream().map(c -> new AiClient.CodeHint(c.getCodeValue(), c.getCodeName(), "")).toList();
    }

    private static void putKnown(Map<String, List<String>> filters, String type, List<ComCd> codes, List<String> picked) {
        if (picked == null) return;
        List<String> known = codes.stream().map(ComCd::getCodeValue).filter(picked::contains).toList();
        if (!known.isEmpty()) filters.put(type, known);
    }

    /** 해석이 준 질문 단어 — 빈 값·너무 긴 값·중복을 빼고 앞에서 MAX_TERMS개(AI 서비스도 거르지만 한 번 더). */
    static List<String> terms(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream().filter(Objects::nonNull).map(t -> t.strip().replaceAll("\\s+", " "))
                .filter(t -> !t.isEmpty() && t.length() <= MAX_TERM_LENGTH).distinct().limit(MAX_TERMS).toList();
    }

    /** 화면이 보낸 앞 대화를 거른다 — 최근 MAX_HISTORY건, 질문 길이 상한, 공통코드에 없는 값·잘못된 날짜는 버린다. */
    private static List<AiClient.InterpretTurn> turns(List<RagQuestionRequest.Turn> history, List<ComCd> categories,
                                                      List<ComCd> itemTypes) {
        if (history == null) {
            return List.of();
        }
        List<RagQuestionRequest.Turn> valid = history.stream()
                .filter(t -> t != null && t.question() != null && !t.question().isBlank()).toList();
        return valid.subList(Math.max(0, valid.size() - MAX_HISTORY), valid.size()).stream()
                .map(t -> {
                    String q = t.question().trim();
                    boolean hasPeriod = t.periodLabel() != null && !t.periodLabel().isBlank()
                            && isDate(t.periodFrom()) && isDate(t.periodTo());
                    return new AiClient.InterpretTurn(q.length() > MAX_QUESTION_LENGTH ? q.substring(0, MAX_QUESTION_LENGTH) : q,
                            t.trend(), Objects.toString(known(categories, t.category()), ""),
                            Objects.toString(known(itemTypes, t.itemType()), ""),
                            hasPeriod ? t.periodLabel().trim() : "", hasPeriod ? t.periodFrom() : "", hasPeriod ? t.periodTo() : "");
                })
                .toList();
    }

    private static boolean isDate(String value) {
        try {
            return value != null && LocalDate.parse(value) != null;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** 유행 질문 고정 경로(도구 경로가 꺼졌거나 실패) — 범위 원문을 한 번 세서 그 집계와 예시 원문으로 답한다. 원문이 없으면 AI를 부르지 않는다. */
    private RagAnswerView askTrend(String text, Interpreted interpreted, LocalDate today) {
        TrendFactsService.Facts facts = trendFactsService.count(interpreted.question(), today);
        QuestionInterpreter.Period p = facts.period();
        RagAnswerView.Period period = new RagAnswerView.Period(p.from(), p.to(), p.label(), p.explicit(), facts.stale());
        RagAnswerView.Facts factsView = factsView(facts);
        if (facts.docCount() == 0) {
            return new RagAnswerView(text, false, "근거 자료 없음 — " + p.label() + "(" + p.from() + " ~ " + p.to() + ")에 "
                    + facts.scopeLabel() + " 원문이 없습니다.", List.of(), "NO_EVIDENCE", List.of(), null, minScore,
                    period, interpreted.view(), factsView);
        }
        AiClient.AnswerResponse response = aiClient.answer(new AiClient.AnswerRequest(interpreted.query(), model, topK, minScore,
                today.toString(), AiClient.AnswerRequest.TREND, interpreted.question().category(),
                new AiClient.AnswerPeriod(p.from().toString(), p.to().toString(), p.label(), p.explicit(), facts.stale()),
                facts.lines(), facts.evidenceDocIds()), apiKey);
        log.info("RAG 유행 질문 {}자 범위={} 기간={}~{} 원문 {}건 매체 {}곳 근거={} 출처 {}건 토큰 {}", text.length(), facts.scopeLabel(),
                p.from(), p.to(), facts.docCount(), facts.mediaCount(), response.grounded(),
                response.sources() == null ? 0 : response.sources().size(), TokenUsage.of(response.usage()));
        return view(text, response, period, interpreted, factsView);
    }

    /**
     * 그 밖의 질문 — 문장 유사도 검색(카테고리·기간으로 거름). 근거 원문만 준다. 집계 수치는 붙이지 않는다 — 질문 대상과
     * 상관없는 수치를 대상의 수치처럼 섞어 썼다. 수치가 필요한 질문은 유행 집계로 간다.
     */
    private RagAnswerView askSearch(String text, Interpreted interpreted, LocalDate today) {
        QuestionInterpreter.Period p = interpreted.question().period();
        AiClient.AnswerResponse response = aiClient.answer(new AiClient.AnswerRequest(interpreted.query(), model, topK, minScore,
                today.toString(), AiClient.AnswerRequest.SEARCH,
                interpreted.question().category(),
                p == null ? null : new AiClient.AnswerPeriod(p.from().toString(), p.to().toString(), p.label(), true, false),
                List.of(), List.of()), apiKey);
        RagAnswerView.Period period = response.period() == null ? null : new RagAnswerView.Period(
                date(response.period().from()), date(response.period().to()), response.period().label(),
                response.period().explicit(), response.period().stale());
        log.info("RAG 질문 {}자 카테고리={} 근거={} 사유={} 최고 유사도={} 출처 {}건 토큰 {}", text.length(),
                interpreted.question().category(), response.grounded(), response.reason(), response.bestScore(),
                response.sources() == null ? 0 : response.sources().size(), TokenUsage.of(response.usage()));
        return view(text, response, period, interpreted, null);
    }

    private RagAnswerView view(String text, AiClient.AnswerResponse response, RagAnswerView.Period period,
                               Interpreted interpreted, RagAnswerView.Facts facts) {
        if (!response.grounded()) {
            return new RagAnswerView(text, false, noEvidenceMessage(response, period), List.of(), response.reason(), List.of(),
                    response.bestScore(), minScore, period, interpreted.view(), facts);
        }
        List<RagAnswerView.Source> sources = sources(response.sources());
        if (sources.isEmpty()) { // 인용한 문서가 모두 삭제·재분석 중이면 출처 없는 답이 된다 — 내보내지 않는다
            return new RagAnswerView(text, false, "근거 자료 없음 — 근거 문서가 삭제되었거나 다시 분석 중입니다.",
                    List.of(), "SOURCE_MISSING", List.of(), response.bestScore(), minScore, period, interpreted.view(), facts);
        }
        return new RagAnswerView(text, true, response.answer(), points(response.points(), sources), null, sources,
                response.bestScore(), minScore, period, interpreted.view(), facts, body(response.body(), sources));
    }

    private static RagAnswerView.Facts factsView(TrendFactsService.Facts facts) {
        return new RagAnswerView.Facts(facts.scopeLabel(), facts.docCount(), facts.mediaCount(), facts.prevDocCount(), facts.change(),
                facts.dimensions().stream().filter(d -> !d.rows().isEmpty())
                        .map(d -> new RagAnswerView.Dimension(d.label(), d.rows().stream().limit(TrendFactsService.TOP) // 도구 경로는 10개까지 센다 — 막대는 5개
                                .map(r -> new RagAnswerView.FactRow(r.label(), r.count(), r.mediaCount(), r.prevCount(),
                                        TrendFactsService.rate(r.count(), r.prevCount()),
                                        r.names().stream().map(n -> n.name() + " " + n.count() + "건").toList(),
                                        r.items().stream().map(n -> n.name() + " " + n.count() + "건").toList(), r.score())).toList()))
                        .toList(),
                facts.terms().stream().map(t -> new RagAnswerView.TermFact(t.term(), t.count(), t.mediaCount(), t.prevCount(),
                        t.expressions().stream().map(n -> n.name() + " " + n.count() + "건").toList(), termDetails(t))).toList());
    }

    /** 질문 단어 대상 옷의 포인트 줄 — 축마다 한 줄, 함께 나온 옷·키워드 한 줄씩. */
    private static List<String> termDetails(TrendFactsService.TermRow t) {
        List<String> lines = new ArrayList<>();
        t.points().forEach(p -> lines.add(p.label() + ": " + p.values().stream()
                .map(v -> v.name() + " " + v.count() + "건(직전 " + v.prevCount() + ", " + TrendFactsService.rate(v.count(), v.prevCount()) + ")")
                .collect(Collectors.joining(" · "))));
        if (!t.together().isEmpty()) {
            lines.add("함께 나온 옷: " + t.together().stream().map(n -> n.name() + " " + n.count() + "건").collect(Collectors.joining(" · ")));
        }
        if (!t.keywords().isEmpty()) {
            lines.add("키워드: " + t.keywords().stream().map(n -> n.name() + " " + n.count() + "건").collect(Collectors.joining(" · ")));
        }
        return lines;
    }

    private static String known(List<ComCd> codes, String value) {
        return value == null || codes.stream().noneMatch(c -> c.getCodeValue().equals(value)) ? null : value;
    }

    private static String name(List<ComCd> codes, String value) {
        return value == null ? null : codes.stream().filter(c -> c.getCodeValue().equals(value)).map(ComCd::getCodeName)
                .findFirst().orElse(value);
    }

    /**
     * 답의 줄과 그 줄의 출처 번호(화면에 보이는 출처만). 인용한 출처가 모두 빠진 줄(삭제·재분석 중인 문서만 인용)은 뺀다 —
     * 확인할 수 없는 문장이 출처 없이 남았다(2026-10-23 평가: 색인에만 남은 지워진 문서로 쓴 줄). 처음부터 인용이 없는 줄(한계·주의 문장)은 둔다.
     */
    /** 자유 형식 본문 — 삭제·재분석 중이라 빠진 출처의 [n]은 지운다(줄의 근거 번호 정리와 같다). 없으면 null. */
    static String body(String body, List<RagAnswerView.Source> sources) {
        body = blankBody(body);
        if (body == null) return null;
        Set<Integer> shown = sources.stream().map(RagAnswerView.Source::n).collect(Collectors.toSet());
        return java.util.regex.Pattern.compile("\\[(\\d+)]").matcher(body.strip())
                .replaceAll(m -> shown.contains(Integer.parseInt(m.group(1))) ? java.util.regex.Matcher.quoteReplacement(m.group()) : "");
    }

    /** 빈 본문 — 모델이 빈 값 대신 따옴표만(`""`) 넣기도 한다(2026-10-29 화면에 ""가 보였다). 비었으면 null. */
    static String blankBody(String body) {
        return body == null || body.replaceAll("[\"'`\\s]", "").isEmpty() ? null : body;
    }

    private static List<RagAnswerView.Point> points(List<AiClient.AnswerPoint> points, List<RagAnswerView.Source> sources) {
        if (points == null) {
            return List.of();
        }
        Set<Integer> shown = sources.stream().map(RagAnswerView.Source::n).collect(Collectors.toSet());
        List<RagAnswerView.Point> kept = new ArrayList<>();
        for (AiClient.AnswerPoint p : points) {
            List<Integer> cited = p.sources() == null ? List.of() : p.sources();
            List<Integer> visible = cited.stream().filter(shown::contains).toList();
            if (!cited.isEmpty() && visible.isEmpty()) {
                log.info("출처가 모두 빠진 줄을 뺍니다(인용 {}): {}", cited, p.text());
                continue;
            }
            kept.add(new RagAnswerView.Point(p.text(), visible));
        }
        return kept;
    }

    private static String noEvidenceMessage(AiClient.AnswerResponse response, RagAnswerView.Period period) {
        if ("MODEL_DECLINED".equals(response.reason()) && response.answer() != null && !response.answer().isBlank()) {
            return "근거 자료 없음 — " + response.answer();
        }
        // 기간을 콕 집은 질문(지난주 등)은 그 범위에서만 찾으므로 그 기간을 같이 알려 준다
        String when = period == null || !period.explicit() ? "" : period.label() + "(" + period.from() + " ~ " + period.to() + ")에 ";
        return "근거 자료 없음 — " + when + "질문과 충분히 비슷한 원문이 없습니다.";
    }

    private static LocalDate date(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }

    private List<RagAnswerView.Source> sources(List<AiClient.AnswerSource> cited) {
        if (cited == null || cited.isEmpty()) {
            return List.of();
        }
        Map<String, SourceDocument> docs = sourceDocumentRepository.findByDocIdIn(
                        cited.stream().map(AiClient.AnswerSource::docId).toList()).stream()
                .filter(doc -> SourceDocument.DONE.equals(doc.getStatus()))
                .collect(Collectors.toMap(SourceDocument::getDocId, Function.identity()));
        return cited.stream()
                .filter(s -> docs.containsKey(s.docId()))
                .map(s -> {
                    SourceDocument doc = docs.get(s.docId());
                    String imageUrl = doc.isImage() ? "/api/rag/sources/" + doc.getDocId() + "/image" : null;
                    return new RagAnswerView.Source(s.n(), doc.getDocId(), doc.getDocType(), doc.getTitle(), doc.getMedia(),
                            TrendAggregationService.statDate(doc), doc.isImage() ? null : doc.getUrl(), imageUrl,
                            s.score(), s.chunk());
                })
                .toList();
    }

    /**
     * 홈 출처 패널에서 보는 이미지 원문의 사진. 홈은 로그인만 요구하므로 이미지 업로드 권한 없이 열린다 — 대신 분석이 끝난
     * 이미지 원문(RAG 근거가 될 수 있는 것)만 연다. 텍스트 원문의 첨부는 여기서 열지 않는다.
     */
    public ImageService.ImageFile sourceImage(String docId) {
        SourceDocument doc = sourceDocumentRepository.findByDocId(docId)
                .filter(d -> d.isImage() && d.getImageId() != null && SourceDocument.DONE.equals(d.getStatus()))
                .orElseThrow(() -> new IllegalArgumentException("이미지 출처가 아닙니다: " + docId));
        return imageService.getFile(doc.getImageId());
    }
}
