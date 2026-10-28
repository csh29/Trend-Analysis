package com.sjinc.trendanalysis.service.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjinc.trendanalysis.config.AnalysisAsyncConfig;
import com.sjinc.trendanalysis.domain.ComCd;
import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.domain.TrendItem;
import com.sjinc.trendanalysis.domain.TrendItemValue;
import com.sjinc.trendanalysis.repository.ComCdRepository;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.repository.TrendItemRepository;
import com.sjinc.trendanalysis.service.image.ImageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P-03 AI 정형화 + P-04 저장·색인. 원문 문서 한 건을 AI 서비스로 정형화하고, 검증(TrendResultValidator)을 통과하면
 * 트렌드 항목(RDB)에 저장한 뒤 Chroma에 색인한다. 형식 오류·일시 오류는 ai.analysis.max-attempts까지 다시 시도하고,
 * 그래도 안 되면 실패 사유를 문서에 남긴다(화면에서 재분석).
 *
 * <p>외부 호출(AI 서비스)은 트랜잭션 밖에서 하고, 트렌드 항목 저장만 짧은 트랜잭션으로 묶는다 — Claude 응답을
 * 기다리는 동안 DB 커넥션을 잡고 있지 않기 위해서다.
 */
@Service
@Slf4j
public class AnalysisService {

    private final SourceDocumentRepository sourceDocumentRepository;
    private final TrendItemRepository trendItemRepository;
    private final ComCdRepository comCdRepository;
    private final ImageService imageService;
    private final AiClient aiClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final String apiKey;
    private final String model;
    private final int maxAttempts;

    /** 기동 후 누적(분석 스레드 2개가 같이 더한다). 재기동하면 0부터 — 로그로만 본다. */
    private final AtomicLong totalInputTokens = new AtomicLong();
    private final AtomicLong totalOutputTokens = new AtomicLong();
    private final AtomicLong totalCacheTokens = new AtomicLong();
    private final AtomicLong claudeCalls = new AtomicLong();

