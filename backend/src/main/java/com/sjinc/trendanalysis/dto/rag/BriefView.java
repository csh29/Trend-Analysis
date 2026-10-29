package com.sjinc.trendanalysis.dto.rag;

import java.util.List;
import java.util.Map;

/**
 * 기획 브리프 — 화면이 PPT로 그린다. 차트·건수는 서버 집계(itemsByCategory·dimensions·sourceMix), 문장은 Claude(brief — 8개 구성 JSON).
 * itemsByCategory는 아우터·상의·하의별 아이템 종류(범위가 카테고리 하나면 그 하나). sources는 예시 원문(사진이면 imageUrl).
 */
public record BriefView(String question, RagAnswerView.Period period, String scopeLabel, int docCount, int mediaCount, int prevDocCount,
                        List<CategoryItems> itemsByCategory, List<RagAnswerView.Dimension> dimensions, List<SourceCount> sourceMix,
                        List<RagAnswerView.Source> sources, Map<String, Object> brief) {

    public record CategoryItems(String category, List<RagAnswerView.FactRow> rows) {
    }

    public record SourceCount(String name, int count) {
    }
}
