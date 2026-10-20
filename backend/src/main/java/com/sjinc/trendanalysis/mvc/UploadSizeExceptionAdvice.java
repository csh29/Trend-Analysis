package com.sjinc.trendanalysis.mvc;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 업로드 크기 상한(ImageUploadConfig)을 넘은 요청을 413과 안내 문구로 돌려준다. 이 예외는 컨트롤러를 고르기 전
 * 멀티파트 해석 단계에서 나서 범위를 한정한 ApiExceptionAdvice로는 잡히지 않으므로, 범위 없이 따로 둔다.
 */
@RestControllerAdvice
public class UploadSizeExceptionAdvice {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public String handleTooLarge(MaxUploadSizeExceededException e) {
        return "업로드 크기 상한을 넘었습니다. 한 장 20MB, 한 번에 합계 200MB까지 올릴 수 있습니다.";
    }
}
