package com.sjinc.trendanalysis.config;

import com.sjinc.trendanalysis.domain.ComCd;
import com.sjinc.trendanalysis.domain.ComCdGroup;
import com.sjinc.trendanalysis.domain.Program;
import com.sjinc.trendanalysis.domain.TrendItemValue;
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
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * 기동 시 기본 데이터(공통코드·관리 프로그램·초기 관리자)를 "없을 때만" 심는다. 파일 DB라 이미 있는 값은 화면에서
 * 사람이 고쳤을 수 있으므로 덮어쓰지 않는다. 비밀번호는 소스에 두지 않고 최초 생성 때 무작위로 만들어 로그로만 알린다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // 다른 기동 작업(샘플 등록 등)이 공통코드·프로그램을 쓰기 전에 먼저 심는다
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private static final String INITIAL_PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int INITIAL_PASSWORD_LENGTH = 20;
    private static final String RETIRED_RAG_PROGRAM = "rag-search";
    private static final String REANALYZE_LABEL = "재분석";

    private final UserRepository userRepository;
    private final ProgramRepository programRepository;
    private final UserProgramPermissionRepository userProgramPermissionRepository;
    private final ComCdGroupRepository comCdGroupRepository;
    private final ComCdRepository comCdRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional // 권한 일괄 삭제(@Modifying 쿼리)는 트랜잭션 안에서만 돈다
    public void run(String... args) {
        dropRetiredColumns();
        seedRoleIfAbsent();
        seedMenuGroupIfAbsent();
        seedTrendMenuGroupIfAbsent();
        seedTrendCodesIfAbsent();
        List<Program> added = seedManagementProgramsIfAbsent();
        seedTrendProgramsIfAbsent(added);
        addReanalyzeButtons();
        if (seedAdminIfAbsent()) return; // 새 관리자는 이미 모든 프로그램 권한을 받았다
        // 기존 DB에 새로 등록한 프로그램은 관리자에게 바로 권한을 준다(사용자별 권한관리에서 조정).
        userRepository.findByUsername("admin").ifPresent(admin ->
                added.forEach(program -> userProgramPermissionRepository.save(allButtonsPermission(admin, program))));
    }

    /**
     * 엔티티에서 뺀 컬럼을 기존 파일 DB에서 지운다 — ddl-auto=update는 컬럼을 지우지 않고, NOT NULL로 남으면 새 행 저장이 깨진다.
     * 분석이 다시 대기열에 들어가기(ApplicationReadyEvent) 전에 돈다.
     */
    private void dropRetiredColumns() {
        // 2026-10-23 사용자 결정: 어느 모델이 분석했는지는 보관할 필요가 없다(화면에서도 뺐다). 모델은 설정 claude.model과 로그로 안다
        jdbcTemplate.execute("ALTER TABLE trend_items DROP COLUMN IF EXISTS model");
        // 2026-10-23 사용자 결정: 감성(긍정·중립·부정)은 트렌드 판단에 쓰지 않아 뽑지도 저장하지도 않는다
        jdbcTemplate.execute("ALTER TABLE trend_items DROP COLUMN IF EXISTS sentiment");
        // 2026-10-23 사용자 결정: 트렌드 분석 유형에서 아이템명을 뺐다(아이템 종류와 뜻이 겹친다) — 지난 집계 줄도 지운다
        jdbcTemplate.update("DELETE FROM trend_weekly_stats WHERE value_type = ?", TrendItemValue.ITEM);
        // 2026-10-28: 값 종류 속성 RAG(챗봇 참고 집계)는 읽는 곳이 없다 — 공통코드 화면에 남은 값을 비운다
        jdbcTemplate.update("UPDATE com_cd_dtl SET attr1 = NULL WHERE code_group = ? AND attr1 = 'RAG'", TrendCodes.VALUE_TYPE);
    }

    /** 초안에 나중에 더한 코드 — 기존 DB에 없으면 그룹 끝에 넣는다(그룹 → 코드값). 화면에서 지운 코드도 다시 들어온다. */
    private static final java.util.Map<String, List<String>> NEW_CODES = java.util.Map.of(
            // 2026-10-23 사용자 결정: 무스탕을 따로 센다(몸판이 무스탕일 때만 — 깃·안감만이면 주 소재 규칙으로 빠진다)
            // 2026-10-28 정답셋(사람이 단 라벨)에 나왔는데 코드에 없던 값: 소재 레이온·아크릴, 실루엣 부츠컷
            // 2026-10-28 사용자 요청: 벨벳 기사의 소재가 비어 벨벳을 따로 센다
            TrendCodes.MATERIAL, List.of("SHEARLING", "RAYON", "ACRYLIC", "VELVET"),
            TrendCodes.SILHOUETTE, List.of("BOOTCUT"),
            // 2026-10-28 사용자 요청: 구르카 팬츠를 따로 센다(기타 하의로 빠졌다)
            TrendCodes.ITEM_TYPE, List.of("BOTTOM_GURKHA"),
            // 2026-10-28 사용자 요청: 패턴을 주간 집계 유형으로 센다(트렌드 분석 화면 유형 목록)
            TrendCodes.VALUE_TYPE, List.of(TrendItemValue.PATTERN, TrendItemValue.DETAIL));

    private void addNewCodes(String group) {
        List<ComCd> codes = comCdRepository.findByCodeGroupOrderBySortOrderAsc(group);
        int sort = codes.stream().mapToInt(c -> c.getSortOrder() == null ? 0 : c.getSortOrder()).max().orElse(0);
        for (String value : NEW_CODES.getOrDefault(group, List.of())) {
            if (codes.stream().anyMatch(c -> c.getCodeValue().equals(value))) continue;
            String[] code = TrendCodes.SEED.get(group).stream().filter(c -> c[0].equals(value)).findFirst().orElseThrow();
            comCdRepository.save(ComCd.builder().codeGroup(group).codeValue(value).codeName(code[1])
                    .attr1(code.length > 2 ? code[2] : null).sortOrder(++sort).useYn("Y").build());
            log.info("공통코드 {}에 {}({})를 추가했습니다.", group, value, code[1]);
        }
    }

    /** 초안에서 뺀 코드 — 기존 DB에 남아 있으면 지운다(그룹 → 코드값). */
    private static final java.util.Map<String, List<String>> RETIRED_CODES = java.util.Map.of(
            TrendCodes.VALUE_TYPE, List.of(TrendItemValue.ITEM),
            // 2026-10-28 사용자 결정: 데님 워싱은 블루·네이비·블랙으로 센다 — 잠깐 넣었던 컬러 데님을 지운다
            TrendCodes.COLOR, List.of("DENIM"),
            // 2026-10-29 사용자 요청: 카테고리는 의류만 — 예전 초안의 신발·가방·액세서리를 지운다(분석은 이미 기타로 옮긴다)
            TrendCodes.CATEGORY, List.of("SHOES", "BAG", "ACC"));

    private void deleteRetiredCodes(String group) {
        List<String> retired = RETIRED_CODES.getOrDefault(group, List.of());
        comCdRepository.findByCodeGroupOrderBySortOrderAsc(group).stream().filter(c -> retired.contains(c.getCodeValue()))
                .forEach(c -> {
                    comCdRepository.delete(c);
                    log.info("공통코드 {}의 {}({})를 지웠습니다.", group, c.getCodeValue(), c.getCodeName());
                });
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
        List<String> groups = List.of(TrendCodes.CATEGORY, TrendCodes.MATERIAL, TrendCodes.COLOR, TrendCodes.SILHOUETTE,
                TrendCodes.SEASON, TrendCodes.ITEM_TYPE, TrendCodes.VALUE_TYPE, TrendCodes.TREND_WORD, TrendCodes.GENDER,
                TrendCodes.PATTERN, TrendCodes.DETAIL);
        List<String> names = List.of("트렌드 카테고리", "트렌드 소재", "트렌드 컬러", "트렌드 실루엣", "트렌드 시즌", "트렌드 아이템 종류",
                "트렌드 값 종류", "유행 질문 단어", "트렌드 대상(성별)",
                "트렌드 패턴", "트렌드 디테일");
        for (int g = 0; g < groups.size(); g++) {
            String group = groups.get(g);
            if (comCdGroupRepository.existsById(group)) {
                fillAttributesIfBlank(group);
                renameOldDefaults(group);
                deleteRetiredCodes(group);
                addNewCodes(group);
                continue;
            }
            comCdGroupRepository.save(ComCdGroup.builder()
                    .codeGroup(group).groupName(names.get(g)).sortOrder(10 + g).useYn("Y")
                    .remark(GROUP_REMARKS.getOrDefault(group,
                            "AI 정형화(P-03)가 이 코드값 중에서만 고른다. 이름의 괄호는 같은 뜻의 다른 표기 힌트")).build());
            int sort = 1;
            for (String[] code : TrendCodes.SEED.get(group)) {
                comCdRepository.save(ComCd.builder().codeGroup(group)
                        .codeValue(code[0]).codeName(code[1]).attr1(code.length > 2 ? code[2] : null).sortOrder(sort++).useYn("Y").build());
            }
            log.info("공통코드 {}({})를 추가했습니다.", group, names.get(g));
        }
    }

    /** 그룹 비고 — 속성(attr1)을 쓰는 그룹은 무엇을 쓰는지 적는다. */
    private static final java.util.Map<String, String> GROUP_REMARKS = java.util.Map.of(
            TrendCodes.MATERIAL, "AI 정형화(P-03)가 이 코드값 중에서만 고른다. 속성 IMAGE = 사진의 질감으로 구분되는 소재(이미지만 있는 원문은 이것만)",
            TrendCodes.SILHOUETTE, "AI 정형화(P-03)가 이 코드값 중에서만 고른다. 속성 = 축(FIT·LENGTH·SHAPE),쓸 수 있는 카테고리(쉼표, 없으면 전체) — 축마다 하나만, 목록 밖 카테고리면 버린다",
            TrendCodes.CATEGORY, "AI 정형화(P-03)가 이 코드값 중에서만 고른다. 속성 = 질문에서 이 카테고리를 알아보는 일상 표현(쉼표)",
            TrendCodes.ITEM_TYPE, "AI 정형화(P-03)가 고른다. 코드값은 카테고리_종류(OUTER_PADDING) — 앞부분이 그 카테고리 코드여야 한다",
            TrendCodes.VALUE_TYPE, "트렌드 분석 화면 유형 목록·이름. 코드값은 집계 값 종류라 바꾸지 않는다",
            TrendCodes.PATTERN, "AI 정형화(P-03)가 아이템마다 0~1개 고른다 — 가장 눈에 띄는 무늬, 무늬가 없으면 무지",
            TrendCodes.DETAIL, "AI 정형화(P-03)가 아이템마다 0~3개 고른다 — 눈에 띄는 디자인 디테일. 속성 = 축(넥라인·여밈·소매밑단·장식·짜임가공), 목록에 없으면 기타 디테일",
            TrendCodes.GENDER, "AI 정형화(P-03)가 아이템마다 0~1개 고른다 — 입은 사람이 아니라 옷의 라인. 판단할 수 없으면 비운다",
            TrendCodes.TREND_WORD, "질문에 이 말(코드명)이 있으면 유행 질문으로 본다 — AI 질문 해석이 실패했을 때 쓰는 규칙");

    /**
     * 초안에서 이름을 바꾼 코드의 예전 기본 이름(그룹 → 코드값 → 예전 이름). 기존 DB에서 이름이 아직 예전 기본값이면 새 이름으로 바꾼다 —
     * 이름의 괄호는 AI가 다른 표기를 그 코드로 옮기는 힌트라, 기존 DB에도 들어가야 분석이 바뀐다.
     */
    private static final java.util.Map<String, java.util.Map<String, String>> OLD_DEFAULT_NAMES = java.util.Map.of(
            // 2026-10-23 사용자 결정: 스타디움(바시티) 재킷은 블루종으로 센다(기타 아우터로 빠졌다)
            // 2026-10-23 사용자 결정: 셋업은 수트·트레이닝 셋업만이다 — 플리스·니트 매칭 세트까지 셋업으로 빨려 들어가 이름에 범위를 넣었다
            // 2026-10-26 정답셋 기준: 가죽 아우터는 모양과 상관없이 레더 재킷
            TrendCodes.ITEM_TYPE, java.util.Map.of("OUTER_BLOUSON", "블루종(봄버·항공 점퍼)",
                    "SETUP_SUIT", "수트·셋업", "SETUP_TRACKSUIT", "트레이닝 셋업", "OUTER_LEATHER_JACKET", "레더 재킷(라이더)"));

    private void renameOldDefaults(String group) {
        java.util.Map<String, String> oldNames = OLD_DEFAULT_NAMES.get(group);
        if (oldNames == null) return;
        for (ComCd code : comCdRepository.findByCodeGroupOrderBySortOrderAsc(group)) {
            String newName = TrendCodes.SEED.get(group).stream().filter(c -> c[0].equals(code.getCodeValue()))
                    .map(c -> c[1]).findFirst().orElse(null);
            if (code.renameIfStill(oldNames.get(code.getCodeValue()), newName)) {
                comCdRepository.save(code);
                log.info("공통코드 {}의 {} 이름을 '{}'로 바꿨습니다.", group, code.getCodeValue(), newName);
            }
        }
    }

    /** 이미 있는 그룹: 초안에 속성이 있는데 DB 코드의 속성이 비었으면 채운다(공통코드 속성 칸 도입 전 DB, 2026-10-22). */
    private void fillAttributesIfBlank(String group) {
        java.util.Map<String, String> defaults = new java.util.HashMap<>();
        TrendCodes.SEED.get(group).stream().filter(c -> c.length > 2).forEach(c -> defaults.put(c[0], c[2]));
        if (defaults.isEmpty()) return;
        int filled = 0;
        for (ComCd code : comCdRepository.findByCodeGroupOrderBySortOrderAsc(group)) {
            String value = defaults.get(code.getCodeValue());
            if (code.fillAttr1IfBlank(value) || code.extendAttr1(value)) {
                comCdRepository.save(code);
                filled++;
            }
        }
        if (filled > 0) {
            log.info("공통코드 {}의 속성 {}개를 기본값으로 채웠습니다.", group, filled);
        }
    }

    /** 트렌드 분석 그룹 화면. 이미지 업로드는 "업로드"를 기타1 버튼으로 쓴다(저장과 동작이 달라서). 화면별로 없을 때만 넣는다. */
    private void seedTrendProgramsIfAbsent(List<Program> added) {
        if (!programRepository.existsById("img-upload")) {
            added.add(programRepository.save(Program.builder()
                    .programId("img-upload").programNm("이미지 업로드").url("/program/img-upload").sortOrder(1).useYn("Y")
                    .menuGroup(Program.GROUP_TREND).icon("🖼")
                    .searchYn("Y").newYn("N").saveYn("N").deleteYn("Y").resetYn("Y").etc1Nm("업로드").etc2Nm(REANALYZE_LABEL)
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
        // P-05 트렌드 분석. "집계"(기타1)는 일 1회 배치를 기다리지 않고 바로 다시 집계한다.
        if (!programRepository.existsById("trend-analysis")) {
            added.add(programRepository.save(Program.builder()
                    .programId("trend-analysis").programNm("트렌드 분석").url("/program/trend-analysis").sortOrder(3).useYn("Y")
                    .menuGroup(Program.GROUP_TREND).icon("📈")
                    .searchYn("Y").newYn("N").saveYn("N").deleteYn("N").resetYn("Y").etc1Nm("집계")
                    .build()));
            log.info("프로그램 trend-analysis(트렌드 분석)를 등록했습니다.");
        }
        // 분석 결과 조회(2026-10-29 사용자 요청) — 분석이 끝난 원문의 사진·카테고리·키워드·컬러·패턴·실루엣, 행을 누르면 사진 팝업
        if (!programRepository.existsById("analysis-result")) {
            added.add(programRepository.save(Program.builder()
                    .programId("analysis-result").programNm("분석 결과 조회").url("/program/analysis-result").sortOrder(4).useYn("Y")
                    .menuGroup(Program.GROUP_TREND).icon("🔍")
                    .searchYn("Y").newYn("N").saveYn("N").deleteYn("N").resetYn("Y")
                    .build()));
            log.info("프로그램 analysis-result(분석 결과 조회)를 등록했습니다.");
        }
        // RAG 검색은 별도 화면 대신 홈 질문창으로 옮겼다(2026-10-21). 잠깐 등록됐던 프로그램이 남아 있으면 지운다 —
        // 메뉴에 남으면 없는 화면(program-not-found)이 열린다.
        if (programRepository.existsById(RETIRED_RAG_PROGRAM)) {
            userProgramPermissionRepository.deleteByProgramId(RETIRED_RAG_PROGRAM);
            programRepository.deleteById(RETIRED_RAG_PROGRAM);
            log.info("프로그램 {}(RAG 검색)를 지웠습니다 — 홈 질문창으로 옮겼습니다.", RETIRED_RAG_PROGRAM);
        }
    }

    /**
     * 일괄 재분석 버튼(2026-10-29 사용자 요청) — 이미지 업로드는 기타2, 텍스트 등록은 기타1. 기존 DB는 그 칸이 비어 있을 때만 이름을 넣는다
     * (사람이 프로그램 관리에서 바꾼 이름은 두지 않는다). admin은 등록 때 모든 버튼 권한을 받았고, 다른 사용자는 사용자별 권한관리에서 준다.
     */
    private void addReanalyzeButtons() {
        int img = jdbcTemplate.update("UPDATE programs SET etc2_nm = ? WHERE program_id = ? AND (etc2_nm IS NULL OR TRIM(etc2_nm) = '')",
                REANALYZE_LABEL, "img-upload");
        int text = jdbcTemplate.update("UPDATE programs SET etc1_nm = ? WHERE program_id = ? AND (etc1_nm IS NULL OR TRIM(etc1_nm) = '')",
                REANALYZE_LABEL, "text-upload");
        if (img + text > 0) {
            log.info("일괄 재분석 버튼을 붙였습니다(이미지 업로드 {}·텍스트 등록 {}).", img, text);
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
