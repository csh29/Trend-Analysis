package com.sjinc.trendanalysis.mvc;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** admin은 DataInitializer가 만들고 관리 화면 5종 권한을 모두 준다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:viewtest")
@AutoConfigureMockMvc
class ViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 로그인하지_않으면_화면은_로그인_페이지로_보낸다() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"));
    }

    @Test
    @WithMockUser(username = "admin")
    void 홈_사이드바에_관리_그룹과_권한있는_화면을_그린다() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("home"))
                .andExpect(content().string(containsString("id=\"navGroupMNG\"")))
                .andExpect(content().string(containsString(">관리<")))
                .andExpect(content().string(containsString("data-menu=\"program-mng\"")))
                .andExpect(content().string(containsString("data-menu=\"user-mng\"")))
                .andExpect(content().string(containsString("data-menu=\"user-permission-mng\"")))
                .andExpect(content().string(containsString("data-menu=\"com-cd-master-mng\"")))
                .andExpect(content().string(containsString("data-menu=\"com-cd-mng\"")))
                // 상단바 로그인 사용자 표시(TopbarModelAdvice)
                .andExpect(content().string(containsString("관리자(admin)")));
    }

    @Test
    @WithMockUser(username = "nobody")
    void 권한이_없는_사용자는_메뉴가_없고_등록된_화면도_열지_못한다() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("data-menu=\"program-mng\""))));
        mockMvc.perform(get("/program/program-mng"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin")
    void 등록된_화면은_프로그램명과_허용된_공통버튼을_그린다() throws Exception {
        mockMvc.perform(get("/program/user-permission-mng"))
                .andExpect(status().isOk())
                .andExpect(view().name("program/user-permission-mng"))
                .andExpect(content().string(containsString("사용자별 권한관리")))
                .andExpect(content().string(containsString("id=\"btnSearch\"")))
                // 사용자별 권한관리는 신규·삭제 버튼을 쓰지 않는다(DataInitializer)
                .andExpect(content().string(not(containsString("id=\"btnAdd\""))));
    }

    @Test
    @WithMockUser(username = "admin")
    void 템플릿이_없거나_이름이_잘못된_화면은_화면없음으로_보낸다() throws Exception {
        mockMvc.perform(get("/program/no-such-page"))
                .andExpect(view().name("program-not-found"));
        // 인코딩된 슬래시는 Spring Security 방화벽(StrictHttpFirewall)이 컨트롤러에 닿기 전에 400으로 막는다
        mockMvc.perform(get("/program/..%2Fhome"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/program/Home_1"))
                .andExpect(view().name("program-not-found"));
    }
}
