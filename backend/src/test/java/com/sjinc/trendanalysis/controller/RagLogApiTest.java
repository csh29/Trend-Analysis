package com.sjinc.trendanalysis.controller;

import com.jayway.jsonpath.JsonPath;
import com.sjinc.trendanalysis.repository.RagQueryLogRepository;
import com.sjinc.trendanalysis.service.analysis.AiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 챗봇 질문 로그 — 질문마다 경로·토큰을 남기고, 질문한 사람만 👍👎를 남기며, 로그 화면은 rag-log 권한이 있어야 본다. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:raglogapitest", "claude.api.key=test-key"})
@AutoConfigureMockMvc
class RagLogApiTest {

    @MockBean
    private AiClient aiClient;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RagQueryLogRepository repository;

    @Test
    @WithMockUser(username = "admin")
    void 질문을_남기고_질문한_사람만_피드백한다() throws Exception {
        // 패션 질문이 아니면 해석만 하고 끝난다 — 해석 토큰이 로그에 남는다
        when(aiClient.interpret(any(), any())).thenReturn(new AiClient.InterpretResponse(false, null, null, null,
                Map.of("input_tokens", 8000, "output_tokens", 100), false, null));
        String body = mockMvc.perform(post("/api/rag/ask").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"오늘 날씨 어때?\",\"history\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("OFF_TOPIC"))   // 답 필드는 그대로
                .andExpect(jsonPath("$.logId").isNumber())
                .andReturn().getResponse().getContentAsString();
        Number logId = JsonPath.read(body, "$.logId");

        var saved = repository.findById(logId.longValue()).orElseThrow();
        assertThat(saved.getRoute()).isEqualTo("OFF_TOPIC");
        assertThat(saved.getInputTokens()).isEqualTo(8000);
        assertThat(saved.getUsername()).isEqualTo("admin");

        mockMvc.perform(post("/api/rag/feedback").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"logId\":" + logId + ",\"value\":\"DOWN\"}"))
                .andExpect(status().isOk());
        assertThat(repository.findById(logId.longValue()).orElseThrow().getFeedback()).isEqualTo("DOWN");
        // 다른 사람은 바꿀 수 없다
        mockMvc.perform(post("/api/rag/feedback").with(csrf()).with(user("someone"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"logId\":" + logId + ",\"value\":\"UP\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/rag-logs").param("feedback", "DOWN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].question").value("오늘 날씨 어때?"))
                .andExpect(jsonPath("$[0].feedback").value("DOWN"));
    }

    @Test
    @WithMockUser(username = "no-permission-user")
    void 로그_화면은_권한이_있어야_본다() throws Exception {
        mockMvc.perform(get("/api/rag-logs")).andExpect(status().isForbidden());
    }
}
