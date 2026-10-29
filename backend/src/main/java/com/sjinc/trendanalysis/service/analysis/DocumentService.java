package com.sjinc.trendanalysis.service.analysis;

import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.domain.TrendItem;
import com.sjinc.trendanalysis.domain.TrendItemValue;
import com.sjinc.trendanalysis.domain.UploadedImage;
import com.sjinc.trendanalysis.repository.UploadedImageRepository;
import com.sjinc.trendanalysis.dto.analysis.AnalysisSummary;
import com.sjinc.trendanalysis.dto.analysis.AnalysisView;
import com.sjinc.trendanalysis.dto.analysis.ReanalyzeResult;
import com.sjinc.trendanalysis.dto.analysis.TextDocumentRequest;
import com.sjinc.trendanalysis.dto.analysis.TextDocumentView;
import com.sjinc.trendanalysis.repository.ComCdRepository;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.repository.TrendItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 원문 문서 등록·조회·재분석·삭제. 원문 1건 = 본문 + 이미지 N장 — 이미지만 있으면 IMAGE, 본문이 있으면 TEXT(첨부 이미지 0~5장). 등록하면 분석 대기열에 넣고(AnalysisRequestedEvent) 바로 돌아간다 — 정형화는
 * AnalysisService가 분석 스레드에서 한다. 저장이 끝난(커밋된) 뒤에 이벤트를 내야 분석 스레드가 문서를 읽을 수 있으므로
 * 이 서비스의 등록 메서드는 트랜잭션으로 감싸지 않는다(저장마다 커밋).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    /** 업로드 이미지의 출처 매체명. 지금은 테스트용 업로드라 업로드 정보로 출처를 대신한다(2026-10-20 사용자 결정). */
    public static final String UPLOAD_MEDIA = "직접 업로드";
    public static final String TEXT_MEDIA = "직접 등록";
    static final int MAX_BODY_LENGTH = 50_000;
    private static final DateTimeFormatter DOC_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final SourceDocumentRepository sourceDocumentRepository;
    private final TrendItemRepository trendItemRepository;
    private final ComCdRepository comCdRepository;
    private final UploadedImageRepository uploadedImageRepository;
    private final AiClient aiClient;
    private final ApplicationEventPublisher eventPublisher;

    /** 업로드한 이미지마다 원문 문서를 만들고 분석 대기열에 넣는다. 출처는 업로드일·이미지 주소로 대신한다. */
    public void registerImages(List<UploadedImage> images, String username) {
        List<Long> ids = new ArrayList<>();
        for (UploadedImage image : images) {
            SourceDocument doc = save(SourceDocument.builder()
                    .docType(SourceDocument.TYPE_IMAGE)
                    .title(image.getOriginalName())
                    .imageId(image.getId())
                    .media(UPLOAD_MEDIA)
                    .publishedAt(image.getUploadedAt().toLocalDate())
                    .url("/api/images/" + image.getId() + "/file")
                    .status(SourceDocument.PENDING)
                    .registeredBy(username)
                    .registeredAt(image.getUploadedAt())
                    .build());
            ids.add(doc.getId());
        }
        requestAnalysis(ids);
    }

    /** 본문만 있는 텍스트 원문 등록. 이미지를 같이 붙일 때는 TextRegistrationService가 saveText → 첨부 → requestAnalysis 순으로 부른다. */
    public TextDocumentView registerText(TextDocumentRequest request, String username) {
        SourceDocument doc = saveText(request, username);
        requestAnalysis(List.of(doc.getId()));
        return TextDocumentView.of(doc, 0, AnalysisSummary.of(doc, null, null));
    }

    /** 텍스트 원문을 저장만 한다(분석 대기열에는 넣지 않는다). 첨부를 다 붙인 뒤 requestAnalysis를 불러야 분석이 이미지까지 본다. */
    public SourceDocument saveText(TextDocumentRequest request, String username) {
        String body = request.body() == null ? "" : request.body().strip();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("본문을 입력하세요.");
        }
        if (body.length() > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("본문은 " + MAX_BODY_LENGTH + "자까지 등록할 수 있습니다.");
        }
        // 같은 원문(정규화한 URL이 같은 것)은 다시 저장하지 않는다 — 두 번 저장되면 유행 집계에서 두 번 세진다
        String url = SourceUrls.normalize(request.url());
        if (url != null) {
            sourceDocumentRepository.findFirstByUrl(url).ifPresent(existing -> {
                throw new IllegalArgumentException("이미 등록된 원문입니다(같은 URL): " + existing.getDocId() + " " + existing.getTitle());
            });
        }
        String title = blankToNull(request.title());
        if (title == null) {
            title = body.substring(0, Math.min(30, body.length())).replaceAll("\\s+", " ");
        }
        LocalDateTime now = LocalDateTime.now();
        SourceDocument doc = save(SourceDocument.builder()
                .docType(SourceDocument.TYPE_TEXT)
                .title(title.length() > 200 ? title.substring(0, 200) : title)
                .body(body)
                .media(blankToNull(request.media()) == null ? TEXT_MEDIA : request.media().trim())
                .publishedAt(request.publishedAt() == null ? now.toLocalDate() : request.publishedAt())
                .url(url)
                .status(SourceDocument.PENDING)
                .registeredBy(username)
                .registeredAt(now)
                .build());
        return doc;
    }

    /** 첨부 저장이 실패했을 때 방금 만든 원문을 되돌린다(분석 전이라 트렌드 항목·색인이 없다). */
    public void discard(SourceDocument doc) {
        sourceDocumentRepository.delete(doc);
    }

    public TextDocumentView view(SourceDocument doc, int imageCount) {
        return TextDocumentView.of(doc, imageCount, AnalysisSummary.of(doc, null, null));
    }

    public List<TextDocumentView> getTexts() {
        List<SourceDocument> docs = sourceDocumentRepository.findByDocTypeOrderByRegisteredAtDescIdDesc(SourceDocument.TYPE_TEXT);
        Map<Long, AnalysisSummary> summaries = summarize(docs);
        Map<Long, Long> imageCounts = uploadedImageRepository.findByDocumentIdIn(docs.stream().map(SourceDocument::getId).toList())
                .stream().collect(Collectors.groupingBy(UploadedImage::getDocumentId, Collectors.counting()));
        return docs.stream().map(doc -> TextDocumentView.of(doc, imageCounts.getOrDefault(doc.getId(), 0L).intValue(),
                summaries.get(doc.getId()))).toList();
    }

    /** 이미지 업로드 그리드용 — 이미지 id → 분석 요약. */
    public Map<Long, AnalysisSummary> summariesByImageIds(Collection<Long> imageIds) {
        if (imageIds.isEmpty()) {
            return Map.of();
        }
        List<SourceDocument> docs = sourceDocumentRepository.findByImageIdIn(imageIds);
        Map<Long, AnalysisSummary> byDocument = summarize(docs);
        return docs.stream().collect(Collectors.toMap(SourceDocument::getImageId, doc -> byDocument.get(doc.getId())));
    }

    public AnalysisView getImageAnalysis(Long imageId) {
        return toView(imageDocument(imageId));
    }

    public AnalysisView getTextAnalysis(Long documentId) {
        return toView(textDocument(documentId));
    }

    public void reanalyzeImage(Long imageId) {
        reanalyze(imageDocument(imageId));
    }

    public void reanalyzeText(Long documentId) {
        reanalyze(textDocument(documentId));
    }

    /** 한 번에 다시 분석할 수 있는 원문 수 — 실수로 전체를 눌러 Claude 비용이 크게 나가지 않게. */
    public static final int MAX_BULK_REANALYZE = 200;

    /** 선택한 이미지들을 한 번에 다시 분석한다. 이미 대기·분석 중인 원문은 건너뛴다. */
    public ReanalyzeResult reanalyzeImages(List<Long> imageIds) {
        return reanalyzeAll(sourceDocumentRepository.findByImageIdIn(checkedIds(imageIds)));
    }

    /** 선택한 텍스트 원문들을 한 번에 다시 분석한다. */
    public ReanalyzeResult reanalyzeTexts(List<Long> documentIds) {
        return reanalyzeAll(sourceDocumentRepository.findAllById(checkedIds(documentIds)).stream()
                .filter(doc -> SourceDocument.TYPE_TEXT.equals(doc.getDocType())).toList());
    }

    private static List<Long> checkedIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("다시 분석할 원문을 선택하세요.");
        }
        if (ids.size() > MAX_BULK_REANALYZE) {
            throw new IllegalArgumentException("한 번에 " + MAX_BULK_REANALYZE + "건까지 다시 분석할 수 있습니다.");
        }
        return ids;
    }

    /** 대기열에 한 번에 넣는다 — 분석 스레드가 순서대로 처리하고, 첫 건이 캐시를 데우면 나머지는 캐시를 읽는다(CacheWarmGate). */
    private ReanalyzeResult reanalyzeAll(List<SourceDocument> docs) {
        List<String> skipped = new java.util.ArrayList<>();
        List<Long> queued = new java.util.ArrayList<>();
        for (SourceDocument doc : docs) {
            if (SourceDocument.PROCESSING.equals(doc.getStatus()) || SourceDocument.PENDING.equals(doc.getStatus())) {
                skipped.add(doc.getDocId());
                continue;
            }
            doc.markPending();
            sourceDocumentRepository.save(doc);
            queued.add(doc.getId());
        }
        requestAnalysis(queued);
        log.info("일괄 재분석 {}건 요청, 분석 중이라 건너뜀 {}건", queued.size(), skipped.size());
        return new ReanalyzeResult(queued.size(), skipped);
    }

    /** 이미지 삭제에 딸린 문서·트렌드 항목·색인을 지운다(ImageService.delete가 부른다). */
    public void deleteByImageIds(List<Long> imageIds) {
        delete(sourceDocumentRepository.findByImageIdIn(imageIds));
    }

    public void deleteTexts(List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            throw new IllegalArgumentException("삭제할 문서를 선택하세요.");
        }
        delete(sourceDocumentRepository.findAllById(documentIds).stream()
                .filter(doc -> SourceDocument.TYPE_TEXT.equals(doc.getDocType())).toList());
    }

    private void reanalyze(SourceDocument doc) {
        if (SourceDocument.PROCESSING.equals(doc.getStatus()) || SourceDocument.PENDING.equals(doc.getStatus())) {
            throw new IllegalArgumentException("이미 분석 중인 문서입니다: " + doc.getDocId());
        }
        doc.markPending();
        sourceDocumentRepository.save(doc);
        requestAnalysis(List.of(doc.getId()));
    }

    /** DB를 먼저 지우고 색인은 그 다음 — 색인 삭제 실패(AI 서비스 꺼짐)는 로그만 남긴다. 남은 청크는 재색인 때 정리된다. */
    private void delete(List<SourceDocument> docs) {
        if (docs.isEmpty()) {
            return;
        }
        List<Long> ids = docs.stream().map(SourceDocument::getId).toList();
        trendItemRepository.deleteAll(trendItemRepository.findByDocumentIdIn(ids));
        sourceDocumentRepository.deleteAll(docs);
        for (SourceDocument doc : docs) {
            try {
                aiClient.deleteIndex(doc.getDocId());
            } catch (AiCallException e) {
                log.warn("색인 삭제 실패 doc={}: {}", doc.getDocId(), e.getMessage());
            }
        }
    }

    private SourceDocument save(SourceDocument doc) {
        SourceDocument saved = sourceDocumentRepository.save(doc);
        String prefix = saved.isImage() ? "IMG" : "TXT";
        saved.assignDocId(prefix + "-" + saved.getRegisteredAt().format(DOC_DATE) + "-" + String.format("%06d", saved.getId()));
        return sourceDocumentRepository.save(saved);
    }

    public void requestAnalysis(List<Long> documentIds) {
        if (!documentIds.isEmpty()) {
            eventPublisher.publishEvent(new AnalysisRequestedEvent(documentIds));
        }
    }

    private SourceDocument imageDocument(Long imageId) {
        return sourceDocumentRepository.findByImageId(imageId)
                .orElseThrow(() -> new IllegalArgumentException("이 이미지의 분석 문서가 없습니다: " + imageId));
    }

    private SourceDocument textDocument(Long documentId) {
        return sourceDocumentRepository.findById(documentId)
                .filter(doc -> SourceDocument.TYPE_TEXT.equals(doc.getDocType()))
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 텍스트 문서입니다: " + documentId));
    }

    private Map<Long, AnalysisSummary> summarize(List<SourceDocument> docs) {
        TrendCodeBook codes = codeBook();
        Map<Long, TrendItem> items = trendItemRepository.findByDocumentIdIn(docs.stream().map(SourceDocument::getId).toList())
                .stream().collect(Collectors.toMap(TrendItem::getDocumentId, Function.identity()));
        return docs.stream().collect(Collectors.toMap(SourceDocument::getId, doc -> {
            TrendItem item = items.get(doc.getId());
            // 코디 사진은 아이템이 여럿이다 — 카테고리 이름을 나온 순서로 이어 보여 준다(예: "상의, 하의")
            return AnalysisSummary.of(doc, item, item == null ? null : item.garmentCategories().stream()
                    .map(c -> codes.name(TrendCodes.CATEGORY, c)).collect(Collectors.joining(", ")));
        }));
    }

    private AnalysisView toView(SourceDocument doc) {
        AnalysisView.Result result = trendItemRepository.findByDocumentId(doc.getId()).map(item -> {
            TrendCodeBook codes = codeBook();
            List<AnalysisView.Item> garments = item.garments().stream().map(g -> new AnalysisView.Item(
                    code(codes, TrendCodes.CATEGORY, g.category()), g.name(),
                    g.type() == null ? null : code(codes, TrendCodes.ITEM_TYPE, g.type()),
                    codesOf(codes, TrendCodes.MATERIAL, g.materials()),
                    codesOf(codes, TrendCodes.COLOR, g.colors()),
                    codesOf(codes, TrendCodes.SILHOUETTE, g.silhouettes()),
                    codesOf(codes, TrendCodes.GENDER, g.genders()),
                    codesOf(codes, TrendCodes.PATTERN, g.patterns()),
                    codesOf(codes, TrendCodes.DETAIL, g.details()))).toList();
            return new AnalysisView.Result(garments, item.valuesOf(TrendItemValue.KEYWORD),
                    codesOf(codes, TrendCodes.SEASON, item.valuesOf(TrendItemValue.SEASON)),
                    item.getSummary(), item.getResultJson());
        }).orElse(null);
        // 이미지 원문은 그 사진(이미지 업로드 화면 권한으로 연다), 텍스트 원문은 첨부 사진
        List<AnalysisView.Image> images = doc.isImage()
                ? (doc.getImageId() == null ? List.of() : List.of(new AnalysisView.Image(doc.getImageId(), doc.getTitle(),
                        "/api/images/" + doc.getImageId() + "/file")))
                : uploadedImageRepository.findByDocumentIdOrderBySortOrderAscIdAsc(doc.getId()).stream()
                .map(image -> new AnalysisView.Image(image.getId(), image.getOriginalName(),
                        "/api/texts/" + doc.getId() + "/images/" + image.getId() + "/file"))
                .toList();
        return new AnalysisView(doc.getId(), doc.getDocId(), doc.getDocType(), doc.getTitle(), doc.getStatus(),
                doc.getFailReason(), doc.getAttempts(), doc.getAnalyzedAt(), doc.getMedia(), doc.getPublishedAt(),
                doc.getUrl(), images, result);
    }

    /** 이름 표시용 — 사용 중지된 코드도 이름을 보여야 하므로 사용여부와 무관하게 읽는다. */
    private TrendCodeBook codeBook() {
        return TrendCodeBook.of(comCdRepository::findByCodeGroupOrderBySortOrderAsc);
    }

    private static AnalysisView.Code code(TrendCodeBook codes, String group, String value) {
        return new AnalysisView.Code(value, codes.name(group, value));
    }

    private static List<AnalysisView.Code> codesOf(TrendCodeBook codes, String group, List<String> values) {
        return values.stream().map(v -> code(codes, group, v)).toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
