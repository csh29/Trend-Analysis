package com.sjinc.trendanalysis.service.rag;

import com.sjinc.trendanalysis.domain.ComCd;
import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.dto.rag.RagAnswerView;
import com.sjinc.trendanalysis.dto.rag.RagQuestionRequest;
import com.sjinc.trendanalysis.dto.trend.TrendView;
import com.sjinc.trendanalysis.repository.ComCdRepository;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.repository.TrendWeeklyStatRepository;
import com.sjinc.trendanalysis.service.analysis.AiCallException;
import com.sjinc.trendanalysis.service.analysis.AiClient;
import com.sjinc.trendanalysis.service.analysis.TokenUsage;
import com.sjinc.trendanalysis.service.analysis.TrendCodes;
import com.sjinc.trendanalysis.service.image.ImageService;
import com.sjinc.trendanalysis.service.trend.TrendAggregationService;
import com.sjinc.trendanalysis.service.trend.TrendQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * P-06 RAG 검색·답변. 질문을 해석해(작은 모델, 실패하면 규칙) 두 갈래로 보낸다.
 * <ul>
 *   <li>유행 질문: 유행 = 많이 입어 대중화된 것(2026-10-22 사용자 정의) — 범위 원문을 세고(TrendFactsService) 그 집계와 예시
 *       원문으로 AI 서비스가 답한다. 문장이 비슷한 자료만 보고 "유행"이라고 하지 않게.</li>
 *   <li>그 밖의 질문: AI 서비스가 문장 유사도로 근거를 찾는다(카테고리·기간으로 거름). 주간 집계 상위 값을 참고로 덧붙인다.</li>
 * </ul>
 * 패션·트렌드 질문이 아니면(해석의 fashion=false) 어느 쪽으로도 보내지 않고 OFF_TOPIC으로 답한다.
 * 근거는 원문 문서(제목·매체·날짜·링크)로 되돌리고, 삭제됐거나 분석 완료가 아니면 출처에서 뺀다.
 */
@Service
@Slf4j
public class RagService {

    public static final int MAX_QUESTION_LENGTH = 500;
    /** 이어 묻기에 넘기는 앞 대화 건수. 이어 묻기는 거의 바로 앞 질문을 가리킨다 — 많이 넣으면 엉뚱한 앞 질문에 이어 붙인다. */
    static final int MAX_HISTORY = 3;
    /** 패션·트렌드 질문이 아니면 이 말만 하고 집계·검색·답변을 하지 않는다(2026-10-22 사용자 결정). */
    static final String OFF_TOPIC_ANSWER = "패션·트렌드 관련 질문에만 답할 수 있어요. 예) 요즘 유행하는 아우터는? · 데님 재킷 코디는 어떻게 해?";
    /** 답변에 덧붙이는 집계 — 유형별 언급량 상위 몇 개. 많이 넣으면 근거보다 수치가 앞선다. */
    static final int STATS_PER_TYPE = 5;

