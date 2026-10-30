package com.sjinc.trendanalysis.service.trend;

import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.domain.TrendItem;
import com.sjinc.trendanalysis.domain.TrendItemValue;
import com.sjinc.trendanalysis.domain.TrendWeekClose;
import com.sjinc.trendanalysis.domain.TrendWeekCloseDoc;
import com.sjinc.trendanalysis.domain.TrendWeekCloseLog;
import com.sjinc.trendanalysis.domain.TrendWeeklyStat;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.repository.TrendItemRepository;
import com.sjinc.trendanalysis.repository.TrendWeekCloseDocRepository;
import com.sjinc.trendanalysis.repository.TrendWeekCloseLogRepository;
import com.sjinc.trendanalysis.repository.TrendWeekCloseRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * P-05 트렌드 집계 배치와 주 마감. 분석이 끝난(DONE) 문서의 트렌드 항목을 주 단위로 세어 trend_weekly_stats를 만든다.
 * 매일 한 번(trend.aggregate.cron, 기본 새벽 3시)과 기동 직후, 그리고 화면의 "집계" 버튼으로 돈다.
 *
 * <p>주 마감(2026-10-30 사용자 요청 — ERP 마감처럼): 마감된 주는 다시 계산하지 않는다. 집계는 <b>열린 주</b>(마지막 마감 주 다음 주부터)만
 * 지우고 다시 만들며, 열린 주의 전주·4주 평균은 마감된 주의 확정 언급량을 쓴다. 마감할 때 그 주에 센 원문을 TrendWeekCloseDoc에 고정한다.
 * <ul>
 *   <li>마감은 오래된 주부터 차례로 — "X주까지 마감"이면 그 앞의 열린 주도 같이 마감한다. 이번 주(진행 중)는 마감할 수 없다.</li>
 *   <li>자동 마감: 매주 월요일 새벽(trend.close.cron) 지난주까지. 앱이 그때 꺼져 있었으면 기동할 때 따라잡는다(trend.close.auto).</li>
 *   <li>마감 취소: 가장 최근 마감 주부터 하나씩, 사유를 남긴다. 취소한 주는 다시 열린 주가 되어 원문에서 다시 센다.</li>
 *   <li>늦게 들어온 원문(게시일이 마감된 주): 첫 열린 주에 센다(ERP 당기 처리). 게시일은 그대로 두고 집계 주만 옮긴다.</li>
 *   <li>마감된 주의 원문을 재분석·삭제해도 그 주 수치는 바뀌지 않는다(마감 당시 그대로).</li>
 * </ul>
 */
@Service
@Slf4j
public class TrendAggregationService {

    public record Result(int documents, int rows, int surges, LocalDateTime aggregatedAt, long elapsedMs) {
    }

    /** 원문의 집계 주 — 마감 때 고정한 원문은 그 주, 나머지는 집계 날짜의 주와 첫 열린 주 중 늦은 쪽. */
    public record WeekAssignment(LocalDate openFrom, Map<Long, LocalDate> closedDocs) {
        public LocalDate weekOf(SourceDocument doc) {
            LocalDate closed = closedDocs.get(doc.getId());
            if (closed != null) {
                return closed;
            }
            LocalDate own = TrendAggregator.weekStart(statDate(doc));
            return openFrom != null && own.isBefore(openFrom) ? openFrom : own;
        }

        public boolean isClosed(LocalDate weekStart) {
            return openFrom != null && weekStart.isBefore(openFrom);
        }
    }

    private final SourceDocumentRepository sourceDocumentRepository;
    private final TrendItemRepository trendItemRepository;
    private final TrendWeeklyStatRepository statRepository;
    private final TrendWeekCloseRepository closeRepository;
    private final TrendWeekCloseDocRepository closeDocRepository;
    private final TrendWeekCloseLogRepository closeLogRepository;
    private final TransactionTemplate transactionTemplate;
    private final TrendAggregator.SurgeRule surgeRule;
    private final boolean autoClose;
    /** 배치·버튼·마감이 겹쳐 돌면 지우고 넣는 사이에 서로 섞인다 — 한 번에 하나만. */
    private final ReentrantLock lock = new ReentrantLock();

    public TrendAggregationService(SourceDocumentRepository sourceDocumentRepository, TrendItemRepository trendItemRepository,
                                   TrendWeeklyStatRepository statRepository, TrendWeekCloseRepository closeRepository,
                                   TrendWeekCloseDocRepository closeDocRepository, TrendWeekCloseLogRepository closeLogRepository,
                                   TransactionTemplate transactionTemplate,
                                   @Value("${trend.surge.rate:20}") double surgeRate,
                                   @Value("${trend.surge.min-count:1}") int surgeMinCount,
                                   @Value("${trend.close.auto:true}") boolean autoClose) {
        this.sourceDocumentRepository = sourceDocumentRepository;
        this.trendItemRepository = trendItemRepository;
        this.statRepository = statRepository;
        this.closeRepository = closeRepository;
        this.closeDocRepository = closeDocRepository;
        this.closeLogRepository = closeLogRepository;
        this.transactionTemplate = transactionTemplate;
        this.surgeRule = new TrendAggregator.SurgeRule(surgeRate, Math.max(1, surgeMinCount));
        this.autoClose = autoClose;
    }

