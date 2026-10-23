package com.sjinc.trendanalysis.controller;

import com.sjinc.trendanalysis.dto.user.HomeLayout;
import com.sjinc.trendanalysis.dto.user.PasswordChangeRequest;
import com.sjinc.trendanalysis.dto.user.UserRequest;
import com.sjinc.trendanalysis.dto.user.UserResponse;
import com.sjinc.trendanalysis.security.RequiresProgram;
import com.sjinc.trendanalysis.service.user.HomeLayoutService;
import com.sjinc.trendanalysis.service.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * 사용자 관리·사용자별 권한관리 화면이 호출하는 REST API. 목록 조회는 두 화면 중 하나의 권한이면 되지만,
 * 추가/수정/삭제는 "user-mng" 권한이 필요하다 — 아니면 임의 계정의 비밀번호·역할을 바꾸는 권한 상승 경로가 된다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final HomeLayoutService homeLayoutService;

    @GetMapping
    @RequiresProgram({"user-mng", "user-permission-mng"})
    public List<UserResponse> getUsers(Principal principal) {
        return userService.getAllUsers(principal.getName());
    }

    @GetMapping("/assignable-roles")
    @RequiresProgram("user-mng")
    public List<String> getAssignableRoles(Principal principal) {
        return userService.getAssignableRoles(principal.getName());
    }

    @PostMapping
    @RequiresProgram("user-mng")
    public UserResponse saveUser(@RequestBody UserRequest request, Principal principal) {
        return userService.saveUser(request, principal.getName());
    }

    /**
     * 상단바 "비밀번호 변경" — 로그인한 본인 계정만 바꾼다. 대상을 Principal로 정하므로 다른 계정을 건드릴
     * 수 없고, 모든 사용자가 써야 하는 기능이라 {@code @RequiresProgram}을 걸지 않는다(로그인만 필요).
     */
    @PostMapping("/me/password")
    public void changeMyPassword(@RequestBody PasswordChangeRequest request, Principal principal) {
        userService.changeMyPassword(principal.getName(), request);
    }

    /**
     * 홈 배치(개인화) — 본인 것만, 로그인만 필요(비밀번호 변경과 같은 이유). 대상은 Principal이라 다른 계정 배치를 건드릴 수 없다.
     * 저장한 적이 없으면 기본 배치, DELETE는 "기본 배치로".
     */
    @GetMapping("/me/home-layout")
    public HomeLayout getMyHomeLayout(Principal principal) {
        return homeLayoutService.get(principal.getName());
    }

    @PutMapping("/me/home-layout")
    public HomeLayout saveMyHomeLayout(@RequestBody HomeLayout layout, Principal principal) {
        return homeLayoutService.save(principal.getName(), layout);
    }

    @DeleteMapping("/me/home-layout")
    public HomeLayout resetMyHomeLayout(Principal principal) {
        return homeLayoutService.reset(principal.getName());
    }

    @DeleteMapping("/{id}")
    @RequiresProgram("user-mng")
    public void deleteUser(@PathVariable Long id, Principal principal) {
        userService.deleteUser(id, principal.getName());
    }
}
