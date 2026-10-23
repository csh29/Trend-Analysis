package com.sjinc.trendanalysis.service.trend;

import com.sjinc.trendanalysis.domain.ComCd;
import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.domain.TrendItem;
import com.sjinc.trendanalysis.domain.TrendItemValue;
import com.sjinc.trendanalysis.domain.TrendWeeklyStat;
import com.sjinc.trendanalysis.dto.trend.TrendEvidenceView;
import com.sjinc.trendanalysis.dto.trend.TrendView;
import com.sjinc.trendanalysis.repository.ComCdRepository;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.repository.TrendItemRepository;
import com.sjinc.trendanalysis.repository.TrendWeeklyStatRepository;
import com.sjinc.trendanalysis.service.analysis.TrendCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 트렌드 분석 화면 조회. 수치는 집계 표(trend_weekly_stats)만 읽고, 근거 원문은 트렌드 항목에서 바로 찾는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TrendQueryService {

    public static final Set<String> VALUE_TYPES = Set.of(TrendItemValue.KEYWORD, TrendItemValue.ITEM_TYPE,
            TrendItemValue.MATERIAL,
            TrendItemValue.COLOR, TrendItemValue.SILHOUETTE, TrendItemValue.SEASON, TrendWeeklyStat.CATEGORY);
    /** 차트에 그리는 값 수 — 많으면 선이 겹쳐 읽을 수 없다. */
    static final int SERIES_SIZE = 5;
    static final int MAX_WEEKS = 52;

    private final TrendWeeklyStatRepository statRepository;
    private final TrendItemRepository trendItemRepository;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final ComCdRepository comCdRepository;

    /**
     * @param baseWeek 기준 주(아무 날짜나 주면 그 주 월요일로 맞춘다). 없으면 지난주 — 이번 주는 진행 중이라 전주보다 작게 나온다
     * @param weeks    기준 주를 포함해 거슬러 올라갈 주 수(차트 기간)
     */
    public TrendView getTrend(String valueType, String category, LocalDate baseWeek, int weeks) {
        checkValueType(valueType);
        if (weeks < 1 || weeks > MAX_WEEKS) {
            throw new IllegalArgumentException("기간은 1~" + MAX_WEEKS + "주로 지정하세요.");
        }
        LocalDate base = TrendAggregator.weekStart(baseWeek != null ? baseWeek : LocalDate.now().minusWeeks(1));
        LocalDate from = base.minusWeeks(weeks - 1L);
        List<LocalDate> weekList = new ArrayList<>();
        for (LocalDate w = from; !w.isAfter(base); w = w.plusWeeks(1)) {
            weekList.add(w);
        }

        List<TrendWeeklyStat> stats = statRepository.findByCategoryAndValueTypeAndWeekStartBetween(
                scope(category), valueType, from, base);
        Map<String, String> labels = labels(valueType);

        List<TrendView.Row> rows = stats.stream()
                .filter(s -> s.getWeekStart().equals(base))
                .sorted(Comparator.comparingInt(TrendWeeklyStat::getMentionCount).reversed()
                        .thenComparing(s -> s.getPrevWeekRate() == null ? Double.MAX_VALUE : s.getPrevWeekRate(), Comparator.reverseOrder())
                        .thenComparing(TrendWeeklyStat::getValue))
                .map(s -> new TrendView.Row(s.getValue(), labels.getOrDefault(s.getValue(), s.getValue()),
                        s.getMentionCount(), s.getPrevWeekCount(), s.getPrevWeekRate(), s.getMonthAvg(), s.getMonthRate(),
                        "Y".equals(s.getSurgeYn()), "Y".equals(s.getNewYn())))
                .toList();

        return new TrendView(base, weekList, rows, series(stats, weekList, labels),
                (int) rows.stream().filter(TrendView.Row::surge).count(),
                (int) rows.stream().filter(TrendView.Row::isNew).count(),
                statRepository.findLastAggregatedAt());
    }

    /** 기간 안 언급량 합계 상위 값의 주별 언급량. */
    private static List<TrendView.Series> series(List<TrendWeeklyStat> stats, List<LocalDate> weeks, Map<String, String> labels) {
        Map<String, Map<LocalDate, Integer>> byValue = new HashMap<>();
        for (TrendWeeklyStat s : stats) {
            byValue.computeIfAbsent(s.getValue(), v -> new HashMap<>()).put(s.getWeekStart(), s.getMentionCount());
        }
        return byValue.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Map<LocalDate, Integer>> e) -> total(e.getValue())).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(SERIES_SIZE)
                .map(e -> new TrendView.Series(e.getKey(), labels.getOrDefault(e.getKey(), e.getKey()),
                        weeks.stream().map(w -> e.getValue().getOrDefault(w, 0)).toList()))
                .toList();
    }

    private static int total(Map<LocalDate, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** 값 하나가 나온 원문(분석 완료) — 집계 날짜가 기간 안이고 카테고리가 맞는 것, 최신순. */
    public List<TrendEvidenceView> getEvidence(String valueType, String value, String category, LocalDate from, LocalDate to) {
        checkValueType(valueType);
        if (value == null || value.isBlank() || from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("값과 기간을 지정하세요.");
        }
        String target = TrendAggregationService.normalize(valueType, value);
        String scope = scope(category);
        // 카테고리 언급은 아이템에 있으므로 전부 보고, 나머지는 그 값 종류가 있는 항목만 읽는다
        List<TrendItem> candidates = TrendWeeklyStat.CATEGORY.equals(valueType)
                ? trendItemRepository.findAll() : trendItemRepository.findHavingValueType(valueType);
        Map<Long, TrendItem> byDocument = candidates.stream()
                .filter(item -> mentions(item, valueType, target, scope))
                .collect(Collectors.toMap(TrendItem::getDocumentId, Function.identity()));

        return sourceDocumentRepository.findAllById(byDocument.keySet()).stream()
                .filter(doc -> SourceDocument.DONE.equals(doc.getStatus()))
                .filter(doc -> {
                    LocalDate date = TrendAggregationService.statDate(doc);
                    return !date.isBefore(from) && !date.isAfter(to);
                })
                .sorted(Comparator.comparing(TrendAggregationService::statDate).reversed().thenComparing(SourceDocument::getDocId))
                .map(doc -> new TrendEvidenceView(doc.getDocId(), doc.getDocType(), doc.getTitle(),
                        byDocument.get(doc.getId()).getSummary(), doc.getMedia(), TrendAggregationService.statDate(doc), doc.getUrl()))
                .toList();
    }

    /** 집계(TrendAggregationService.mentions)와 같은 기준 — 속성은 그 아이템의 카테고리로, 키워드는 문서의 아이템 카테고리로 거른다. */
    private static boolean mentions(TrendItem item, String valueType, String target, String scope) {
        boolean anyScope = TrendWeeklyStat.ALL.equals(scope);
        if (TrendItemValue.KEYWORD.equals(valueType) || TrendItemValue.SEASON.equals(valueType)) { // 문서 단위 값
            return item.valuesOf(valueType).stream()
                    .anyMatch(v -> TrendAggregationService.normalize(valueType, v).equals(target))
                    && (anyScope || item.garmentCategories().contains(scope));
        }
        return item.garments().stream()
                .filter(g -> anyScope || scope.equals(g.category()))
                .anyMatch(g -> garmentValues(g, valueType).stream()
                        .anyMatch(v -> TrendAggregationService.normalize(valueType, v).equals(target)));
    }

    private static List<String> garmentValues(TrendItem.Garment g, String valueType) {
        return switch (valueType) {
            case TrendWeeklyStat.CATEGORY -> g.category() == null ? List.of() : List.of(g.category());
            case TrendItemValue.ITEM_TYPE -> g.type() == null ? List.of() : List.of(g.type());
            case TrendItemValue.MATERIAL -> g.materials();
            case TrendItemValue.COLOR -> g.colors();
            case TrendItemValue.SILHOUETTE -> g.silhouettes();
            default -> List.of();
        };
    }

    private static String scope(String category) {
        return category == null || category.isBlank() ? TrendWeeklyStat.ALL : category.trim();
    }

    private static void checkValueType(String valueType) {
        if (valueType == null || !VALUE_TYPES.contains(valueType)) {
            throw new IllegalArgumentException("유형은 " + VALUE_TYPES + " 중 하나여야 합니다.");
        }
    }

    /** 코드값 → 이름. 사용 안 함으로 바꾼 코드도 과거 집계에 남아 있으므로 전부 읽는다. 키워드는 이름이 곧 값이다. */
    private Map<String, String> labels(String valueType) {
        String group = TrendWeeklyStat.CATEGORY.equals(valueType) ? TrendCodes.CATEGORY : TrendCodes.VALUE_TYPE_GROUPS.get(valueType);
        if (group == null) {
            return Map.of();
        }
        Map<String, String> labels = new LinkedHashMap<>();
        for (ComCd code : comCdRepository.findByCodeGroupOrderBySortOrderAsc(group)) {
            labels.put(code.getCodeValue(), code.getCodeName());
        }
        return labels;
    }
}
