package com.sjinc.trendanalysis.controller;

import com.sjinc.trendanalysis.domain.User;
import com.sjinc.trendanalysis.repository.UserPreferenceRepository;
import com.sjinc.trendanalysis.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 홈 배치 개인화(2026-10-23) — 본인 것만, 로그인만 필요, 화면이 보낸 값은 걸러서 저장한다.
 * 사용자마다 다른 이름을 써서 테스트끼리 저장 값이 섞이지 않게 한다(같은 클래스는 DB를 공유).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:homelayouttest")
@AutoConfigureMockMvc
class HomeLayoutApiTest {

    private static final String API = "/api/users/me/home-layout";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserPreferenceRepository preferenceRepository;

    @BeforeEach
    void users() {
        for (String name : new String[]{"layout-a", "layout-b", "layout-c", "layout-d", "layout-e"}) {
            if (userRepository.findByUsername(name).isEmpty()) {
                userRepository.save(User.builder().username(name).userNm(name).password("x").role("USER").useYn("Y").build());
            }
        }
    }

    private static String layout(String panels, boolean collapsed, boolean autoRefresh) {
        return "{\"panels\":[" + panels + "],\"summaryCollapsed\":" + collapsed + ",\"autoRefresh\":" + autoRefresh + "}";
    }

    @Test
    void 저장한_적이_없으면_기본_배치() throws Exception {
        mockMvc.perform(get(API).with(user("layout-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.panels[0].id").value("trend"))
                .andExpect(jsonPath("$.panels[0].width").value("half"))
                .andExpect(jsonPath("$.panels[2].id").value("chat"))
                .andExpect(jsonPath("$.panels[2].width").value("full"))
                .andExpect(jsonPath("$.summaryCollapsed").value(false))
                .andExpect(jsonPath("$.autoRefresh").value(true));
    }

    @Test
    void 저장하면_내_계정에만_남고_다른_사용자는_기본_배치() throws Exception {
        // 챗봇 맨 위, 분석 현황 숨김 — 빠진 패널(trend)은 기본값으로 끝에 붙는다
        String body = layout("{\"id\":\"chat\",\"width\":\"full\",\"hidden\":false},"
                + "{\"id\":\"analysis\",\"width\":\"half\",\"hidden\":true}", true, false);
        mockMvc.perform(put(API).with(user("layout-b")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.panels[0].id").value("chat"))
                .andExpect(jsonPath("$.panels[2].id").value("trend"));

        mockMvc.perform(get(API).with(user("layout-b")))
                .andExpect(jsonPath("$.panels[0].id").value("chat"))
                .andExpect(jsonPath("$.panels[1].hidden").value(true))
                .andExpect(jsonPath("$.summaryCollapsed").value(true))
                .andExpect(jsonPath("$.autoRefresh").value(false));
        mockMvc.perform(get(API).with(user("layout-c")))
                .andExpect(jsonPath("$.panels[0].id").value("trend"));

        // 다시 저장하면 한 행을 고친다(사용자·키당 한 행)
        mockMvc.perform(put(API).with(user("layout-b")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(layout("{\"id\":\"trend\",\"width\":\"full\",\"hidden\":false}", false, true)))
                .andExpect(status().isOk());
        Long userId = userRepository.findByUsername("layout-b").orElseThrow().getId();
        assertThat(preferenceRepository.findAll().stream().filter(p -> p.getUser().getId().equals(userId))).hasSize(1);
    }

    @Test
    void 기본_배치로_되돌리면_저장한_배치를_지운다() throws Exception {
        mockMvc.perform(put(API).with(user("layout-d")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(layout("{\"id\":\"chat\",\"width\":\"half\",\"hidden\":false}", false, true)))
                .andExpect(status().isOk());
        mockMvc.perform(delete(API).with(user("layout-d")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.panels[0].id").value("trend"));
        assertThat(preferenceRepository.findByUserUsernameAndPrefKey("layout-d", "home-layout")).isEmpty();
    }

    @Test
    void 화면이_보낸_잘못된_배치는_400() throws Exception {
        String[] bad = {
                layout("{\"id\":\"admin-panel\",\"width\":\"half\",\"hidden\":false}", false, true),        // 모르는 패널
                layout("{\"id\":\"trend\",\"width\":\"half\",\"hidden\":false},{\"id\":\"trend\",\"width\":\"full\",\"hidden\":false}", false, true), // 두 번
                layout("{\"id\":\"trend\",\"width\":\"wide\",\"hidden\":false}", false, true),               // 넓이 값
                layout("{\"id\":\"chat\",\"width\":\"full\",\"hidden\":true}", false, true),                  // 챗봇 숨김
                "{\"summaryCollapsed\":true}"                                                                   // 패널 없음
        };
        for (String body : bad) {
            mockMvc.perform(put(API).with(user("layout-e")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(preferenceRepository.findByUserUsernameAndPrefKey("layout-e", "home-layout")).isEmpty();
    }

    @Test
    void 로그인만_필요하고_CSRF는_검사한다() throws Exception {
        mockMvc.perform(get(API)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(API).with(user("layout-a")).contentType(MediaType.APPLICATION_JSON)
                        .content(layout("{\"id\":\"chat\",\"width\":\"full\",\"hidden\":false}", false, true)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin")
    void 사용자를_지우면_그_사용자의_설정도_지운다() throws Exception {
        if (userRepository.findByUsername("layout-gone").isEmpty()) {
            userRepository.save(User.builder().username("layout-gone").userNm("x").password("x").role("USER").useYn("Y").build());
        }
        mockMvc.perform(put(API).with(user("layout-gone")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(layout("{\"id\":\"chat\",\"width\":\"full\",\"hidden\":false}", false, true)))
                .andExpect(status().isOk());
        Long id = userRepository.findByUsername("layout-gone").orElseThrow().getId();
        mockMvc.perform(delete("/api/users/" + id).with(csrf())).andExpect(status().isOk());
        assertThat(userRepository.findByUsername("layout-gone")).isEmpty();
        assertThat(preferenceRepository.findByUserUsernameAndPrefKey("layout-gone", "home-layout")).isEmpty();
    }

    @Test
    @WithMockUser(username = "admin")
    void 홈_화면에_내_배치를_넣어_보낸다() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"homeGrid\"")))
                .andExpect(content().string(containsString("data-layout=\"{&quot;panels&quot;")))
                .andExpect(content().string(containsString("data-panel=\"chat\"")));
    }
}
