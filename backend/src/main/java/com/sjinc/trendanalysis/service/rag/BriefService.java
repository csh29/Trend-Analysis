package com.sjinc.trendanalysis.service.rag;

import com.sjinc.trendanalysis.domain.ComCd;
import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.domain.TrendItem;
import com.sjinc.trendanalysis.domain.TrendItemValue;
import com.sjinc.trendanalysis.dto.rag.BriefView;
import com.sjinc.trendanalysis.dto.rag.RagAnswerView;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 기획 브리프(2026-10-29 사용자 요청) — 챗봇 답 아래 "기획 브리프 PPT". 질문 범위를 여러 갈래로 세고(전체 축·카테고리별 아이템·늘어난 값·
 * 출처 분포) 예시 원문과 함께 Claude에게 주어 8개 구성의 서술만 받는다. 차트·건수는 이 집계를 화면이 그린다 — Claude가 숫자를 만들지 않게.
 */
@Service
@Slf4j
public class BriefService {

    static final int TOP = 10;
    static final int CATEGORY_TOP = 5;
    static final int MAX_SOURCES = 8;
    static final int SUMMARY_CHARS = 200;
    /** 범위가 정해지지 않았을 때 아이템을 나눠 보는 카테고리(사용자 브리프 구성 "아우터·상의·하의별"). */
    static final List<String> BRIEF_CATEGORIES = List.of("OUTER", "TOP", "BOTTOM");

    private final RagService ragService;
    private final TrendFactsService trendFactsService;
    private final AiClient aiClient;
    private final ComCdRepository comCdRepository;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final TrendItemRepository trendItemRepository;
    private final String apiKey;
    private final String model;

    public BriefService(RagService ragService, TrendFactsService trendFactsService, AiClient aiClient, ComCdRepository comCdRepository,
                        SourceDocumentRepository sourceDocumentRepository, TrendItemRepository trendItemRepository,
                        @Value("${claude.api.key:}") String apiKey, @Value("${claude.model:claude-sonnet-5}") String model) {
        this.ragService = ragService;
        this.trendFactsService = trendFactsService;
        this.aiClient = aiClient;
        this.comCdRepository = comCdRepository;
        this.sourceDocumentRepository = sourceDocumentRepository;
        this.trendItemRepository = trendItemRepository;
        this.apiKey = apiKey;
        this.model = model;
    }

    public BriefView build(String question) {
        String text = question == null ? "" : question.trim();
        if (text.isEmpty() || text.length() > RagService.MAX_QUESTION_LENGTH) {
            throw new IllegalArgumentException("브리프를 만들 질문이 필요합니다(" + RagService.MAX_QUESTION_LENGTH + "자 이하).");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiCallException("claude.api.key가 설정되지 않았습니다.", false);
        }
        long started = System.currentTimeMillis();
        LocalDate today = LocalDate.now();
        RagService.Answered[] interpretUsage = new RagService.Answered[1];
        QuestionInterpreter.Interpretation scope = ragService.briefScope(text, today, interpretUsage);

        TrendFactsService.Facts facts = trendFactsService.count(scope, today, TOP);
        if (facts.docCount() == 0) {
            throw new IllegalArgumentException("기간 안에 " + facts.scopeLabel() + " 원문이 없어 브리프를 만들 수 없습니다.");
        }
        QuestionInterpreter.Period period = facts.period();
        QuestionInterpreter.Interpretation fixed = withPeriod(scope, period); // 갈래마다 같은 기간으로 센다

        List<String> data = new ArrayList<>(facts.lines());
        List<BriefView.CategoryItems> itemsByCategory = new ArrayList<>();
        Map<String, String> categoryNames = comCdRepository.findByCodeGroupAndUseYnOrderBySortOrderAsc(TrendCodes.CATEGORY, "Y").stream()
                .collect(Collectors.toMap(ComCd::getCodeValue, ComCd::getCodeName, (a, b) -> a));
        if (scope.category() == null && scope.itemType() == null) {
            for (String category : BRIEF_CATEGORIES) {
                TrendFactsService.Facts byCategory = trendFactsService.count(withCategory(fixed, category, categoryNames.get(category)), today, CATEGORY_TOP);
                RagAnswerView.Dimension items = itemDimension(byCategory);
                if (items == null) continue;
                itemsByCategory.add(new BriefView.CategoryItems(categoryNames.getOrDefault(category, category), items.rows()));
                data.add("[카테고리별 아이템 — " + categoryNames.getOrDefault(category, category) + "] " + items.rows().stream()
                        .map(r -> r.label() + " " + r.count() + "건(직전 " + r.prevCount() + "건, " + r.rate() + ")")
                        .collect(Collectors.joining(", ")));
            }
        } else {
            RagAnswerView.Dimension items = itemDimension(facts);
            if (items != null) itemsByCategory.add(new BriefView.CategoryItems(facts.scopeLabel(), items.rows()));
        }
        // 늘어난 값(축마다 상위 5개) — "가장 주목할 변화"의 근거
        TrendFactsService.Facts rising = trendFactsService.count(withChange(fixed, "UP"), today, 5);
        rising.lines().stream().filter(l -> l.startsWith("[")).forEach(l -> data.add("[늘어난 값] " + l));
        List<TrendFactsService.NameCount> mix = trendFactsService.sourceMix(fixed, period);
        data.add("[출처 분포] " + mix.stream().map(m -> m.name() + " " + m.count() + "건").collect(Collectors.joining(", ")));

        List<RagAnswerView.Source> sources = sources(facts.evidenceDocIds());
        List<AiClient.BriefSource> briefSources = sources.stream()
                .map(s -> new AiClient.BriefSource(s.n(), s.title(), s.media(), Objects.toString(s.statDate(), ""), s.chunk())).toList();
        AiClient.BriefResponse response = aiClient.brief(new AiClient.BriefRequest(text, model, today.toString(), data, briefSources), apiKey);

        TokenUsage usage = TokenUsage.of(response.usage()).plus(interpretUsage[0].usage());
        log.info("기획 브리프 {}자 범위={} 기간={}~{} 원문 {}건 집계 {}줄 예시 {}건 토큰 {} 소요 {}ms", text.length(), facts.scopeLabel(),
                period.from(), period.to(), facts.docCount(), data.size(), sources.size(), usage, System.currentTimeMillis() - started);
        RagAnswerView.Facts view = RagService.factsView(facts);
        return new BriefView(text, new RagAnswerView.Period(period.from(), period.to(), period.label(), period.explicit(), facts.stale()),
                facts.scopeLabel(), facts.docCount(), facts.mediaCount(), facts.prevDocCount(), itemsByCategory, view.dimensions(),
                mix.stream().map(m -> new BriefView.SourceCount(m.name(), m.count())).toList(), sources, response.brief());
    }

