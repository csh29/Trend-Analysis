package com.sjinc.trendanalysis.service.program;

import com.sjinc.trendanalysis.domain.ComCd;
import com.sjinc.trendanalysis.domain.Program;
import com.sjinc.trendanalysis.domain.ProgramButton;
import com.sjinc.trendanalysis.dto.program.MenuGroup;
import com.sjinc.trendanalysis.dto.program.PageButtonView;
import com.sjinc.trendanalysis.dto.program.ProgramRequest;
import com.sjinc.trendanalysis.repository.ComCdRepository;
import com.sjinc.trendanalysis.repository.ProgramRepository;
import com.sjinc.trendanalysis.repository.UserProgramPermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 프로그램(메뉴) 조회·저장·삭제와, 사이드바 메뉴·화면 툴바 버튼 구성을 담당한다. */
@Service
@RequiredArgsConstructor
public class ProgramService {

    /** 사이드바 그룹의 출처. 프로그램 관리 화면의 "메뉴 그룹" select도 이 그룹을 쓴다. */
    public static final String MENU_GROUP_CODE_GROUP = "MENU_GROUP";

    /** 사이드바를 접었을 때 보일 그룹 아이콘. 없는 그룹은 기본 아이콘. */
    private static final Map<String, String> GROUP_ICONS = Map.of(Program.GROUP_MNG, "⚙", Program.GROUP_TREND, "📈");
    private static final String DEFAULT_GROUP_ICON = "▦";

    private final ProgramRepository programRepository;
    private final UserProgramPermissionRepository userProgramPermissionRepository;
    private final ComCdRepository comCdRepository;

    /** 프로그램 전체 목록. 프로그램 관리 화면의 그리드, 사용자별 권한관리 화면의 체크리스트 등에서 공용으로 쓰인다. */
    @Transactional(readOnly = true)
    public List<Program> getAllPrograms() {
        return programRepository.findAllSorted();
    }

    /**
     * 사이드바 메뉴 — 공통코드 MENU_GROUP 순서대로 그룹을 만들고, 로그인 사용자가 권한을 가진 사용중 프로그램을 넣는다.
     * 프로그램이 없는 그룹은 사이드바가 그리지 않는다.
     */
    @Transactional(readOnly = true)
    public List<MenuGroup> getSidebarGroups(String username) {
        if (username == null || username.isBlank()) {
            return List.of();
        }
        List<Program> programs = userProgramPermissionRepository.findAccessiblePrograms(username);
        List<ComCd> groups = comCdRepository.findByCodeGroupAndUseYnOrderBySortOrderAsc(MENU_GROUP_CODE_GROUP, "Y");
        return groups.stream()
                .map(group -> new MenuGroup(
                        "navGroup" + group.getCodeValue(),
                        group.getCodeName(),
                        GROUP_ICONS.getOrDefault(group.getCodeValue(), DEFAULT_GROUP_ICON),
                        true,
                        programs.stream().filter(p -> group.getCodeValue().equals(p.sidebarGroup())).toList()))
                .toList();
    }

    /**
     * 화면 툴바에 그릴 공통 버튼 — 프로그램에 지정된 버튼 중 이 사용자에게 허용된 것만 ProgramButton 순서대로 돌려준다.
     * Program 테이블에 없는 화면이면 empty, 권한 행이 없으면 빈 목록이다.
     */
    @Transactional(readOnly = true)
    public Optional<List<PageButtonView>> getPageButtons(String username, String programId) {
        return programRepository.findById(programId).map(program -> userProgramPermissionRepository
                .findByUserUsernameAndProgramProgramId(username, programId)
                .map(perm -> Arrays.stream(ProgramButton.values())
                        .filter(button -> program.uses(button) && perm.allows(button))
                        .map(button -> PageButtonView.of(button, program.buttonLabel(button)))
                        .toList())
                .orElse(List.of()));
    }

    @Transactional(readOnly = true)
    public Optional<Program> getProgram(String programId) {
        return programRepository.findById(programId);
    }

    /** 프로그램 관리 화면의 "저장" 버튼. request.id()가 이미 존재하는 program_id면 수정, 아니면 신규 등록. */
    @Transactional
    public Program saveProgram(ProgramRequest request) {
        if (request.id() == null || request.id().isBlank() || request.programNm() == null || request.programNm().isBlank()) {
            throw new IllegalArgumentException("프로그램ID, 프로그램명은 필수입니다.");
        }
        Program program = Program.builder()
                .programId(request.id().trim())
                .programNm(request.programNm())
                .url(request.url())
                .sortOrder(request.sortOrder())
                .useYn(request.useYn())
                .searchYn(yn(request.searchYn()))
                .newYn(yn(request.newYn()))
                .saveYn(yn(request.saveYn()))
                .deleteYn(yn(request.deleteYn()))
                .resetYn(yn(request.resetYn()))
                .etc1Nm(blankToNull(request.etc1Nm()))
                .etc2Nm(blankToNull(request.etc2Nm()))
                .etc3Nm(blankToNull(request.etc3Nm()))
                .etc4Nm(blankToNull(request.etc4Nm()))
                .etc5Nm(blankToNull(request.etc5Nm()))
                .menuGroup(blankToNull(request.menuGroup()))
                .icon(blankToNull(request.icon()))
                .build();
        return programRepository.save(program);
    }

    /** 프로그램 관리 화면의 "삭제" 버튼. 그 프로그램을 가리키는 사용자 권한도 함께 지운다(FK). */
    @Transactional
    public void deleteProgram(String id) {
        userProgramPermissionRepository.deleteByProgramId(id);
        programRepository.deleteById(id);
    }

    private static String yn(String value) {
        return "Y".equals(value) ? "Y" : "N";
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
