package com.sjinc.trendanalysis.dto.analysis;

import com.sjinc.trendanalysis.domain.SourceDocument;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 텍스트 등록 화면 그리드의 한 행. 본문은 길어서 목록에는 앞부분(preview)만 내린다. imageCount는 첨부 이미지 수. */
public record TextDocumentView(Long id, String docId, String title, String preview, int bodyLength, int imageCount,
                               String media, LocalDate publishedAt, String url,
                               String registeredBy, LocalDateTime registeredAt, AnalysisSummary analysis) {

    private static final int PREVIEW_LENGTH = 80;

    public static TextDocumentView of(SourceDocument doc, int imageCount, AnalysisSummary analysis) {
        String body = doc.getBody() == null ? "" : doc.getBody();
        String preview = body.replaceAll("\\s+", " ").trim();
        if (preview.length() > PREVIEW_LENGTH) {
            preview = preview.substring(0, PREVIEW_LENGTH) + "…";
        }
        return new TextDocumentView(doc.getId(), doc.getDocId(), doc.getTitle(), preview, body.length(), imageCount,
                doc.getMedia(), doc.getPublishedAt(), doc.getUrl(), doc.getRegisteredBy(), doc.getRegisteredAt(), analysis);
    }
}
