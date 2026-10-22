package com.sjinc.trendanalysis.service.rag;

import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.domain.TrendItemValue;
import com.sjinc.trendanalysis.dto.rag.RagAnswerView;
import com.sjinc.trendanalysis.dto.trend.TrendView;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.repository.TrendWeeklyStatRepository;
import com.sjinc.trendanalysis.service.analysis.AiCallException;
import com.sjinc.trendanalysis.service.analysis.AiClient;
import com.sjinc.trendanalysis.service.analysis.TokenUsage;
import com.sjinc.trendanalysis.service.trend.TrendAggregationService;
import com.sjinc.trendanalysis.service.trend.TrendQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * P-06 RAG 검색·답변. 검색·근거 판정·답변은 AI 서비스(/answer)가 하고, 여기서는 주간 집계 수치를 덧붙여 보내고
 * 근거를 원문 문서(제목·매체·날짜·링크)로 되돌린다. 근거 문서가 삭제됐거나 분석 완료가 아니면 출처에서 뺀다.
 */
@Service
@Slf4j
public class RagService {

    public static final int MAX_QUESTION_LENGTH = 500;
    /** 답변에 덧붙이는 집계 — 유형별 언급량 상위 몇 개. 많이 넣으면 근거보다 수치가 앞선다. */
    static final int STATS_PER_TYPE = 5;
    static final Map<String, String> STAT_TYPES = statTypes();

    private final AiClient aiClient;
    private final TrendQueryService trendQueryService;
    private final TrendWeeklyStatRepository statRepository;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final String apiKey;
    private final String model;
    private final double minScore;
    private final int topK;

    /**
     * @param minScore 근거로 인정하는 최소 유사도(1 - 코사인 거리). 0.60은 2026-10-21 이미지 32건으로 잰 임시값 —
     *                 관련 질문 1위 근거가 0.64~0.78, 데이터에 없는 패션 주제가 0.48~0.59, 패션과 무관한 질문이 0.29~0.37이었다.
     *                 데이터가 쌓이면 다시 잰다(ai /search로 점수 분포 확인).
     */
    public RagService(AiClient aiClient, TrendQueryService trendQueryService, TrendWeeklyStatRepository statRepository,
                      SourceDocumentRepository sourceDocumentRepository,
                      @Value("${claude.api.key:}") String apiKey,
                      @Value("${claude.model:claude-sonnet-5}") String model,
                      @Value("${rag.min-score:0.60}") double minScore,
                      @Value("${rag.top-k:8}") int topK) {
        this.aiClient = aiClient;
        this.trendQueryService = trendQueryService;
        this.statRepository = statRepository;
        this.sourceDocumentRepository = sourceDocumentRepository;
        this.apiKey = apiKey;
        this.model = model;
        this.minScore = minScore;
        this.topK = topK;
    }

    public RagAnswerView ask(String question) {
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

        LocalDate week = statRepository.findLastWeekStart();
        List<AiClient.StatRow> stats = week == null ? List.of() : stats(week);
        AiClient.AnswerResponse response = aiClient.answer(new AiClient.AnswerRequest(
                text, model, topK, minScore, week == null ? null : week.toString(), stats, LocalDate.now().toString()), apiKey);
        RagAnswerView.Period period = response.period() == null ? null : new RagAnswerView.Period(
                date(response.period().from()), date(response.period().to()), response.period().label(),
                response.period().explicit(), response.period().stale());

        TokenUsage usage = TokenUsage.of(response.usage());
        log.info("RAG 질문 {}자 근거={} 사유={} 최고 유사도={} 출처 {}건 토큰 {}", text.length(), response.grounded(),
                response.reason(), response.bestScore(), response.sources() == null ? 0 : response.sources().size(), usage);

        if (!response.grounded()) {
            return new RagAnswerView(text, false, noEvidenceMessage(response, period), List.of(), response.reason(), List.of(),
                    response.bestScore(), minScore, week, period);
        }
        List<RagAnswerView.Source> sources = sources(response.sources());
        if (sources.isEmpty()) { // 인용한 문서가 모두 삭제·재분석 중이면 출처 없는 답이 된다 — 내보내지 않는다
            return new RagAnswerView(text, false, "근거 자료 없음 — 근거 문서가 삭제되었거나 다시 분석 중입니다.",
                    List.of(), "SOURCE_MISSING", List.of(), response.bestScore(), minScore, week, period);
        }
        return new RagAnswerView(text, true, response.answer(), points(response.points(), sources), null, sources,
                response.bestScore(), minScore, week, period);
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

    /** 집계가 있는 마지막 주의 유형별 상위 값(전체 카테고리). 이름은 공통코드명으로. */
    private List<AiClient.StatRow> stats(LocalDate week) {
        List<AiClient.StatRow> rows = new ArrayList<>();
        STAT_TYPES.forEach((type, label) -> trendQueryService.getTrend(type, null, week, 1).rows().stream()
                .limit(STATS_PER_TYPE)
                .forEach(r -> rows.add(new AiClient.StatRow(label, r.label(), r.count(), r.prevCount(), r.prevRate(),
                        r.surge(), r.isNew()))));
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
                    return new RagAnswerView.Source(s.n(), doc.getDocId(), doc.getDocType(), doc.getTitle(), doc.getMedia(),
                            TrendAggregationService.statDate(doc), doc.getUrl(), s.score(), s.chunk());
                })
                .toList();
    }

    private static Map<String, String> statTypes() {
        Map<String, String> types = new LinkedHashMap<>();
        types.put(TrendItemValue.KEYWORD, "키워드");
        types.put(TrendItemValue.ITEM, "아이템");
        types.put(TrendItemValue.MATERIAL, "소재");
        types.put(TrendItemValue.COLOR, "컬러");
        types.put(TrendItemValue.SILHOUETTE, "실루엣");
        return types;
    }
}
