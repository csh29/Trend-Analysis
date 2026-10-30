package com.sjinc.trendanalysis.security;

import com.sjinc.trendanalysis.domain.ProgramButton;
import com.sjinc.trendanalysis.repository.UserProgramPermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * 사이드바 메뉴 노출과 같은 UserProgramPermission 데이터로 프로그램 화면·API의 실제 접근도 막는다.
 * 메뉴를 숨기는 것만으로는 접근 제어가 되지 않으므로 서버에서도 이 빈으로 검증한다.
 */
@Component("programAccess")
@RequiredArgsConstructor
public class ProgramAccessGuard {

    private final UserProgramPermissionRepository userProgramPermissionRepository;

    public boolean check(String username, String programId) {
        if (username == null) {
            return false;
        }
        return userProgramPermissionRepository.findAccessiblePrograms(username).stream()
                .anyMatch(program -> program.getProgramId().equals(programId));
    }

    /**
     * 그 프로그램의 버튼 권한까지 서버에서 확인한다 — 툴바에서 버튼을 숨기는 것만으로는 API를 막지 못한다.
     * 되돌리기 어려운 동작(트렌드 주 마감·마감 취소)에 쓴다. 권한이 없으면 AccessDeniedException(403).
     */
    public void requireButton(String username, String programId, ProgramButton button) {
        boolean allowed = username != null && userProgramPermissionRepository.findByUserUsernameAndProgramProgramId(username, programId)
                .map(p -> p.allows(button)).orElse(false);
        if (!allowed) {
            throw new AccessDeniedException("이 버튼을 쓸 권한이 없습니다: " + programId + " " + button);
        }
    }
}
