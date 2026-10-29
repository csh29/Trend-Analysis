package com.sjinc.trendanalysis.dto.rag;

import java.time.LocalDate;
import java.util.List;

/**
 * RAG 질문 한 건의 답. 화면은 answer를 결론 한 줄로, points를 글머리 목록으로, 줄마다 인용 번호(sources의 n)를 붙여 그린다.
 * grounded=false면 answer는 "근거 자료 없음" 안내이고 points·sources는 비어 있다.
 *
 * @param reason         근거 없음 사유 — NO_EVIDENCE(근거 없음), MODEL_DECLINED(근거가 질문에 맞지 않음). 답했으면 null
 * @param bestScore      검색된 근거 중 가장 높은 유사도(0~1). 유행 질문(집계로 답함)은 null
 * @param period         근거의 기간. explicit이면 질문이 정한 기간, 아니면 근거 원문의 게시일 범위(검색) 또는 집계 기간(유행).
 *                       stale이면 가장 최근 근거도 8주가 넘었다(화면이 알린다)
 * @param interpretation 질문을 어떻게 알아들었는지(유행 질문인지·범위·누가 해석했는지) — 화면에 보여 근거를 확인하게 한다
 * @param facts          유행 질문이면 판단 근거가 된 집계(원문 수·매체 수), 아니면 null
 */
public record RagAnswerView(String question, boolean grounded, String answer, List<Point> points, String reason,
                            List<Source> sources, Double bestScore, double minScore, Period period,
                            Interpretation interpretation, Facts facts, String body) {

    /** body 없는 답(근거 없음·안내) — 자유 형식 본문은 답이 형식을 요구했을 때만 있다. */
    public RagAnswerView(String question, boolean grounded, String answer, List<Point> points, String reason,
                         List<Source> sources, Double bestScore, double minScore, Period period,
                         Interpretation interpretation, Facts facts) {
        this(question, grounded, answer, points, reason, sources, bestScore, minScore, period, interpretation, facts, null);
    }

    public record Period(LocalDate from, LocalDate to, String label, boolean explicit, boolean stale) {
    }

    /**
     * by: AI(작은 모델) 또는 RULE(규칙 — AI 해석 실패). fashion이 false면 패션 질문이 아니라 답하지 않았다(규칙 해석은 늘 true).
     * resolvedQuestion: 이어 묻기를 앞 대화로 채워 다시 쓴 질문(검색·답변에 쓴 질문) — 원래 질문과 같으면 null.
     * period: 질문이 콕 집은 기간(없으면 null) — 화면이 다음 질문의 앞 대화로 돌려보낸다.
     */
    public record Interpretation(boolean fashion, boolean trend, String category, String categoryName, String itemType,
                                 String itemTypeName, String by, String resolvedQuestion, Period period) {
    }

    /**
     * 유행 판단 근거 집계. dimensions의 첫 번째가 주 축(아이템 종류 등). terms는 질문 단어(코드로 못 옮긴 말)별 언급 원문 수.
     * change는 증감 질문의 방향(UP·DOWN·BOTH) — 있으면 행이 증감 순이고 화면이 증감률을 보인다. 없으면 null.
     */
    public record Facts(String scopeLabel, int docCount, int mediaCount, int prevDocCount, String change, List<Dimension> dimensions,
                        List<TermFact> terms) {
    }

    /**
     * 질문 단어가 키워드·아이템명에 나온 원문 수. expressions는 실제로 나온 표현 상위(예: "무스탕 칼라 1건").
     * details는 대상 옷의 포인트 한 줄씩(예: "컬러: 블랙 6건(직전 1) · 브라운 2건(직전 0)", "함께 나온 옷: 데님 팬츠 3건").
     */
    public record TermFact(String term, int count, int mediaCount, int prevCount, List<String> expressions, List<String> details) {
    }

    public record Dimension(String label, List<FactRow> rows) {
    }

    /**
     * names는 기타 값일 때 그 안의 아이템명 상위(예: "퀼팅 재킷 4건") — 다른 값은 비어 있다.
     * items는 소재·컬러·실루엣 값일 때 그 값으로 나온 아이템 종류 상위(예: "레더 재킷 3건") — 컬러별 활용 아이템의 근거.
     * rate는 직전 대비 증감률("+200%"·"-50%"·"0%", 직전 0건이면 "신규", 둘 다 0이면 "-"). score는 유행점수(막대 길이·순서의 기준).
     */
    public record FactRow(String label, int count, int mediaCount, int prevCount, String rate, List<String> names, List<String> items,
                          double score) {
    }

    /** 답의 한 줄. sources는 그 줄을 뒷받침하는 출처 번호(Source.n) — 집계 수치만으로 쓴 줄은 비어 있다. */
    public record Point(String text, List<Integer> sources) {
    }

    /**
     * n은 인용 번호. statDate는 집계에 쓴 날짜(게시일, 없으면 업로드일). score는 유사도(유행 질문의 예시 원문이면 null).
     * imageUrl은 이미지만 있는 원문(IMAGE)일 때 홈의 오른쪽 슬라이드 패널에서 여는 주소(로그인만 필요) — 링크가 있는 원문은 url을 새 탭으로 연다.
     */
    public record Source(int n, String docId, String docType, String title, String media, LocalDate statDate,
                         String url, String imageUrl, Double score, String chunk) {
    }
}