    @Scheduled(cron = "${trend.aggregate.cron:0 0 3 * * *}")
    public void scheduled() {
        aggregate();
    }

    /** 매주 월요일 새벽 지난주까지 자동 마감(일 집계 뒤). */
    @Scheduled(cron = "${trend.close.cron:0 30 3 * * MON}")
    public void scheduledClose() {
        if (autoClose) {
            closeThroughLastWeek();
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        aggregate();
        if (autoClose) {
            closeThroughLastWeek(); // 월요일 새벽에 꺼져 있었으면 따라잡는다
        }
    }

    /** 지난주까지 아직 안 닫힌 주를 SYSTEM으로 마감한다. 닫을 주가 없으면 아무것도 하지 않는다. */
    void closeThroughLastWeek() {
        LocalDate lastWeek = TrendAggregator.weekStart(LocalDate.now()).minusWeeks(1);
        LocalDate lastClosed = closeRepository.findLastWeekStart();
        if (lastClosed == null || lastClosed.isBefore(lastWeek)) {
            close(lastWeek, TrendWeekClose.SYSTEM);
        }
    }

    /** 원문 → 집계 주 계산에 쓰는 마감 현황(근거 원문 조회도 같은 기준을 쓴다). */
    public WeekAssignment assignment() {
        LocalDate lastClosed = closeRepository.findLastWeekStart();
        Map<Long, LocalDate> closedDocs = new HashMap<>();
        for (TrendWeekCloseDoc d : closeDocRepository.findAll()) {
            closedDocs.put(d.getDocumentId(), d.getWeekStart());
        }
        return new WeekAssignment(lastClosed == null ? null : lastClosed.plusWeeks(1), closedDocs);
    }

    /** 열린 주만 다시 집계한다. 마감된 주의 행은 그대로 둔다. */
    public Result aggregate() {
        lock.lock();
        try {
            long started = System.currentTimeMillis();
            WeekAssignment assignment = assignment();
            List<SourceDocument> docs = openDocuments(assignment);
            List<TrendAggregator.Mention> mentions = mentions(docs, assignment::weekOf);
            LocalDate openFrom = assignment.openFrom();
            List<TrendAggregator.WeekCount> closed = openFrom == null ? List.of()
                    : statRepository.findByWeekStartBetween(openFrom.minusWeeks(4), openFrom.minusWeeks(1)).stream()
                    .map(s -> new TrendAggregator.WeekCount(s.getWeekStart(), s.getCategory(), s.getValueType(), s.getValue(), s.getMentionCount()))
                    .toList();
            LocalDateTime now = LocalDateTime.now();
            List<TrendAggregator.Row> rows = TrendAggregator.aggregate(mentions, closed, openFrom, now.toLocalDate(), surgeRule);
            List<TrendWeeklyStat> stats = rows.stream().map(row -> toEntity(row, now)).toList();
            transactionTemplate.executeWithoutResult(status -> {
                if (openFrom == null) {
                    statRepository.deleteAllInBatch();
                } else {
                    statRepository.deleteFromWeek(openFrom);
                }
                statRepository.saveAll(stats);
            });
            int surges = (int) rows.stream().filter(TrendAggregator.Row::surge).count();
            Result result = new Result(docs.size(), stats.size(), surges, now, System.currentTimeMillis() - started);
            log.info("트렌드 집계 완료: 열린 주({}~) 문서 {}건 → 집계 {}행(급상승 {}행, 기준 전주 대비 {}% 이상·최소 {}건), {}ms",
                    openFrom == null ? "전체" : openFrom, result.documents(), result.rows(), surges, surgeRule.minRate(),
                    surgeRule.minCount(), result.elapsedMs());
            return result;
        } finally {
            lock.unlock();
        }
    }

    /** 마감 때 고정되지 않은 분석 완료 원문 — 열린 주에 센다. */
    private List<SourceDocument> openDocuments(WeekAssignment assignment) {
        return sourceDocumentRepository.findByStatusIn(List.of(SourceDocument.DONE)).stream()
                .filter(doc -> !assignment.closedDocs().containsKey(doc.getId()))
                .toList();
    }

    /**
     * weekStart(그 주의 아무 날)까지 마감한다 — 그 앞의 열린 주도 오래된 주부터 같이. 마감 전에 열린 주를 한 번 더 집계해 최신 원문까지 넣는다.
     *
     * @return 이번에 마감한 주(오래된 순)
     */
    public List<TrendWeekClose> close(LocalDate weekStart, String actor) {
        LocalDate target = TrendAggregator.weekStart(weekStart);
        if (!target.isBefore(TrendAggregator.weekStart(LocalDate.now()))) {
            throw new IllegalArgumentException("이번 주(진행 중)와 이후 주는 마감할 수 없습니다.");
        }
        lock.lock();
        try {
            LocalDate lastClosed = closeRepository.findLastWeekStart();
            if (lastClosed != null && !target.isAfter(lastClosed)) {
                throw new IllegalArgumentException(target + " 주는 이미 마감됐습니다(마지막 마감 주 " + lastClosed + ").");
            }
            aggregate();
            WeekAssignment assignment = assignment();
            // 열린 원문을 집계 주별로 — 분석 결과가 없는 원문은 세지 않았으므로 고정하지 않는다
            List<SourceDocument> docs = openDocuments(assignment);
            Map<Long, TrendItem> items = trendItemRepository.findByDocumentIdIn(docs.stream().map(SourceDocument::getId).toList())
                    .stream().collect(Collectors.toMap(TrendItem::getDocumentId, Function.identity()));
            Map<LocalDate, List<Long>> docsByWeek = docs.stream().filter(d -> items.containsKey(d.getId()))
                    .collect(Collectors.groupingBy(assignment::weekOf, Collectors.mapping(SourceDocument::getId, Collectors.toList())));
            LocalDate first = assignment.openFrom() != null ? assignment.openFrom()
                    : docsByWeek.keySet().stream().min(LocalDate::compareTo).filter(w -> w.isBefore(target)).orElse(target);
            LocalDateTime now = LocalDateTime.now();
            List<TrendWeekClose> closed = new ArrayList<>();
            transactionTemplate.executeWithoutResult(status -> {
                for (LocalDate week = first; !week.isAfter(target); week = week.plusWeeks(1)) {
                    LocalDate w = week;
                    List<Long> ids = docsByWeek.getOrDefault(w, List.of());
                    closeDocRepository.saveAll(ids.stream().map(id -> TrendWeekCloseDoc.builder().weekStart(w).documentId(id).build()).toList());
                    closed.add(closeRepository.save(TrendWeekClose.builder().weekStart(w).docCount(ids.size())
                            .rowCount(statRepository.countByWeekStart(w)).closedAt(now).closedBy(actor).build()));
                    closeLogRepository.save(TrendWeekCloseLog.builder().weekStart(w).action(TrendWeekCloseLog.CLOSE)
                            .actor(actor).actedAt(now).build());
                }
            });
            log.info("트렌드 주 마감: {}~{} {}주(마감한 사람 {})", first, target, closed.size(), actor);
            return closed;
        } finally {
            lock.unlock();
        }
    }

    /** 가장 최근 마감 주의 마감을 취소하고(사유 필수) 그 주부터 다시 집계한다. */
    public LocalDate cancelLastClose(String actor, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("마감 취소 사유를 입력하세요.");
        }
        lock.lock();
        try {
            TrendWeekClose last = closeRepository.findTopByOrderByWeekStartDesc()
                    .orElseThrow(() -> new IllegalArgumentException("마감된 주가 없습니다."));
            LocalDate week = last.getWeekStart();
            transactionTemplate.executeWithoutResult(status -> {
                closeDocRepository.deleteByWeekStart(week);
                closeRepository.delete(last);
                closeLogRepository.save(TrendWeekCloseLog.builder().weekStart(week).action(TrendWeekCloseLog.CANCEL)
                        .actor(actor).reason(reason.strip()).actedAt(LocalDateTime.now()).build());
            });
            log.info("트렌드 주 마감 취소: {} (취소한 사람 {}, 사유 {})", week, actor, reason.strip());
            aggregate();
            return week;
        } finally {
            lock.unlock();
        }
    }

