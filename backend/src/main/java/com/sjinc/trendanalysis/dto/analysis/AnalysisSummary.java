package com.sjinc.trendanalysis.dto.analysis;

import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.domain.TrendItem;
import com.sjinc.trendanalysis.domain.TrendItemValue;

import java.util.List;

/** 목록 그리드 한 행에 붙는 분석 상태와 요약(의류 아이템 카테고리 이름들·키워드). 분석 전이면 비어 있다. */
public record AnalysisSummary(Long documentId, String docId, String status, String failReason,
                              String categoryName, List<String> keywords) {

    public static AnalysisSummary of(SourceDocument doc, TrendItem item, String categoryName) {
        return new AnalysisSummary(doc.getId(), doc.getDocId(), doc.getStatus(), doc.getFailReason(),
                item == null ? null : categoryName,
                item == null ? List.of() : item.valuesOf(TrendItemValue.KEYWORD));
    }
}