    private static RagAnswerView.Dimension itemDimension(TrendFactsService.Facts facts) {
        return RagService.factsView(facts).dimensions().stream().filter(d -> "아이템 종류".equals(d.label()) || TrendItemValue.ITEM_TYPE.equals(d.label()))
                .findFirst().orElse(null);
    }

    private static QuestionInterpreter.Interpretation withPeriod(QuestionInterpreter.Interpretation q, QuestionInterpreter.Period period) {
        return new QuestionInterpreter.Interpretation(true, q.category(), q.categoryName(), q.itemType(), q.itemTypeName(),
                new QuestionInterpreter.Period(period.from(), period.to(), period.label(), true), q.terms(), q.seasons(), null, q.filters(), null);
    }

    private static QuestionInterpreter.Interpretation withCategory(QuestionInterpreter.Interpretation q, String category, String name) {
        return new QuestionInterpreter.Interpretation(true, category, name, null, null, q.period(), List.of(), q.seasons(),
                TrendItemValue.ITEM_TYPE, q.filters(), null);
    }

    private static QuestionInterpreter.Interpretation withChange(QuestionInterpreter.Interpretation q, String change) {
        return new QuestionInterpreter.Interpretation(true, q.category(), q.categoryName(), q.itemType(), q.itemTypeName(), q.period(),
                List.of(), q.seasons(), null, q.filters(), change);
    }

    /** 예시 원문 → 출처(번호·제목·매체·날짜·사진 주소·요약). 분석이 끝난 원문만. */
    private List<RagAnswerView.Source> sources(List<String> docIds) {
        List<String> ids = docIds.stream().limit(MAX_SOURCES).toList();
        Map<String, SourceDocument> docs = sourceDocumentRepository.findByDocIdIn(ids).stream()
                .filter(d -> SourceDocument.DONE.equals(d.getStatus()))
                .collect(Collectors.toMap(SourceDocument::getDocId, Function.identity()));
        Map<Long, TrendItem> items = trendItemRepository.findByDocumentIdIn(docs.values().stream().map(SourceDocument::getId).toList())
                .stream().collect(Collectors.toMap(TrendItem::getDocumentId, Function.identity()));
        List<RagAnswerView.Source> out = new ArrayList<>();
        for (String id : ids) {
            SourceDocument doc = docs.get(id);
            if (doc == null) continue;
            TrendItem item = items.get(doc.getId());
            String summary = item == null || item.getSummary() == null ? "" : item.getSummary();
            if (summary.length() > SUMMARY_CHARS) summary = summary.substring(0, SUMMARY_CHARS) + "…";
            out.add(new RagAnswerView.Source(out.size() + 1, doc.getDocId(), doc.getDocType(), doc.getTitle(), doc.getMedia(),
                    TrendAggregationService.statDate(doc), doc.isImage() ? null : doc.getUrl(),
                    doc.isImage() ? "/api/rag/sources/" + doc.getDocId() + "/image" : null, null, summary));
        }
        return out;
    }
}