    public AnalysisService(SourceDocumentRepository sourceDocumentRepository, TrendItemRepository trendItemRepository,
                           ComCdRepository comCdRepository, ImageService imageService, AiClient aiClient,
                           ObjectMapper objectMapper, TransactionTemplate transactionTemplate,
                           ApplicationEventPublisher eventPublisher,
                           @Value("${claude.api.key:}") String apiKey,
                           @Value("${claude.model:claude-sonnet-5}") String model,
                           @Value("${ai.analysis.max-attempts:3}") int maxAttempts) {
        this.sourceDocumentRepository = sourceDocumentRepository;
        this.trendItemRepository = trendItemRepository;
        this.comCdRepository = comCdRepository;
        this.imageService = imageService;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.eventPublisher = eventPublisher;
        this.apiKey = apiKey;
        this.model = model;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    /** 등록·재분석 이벤트를 받아 분석 스레드에서 한 건씩 처리한다. */
    @Async(AnalysisAsyncConfig.EXECUTOR)
    @EventListener
    public void onRequested(AnalysisRequestedEvent event) {
        log.info("분석 요청 {}건 처리 시작 (전체 대기 {}건 · 처리 중 {}건)", event.documentIds().size(),
                sourceDocumentRepository.countByStatus(SourceDocument.PENDING),
                sourceDocumentRepository.countByStatus(SourceDocument.PROCESSING));
        event.documentIds().forEach(this::analyzeSafely);
    }

    /**
     * 서버가 분석 도중 내려갔으면 대기·처리 중으로 남은 문서가 있다 — 기동이 끝나면 다시 맡긴다. 처리 중으로 남은 것은 대기로 돌린다
     * (분석은 대기 → 처리 중으로 바꾼 요청만 맡는다). 기동 때 막 등록된 문서(샘플 로더 등)가 같이 실려도 두 번 분석되지 않는다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void requeueUnfinished() {
        sourceDocumentRepository.changeAllStatus(SourceDocument.PROCESSING, SourceDocument.PENDING);
        List<Long> ids = sourceDocumentRepository.findByStatusIn(List.of(SourceDocument.PENDING))
                .stream().map(SourceDocument::getId).toList();
        if (!ids.isEmpty()) {
            log.info("끝나지 않은 분석 {}건을 다시 대기열에 넣습니다.", ids.size());
            eventPublisher.publishEvent(new AnalysisRequestedEvent(ids));
        }
    }

    private void analyzeSafely(Long documentId) {
        try {
            analyze(documentId);
        } catch (RuntimeException e) {
            // 한 건의 예기치 못한 오류가 대기열의 나머지를 막지 않게 한다. 사유는 문서에 남긴다.
            log.error("분석 중 오류 document={}", documentId, e);
            sourceDocumentRepository.findById(documentId).ifPresent(doc -> {
                doc.markFailed("분석 중 오류: " + e.getMessage());
                sourceDocumentRepository.save(doc);
            });
        }
    }

    /**
     * 문서 한 건 분석(동기). 테스트는 이 메서드를 직접 부른다. 대기(PENDING)를 처리 중으로 바꾼 요청만 분석한다 — 같은 문서가 두 번
     * 대기열에 실려도(2026-10-23 샘플 37건이 등록 이벤트와 기동 재대기에 함께 실려 끝난 문서를 다시 분석했다) 한 번만 Claude를 부른다.
     */
    public void analyze(Long documentId) {
        if (sourceDocumentRepository.changeStatus(documentId, SourceDocument.PENDING, SourceDocument.PROCESSING) == 0) {
            log.info("분석 건너뜀 document={} — 대기 상태가 아니다(이미 분석했거나 처리 중이거나 삭제됨)", documentId);
            return;
        }
        SourceDocument doc = sourceDocumentRepository.findById(documentId).orElse(null);
        if (doc == null) {
            return; // 대기 중에 삭제된 문서
        }
        log.info("분석 시작 doc={} type={} (남은 대기 {}건)", doc.getDocId(), doc.getDocType(),
                Math.max(0, sourceDocumentRepository.countByStatus(SourceDocument.PENDING) - 1));
        if (apiKey == null || apiKey.isBlank()) {
            fail(doc, "claude.api.key가 설정되지 않았습니다. application.properties에 키를 넣고 재분석하세요.");
            return;
        }
        if (doc.getMedia() == null || doc.getMedia().isBlank() || doc.getPublishedAt() == null) {
            fail(doc, "출처(매체명·게시일)가 없어 분석하지 않습니다.");
            return;
        }
        TrendCodeBook codes = TrendCodeBook.of(group -> comCdRepository.findByCodeGroupAndUseYnOrderBySortOrderAsc(group, "Y"));
        if (doc.isImage()) {
            // 사진으로 구분되는 소재만 허용 값으로 보낸다 — 요청(스키마 enum)과 검증이 같은 목록을 쓴다
            codes = codes.restrict(TrendCodes.MATERIAL, codes.imageMaterials());
        }
        if (!codes.emptyGroups().isEmpty()) {
            fail(doc, "사용중인 공통코드가 없는 그룹이 있습니다: " + String.join(", ", codes.emptyGroups()));
            return;
        }

        AiClient.StructureRequest request;
        try {
            request = buildRequest(doc, codes);
        } catch (RuntimeException e) {
            fail(doc, "원문을 읽지 못했습니다: " + e.getMessage());
            return;
        }

        TrendResultValidator.Validated validated = null;
        String lastError = null;
        TokenUsage docUsage = TokenUsage.ZERO; // 이 문서에 쓴 토큰(재시도 합계)
        for (int attempt = 1; attempt <= maxAttempts && validated == null; attempt++) {
            doc.markProcessing(attempt);
            doc = sourceDocumentRepository.save(doc);
            try {
                AiClient.StructureResponse response = aiClient.structure(request, apiKey);
                docUsage = docUsage.plus(recordUsage(doc, attempt, TokenUsage.of(response == null ? null : response.usage())));
                TrendResultValidator.Outcome outcome = TrendResultValidator.validate(
                        response == null ? null : response.result(), codes);
                if (outcome.ok()) {
                    validated = outcome.validated();
                } else {
                    lastError = "스키마 검증 실패: " + String.join(" / ", outcome.errors());
                    log.warn("정형 결과 검증 실패 doc={} attempt={}: {}", doc.getDocId(), attempt, lastError);
                }
            } catch (AiCallException e) {
                lastError = e.getMessage();
                docUsage = docUsage.plus(recordUsage(doc, attempt, e.getUsage()));
                log.warn("정형화 호출 실패 doc={} attempt={} retryable={}: {}", doc.getDocId(), attempt, e.isRetryable(), lastError);
                if (!e.isRetryable()) {
                    break;
                }
                backoff(attempt);
            }
        }
        if (validated == null) {
            fail(doc, lastError);
            log.info("분석 실패 doc={} 토큰 {} | {}", doc.getDocId(), docUsage, totalsText());
            return;
        }

        saveTrendItem(doc, validated, codes);
        try {
            aiClient.index(new AiClient.IndexRequest(doc.getDocId(), doc.getDocType(), doc.getBody(),
                    structuredMap(validated), codes.allNames(), sourceMeta(doc)));
        } catch (AiCallException e) {
            // 트렌드 항목은 남기되 색인이 없으면 RAG 근거로 쓰이지 않으므로 실패로 표시해 재분석하게 한다
            fail(doc, "색인(P-04) 실패: " + e.getMessage());
            return;
        }
        doc.markDone();
        sourceDocumentRepository.save(doc);
        log.info("분석 완료 doc={} 아이템={} keywords={} 토큰 {} | {}", doc.getDocId(),
                validated.items().stream().map(g -> g.category() + ":" + g.item()).toList(),
                validated.keywords(), docUsage, totalsText());
    }

    /** Claude 호출 한 번의 토큰을 누적하고 로그에 남긴다. 응답 전에 실패한 호출(0)은 세지 않는다. */
    private TokenUsage recordUsage(SourceDocument doc, int attempt, TokenUsage usage) {
        if (usage.total() == 0) {
            return usage;
        }
        claudeCalls.incrementAndGet();
        totalInputTokens.addAndGet(usage.input());
        totalOutputTokens.addAndGet(usage.output());
        totalCacheTokens.addAndGet(usage.cacheCreation() + usage.cacheRead());
        log.info("Claude 토큰 doc={} attempt={} {}", doc.getDocId(), attempt, usage);
        return usage;
    }

    private String totalsText() {
        return "기동 후 누적 호출 " + claudeCalls.get() + "회 in=" + totalInputTokens.get() + " out=" + totalOutputTokens.get()
                + " 캐시=" + totalCacheTokens.get();
    }

    private AiClient.StructureRequest buildRequest(SourceDocument doc, TrendCodeBook codes) {
        AiClient.AllowedCodes allowed = new AiClient.AllowedCodes(
                options(codes.codes(TrendCodes.CATEGORY)), options(codes.codes(TrendCodes.ITEM_TYPE)),
                options(codes.codes(TrendCodes.MATERIAL)),
                options(codes.codes(TrendCodes.COLOR)), options(codes.codes(TrendCodes.SILHOUETTE)),
                options(codes.codes(TrendCodes.SEASON)), options(codes.codes(TrendCodes.GENDER)));
        // 원문 1건 = 본문 + 이미지 — 텍스트 원문에 첨부한 이미지도 한 요청에 같이 보낸다
        List<AiClient.ImageInput> images = imageService.readDocumentImages(doc).stream()
                .map(image -> new AiClient.ImageInput(Base64.getEncoder().encodeToString(image.bytes()), image.contentType()))
                .toList();
        if (doc.isImage() && images.isEmpty()) {
            throw new IllegalStateException("이미지 파일이 없습니다.");
        }
        return new AiClient.StructureRequest(doc.getDocId(), doc.getDocType(), model, doc.getTitle(),
                doc.isImage() ? null : doc.getBody(), images, allowed);
    }

    private static List<AiClient.CodeOption> options(List<ComCd> codes) {
        return codes.stream().map(code -> new AiClient.CodeOption(code.getCodeValue(), code.getCodeName())).toList();
    }

    /** 트렌드 항목을 갈아끼운다(재분석이면 이전 결과를 지운다). */
    private void saveTrendItem(SourceDocument doc, TrendResultValidator.Validated v, TrendCodeBook codes) {
        String resultJson = toResultJson(doc, v);
        transactionTemplate.executeWithoutResult(status -> {
            trendItemRepository.findByDocumentId(doc.getId()).ifPresent(old -> {
                trendItemRepository.delete(old);
                trendItemRepository.flush(); // document_id 유일 제약 — 새 행보다 먼저 지운다
            });
            TrendItem item = TrendItem.builder()
                    .documentId(doc.getId())
                    .category(v.mainCategory()) // 대표(첫) 아이템 — 목록 표시용
                    .summary(v.summary())
                    .resultJson(resultJson)
                    .createdAt(LocalDateTime.now())
                    .build();
            for (int i = 0; i < v.keywords().size(); i++) {
                item.addValue(TrendItemValue.KEYWORD, v.keywords().get(i), i);
            }
            for (int i = 0; i < v.seasons().size(); i++) {
                item.addValue(TrendItemValue.SEASON, v.seasons().get(i), i);
            }
            for (int no = 0; no < v.items().size(); no++) {
                TrendResultValidator.Garment g = v.items().get(no);
                item.addGarmentValue(TrendItemValue.ITEM, g.item(), 0, no, g.category());
                item.addGarmentValue(TrendItemValue.ITEM_TYPE, g.type(), 0, no, g.category());
                addGarmentValues(item, TrendItemValue.MATERIAL, g.materials(), no, g.category());
                addGarmentValues(item, TrendItemValue.COLOR, g.colors(), no, g.category());
                addGarmentValues(item, TrendItemValue.SILHOUETTE, g.silhouettes(), no, g.category());
                addGarmentValues(item, TrendItemValue.GENDER, g.genders(), no, g.category());
            }
            trendItemRepository.save(item);
        });
    }

    private static void addGarmentValues(TrendItem item, String type, List<String> values, int garmentNo, String category) {
        for (int i = 0; i < values.size(); i++) {
            item.addGarmentValue(type, values.get(i), i + 1, garmentNo, category); // 0은 아이템명
        }
    }

    /**
     * 프로세스 정의서 10번 슬라이드의 정형 JSON — 문서 한 건당 하나, source는 원문 문서 값.
     * 정의서와 달리 category·소재·컬러·실루엣을 의류 아이템 배열(items)로 둔다(2026-10-20 사용자 결정).
     */
    private String toResultJson(SourceDocument doc, TrendResultValidator.Validated v) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("doc_id", doc.getDocId());
        json.putAll(structuredMap(v));
        json.put("source", Map.of(
                "media", doc.getMedia(),
                "published_at", doc.getPublishedAt().toString(),
                "url", doc.getUrl() == null ? "" : doc.getUrl()));
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> structuredMap(TrendResultValidator.Validated v) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("items", v.items().stream().map(g -> {
            Map<String, Object> garment = new LinkedHashMap<>();
            garment.put("category", g.category());
            garment.put("item", g.item());
            garment.put("type", g.type());
            garment.put("materials", g.materials());
            garment.put("colors", g.colors());
            garment.put("silhouettes", g.silhouettes());
            garment.put("genders", g.genders());
            return garment;
        }).toList());
        map.put("keywords", v.keywords());
        map.put("seasons", v.seasons());
        map.put("summary", v.summary());
        return map;
    }

