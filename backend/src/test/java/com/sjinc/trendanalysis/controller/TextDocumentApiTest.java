package com.sjinc.trendanalysis.controller;

import com.jayway.jsonpath.JsonPath;
import com.sjinc.trendanalysis.service.analysis.AiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 텍스트 등록 API — 등록·목록·결과 보기·삭제, 출처 기본값, 권한. AI 서비스는 가짜로 바꿔 끼운다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:texttest")
@AutoConfigureMockMvc
class TextDocumentApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AiClient aiClient;

    @Test
    @WithMockUser(username = "admin")
    void 등록하면_출처_기본값을_채우고_분석_대기열에_넣는다() throws Exception {
        String body = mockMvc.perform(post("/api/texts").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"레더 재킷과 브라운 컬러가 올가을 유행이다.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docId").value(startsWith("TXT-")))
                .andExpect(jsonPath("$.media").value("직접 등록"))
                .andExpect(jsonPath("$.publishedAt").isNotEmpty())
                .andExpect(jsonPath("$.title").value(startsWith("레더 재킷")))
                .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(body, "$.id");
        String docId = JsonPath.read(body, "$.docId");

        mockMvc.perform(get("/api/texts"))
                .andExpect(jsonPath("$[*].docId", hasItem(docId)));
        mockMvc.perform(get("/api/texts/" + id + "/analysis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docType").value("TEXT"))
                .andExpect(jsonPath("$.media").value("직접 등록"));

        mockMvc.perform(delete("/api/texts").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("[" + id + "]"))
                .andExpect(status().isOk());
        verify(aiClient, timeout(2000)).deleteIndex(docId); // 색인도 지운다
        mockMvc.perform(get("/api/texts/" + id + "/analysis")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "admin")
    void 빈_본문은_400() throws Exception {
        mockMvc.perform(post("/api/texts").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("본문")));
    }

    @Test
    @WithMockUser(username = "admin")
    void 같은_URL의_원문은_다시_등록하지_않는다() throws Exception {
        mockMvc.perform(post("/api/texts").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"첫 등록\",\"url\":\"https://www.example.com/news/1/?utm_source=ig\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://example.com/news/1")); // 정규화해서 저장
        mockMvc.perform(post("/api/texts").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"같은 기사 공유 링크\",\"url\":\"http://example.com/news/1#comments\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("이미 등록된 원문")));
    }

    @Test
    @WithMockUser(username = "nobody")
    void text_upload_권한이_없으면_403() throws Exception {
        mockMvc.perform(get("/api/texts")).andExpect(status().isForbidden());
    }
}
