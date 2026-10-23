package com.sjinc.trendanalysis.mvc;

import com.sjinc.trendanalysis.service.analysis.AiCallException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * REST API의 입력 검증 실패({@link IllegalArgumentException})를 500 대신 400으로 내려 잘못된 요청과 서버 오류를 구분한다.
 * AI 서비스 호출 실패({@link AiCallException})는 502로 내린다(RAG 질문처럼 화면이 바로 기다리는 호출).
 * 범위를 REST 컨트롤러로 한정해 {@link ViewController}의 오류가 HTML 대신 문자열로 나가지 않게 한다.
 */
@RestControllerAdvice(annotations = RestController.class)
@Slf4j
public class ApiExceptionAdvice {

    /**
     * AI 호출 실패 때 화면에 보이는 말 — 사유(AI 서비스 주소·실행 스크립트·설정 키 이름)는 사용자에게 보이지 않게 서버 로그에만 남긴다
     * (2026-10-23 사용자 요청: 챗봇에 서버 정보가 보였다). 다시 해 볼 만한 실패(연결·일시 오류)와 아닌 것만 나눈다.
     */
    static final String AI_RETRY_MESSAGE = "AI 서비스가 잠시 응답하지 않습니다. 잠시 후 다시 시도해 주세요.";
    static final String AI_UNAVAILABLE_MESSAGE = "지금은 AI 답변을 받을 수 없습니다. 계속되면 관리자에게 문의해 주세요.";

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleInvalidRequest(IllegalArgumentException e) {
        log.debug("잘못된 요청: {}", e.getMessage());
        return e.getMessage();
    }

    @ExceptionHandler(AiCallException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public String handleAiFailure(AiCallException e) {
        log.warn("AI 서비스 호출 실패(재시도 가능={}): {}", e.isRetryable(), e.getMessage());
        return e.isRetryable() ? AI_RETRY_MESSAGE : AI_UNAVAILABLE_MESSAGE;
    }
}
