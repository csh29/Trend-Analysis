package com.sjinc.trendanalysis.dto.rag;

import java.time.LocalDate;
import java.util.List;

/**
 * RAG 질문 한 건의 답. 화면은 answer를 결론 한 줄로, points를 글머리 목록으로, 줄마다 인용 번호(sources의 n)를 붙여 그린다.
 * grounded=false면 answer는 "근거 자료 없음" 안내이고 points·sources는 비어 있다.
 *
 * @param reason    근거 없음 사유 — NO_EVIDENCE(기준 유사도 미달), MODEL_DECLINED(근거가 질문에 맞지 않음). 답했으면 null
 * @param bestScore 검색된 근거 중 가장 높은 유사도(0~1). 기준(minScore)과 같이 보여 주어 왜 막혔는지 알 수 있게 한다
 * @param statsWeek 답변에 덧붙인 주간 집계의 기준 주. 집계가 없으면 null
 * @param period    근거의 기간. explicit이면 질문이 정한 기간, 아니면 근거로 쓴 원문의 게시일 범위(최근 자료 우선).
 *                  stale이면 가장 최근 근거도 8주가 넘었다(화면이 알린다)
 */
public record RagAnswerView(String question, boolean grounded, String answer, List<Point> points, String reason,
                            List<Source> sources, Double bestScore, double minScore, LocalDate statsWeek, Period period) {

    public record Period(LocalDate from, LocalDate to, String label, boolean explicit, boolean stale) {
    }

    /** 답의 한 줄. sources는 그 줄을 뒷받침하는 출처 번호(Source.n) — 집계 수치만으로 쓴 줄은 비어 있다. */
    public record Point(String text, List<Integer> sources) {
    }

    /** n은 인용 번호. statDate는 집계에 쓴 날짜(게시일, 없으면 업로드일). */
    public record Source(int n, String docId, String docType, String title, String media, LocalDate statDate,
                         String url, double score, String chunk) {
    }
}