    private final AiClient aiClient;
    private final TrendQueryService trendQueryService;
    private final TrendWeeklyStatRepository statRepository;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final ImageService imageService;
    private final TrendFactsService trendFactsService;
    private final ComCdRepository comCdRepository;
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
    public RagService(AiClient aiClient, TrendQueryService trendQueryService, TrendWeeklyStatRepository statRepository,
                      SourceDocumentRepository sourceDocumentRepository, ImageService imageService,
                      TrendFactsService trendFactsService, ComCdRepository comCdRepository,
                      @Value("${claude.api.key:}") String apiKey,
                      @Value("${claude.model:claude-sonnet-5}") String model,
                      @Value("${claude.interpret-model:claude-haiku-5-5}") String interpretModel,
                      @Value("${rag.min-score:0.60}") double minScore,
                      @Value("${rag.top-k:8}") int topK) {
        this.aiClient = aiClient;
        this.trendQueryService = trendQueryService;
        this.statRepository = statRepository;
        this.sourceDocumentRepository = sourceDocumentRepository;
        this.imageService = imageService;
        this.trendFactsService = trendFactsService;
        this.comCdRepository = comCdRepository;
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
            return new RagAnswerView(text, false, OFF_TOPIC_ANSWER, List.of(), "OFF_TOPIC", List.of(), null, minScore, null,
                    null, interpreted.view(), null);
        }
        return interpreted.question().trend() ? askTrend(text, interpreted, today) : askSearch(text, interpreted, today);
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
        List<AiClient.InterpretTurn> turns = turns(history, categories, itemTypes);
        try {
            AiClient.InterpretResponse r = aiClient.interpret(new AiClient.InterpretRequest(text, interpretModel, today.toString(),
                    // 일상 표현이 없으면 null이 아니라 ""로 보낸다(예전 AI 서비스는 null이면 422로 거부했다)
                    categories.stream().map(c -> new AiClient.CodeHint(c.getCodeValue(), c.getCodeName(), Objects.toString(c.getAttr1(), ""))).toList(),
                    itemTypes.stream().map(t -> new AiClient.CodeHint(t.getCodeValue(), t.getCodeName(), "")).toList(), turns), apiKey);
            String category = known(categories, r.category());
            String itemType = known(itemTypes, r.itemType());
            if (itemType != null) {
                category = TrendCodes.categoryOfType(itemType);
            }
            QuestionInterpreter.Period period = r.period() == null ? null : new QuestionInterpreter.Period(
                    LocalDate.parse(r.period().from()), LocalDate.parse(r.period().to()), r.period().label(), true);
            String query = r.question() == null || r.question().isBlank() || r.question().length() > MAX_QUESTION_LENGTH
                    ? text : r.question().trim();
            log.info("질문 해석(AI) 앞 대화 {}건 다시 씀={} 패션={} 유행={} 카테고리={} 종류={} 기간={} 토큰 {}", turns.size(),
                    !query.equals(text), !r.offTopic(), r.trend(), category, itemType, period == null ? "-" : period.label(),
                    TokenUsage.of(r.usage()));
            return new Interpreted(new QuestionInterpreter.Interpretation(r.trend(), category, name(categories, category),
                    itemType, name(itemTypes, itemType), period), !r.offTopic(), "AI", text, query);
        } catch (RuntimeException e) {
            log.warn("질문 해석(AI) 실패 — 규칙으로 대신합니다: {}", e.getMessage());
            List<ComCd> trendWords = comCdRepository.findByCodeGroupAndUseYnOrderBySortOrderAsc(TrendCodes.TREND_WORD, "Y");
            // 규칙은 주제를 가리지 않고 이어 묻기도 모른다 — 검색 질문은 기준 유사도가 무관한 질문을 따로 막는다
            return new Interpreted(QuestionInterpreter.interpret(text, today, categories, itemTypes, trendWords), true, "RULE",
                    text, text);
        }
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

    /** 유행 질문 — 범위 원문을 세서(TrendFactsService) 그 집계와 예시 원문으로 답한다. 기간 안에 원문이 없으면 AI를 부르지 않는다. */
    private RagAnswerView askTrend(String text, Interpreted interpreted, LocalDate today) {
        TrendFactsService.Facts facts = trendFactsService.count(interpreted.question(), today);
        QuestionInterpreter.Period p = facts.period();
        RagAnswerView.Period period = new RagAnswerView.Period(p.from(), p.to(), p.label(), p.explicit(), facts.stale());
        RagAnswerView.Facts factsView = factsView(facts);
        if (facts.docCount() == 0) {
            return new RagAnswerView(text, false, "근거 자료 없음 — " + p.label() + "(" + p.from() + " ~ " + p.to() + ")에 "
                    + facts.scopeLabel() + " 원문이 없습니다.", List.of(), "NO_EVIDENCE", List.of(), null, minScore, null,
                    period, interpreted.view(), factsView);
        }
        AiClient.AnswerResponse response = aiClient.answer(new AiClient.AnswerRequest(interpreted.query(), model, topK, minScore, null, List.of(),
                today.toString(), AiClient.AnswerRequest.TREND, interpreted.question().category(),
                new AiClient.AnswerPeriod(p.from().toString(), p.to().toString(), p.label(), p.explicit(), facts.stale()),
                facts.lines(), facts.evidenceDocIds()), apiKey);
        log.info("RAG 유행 질문 {}자 범위={} 기간={}~{} 원문 {}건 매체 {}곳 근거={} 출처 {}건 토큰 {}", text.length(), facts.scopeLabel(),
                p.from(), p.to(), facts.docCount(), facts.mediaCount(), response.grounded(),
                response.sources() == null ? 0 : response.sources().size(), TokenUsage.of(response.usage()));
        return view(text, response, period, null, interpreted, factsView);
    }

