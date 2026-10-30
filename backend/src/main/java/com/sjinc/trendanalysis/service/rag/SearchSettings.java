package com.sjinc.trendanalysis.service.rag;

import com.sjinc.trendanalysis.domain.ComCd;
import com.sjinc.trendanalysis.repository.ComCdRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 챗봇 근거 검색 방식 — 공통코드 RAG_SEARCH(관리 > 공통코드)에서 질문마다 읽는다. 재기동 없이 다음 질문부터 바뀐다(2026-10-30 사용자 결정).
 * 리랭킹(코드 RERANK)의 사용 여부가 켜기/끄기, 속성이 리랭커 근거 기준이다. 끄면 rag.min-score(코사인 유사도) 기준으로 돌아간다 —
 * 두 점수는 척도가 달라(리랭커 0~1 확률, 유사도 0.5~0.8에 몰림) 방식마다 기준을 따로 둔다.
 */
@Slf4j
@Component
public class SearchSettings {

    public static final String GROUP = "RAG_SEARCH";
    public static final String RERANK = "RERANK";
    /** 리랭커 기준 기본값 — 2026-10-30 rerank_eval(bge-reranker-v2-m3): 자료 없는 주제 1위 최고 0.042, 정답 있는 질문은 모두 0.10 이상. */
    public static final String DEFAULT_RERANK_MIN_SCORE = "0.10";

    /** rerank면 근거 기준(minScore)이 리랭커 점수, 아니면 유사도다. */
    public record Mode(boolean rerank, double minScore) {
    }

    private final ComCdRepository comCdRepository;
    private final double minScore;

    public SearchSettings(ComCdRepository comCdRepository, @Value("${rag.min-score:0.60}") double minScore) {
        this.comCdRepository = comCdRepository;
        this.minScore = minScore;
    }

    public Mode current() {
        ComCd code = comCdRepository.findByCodeGroupOrderBySortOrderAsc(GROUP).stream()
                .filter(c -> RERANK.equals(c.getCodeValue())).findFirst().orElse(null);
        if (code == null || !"Y".equals(code.getUseYn())) {
            return new Mode(false, minScore);
        }
        return new Mode(true, rerankMinScore(code.getAttr1()));
    }

    /** 속성이 0~1 숫자가 아니면 기본값으로 쓰고 경고를 남긴다 — 잘못 적어 기준이 사라지면(0) 무관한 원문이 근거가 된다. */
    static double rerankMinScore(String attr) {
        try {
            double value = Double.parseDouble(attr == null ? "" : attr.trim());
            if (value > 0 && value < 1) return value;
        } catch (NumberFormatException ignored) {
            // 아래에서 기본값
        }
        log.warn("공통코드 {}.{}의 속성(리랭커 기준) '{}'이 0~1 사이 숫자가 아니라 기본값 {}을 씁니다.", GROUP, RERANK, attr,
                DEFAULT_RERANK_MIN_SCORE);
        return Double.parseDouble(DEFAULT_RERANK_MIN_SCORE);
    }
}
