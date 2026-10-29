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
}
