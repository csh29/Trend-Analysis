package com.sjinc.trendanalysis.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리 화면 5종이 호출하는 API가 화면과 같은 계약으로 동작하고, 로그인·프로그램 권한으로 막히는지 확인한다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:admintest")
@AutoConfigureMockMvc
class AdminApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 로그인하지_않은_API_호출은_401() throws Exception {
        mockMvc.perform(get("/api/programs")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "nobody")
    void 프로그램_권한이_없으면_403() throws Exception {
        mockMvc.perform(get("/api/programs")).andExpect(status().isForbidden());
        // 사용중 공통코드 조회는 로그인만 있으면 되고, 비활성 포함 조회는 com-cd-mng 권한이 필요하다
        mockMvc.perform(get("/api/com-cds").param("group", "ROLE")).andExpect(status().isOk());
        mockMvc.perform(get("/api/com-cds").param("group", "ROLE").param("includeInactive", "true"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin")
    void CSRF_토큰이_없는_변경_요청은_403() throws Exception {
        mockMvc.perform(post("/api/com-cd-groups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codeGroup\":\"X\",\"groupName\":\"X\",\"sortOrder\":1,\"useYn\":\"Y\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin")
    void 공통코드_그룹과_코드를_저장_조회_삭제한다() throws Exception {
        mockMvc.perform(post("/api/com-cd-groups").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codeGroup\":\"SEASON\",\"groupName\":\"시즌\",\"sortOrder\":9,\"useYn\":\"Y\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/com-cds").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codeGroup\":\"SEASON\",\"codeValue\":\"SS\",\"codeName\":\"봄여름\",\"sortOrder\":1,\"useYn\":\"N\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/com-cd-groups"))
                .andExpect(jsonPath("$[*].codeGroup", hasItem("SEASON")));
        // 사용 중지 코드는 select용 조회에는 빠지고, 관리 화면 조회(includeInactive)에는 나온다
        mockMvc.perform(get("/api/com-cds").param("group", "SEASON"))
                .andExpect(content().json("[]"));
        mockMvc.perform(get("/api/com-cds").param("group", "SEASON").param("includeInactive", "true"))
                .andExpect(jsonPath("$[0].codeValue").value("SS"));

        mockMvc.perform(delete("/api/com-cd-groups/SEASON").with(csrf())).andExpect(status().isOk());
        mockMvc.perform(get("/api/com-cds").param("group", "SEASON").param("includeInactive", "true"))
                .andExpect(content().json("[]"));
    }

    @Test
    @WithMockUser(username = "admin")
    void 사용자_응답에는_비밀번호가_없고_본인은_수정_불가로_내려준다() throws Exception {
        mockMvc.perform(post("/api/users").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"kim\",\"userNm\":\"김\",\"password\":\"pw1234\",\"role\":\"USER\",\"useYn\":\"Y\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("password"))));

        mockMvc.perform(post("/api/users").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"lee\",\"userNm\":\"이\",\"password\":\"pw\",\"role\":\"ROOT\",\"useYn\":\"Y\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/users"))
                .andExpect(jsonPath("$[?(@.username=='admin')].self").value(hasItem(true)))
                .andExpect(jsonPath("$[?(@.username=='admin')].editable").value(hasItem(false)))
                .andExpect(jsonPath("$[?(@.username=='kim')].editable").value(hasItem(true)));
    }

    @Test
    @WithMockUser(username = "admin")
    void 자기_자신은_삭제할_수_없다() throws Exception {
        mockMvc.perform(delete("/api/users/1").with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "admin")
    void 사용자별_권한을_통째로_교체한다() throws Exception {
        String body = mockMvc.perform(post("/api/users").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"park\",\"userNm\":\"박\",\"password\":\"pw1234\",\"role\":\"USER\",\"useYn\":\"Y\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Number userId = JsonPath.read(body, "$.id");

        mockMvc.perform(post("/api/permissions/" + userId).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[{\"programId\":\"program-mng\",\"searchYn\":\"Y\"}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/permissions/" + userId))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].searchYn").value("Y"))
                .andExpect(jsonPath("$[0].saveYn").value("N"));
    }
}