    /**
     * 저장된 분석 결과(trend_items.result_json)로 색인만 다시 만든다 — Claude를 부르지 않는다(임베딩만 다시 한다).
     * 색인이 빠진 분석 완료 문서를 메우는 데 쓴다(IndexSyncService, 2026-10-26 — 색인이 통째로 지워진 사고 뒤).
     *
     * @return 색인했으면 true, 분석 결과나 출처가 없어 못 했으면 false
     */
    public boolean reindex(SourceDocument doc) {
        TrendItem item = trendItemRepository.findByDocumentId(doc.getId()).orElse(null);
        if (item == null || doc.getMedia() == null || doc.getPublishedAt() == null) {
            return false;
        }
        Map<String, Object> saved;
        try {
            saved = objectMapper.readValue(item.getResultJson(), new com.fasterxml.jackson.core.type.TypeReference<>() { });
        } catch (JsonProcessingException e) {
            log.warn("다시 색인 못 함 doc={} — 저장된 결과 JSON을 읽지 못했습니다: {}", doc.getDocId(), e.getMessage());
            return false;
        }
        Map<String, Object> structured = new LinkedHashMap<>(); // 분석 때 보낸 것과 같은 키만(doc_id·source는 빼고)
        for (String key : List.of("items", "keywords", "seasons", "summary")) {
            structured.put(key, saved.get(key));
        }
        // 이름은 사용 안 함 코드까지 — 예전 결과에 쓰인 코드도 이름으로 색인되게
        TrendCodeBook codes = TrendCodeBook.of(comCdRepository::findByCodeGroupOrderBySortOrderAsc);
        aiClient.index(new AiClient.IndexRequest(doc.getDocId(), doc.getDocType(), doc.getBody(), structured,
                codes.allNames(), sourceMeta(doc)));
        return true;
    }

    private static Map<String, String> sourceMeta(SourceDocument doc) {
        return Map.of(
                "media", doc.getMedia(),
                "published_at", doc.getPublishedAt().toString(),
                "url", doc.getUrl() == null ? "" : doc.getUrl());
    }

    private void fail(SourceDocument doc, String reason) {
        doc.markFailed(reason == null ? "알 수 없는 오류" : reason);
        sourceDocumentRepository.save(doc);
        log.warn("분석 실패 doc={}: {}", doc.getDocId(), reason);
    }

    /** 일시 오류 재시도 간격(2초, 4초 …). 요청 한도(429)일 때 바로 다시 두드리지 않기 위해서다. */
    private static void backoff(int attempt) {
        try {
            Thread.sleep(2000L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
