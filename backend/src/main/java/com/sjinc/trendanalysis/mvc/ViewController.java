package com.sjinc.trendanalysis.mvc;

import com.sjinc.trendanalysis.domain.Program;
import com.sjinc.trendanalysis.security.ProgramAccessGuard;
import com.sjinc.trendanalysis.service.home.HomeService;
import com.sjinc.trendanalysis.service.user.HomeLayoutService;
import com.sjinc.trendanalysis.service.program.ProgramService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** REST API(controller 패키지)와 구분되는, 화면(뷰)을 반환하는 페이지 컨트롤러. */
@Controller
@RequiredArgsConstructor
public class ViewController {

    private final ResourceLoader resourceLoader;
    private final ProgramService programService;
    private final ProgramAccessGuard programAccess;
    private final HomeService homeService;
    private final HomeLayoutService homeLayoutService;

    /**
     * 화면 이름으로 허용하는 형태. 경로 값을 템플릿 경로에 그대로 붙이므로 {@code ..}·{@code /}로 templates/program/
     * 바깥을 렌더링하지 못하게 막는다(방화벽에만 기대지 않는다). 실제 화면 이름은 소문자·숫자·하이픈뿐이다.
     */
    private static final Pattern VIEW_NAME = Pattern.compile("[a-z0-9-]+");

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @GetMapping("/")
    public String home(Principal principal, Model model) {
        model.addAttribute("activeMenu", "home");
        model.addAttribute("home", homeService.getHome());
        // 사용자별 홈 배치(JSON) — 화면 JS가 처음 그릴 때 쓴다(따로 불러오면 기본 배치가 잠깐 보였다가 바뀐다)
        model.addAttribute("homeLayout", homeLayoutService.getJson(principal.getName()));
        return "home";
    }

    /**
     * 프로그램 화면을 하나의 경로에서 처리한다. "/program/" 뒤 URL을 그대로 templates/program/의
     * 템플릿 이름으로 쓰므로, 화면을 추가할 때 이 컨트롤러는 고치지 않고 같은 이름의 템플릿과
     * 프로그램 관리 등록만 하면 된다.
     */
    @GetMapping("/program/{*path}")
    public String programPage(@PathVariable String path, Principal principal, Model model) {
        String view = path.startsWith("/") ? path.substring(1) : path;
        if (!VIEW_NAME.matcher(view).matches()) {
            return "program-not-found";
        }

        // 화면 이름은 Program.programId와 같으므로, Program에 등록된 화면은 그 프로그램 권한이 있어야 연다.
        // 사이드바 노출 기준과 화면·API 접근 기준을 한 데이터로 맞추기 위해서다. 미등록 화면은 로그인만 요구한다.
        String username = principal != null ? principal.getName() : null;
        Optional<Program> program = programService.getProgram(view);
        if (program.isPresent() && !programAccess.check(username, view)) {
            throw new AccessDeniedException("이 화면에 접근할 권한이 없습니다: " + view);
        }

        model.addAttribute("activeMenu", view);
        // 화면 첫 줄(fragments/page-toolbar)의 프로그램명과 공통 버튼. 프로그램 관리에서 바꾼 이름이 사이드바·탭 제목과
        // 똑같이 따라오도록 DB 값을 쓰고, 버튼은 "프로그램이 쓰는 버튼 ∩ 이 사용자에게 허용된 버튼"이다.
        model.addAttribute("programNm", program.map(Program::getProgramNm).orElse(""));
        model.addAttribute("pageButtons", programService.getPageButtons(username, view).orElse(List.of()));

        Resource template = resourceLoader.getResource("classpath:/templates/program/" + view + ".html");
        if (!template.exists()) {
            return "program-not-found";
        }
        return "program/" + view;
    }
}
