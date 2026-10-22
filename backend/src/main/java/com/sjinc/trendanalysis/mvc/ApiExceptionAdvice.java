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
 * AI 서비스 호출 실패({@link AiCallException})는 502로 사유를 내린다(RAG 질문처럼 화면이 바로 기다리는 호출).
 * 범위를 REST 컨트롤러로 한정해 {@link ViewController}의 오류가 HTML 대신 문자열로 나가지 않게 한다.
 */
@RestControllerAdvice(annotations = RestController.class)
@Slf4j
public class ApiExceptionAdvice {

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleInvalidRequest(IllegalArgumentException e) {
        log.debug("잘못된 요청: {}", e.getMessage());
        return e.getMessage();
    }

    @ExceptionHandler(AiCallException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public String handleAiFailure(AiCallException e) {
        log.warn("AI 서비스 호출 실패: {}", e.getMessage());
        return e.getMessage();
    }
}