    private List<TrendAggregator.Mention> mentions(List<SourceDocument> docs, Function<SourceDocument, LocalDate> weekOf) {
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
            LocalDate date = weekOf.apply(doc); // 집계 주(늦게 온 원문은 첫 열린 주)
            long id = doc.getId();
            // 소재·컬러·실루엣은 그 의류 아이템의 카테고리로 센다 — 코디 사진의 "그레이 니트"가 하의로 잡히지 않게.
            // 아이템명은 세지 않는다(화면 유형에서 뺐다 — 종류와 뜻이 겹친다, 2026-10-23)
            for (TrendItem.Garment g : item.garments()) {
                mentions.add(new TrendAggregator.Mention(id, date, g.category(), TrendWeeklyStat.CATEGORY, g.category()));
                if (g.type() != null) {
                    add(mentions, id, date, g.category(), TrendItemValue.ITEM_TYPE, g.type());
                }
                g.materials().forEach(v -> add(mentions, id, date, g.category(), TrendItemValue.MATERIAL, v));
                g.colors().forEach(v -> add(mentions, id, date, g.category(), TrendItemValue.COLOR, v));
                g.silhouettes().forEach(v -> add(mentions, id, date, g.category(), TrendItemValue.SILHOUETTE, v));
                g.patterns().forEach(v -> add(mentions, id, date, g.category(), TrendItemValue.PATTERN, v));
                g.details().forEach(v -> add(mentions, id, date, g.category(), TrendItemValue.DETAIL, v));
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
        return TrendItemValue.KEYWORD.equals(valueType) ? trimmed.replaceAll("\\s+", " ") : trimmed;
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
