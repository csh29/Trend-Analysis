package com.sjinc.trendanalysis.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * 업로드 크기 상한. Spring 기본값(파일 1MB·요청 10MB)으로는 사진 몇 장만 올려도 거부돼서 올린다.
 * application.properties가 커밋되지 않으므로 값이 없어도 동작하게 기본값을 여기 둔다.
 * 받은 파일은 메모리가 아니라 임시 파일로 받는다(fileSizeThreshold 0).
 */
@Configuration
public class ImageUploadConfig {

    @Bean
    public MultipartConfigElement multipartConfigElement(
            @Value("${image.upload.max-file-size:20MB}") DataSize maxFileSize,
            @Value("${image.upload.max-request-size:200MB}") DataSize maxRequestSize) {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(maxFileSize);
        factory.setMaxRequestSize(maxRequestSize);
        factory.setFileSizeThreshold(DataSize.ofBytes(0));
        return factory.createMultipartConfig();
    }
}
