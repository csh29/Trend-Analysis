package com.sjinc.trendanalysis.service.analysis;

import com.sjinc.trendanalysis.config.AnalysisAsyncConfig;
import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 기동 때 색인(Chroma)과 DB를 양쪽으로 맞춘다(2026-10-23 도입, 2026-10-26 양방향·안전장치).
 *
 * <ul>
 *   <li>지우기: DB에 없는 문서의 색인을 지운다 — 원문을 지울 때 AI 서비스가 꺼져 있으면 색인만 남아 검색에 걸렸다(지워진 이미지 25건).</li>
 *   <li>채우기: DB에는 분석 완료인데 색인에 없는 문서를 저장된 분석 결과로 다시 색인한다(Claude 호출 없음, 임베딩만).</li>
 * </ul>
 *
 * <p>안전장치 — 2026-10-26 사고: 메모리 DB로 뜬 테스트가 실제 AI 서비스 색인을 조회해 "DB에 없다"며 56건을 모두 지웠다.
 * 그래서 (1) DB에 문서가 하나도 없거나 지울 대상이 색인의 절반을 넘으면 지우지 않고 경고만 남기고, (2) index.sync.enabled=false면
 * 돌지 않는다(테스트 설정 src/test/resources/config/application.properties). 임베딩이 오래 걸려 기동을 막지 않게 분석 스레드에서 돈다.
 */
@Service
@Slf4j
public class IndexSyncService {

    /** 지울 대상이 색인 문서의 이 비율을 넘으면 지우지 않는다 — DB를 잘못 보고 있을 가능성이 크다. */
    static final double MAX_STALE_RATIO = 0.5;

    private final AiClient aiClient;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final AnalysisService analysisService;
    private final boolean enabled;

    public IndexSyncService(AiClient aiClient, SourceDocumentRepository sourceDocumentRepository, AnalysisService analysisService,
                            @Value("${index.sync.enabled:true}") boolean enabled) {
        this.aiClient = aiClient;
        this.sourceDocumentRepository = sourceDocumentRepository;
        this.analysisService = analysisService;
        this.enabled = enabled;
    }

    /** deleted: 지운 문서, deleteSkipped: 안전장치로 지우지 않은 문서, reindexed: 다시 색인한 문서, failed: 다시 색인하지 못한 문서. */
    public record Result(List<String> deleted, List<String> deleteSkipped, List<String> reindexed, List<String> failed) {
    }

    @Async(AnalysisAsyncConfig.EXECUTOR)
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) {
            log.info("색인 정리를 끈 설정입니다(index.sync.enabled=false).");
            return;
        }
        try {
            sync();
        } catch (RuntimeException e) {
            log.warn("색인 정리를 건너뜁니다(AI 서비스에 연결하지 못했습니다): {}", e.getMessage());
        }
    }

    public Result sync() {
        List<SourceDocument> docs = sourceDocumentRepository.findAll();
        Set<String> known = docs.stream().map(SourceDocument::getDocId).collect(Collectors.toSet());
        List<String> indexed = aiClient.indexedDocIds();
        Set<String> indexedSet = new HashSet<>(indexed);

        List<String> stale = indexed.stream().filter(id -> !known.contains(id)).toList();
        List<String> deleted = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        if (!stale.isEmpty()) {
            if (known.isEmpty() || stale.size() > indexed.size() * MAX_STALE_RATIO) {
                skipped.addAll(stale);
                log.warn("DB에 없는 문서의 색인 {}건(색인 {}건 중)을 지우지 않았습니다 — DB가 비었거나 지울 대상이 너무 많습니다. "
                        + "DB 연결을 확인하고, 맞으면 AI 서비스 DELETE /index/{{doc_id}}로 직접 지우세요: {}", stale.size(), indexed.size(), stale);
            } else {
                for (String docId : stale) {
                    aiClient.deleteIndex(docId);
                    deleted.add(docId);
                }
                log.info("DB에 없는 문서의 색인 {}건을 지웠습니다: {}", deleted.size(), deleted);
            }
        }

        List<SourceDocument> missing = docs.stream()
                .filter(d -> SourceDocument.DONE.equals(d.getStatus()) && !indexedSet.contains(d.getDocId())).toList();
        List<String> reindexed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        if (!missing.isEmpty()) {
            log.info("색인이 빠진 분석 완료 문서 {}건을 저장된 결과로 다시 색인합니다(Claude 호출 없음).", missing.size());
            for (SourceDocument doc : missing) {
                try {
                    (analysisService.reindex(doc) ? reindexed : failed).add(doc.getDocId());
                } catch (RuntimeException e) { // 한 건이 실패해도 나머지는 한다
                    failed.add(doc.getDocId());
                    log.warn("다시 색인 실패 doc={}: {}", doc.getDocId(), e.getMessage());
                }
                if ((reindexed.size() + failed.size()) % 10 == 0) {
                    log.info("다시 색인 {}/{}건", reindexed.size() + failed.size(), missing.size());
                }
            }
            log.info("다시 색인 끝: 성공 {}건, 실패 {}건 {}", reindexed.size(), failed.size(), failed);
        }
        return new Result(deleted, skipped, reindexed, failed);
    }
}
