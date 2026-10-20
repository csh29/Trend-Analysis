package com.sjinc.trendanalysis.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 이미지 업로드 API — 여러 장 업로드, 파일 내용으로 형식 확인, 원본 내려주기, 삭제, 권한. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:imagetest",
        "image.upload.dir=target/test-uploads"
})
@AutoConfigureMockMvc
class ImageApiTest {

    /** PNG·JPEG 시그니처로 시작하는 바이트(형식 판별은 앞부분만 본다). */
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1};

    @Autowired
    private MockMvc mockMvc;

    /** "files"라는 같은 이름으로 여러 파일을 담은 업로드 요청(CSRF 포함). */
    private static MockHttpServletRequestBuilder upload(MockMultipartFile... files) {
        var builder = multipart("/api/images");
        for (MockMultipartFile file : files) {
            builder.file(file);
        }
        return builder.with(csrf());
    }

    private static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("files", name, "image/png", bytes);
    }

    @Test
    @WithMockUser(username = "admin")
    void 여러_장을_올리고_조회_보기_삭제한다() throws Exception {
        String body = mockMvc.perform(upload(file("look1.png", PNG), file("C:\\fakepath\\look2.jpg", JPEG)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // 브라우저가 경로까지 보내도 파일명만 남기고, 형식은 파일 내용으로 정한다. 저장 파일명은 내보내지 않는다
                .andExpect(jsonPath("$[1].originalName").value("look2.jpg"))
                .andExpect(jsonPath("$[1].contentType").value("image/jpeg"))
                .andExpect(content().string(not(containsString("storedName"))))
                .andReturn().getResponse().getContentAsString();
        Number pngId = JsonPath.read(body, "$[0].id");
        Number jpgId = JsonPath.read(body, "$[1].id");

        mockMvc.perform(get("/api/images"))
                .andExpect(jsonPath("$[*].originalName", hasItem("look1.png")))
                .andExpect(jsonPath("$[*].uploadedBy", hasItem("admin")));

        mockMvc.perform(get("/api/images/" + pngId + "/file"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(content().bytes(PNG));

        mockMvc.perform(delete("/api/images").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("[" + pngId + "," + jpgId + "]"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/images/" + pngId + "/file"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "admin")
    void 이미지가_아닌_파일이_섞이면_아무것도_저장하지_않는다() throws Exception {
        int before = countImages();
        // 확장자만 png인 SVG 스크립트 — 내용으로 걸러야 한다
        mockMvc.perform(upload(file("ok.png", PNG), file("evil.png", "<svg onload=alert(1)>".getBytes())))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("evil.png")));
        assertEquals(before, countImages());
    }

    @Test
    @WithMockUser(username = "admin")
    void 빈_파일만_올리면_400() throws Exception {
        mockMvc.perform(upload(file("empty.png", new byte[0])))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "nobody")
    void img_upload_권한이_없으면_403() throws Exception {
        mockMvc.perform(get("/api/images")).andExpect(status().isForbidden());
        mockMvc.perform(upload(file("a.png", PNG))).andExpect(status().isForbidden());
    }

    private int countImages() throws Exception {
        String json = mockMvc.perform(get("/api/images")).andReturn().getResponse().getContentAsString();
        List<?> list = JsonPath.read(json, "$");
        return list.size();
    }
}
