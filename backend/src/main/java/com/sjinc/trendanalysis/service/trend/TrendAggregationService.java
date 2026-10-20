package com.sjinc.trendanalysis.service.trend;

import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.domain.TrendItem;
import com.sjinc.trendanalysis.domain.TrendItemValue;
import com.sjinc.trendanalysis.domain.TrendWeeklyStat;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.repository.TrendItemRepository;
import com.sjinc.trendanalysis.repository.TrendWeeklyStatRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * P-05 트렌드 집계 배치. 분석이 끝난(DONE) 문서의 트렌드 항목을 주 단위로 세어 trend_weekly_stats를 통째로 다시 만든다.
 * 매일 한 번(trend.aggregate.cron, 기본 새벽 3시)과 기동 직후, 그리고 화면의 "집계" 버튼으로 돈다.
 * 통째로 다시 만드는 이유: 재분석·삭제로 과거 주의 값이 바뀌어도 따로 추적하지 않고 항상 원본과 맞추기 위해서다.
 */
@Service
@Slf4j
public class TrendAggregationService {

    public record Result(int documents, int rows, int surges, LocalDateTime aggregatedAt, long elapsedMs) {
    }

    private final SourceDocumentRepository sourceDocumentRepository;
    private final TrendItemRepository trendItemRepository;
    private final TrendWeeklyStatRepository statRepository;
    private final TransactionTemplate transactionTemplate;
    private final TrendAggregator.SurgeRule surgeRule;
    /** 배치·버튼이 겹쳐 돌면 지우고 넣는 사이에 서로 섞인다 — 한 번에 하나만. */
    private final ReentrantLock lock = new ReentrantLock();

    public TrendAggregationService(SourceDocumentRepository sourceDocumentRepository, TrendItemRepository trendItemRepository,
                                   TrendWeeklyStatRepository statRepository, TransactionTemplate transactionTemplate,
                                   @Value("${trend.surge.rate:20}") double surgeRate,
                                   @Value("${trend.surge.min-count:1}") int surgeMinCount) {
        this.sourceDocumentRepository = sourceDocumentRepository;
        this.trendItemRepository = trendItemRepository;
        this.statRepository = statRepository;
        this.transactionTemplate = transactionTemplate;
        this.surgeRule = new TrendAggregator.SurgeRule(surgeRate, Math.max(1, surgeMinCount));
    }

    @Scheduled(cron = "${trend.aggregate.cron:0 0 3 * * *}")
    public void scheduled() {
        aggregate();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        aggregate();
    }

    public Result aggregate() {
        lock.lock();
        try {
            long started = System.currentTimeMillis();
            List<SourceDocument> docs = sourceDocumentRepository.findByStatusIn(List.of(SourceDocument.DONE));
            List<TrendAggregator.Mention> mentions = mentions(docs);
            LocalDateTime now = LocalDateTime.now();
            List<TrendAggregator.Row> rows = TrendAggregator.aggregate(mentions, now.toLocalDate(), surgeRule);
            List<TrendWeeklyStat> stats = rows.stream().map(row -> toEntity(row, now)).toList();
            transactionTemplate.executeWithoutResult(status -> {
                statRepository.deleteAllInBatch();
                statRepository.saveAll(stats);
            });
            int surges = (int) rows.stream().filter(TrendAggregator.Row::surge).count();
            Result result = new Result(docs.size(), stats.size(), surges, now, System.currentTimeMillis() - started);
            log.info("트렌드 집계 완료: 문서 {}건 → 집계 {}행(급상승 {}행, 기준 전주 대비 {}% 이상·최소 {}건), {}ms",
                    result.documents(), result.rows(), surges, surgeRule.minRate(), surgeRule.minCount(), result.elapsedMs());
            return result;
        } finally {
            lock.unlock();
        }
    }

    private List<TrendAggregator.Mention> mentions(List<SourceDocument> docs) {
        if (docs.isEmpty()) {
            return List.of();
        }
        Map<Long, TrendItem> items = trendItemRepository.findByDocumentIdIn(docs.stream().map(SourceDocument::getId).toList())
                .stream().collect(Collectors.toMap(TrendItem::getDocumentId, Function.identity()));
        List<TrendAggregator.Mention> mentions = new ArrayList<>();
        for (SourceDocument doc : docs) {
            TrendItem item = items.get(doc.getId());
            if (item == null) {
                continue;
            }
            LocalDate date = statDate(doc);
            long id = doc.getId();
            // 소재·컬러·실루엣·아이템명은 그 의류 아이템의 카테고리로 센다 — 코디 사진의 "그레이 니트"가 하의로 잡히지 않게
            for (TrendItem.Garment g : item.garments()) {
                mentions.add(new TrendAggregator.Mention(id, date, g.category(), TrendWeeklyStat.CATEGORY, g.category()));
                add(mentions, id, date, g.category(), TrendItemValue.ITEM, g.name());
                g.materials().forEach(v -> add(mentions, id, date, g.category(), TrendItemValue.MATERIAL, v));
                g.colors().forEach(v -> add(mentions, id, date, g.category(), TrendItemValue.COLOR, v));
                g.silhouettes().forEach(v -> add(mentions, id, date, g.category(), TrendItemValue.SILHOUETTE, v));
            }
            // 키워드·시즌은 문서 단위라 문서에 나온 아이템 카테고리마다 센다(전체 범위에서는 문서당 1건)
            for (String docLevelType : List.of(TrendItemValue.KEYWORD, TrendItemValue.SEASON)) {
                for (String value : item.valuesOf(docLevelType)) {
                    for (String category : item.garmentCategories()) {
                        add(mentions, id, date, category, docLevelType, value);
                    }
                }
            }
        }
        return mentions;
    }

    private static void add(List<TrendAggregator.Mention> mentions, long documentId, LocalDate date, String category,
                            String valueType, String value) {
        String normalized = normalize(valueType, value);
        if (!normalized.isEmpty()) {
            mentions.add(new TrendAggregator.Mention(documentId, date, category, valueType, normalized));
        }
    }

    /** 집계 날짜: 원문 게시일, 없으면 업로드(등록)일. */
    public static LocalDate statDate(SourceDocument doc) {
        return doc.getPublishedAt() != null ? doc.getPublishedAt() : doc.getRegisteredAt().toLocalDate();
    }

    /** 키워드·아이템명은 LLM이 쓴 문자열이라 공백 차이("레더  재킷")로 갈라지지 않게 맞춘다. 코드값은 그대로. */
    public static String normalize(String valueType, String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip();
        boolean freeText = TrendItemValue.KEYWORD.equals(valueType) || TrendItemValue.ITEM.equals(valueType);
        return freeText ? trimmed.replaceAll("\\s+", " ") : trimmed;
    }

    private static TrendWeeklyStat toEntity(TrendAggregator.Row row, LocalDateTime now) {
        return TrendWeeklyStat.builder()
                .weekStart(row.weekStart())
                .category(row.category())
                .valueType(row.valueType())
                .value(row.value())
                .mentionCount(row.count())
                .prevWeekCount(row.prevCount())
                .prevWeekRate(row.prevRate())
                .monthAvg(row.monthAvg())
                .monthRate(row.monthRate())
                .surgeYn(row.surge() ? "Y" : "N")
                .newYn(row.isNew() ? "Y" : "N")
                .aggregatedAt(now)
                .build();
    }
}
