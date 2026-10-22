package com.sjinc.trendanalysis.service.rag;

import com.sjinc.trendanalysis.domain.ComCd;
import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.domain.TrendItem;
import com.sjinc.trendanalysis.domain.TrendItemValue;
import com.sjinc.trendanalysis.domain.TrendWeeklyStat;
import com.sjinc.trendanalysis.repository.ComCdRepository;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.repository.TrendItemRepository;
import com.sjinc.trendanalysis.service.analysis.TrendCodes;
import com.sjinc.trendanalysis.service.trend.TrendAggregationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 유행 질문의 근거 집계 — 유행 = 많이 입어 대중화된 것(2026-10-22 사용자 정의)이라, 질문 범위(카테고리·아이템 종류)의 원문을
 * 기간 안에서 센다. 질문할 때 바로 센다(주간 배치를 기다리지 않는다 — 방금 올린 원문도 반영).
 *
 * <ul>
 *   <li>원문 수: 그 값이 나온 분석 완료 원문 수(한 원문에 여러 번 나와도 1). 매체 수: 그 원문들의 서로 다른 출처 수 — 한 매체가
 *       여러 번 올린 것과 여러 곳에서 보이는 것(대중화)을 가르기 위해서다. 같은 원문이 다시 수집된 것은 저장 단계에서 URL로 막는다.</li>
 *   <li>기간: 질문이 정한 기간, 없으면 오늘까지 4주. 그 안에 원문이 없으면 범위 원문 중 가장 최근 게시일까지 4주로 옮기고(8주 넘게
 *       오래됐으면 stale), 직전 같은 길이 기간과 비교한다.</li>
 *   <li>예시 원문: 첫 번째 축(아이템 종류, 없으면 카테고리) 상위 값마다 최근 원문 2건 — 답변의 출처가 된다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TrendFactsService {

    static final int TOP = 5;
    static final int EXAMPLE_VALUES = 3;
    static final int EXAMPLES_PER_VALUE = 2;
    static final int MAX_EXAMPLES = 6;
    static final int STALE_DAYS = 56;

    private final SourceDocumentRepository sourceDocumentRepository;
    private final TrendItemRepository trendItemRepository;
    private final ComCdRepository comCdRepository;

    /** 값 하나의 집계. prevCount는 직전 같은 길이 기간의 원문 수. */
    public record Row(String value, String label, int count, int mediaCount, int prevCount) {
    }

    /** 축 하나(아이템 종류·소재…)의 상위 값들. */
    public record Dimension(String valueType, String label, List<Row> rows) {
    }

    /**
     * @param scopeLabel 집계 범위(예: "아우터", "아우터 · 패딩", 범위가 없으면 "전체")
     * @param docCount   기간 안 범위 원문 수
     * @param prevDocCount 직전 기간 범위 원문 수
     */
    public record Facts(QuestionInterpreter.Period period, boolean stale, String scopeLabel, int docCount, int mediaCount,
                        int prevDocCount, List<Dimension> dimensions, List<String> evidenceDocIds) {

        /** AI 서비스에 보내는 집계 줄(한국어) — 해석 규칙은 prompts/rag/answer.md. */
        public List<String> lines() {
            List<String> lines = new ArrayList<>();
            lines.add("대상: " + scopeLabel + " · 원문 " + docCount + "건 · 매체 " + mediaCount + "곳 (직전 기간 원문 " + prevDocCount + "건)");
            for (Dimension d : dimensions) {
                if (d.rows().isEmpty()) continue;
                lines.add("[" + d.label() + "] " + d.rows().stream()
                        .map(r -> r.label() + " " + r.count() + "건 · 매체 " + r.mediaCount() + "곳 · 직전 " + r.prevCount() + "건")
                        .collect(Collectors.joining(" / ")));
            }
            return lines;
        }
    }

    public Facts count(QuestionInterpreter.Interpretation question, LocalDate today) {
        Scope scope = new Scope(question.category(), question.itemType());
        QuestionInterpreter.Period period = question.period() != null ? question.period() : QuestionInterpreter.recentWeeks(today);
        Facts facts = countIn(period, scope, question, false);
        if (facts.docCount() == 0 && question.period() == null) {
            // 최근 4주에 없으면 범위 원문 중 가장 최근 게시일까지 4주로 옮긴다(오래된 자료라고 알린다)
            LocalDate latest = latestDate(scope);
            if (latest != null) {
                boolean stale = ChronoUnit.DAYS.between(latest, today) > STALE_DAYS;
                QuestionInterpreter.Period moved = new QuestionInterpreter.Period(latest.minusDays(QuestionInterpreter.TREND_DAYS - 1L),
                        latest, "가장 최근 자료 4주", false);
                facts = countIn(moved, scope, question, stale);
            }
        }
        return facts;
    }

    private record Scope(String category, String itemType) {
        boolean matches(TrendItem.Garment g) {
            return (category == null || category.equals(g.category())) && (itemType == null || itemType.equals(g.type()));
        }
    }

    /** 값 하나를 센 것 — 기간 안 원문·매체, 직전 기간 원문. */
    private static final class Tally {
        final Set<Long> docs = new HashSet<>();
        final Set<String> media = new HashSet<>();
        final Set<Long> prevDocs = new HashSet<>();
        final List<SourceDocument> examples = new ArrayList<>();
    }

    private Facts countIn(QuestionInterpreter.Period period, Scope scope, QuestionInterpreter.Interpretation question, boolean stale) {
        long days = ChronoUnit.DAYS.between(period.from(), period.to()) + 1;
        LocalDate prevFrom = period.from().minusDays(days);
        List<SourceDocument> docs = sourceDocumentRepository.findByStatusAndPublishedAtBetween(SourceDocument.DONE, prevFrom, period.to());
        Map<Long, TrendItem> items = trendItemRepository.findByDocumentIdIn(docs.stream().map(SourceDocument::getId).toList())
                .stream().collect(Collectors.toMap(TrendItem::getDocumentId, Function.identity()));

        List<String> types = dimensionTypes(scope);
        Map<String, Map<String, Tally>> tallies = new LinkedHashMap<>();
        types.forEach(t -> tallies.put(t, new LinkedHashMap<>()));
        Set<Long> scopeDocs = new HashSet<>();
        Set<String> scopeMedia = new HashSet<>();
        Set<Long> prevScopeDocs = new HashSet<>();

        // 최신 원문부터 — 예시 원문이 최근 것이 되게
        docs.sort(Comparator.comparing(TrendAggregationService::statDate).reversed().thenComparing(SourceDocument::getId, Comparator.reverseOrder()));
        for (SourceDocument doc : docs) {
            TrendItem item = items.get(doc.getId());
            if (item == null) continue;
            List<TrendItem.Garment> garments = item.garments().stream().filter(scope::matches).toList();
            if (garments.isEmpty()) continue;
            LocalDate date = TrendAggregationService.statDate(doc);
            boolean current = !date.isBefore(period.from());
            if (current) {
                scopeDocs.add(doc.getId());
                scopeMedia.add(doc.getMedia());
            } else {
                prevScopeDocs.add(doc.getId());
            }
            for (String type : types) {
                Set<String> values = new LinkedHashSet<>();
                garments.forEach(g -> values.addAll(valuesOf(g, type)));
                for (String value : values) {
                    Tally t = tallies.get(type).computeIfAbsent(value, v -> new Tally());
                    if (current) {
                        if (t.docs.add(doc.getId())) {
                            t.examples.add(doc);
                        }
                        t.media.add(doc.getMedia());
                    } else {
                        t.prevDocs.add(doc.getId());
                    }
                }
            }
        }

        Map<String, String> typeLabels = names(TrendCodes.VALUE_TYPE);
        List<Dimension> dimensions = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        for (String type : types) {
            Map<String, String> labels = names(TrendWeeklyStat.CATEGORY.equals(type) ? TrendCodes.CATEGORY : TrendCodes.VALUE_TYPE_GROUPS.get(type));
            List<Map.Entry<String, Tally>> ranked = tallies.get(type).entrySet().stream()
                    .filter(e -> !e.getValue().docs.isEmpty())
                    .sorted(Comparator.comparingInt((Map.Entry<String, Tally> e) -> e.getValue().docs.size()).reversed()
                            .thenComparing(e -> e.getValue().media.size(), Comparator.reverseOrder())
                            .thenComparing(Map.Entry::getKey))
                    .limit(TOP).toList();
            dimensions.add(new Dimension(type, typeLabels.getOrDefault(type, type), ranked.stream()
                    .map(e -> new Row(e.getKey(), labels.getOrDefault(e.getKey(), e.getKey()), e.getValue().docs.size(),
                            e.getValue().media.size(), e.getValue().prevDocs.size()))
                    .toList()));
            if (evidence.isEmpty() && !ranked.isEmpty()) { // 첫 번째 축의 상위 값마다 최근 원문
                ranked.stream().limit(EXAMPLE_VALUES).forEach(e -> e.getValue().examples.stream().limit(EXAMPLES_PER_VALUE)
                        .map(SourceDocument::getDocId).filter(id -> !evidence.contains(id)).forEach(evidence::add));
            }
        }
        if (scope.itemType() != null) { // 종류를 콕 집었으면 그 종류의 최근 원문
            evidence.clear();
            docs.stream().filter(d -> scopeDocs.contains(d.getId())).limit(MAX_EXAMPLES).map(SourceDocument::getDocId).forEach(evidence::add);
        }
        return new Facts(period, stale, scopeLabel(question), scopeDocs.size(), scopeMedia.size(), prevScopeDocs.size(),
                dimensions, evidence.stream().limit(MAX_EXAMPLES).toList());
    }

    /** 범위에 따라 셀 축 — 종류를 콕 집었으면 종류 축은 빼고, 카테고리를 정했으면 카테고리 축은 뺀다. */
    private static List<String> dimensionTypes(Scope scope) {
        List<String> types = new ArrayList<>();
        if (scope.category() == null) types.add(TrendWeeklyStat.CATEGORY);
        if (scope.itemType() == null) types.add(TrendItemValue.ITEM_TYPE);
        types.addAll(List.of(TrendItemValue.MATERIAL, TrendItemValue.COLOR, TrendItemValue.SILHOUETTE));
        if (scope.category() == null && scope.itemType() == null) { // 전체를 물으면 종류가 먼저(카테고리보다 구체적)
            types.remove(TrendWeeklyStat.CATEGORY);
            types.add(1, TrendWeeklyStat.CATEGORY);
        }
        return types;
    }

    private static List<String> valuesOf(TrendItem.Garment g, String type) {
        return switch (type) {
            case TrendWeeklyStat.CATEGORY -> g.category() == null ? List.of() : List.of(g.category());
            case TrendItemValue.ITEM_TYPE -> g.type() == null ? List.of() : List.of(g.type());
            case TrendItemValue.MATERIAL -> g.materials();
            case TrendItemValue.COLOR -> g.colors();
            case TrendItemValue.SILHOUETTE -> g.silhouettes();
            default -> List.of();
        };
    }

    /** 범위 원문 중 가장 최근 게시일(분석 완료). 범위 원문이 없으면 null. */
    private LocalDate latestDate(Scope scope) {
        List<SourceDocument> done = sourceDocumentRepository.findByStatus(SourceDocument.DONE);
        Map<Long, TrendItem> items = trendItemRepository.findByDocumentIdIn(done.stream().map(SourceDocument::getId).toList())
                .stream().collect(Collectors.toMap(TrendItem::getDocumentId, Function.identity()));
        return done.stream()
                .filter(d -> items.containsKey(d.getId()) && items.get(d.getId()).garments().stream().anyMatch(scope::matches))
                .map(TrendAggregationService::statDate)
                .max(Comparator.naturalOrder()).orElse(null);
    }

    private static String scopeLabel(QuestionInterpreter.Interpretation q) {
        if (q.itemTypeName() != null) {
            return (q.categoryName() == null ? "" : q.categoryName() + " · ") + q.itemTypeName();
        }
        return q.categoryName() == null ? "전체" : q.categoryName();
    }

    /** 코드값 → 이름(사용 중지 코드도 이름이 보여야 하므로 전부). */
    private Map<String, String> names(String group) {
        Map<String, String> names = new HashMap<>();
        if (group != null) {
            for (ComCd code : comCdRepository.findByCodeGroupOrderBySortOrderAsc(group)) {
                names.put(code.getCodeValue(), code.getCodeName());
            }
        }
        return names;
    }
}
