package com.sjinc.trendanalysis.service.analysis;

import com.sjinc.trendanalysis.domain.ComCd;
import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.domain.TrendItem;
import com.sjinc.trendanalysis.domain.TrendItemValue;
import com.sjinc.trendanalysis.domain.UploadedImage;
import com.sjinc.trendanalysis.dto.analysis.AnalysisResultRow;
import com.sjinc.trendanalysis.repository.ComCdRepository;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.repository.TrendItemRepository;
import com.sjinc.trendanalysis.repository.UploadedImageRepository;
import com.sjinc.trendanalysis.service.image.ImageService;
import com.sjinc.trendanalysis.service.trend.TrendAggregationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 분석 결과 조회 화면(2026-10-29 사용자 요청) — 분석이 끝난 원문의 정형 결과를 사진과 함께 본다.
 * 조회 조건(카테고리·아이템 종류)에 맞는 의류 아이템이 있는 원문만, 값은 그 아이템들의 값만 보인다(키워드는 원문 단위).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisResultService {

    /** 한 번에 보이는 원문 상한(최근 것부터) — 데이터가 많아지면 페이지 나누기를 붙인다. */
    static final int MAX_ROWS = 1000;

    private final SourceDocumentRepository sourceDocumentRepository;
    private final TrendItemRepository trendItemRepository;
    private final UploadedImageRepository uploadedImageRepository;
    private final ComCdRepository comCdRepository;
    private final ImageService imageService;

    public List<AnalysisResultRow> search(String category, String itemType) {
        String cat = blank(category);
        String type = blank(itemType);
        List<SourceDocument> docs = sourceDocumentRepository.findByStatus(SourceDocument.DONE);
        Map<Long, TrendItem> items = trendItemRepository.findByDocumentIdIn(docs.stream().map(SourceDocument::getId).toList()).stream()
                .collect(Collectors.toMap(TrendItem::getDocumentId, Function.identity()));
        Map<String, String> categoryNames = names(TrendCodes.CATEGORY);
        Map<String, String> typeNames = names(TrendCodes.ITEM_TYPE);
        Map<String, String> colorNames = names(TrendCodes.COLOR);
        Map<String, String> patternNames = names(TrendCodes.PATTERN);
        Map<String, String> silhouetteNames = names(TrendCodes.SILHOUETTE);

        List<SourceDocument> hits = docs.stream()
                .filter(d -> items.containsKey(d.getId()))
                .sorted(Comparator.comparing(TrendAggregationService::statDate).reversed().thenComparing(SourceDocument::getId, Comparator.reverseOrder()))
                .toList();
        Map<Long, Long> textImages = firstAttachments(hits);
        return hits.stream()
                .map(doc -> {
                    TrendItem item = items.get(doc.getId());
                    List<TrendItem.Garment> garments = item.garments().stream()
                            .filter(g -> (cat == null || cat.equals(g.category())) && (type == null || type.equals(g.type()))).toList();
                    if (garments.isEmpty()) return null;
                    boolean hasImage = doc.isImage() ? doc.getImageId() != null : textImages.containsKey(doc.getId());
                    return new AnalysisResultRow(doc.getDocId(), doc.getDocType(), doc.getTitle(), doc.getMedia(),
                            TrendAggregationService.statDate(doc), hasImage ? "/api/analysis-results/" + doc.getDocId() + "/image" : null,
                            values(garments, g -> List.of(Objects.toString(g.category(), "")), categoryNames),
                            values(garments, g -> g.type() == null ? List.of() : List.of(g.type()), typeNames),
                            item.valuesOf(TrendItemValue.KEYWORD),
                            values(garments, TrendItem.Garment::colors, colorNames),
                            values(garments, TrendItem.Garment::patterns, patternNames),
                            values(garments, TrendItem.Garment::silhouettes, silhouetteNames));
                })
                .filter(Objects::nonNull)
                .limit(MAX_ROWS)
                .toList();
    }

    /** 행의 사진 — 이미지 원문은 그 사진, 텍스트 원문은 첫 첨부 사진. 분석이 끝난 원문만 연다. */
    public ImageService.ImageFile image(String docId) {
        SourceDocument doc = sourceDocumentRepository.findByDocId(docId)
                .filter(d -> SourceDocument.DONE.equals(d.getStatus()))
                .orElseThrow(() -> new IllegalArgumentException("분석이 끝난 원문이 아닙니다: " + docId));
        if (doc.isImage() && doc.getImageId() != null) {
            return imageService.getFile(doc.getImageId());
        }
        return uploadedImageRepository.findByDocumentIdOrderBySortOrderAscIdAsc(doc.getId()).stream().findFirst()
                .map(image -> imageService.getAttachmentFile(doc.getId(), image.getId()))
                .orElseThrow(() -> new IllegalArgumentException("사진이 없는 원문입니다: " + docId));
    }

    /** 텍스트 원문 id → 첫 첨부 사진 id. */
    private Map<Long, Long> firstAttachments(List<SourceDocument> docs) {
        List<Long> textIds = docs.stream().filter(d -> !d.isImage()).map(SourceDocument::getId).toList();
        if (textIds.isEmpty()) return Map.of();
        return uploadedImageRepository.findByDocumentIdIn(textIds).stream()
                .sorted(Comparator.comparing(UploadedImage::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(UploadedImage::getId))
                .collect(Collectors.toMap(UploadedImage::getDocumentId, UploadedImage::getId, (a, b) -> a, LinkedHashMap::new));
    }

    private static List<String> values(List<TrendItem.Garment> garments, Function<TrendItem.Garment, List<String>> of, Map<String, String> names) {
        return garments.stream().flatMap(g -> of.apply(g).stream()).filter(v -> v != null && !v.isBlank()).distinct()
                .map(v -> names.getOrDefault(v, v)).toList();
    }

    private Map<String, String> names(String group) {
        return comCdRepository.findByCodeGroupOrderBySortOrderAsc(group).stream()
                .collect(Collectors.toMap(ComCd::getCodeValue, ComCd::getCodeName, (a, b) -> a));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
