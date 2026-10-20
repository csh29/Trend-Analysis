package com.sjinc.trendanalysis.config;

import com.sjinc.trendanalysis.domain.ComCd;
import com.sjinc.trendanalysis.domain.ComCdGroup;
import com.sjinc.trendanalysis.domain.Program;
import com.sjinc.trendanalysis.domain.User;
import com.sjinc.trendanalysis.domain.UserProgramPermission;
import com.sjinc.trendanalysis.repository.ComCdGroupRepository;
import com.sjinc.trendanalysis.repository.ComCdRepository;
import com.sjinc.trendanalysis.repository.ProgramRepository;
import com.sjinc.trendanalysis.repository.UserProgramPermissionRepository;
import com.sjinc.trendanalysis.repository.UserRepository;
import com.sjinc.trendanalysis.service.analysis.TrendCodes;
import com.sjinc.trendanalysis.service.program.ProgramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * 기동 시 기본 데이터(공통코드·관리 프로그램·초기 관리자)를 "없을 때만" 심는다. 파일 DB라 이미 있는 값은 화면에서
 * 사람이 고쳤을 수 있으므로 덮어쓰지 않는다. 비밀번호는 소스에 두지 않고 최초 생성 때 무작위로 만들어 로그로만 알린다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private static final String INITIAL_PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int INITIAL_PASSWORD_LENGTH = 20;

    private final UserRepository userRepository;
    private final ProgramRepository programRepository;
    private final UserProgramPermissionRepository userProgramPermissionRepository;
    private final ComCdGroupRepository comCdGroupRepository;
    private final ComCdRepository comCdRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        seedRoleIfAbsent();
        seedMenuGroupIfAbsent();
        seedTrendMenuGroupIfAbsent();
        seedTrendCodesIfAbsent();
        List<Program> added = seedManagementProgramsIfAbsent();
        seedTrendProgramsIfAbsent(added);
        if (seedAdminIfAbsent()) return; // 새 관리자는 이미 모든 프로그램 권한을 받았다
        // 기존 DB에 새로 등록한 프로그램은 관리자에게 바로 권한을 준다(사용자별 권한관리에서 조정).
        userRepository.findByUsername("admin").ifPresent(admin ->
                added.forEach(program -> userProgramPermissionRepository.save(allButtonsPermission(admin, program))));
    }

    /** 역할(공통코드 ROLE) — 사용자 관리 화면의 select. 정렬순서가 등급이다(작을수록 높음). */
    private void seedRoleIfAbsent() {
        if (comCdGroupRepository.existsById("ROLE")) return;
        comCdGroupRepository.save(ComCdGroup.builder()
                .codeGroup("ROLE").groupName("역할").sortOrder(1).useYn("Y").build());
        comCdRepository.save(ComCd.builder()
                .codeGroup("ROLE").codeValue("ADMIN").codeName("ADMIN").sortOrder(1).useYn("Y").build());
        comCdRepository.save(ComCd.builder()
                .codeGroup("ROLE").codeValue("USER").codeName("USER").sortOrder(2).useYn("Y").build());
        log.info("공통코드 ROLE(역할)을 추가했습니다.");
    }

    /** 사이드바 메뉴 그룹(공통코드 MENU_GROUP) — 프로그램 관리 화면의 select. 코드명이 사이드바 그룹 이름이 된다. */
    private void seedMenuGroupIfAbsent() {
        if (comCdGroupRepository.existsById(ProgramService.MENU_GROUP_CODE_GROUP)) return;
        comCdGroupRepository.save(ComCdGroup.builder()
                .codeGroup(ProgramService.MENU_GROUP_CODE_GROUP).groupName("메뉴 그룹").sortOrder(2).useYn("Y").build());
        comCdRepository.save(ComCd.builder().codeGroup(ProgramService.MENU_GROUP_CODE_GROUP)
                .codeValue(Program.GROUP_MNG).codeName("관리").sortOrder(99).useYn("Y")
                .remark("기능 그룹은 이 코드보다 작은 정렬순서로 추가한다(사이드바에서 관리가 맨 아래)").build());
        log.info("공통코드 MENU_GROUP(메뉴 그룹)을 추가했습니다.");
    }

    /** 트렌드 분석 메뉴 그룹 코드. MENU_GROUP 그룹이 이미 있는 DB에도 들어가야 하므로 코드 단위로 확인한다. */
    private void seedTrendMenuGroupIfAbsent() {
        if (comCdRepository.existsByCodeGroupAndCodeValue(ProgramService.MENU_GROUP_CODE_GROUP, Program.GROUP_TREND)) return;
        comCdRepository.save(ComCd.builder().codeGroup(ProgramService.MENU_GROUP_CODE_GROUP)
                .codeValue(Program.GROUP_TREND).codeName("트렌드 분석").sortOrder(1).useYn("Y").build());
        log.info("메뉴 그룹 {}(트렌드 분석)을 추가했습니다.", Program.GROUP_TREND);
    }

    /** 트렌드 항목 표준 코드(카테고리·소재·컬러·실루엣) 초안. 그룹 단위로 없을 때만 넣는다 — 화면에서 고친 값을 덮어쓰지 않는다. */
    private void seedTrendCodesIfAbsent() {
        List<String> groups = List.of(TrendCodes.CATEGORY, TrendCodes.MATERIAL, TrendCodes.COLOR, TrendCodes.SILHOUETTE);
        List<String> names = List.of("트렌드 카테고리", "트렌드 소재", "트렌드 컬러", "트렌드 실루엣");
        for (int g = 0; g < groups.size(); g++) {
            String group = groups.get(g);
            if (comCdGroupRepository.existsById(group)) continue;
            comCdGroupRepository.save(ComCdGroup.builder()
                    .codeGroup(group).groupName(names.get(g)).sortOrder(10 + g).useYn("Y")
                    .remark("AI 정형화(P-03)가 이 코드값 중에서만 고른다. 이름의 괄호는 같은 뜻의 다른 표기 힌트").build());
            int sort = 1;
            for (String[] code : TrendCodes.SEED.get(group)) {
                comCdRepository.save(ComCd.builder().codeGroup(group)
                        .codeValue(code[0]).codeName(code[1]).sortOrder(sort++).useYn("Y").build());
            }
            log.info("공통코드 {}({})를 추가했습니다.", group, names.get(g));
        }
    }

    /** 트렌드 분석 그룹 화면. 이미지 업로드는 "업로드"를 기타1 버튼으로 쓴다(저장과 동작이 달라서). 화면별로 없을 때만 넣는다. */
    private void seedTrendProgramsIfAbsent(List<Program> added) {
        if (!programRepository.existsById("img-upload")) {
            added.add(programRepository.save(Program.builder()
                    .programId("img-upload").programNm("이미지 업로드").url("/program/img-upload").sortOrder(1).useYn("Y")
                    .menuGroup(Program.GROUP_TREND).icon("🖼")
                    .searchYn("Y").newYn("N").saveYn("N").deleteYn("Y").resetYn("Y").etc1Nm("업로드")
                    .build()));
            log.info("프로그램 img-upload(이미지 업로드)를 등록했습니다.");
        }
        if (!programRepository.existsById("text-upload")) {
            added.add(programRepository.save(Program.builder()
                    .programId("text-upload").programNm("텍스트 등록").url("/program/text-upload").sortOrder(2).useYn("Y")
                    .menuGroup(Program.GROUP_TREND).icon("📝")
                    .searchYn("Y").newYn("Y").saveYn("N").deleteYn("Y").resetYn("Y")
                    .build()));
            log.info("프로그램 text-upload(텍스트 등록)를 등록했습니다.");
        }
    }

    /** 관리 그룹 화면 5종. 없을 때만 넣어 사람이 고친 이름·순서를 덮어쓰지 않는다. */
    private List<Program> seedManagementProgramsIfAbsent() {
        List<Program> added = new ArrayList<>();
        addIfAbsent(added, "program-mng", "프로그램 관리", "▤", 1, "Y", "Y", "Y", "Y", "Y");
        addIfAbsent(added, "user-mng", "사용자 관리", "👤", 2, "Y", "Y", "Y", "Y", "Y");
        addIfAbsent(added, "user-permission-mng", "사용자별 권한관리", "🔑", 3, "Y", "N", "Y", "N", "Y");
        addIfAbsent(added, "com-cd-master-mng", "공통코드마스터 관리", "▦", 4, "Y", "Y", "Y", "Y", "Y");
        addIfAbsent(added, "com-cd-mng", "공통코드 관리", "☰", 5, "Y", "Y", "Y", "Y", "Y");
        if (!added.isEmpty()) {
            log.info("관리 프로그램 {}개를 등록했습니다: {}", added.size(), added.stream().map(Program::getProgramId).toList());
        }
        return added;
    }

    private void addIfAbsent(List<Program> added, String id, String name, String icon, int sort,
                             String search, String newYn, String save, String delete, String reset) {
        if (programRepository.existsById(id)) return;
        added.add(programRepository.save(Program.builder()
                .programId(id).programNm(name).url("/program/" + id).sortOrder(sort).useYn("Y")
                .menuGroup(Program.GROUP_MNG).icon(icon)
                .searchYn(search).newYn(newYn).saveYn(save).deleteYn(delete).resetYn(reset)
                .build()));
    }

    /** 초기 관리자. 사용자가 한 명도 없을 때만 만들고, 만들었으면 true. */
    private boolean seedAdminIfAbsent() {
        if (userRepository.count() > 0) return false;
        String initialPassword = generateInitialPassword();
        User admin = userRepository.save(User.builder()
                .username("admin")
                .userNm("관리자")
                .password(passwordEncoder.encode(initialPassword))
                .role("ADMIN")
                .build());
        log.warn("초기 관리자 계정을 생성했습니다. username=admin, password={} "
                + "(이 로그에만 한 번 출력됩니다 — 로그인 후 반드시 비밀번호를 변경하세요)", initialPassword);
        programRepository.findAll().forEach(program ->
                userProgramPermissionRepository.save(allButtonsPermission(admin, program)));
        return true;
    }

    /** 혼동되기 쉬운 문자(0/O, 1/l/I 등)를 뺀 문자셋에서 무작위로 뽑은 초기 비밀번호. */
    private String generateInitialPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder(INITIAL_PASSWORD_LENGTH);
        for (int i = 0; i < INITIAL_PASSWORD_LENGTH; i++) {
            password.append(INITIAL_PASSWORD_CHARS.charAt(random.nextInt(INITIAL_PASSWORD_CHARS.length())));
        }
        return password.toString();
    }

    private static UserProgramPermission allButtonsPermission(User user, Program program) {
        return UserProgramPermission.builder()
                .user(user)
                .program(program)
                .searchYn("Y").newYn("Y").saveYn("Y").deleteYn("Y").resetYn("Y")
                .etc1Yn("Y").etc2Yn("Y").etc3Yn("Y").etc4Yn("Y").etc5Yn("Y")
                .build();
    }
}
