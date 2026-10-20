package com.sjinc.trendanalysis.mvc;

import com.sjinc.trendanalysis.dto.program.MenuGroup;
import com.sjinc.trendanalysis.service.program.ProgramService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.security.Principal;
import java.util.List;

/**
 * 사이드바 메뉴 — 로그인한 사용자가 권한을 가진 프로그램을 메뉴 그룹(공통코드 MENU_GROUP)별로 얹는다.
 * 모든 화면이 같은 조각(fragments/sidebar)을 쓰므로 화면 컨트롤러마다 넣지 않고 여기서 채운다.
 */
@ControllerAdvice(assignableTypes = ViewController.class)
@RequiredArgsConstructor
public class SidebarModelAdvice {

    private final ProgramService programService;

    @ModelAttribute("menuGroups")
    public List<MenuGroup> menuGroups(Principal principal) {
        return programService.getSidebarGroups(principal != null ? principal.getName() : null);
    }
}