    /** 그 밖의 질문 — 문장 유사도 검색(카테고리·기간으로 거름). 주간 집계 상위 값을 참고로 덧붙인다. */
    private RagAnswerView askSearch(String text, Interpreted interpreted, LocalDate today) {
        LocalDate week = statRepository.findLastWeekStart();
        List<AiClient.StatRow> stats = week == null ? List.of() : stats(week);
        QuestionInterpreter.Period p = interpreted.question().period();
        AiClient.AnswerResponse response = aiClient.answer(new AiClient.AnswerRequest(interpreted.query(), model, topK, minScore,
                week == null ? null : week.toString(), stats, today.toString(), AiClient.AnswerRequest.SEARCH,
                interpreted.question().category(),
                p == null ? null : new AiClient.AnswerPeriod(p.from().toString(), p.to().toString(), p.label(), true, false),
                List.of(), List.of()), apiKey);
        RagAnswerView.Period period = response.period() == null ? null : new RagAnswerView.Period(
                date(response.period().from()), date(response.period().to()), response.period().label(),
                response.period().explicit(), response.period().stale());
        log.info("RAG 질문 {}자 카테고리={} 근거={} 사유={} 최고 유사도={} 출처 {}건 토큰 {}", text.length(),
                interpreted.question().category(), response.grounded(), response.reason(), response.bestScore(),
                response.sources() == null ? 0 : response.sources().size(), TokenUsage.of(response.usage()));
        return view(text, response, period, week, interpreted, null);
    }

    private RagAnswerView view(String text, AiClient.AnswerResponse response, RagAnswerView.Period period, LocalDate week,
                               Interpreted interpreted, RagAnswerView.Facts facts) {
        if (!response.grounded()) {
            return new RagAnswerView(text, false, noEvidenceMessage(response, period), List.of(), response.reason(), List.of(),
                    response.bestScore(), minScore, week, period, interpreted.view(), facts);
        }
        List<RagAnswerView.Source> sources = sources(response.sources());
        if (sources.isEmpty()) { // 인용한 문서가 모두 삭제·재분석 중이면 출처 없는 답이 된다 — 내보내지 않는다
            return new RagAnswerView(text, false, "근거 자료 없음 — 근거 문서가 삭제되었거나 다시 분석 중입니다.",
                    List.of(), "SOURCE_MISSING", List.of(), response.bestScore(), minScore, week, period, interpreted.view(), facts);
        }
        return new RagAnswerView(text, true, response.answer(), points(response.points(), sources), null, sources,
                response.bestScore(), minScore, week, period, interpreted.view(), facts);
    }

    private static RagAnswerView.Facts factsView(TrendFactsService.Facts facts) {
        return new RagAnswerView.Facts(facts.scopeLabel(), facts.docCount(), facts.mediaCount(), facts.prevDocCount(),
                facts.dimensions().stream().filter(d -> !d.rows().isEmpty())
                        .map(d -> new RagAnswerView.Dimension(d.label(), d.rows().stream()
                                .map(r -> new RagAnswerView.FactRow(r.label(), r.count(), r.mediaCount(), r.prevCount())).toList()))
                        .toList());
    }

    private static String known(List<ComCd> codes, String value) {
        return value == null || codes.stream().noneMatch(c -> c.getCodeValue().equals(value)) ? null : value;
    }

    private static String name(List<ComCd> codes, String value) {
        return value == null ? null : codes.stream().filter(c -> c.getCodeValue().equals(value)).map(ComCd::getCodeName)
                .findFirst().orElse(value);
    }

    /** 줄마다 인용 번호를 남은 출처(삭제·재분석 중 문서 제외)로만 맞춘다 — 화면의 번호가 없는 출처를 가리키지 않게. */
    private static List<RagAnswerView.Point> points(List<AiClient.AnswerPoint> points, List<RagAnswerView.Source> sources) {
        if (points == null) {
            return List.of();
        }
        Set<Integer> shown = sources.stream().map(RagAnswerView.Source::n).collect(Collectors.toSet());
        return points.stream()
                .map(p -> new RagAnswerView.Point(p.text(), p.sources() == null ? List.of()
                        : p.sources().stream().filter(shown::contains).toList()))
                .toList();
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

    /** 집계가 있는 마지막 주의 유형별 상위 값(전체 카테고리). 유형은 공통코드 TREND_VALUE_TYPE 중 속성 RAG인 것, 이름은 코드명. */
    private List<AiClient.StatRow> stats(LocalDate week) {
        List<AiClient.StatRow> rows = new ArrayList<>();
        for (ComCd type : comCdRepository.findByCodeGroupAndUseYnOrderBySortOrderAsc(TrendCodes.VALUE_TYPE, "Y")) {
            if (!type.attrValues().contains(TrendCodes.ATTR_RAG) || !TrendQueryService.VALUE_TYPES.contains(type.getCodeValue())) {
                continue;
            }
            trendQueryService.getTrend(type.getCodeValue(), null, week, 1).rows().stream().limit(STATS_PER_TYPE)
                    .forEach(r -> rows.add(new AiClient.StatRow(type.getCodeName(), r.label(), r.count(), r.prevCount(),
                            r.prevRate(), r.surge(), r.isNew())));
        }
        return rows;
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
