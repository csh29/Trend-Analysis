package com.sjinc.trendanalysis.dto.rag;

import java.util.List;

/**
 * 챗봇 질문. history는 같은 화면의 앞 질문과 그때의 해석(오래된 것 → 최근 순) — 이어 묻기("그럼 하의는?")를 알아듣는 데만 쓴다.
 * 화면이 보낸 값이라 RagService가 건수·길이·코드를 다시 거른다. 서버는 대화를 저장하지 않는다.
 */
public record RagQuestionRequest(String question, List<Turn> history) {

    /** 앞 질문(다시 쓴 질문)과 해석. 기간은 콕 집은 질문만 채운다. */
    public record Turn(String question, boolean trend, String category, String itemType, String periodLabel,
                       String periodFrom, String periodTo) {
    }

    /** 판단 근거 원문 목록 요청 — 답의 facts.query에 값(valueType·value, 없으면 범위 전체)과 기간을 더한 것. */
    public record FactDocs(String category, String itemType, java.util.Map<String, java.util.List<String>> filters,
                           java.util.List<String> seasons, String valueType, String value,
                           java.time.LocalDate from, java.time.LocalDate to) {
    }
}
