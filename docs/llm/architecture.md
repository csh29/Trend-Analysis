# architecture.md — 시스템 구조

요청 처리 흐름, 화면 프레임워크, 메뉴·권한 데이터 관계.

## 1. 시스템 구조

```
브라우저 ── 화면 요청 ──▶ SecurityFilterChain ──▶ ViewController ──▶ Thymeleaf (templates/)
        └─ fetch /api/** ─▶ SecurityFilterChain ──▶ controller ──▶ service ──▶ repository ──▶ H2
```

- **화면(`mvc`)과 API(`controller`)는 분리돼 있다.** 화면은 HTML 껍데기만 그리고, 데이터는 화면 JS가 `/api/**`를 fetch해서 채운다.
- 화면 컨트롤러는 `ViewController` 하나다.
  - `/` → `home.html`(탭 셸)
  - `/login` → `login.html`
  - `/program/{화면id}` → `templates/program/{화면id}.html`. 화면을 추가해도 컨트롤러는 고치지 않는다.
    화면 id는 `[a-z0-9-]+`만 허용하고(경로 조작 방지), 템플릿이 없으면 `program-not-found.html`을 보여준다.
- 모든 화면에 공통으로 얹히는 모델: `menuGroups`(사이드바, `SidebarModelAdvice`), `loginUserLabel`(상단바, `TopbarModelAdvice`).

### 보안 필터 흐름 (`SecurityConfig`)

1. `PUBLIC_URLS`(`/login`, `/css/**`, `/js/**`, `/favicon.ico`, `/error`)는 통과한다.
2. 그 외 요청은 모두 `RequiresProgramAuthorizationManager`가 판단한다. 기본이 거부(fail closed)다.
   - 익명이면 거부한다. API(`/api/**`)는 401을 돌려주고, 화면은 `/login`으로 리다이렉트한다.
   - 처리할 핸들러에 `@RequiresProgram`이 있으면(메서드 우선, 없으면 클래스) 나열한 프로그램 중 하나의 권한을 요구한다.
     없으면 로그인만 요구한다.
3. 권한 판단은 `ProgramAccessGuard.check(username, programId)`가 한다. 그 사용자의 `UserProgramPermission` 중 사용중(`useYn=Y`) 프로그램에 있는지를 본다.
4. `ViewController`도 프로그램으로 등록된 화면이면 같은 검사를 하고, 권한이 없으면 403을 낸다(메뉴에만 숨기면 주소로 열 수 있으므로).
5. CSRF: `CookieCsrfTokenRepository`(`XSRF-TOKEN` 쿠키, HttpOnly 아님). 폼은 `_csrf` 히든 필드로, fetch는 `X-XSRF-TOKEN` 헤더로 보낸다.
   `CsrfCookieFilter`는 토큰을 응답 초반에 만들어, 로그인 직후 첫 POST가 쿠키 없이 403 나는 것을 막는다.
6. `frameOptions(sameOrigin)` — 홈 탭(iframe)이 같은 출처 화면을 띄우는 데 필요하다.

## 2. 데이터 흐름

### 로그인

`/login` POST → `CustomUserDetailsService`가 `users` 테이블에서 조회한다(BCrypt, `useYn=N`이면 비활성) → 성공하면 `/`, 실패하면 `/login?error`.
로그아웃은 `/logout` POST로 하고 `/login?logout`으로 간다.

### 화면 한 장이 그려지는 과정

1. 사이드바(`menuGroups`): 공통코드 `MENU_GROUP`(사용중, 정렬순서) 순서로 그룹을 만들고, 각 그룹에 로그인 사용자가 권한을 가진 사용중 프로그램을 `sortOrder` 순으로 넣는다. 프로그램이 없는 그룹은 그리지 않는다.
2. 툴바(`fragments/page-toolbar`): `programNm`과 `pageButtons`를 그린다. 버튼은 **프로그램이 쓰는 버튼 ∩ 사용자에게 허용된 버튼**이며, 순서는 `ProgramButton` 순서다.
3. 화면 JS가 `PageButtons.bind({...})`로 핸들러를 걸고, 조회 API를 fetch해서 `Grid.render`로 그린다.

### 탭 셸

- `home.html` + `tabs.js`: 사이드바 링크를 가로채, 페이지를 이동하는 대신 iframe 탭(`{url}?embed=1`)으로 연다. 같은 화면은 탭을 하나만 연다.
- `?embed=1`로 열린 화면은 `app-shell--embedded`(common-ui.css)가 사이드바·상단바를 숨기고 본문만 보인다.
- 펑션키(`hotkeys.js`)는 보이는 탭 iframe으로 전달된다.

### 공통 fetch 래퍼 (`fragments/loading-overlay.html`)

모든 `fetch`에 다음을 자동으로 처리한다. 화면 코드는 신경 쓰지 않는다.
- 로딩 스피너. `{ silent: true }`를 주면 띄우지 않는다.
- 상태 변경 요청(POST/PUT/PATCH/DELETE)에 `X-XSRF-TOKEN` 헤더를 붙인다.
- 401 응답이면 창 전체(`window.top`)를 `/login`으로 보낸다.

### 분석 (P-03 AI 정형화 · P-04 저장·색인)

```
업로드/등록 ─▶ DocumentService: source_documents(PENDING) 저장 ─▶ AnalysisRequestedEvent
                                                                    │ (analysisExecutor, 동시 2건)
AnalysisService.analyze ─▶ AiClient POST /structure ─▶ FastAPI ─▶ Claude Structured Output
        │  (X-Claude-Api-Key 헤더, 공통코드 허용값, 이미지는 base64)
        ├─ TrendResultValidator 검증 실패 / 일시 오류 → 재시도(최대 ai.analysis.max-attempts, 기본 3)
        ├─ 재시도 불가(키 오류·거절·형식 오류 400) → FAILED + 사유
        ├─ 통과 → trend_items·trend_item_values 저장(짧은 트랜잭션, 재분석이면 갈아끼움)
        └─ AiClient POST /index ─▶ FastAPI ─▶ Chroma(문서 ID 단위 갈아끼움) → DONE (실패면 FAILED "색인 실패")
```

- 업로드·등록 요청은 분석을 기다리지 않는다. 문서 저장이 커밋된 뒤 이벤트를 내야 분석 스레드가 읽을 수 있어서, `DocumentService`의 등록은 트랜잭션으로 감싸지 않는다.
- 외부 호출(AI 서비스)은 트랜잭션 밖에서 하고, 트렌드 항목 저장만 `TransactionTemplate`으로 짧게 묶는다.
- 서버가 분석 도중 내려가면 PENDING·PROCESSING이 남는다. 기동이 끝나면(`ApplicationReadyEvent`) 다시 대기열에 넣는다.
- **Claude API 키**는 Spring `claude.api.key` 한 곳에만 있다. 요청마다 헤더로 넘기고 AI 서비스는 저장하지 않는다. 키가 비어 있으면 AI 서비스를 부르지 않고 바로 실패 사유를 남긴다.
- **표준 코드:** 카테고리·소재·컬러·실루엣은 공통코드 값만 저장한다. 분석할 때마다 사용중 코드를 읽어 Claude 스키마의 enum으로 넘기므로, 공통코드 관리에서 값을 바꾸면 재기동 없이 다음 분석부터 적용된다. 코드 이름의 괄호(예: "레더(가죽)")는 같은 뜻의 다른 표기를 그 코드로 옮기게 하는 힌트다.
- **출처(source)**는 LLM이 만들지 않는다. Spring이 원문 문서 값으로 정형 JSON에 붙인다(프로세스 정의서 10번 슬라이드 형식).
- **색인 청크:** 문서마다 정형 결과를 문장으로 만든 요약 청크 1개(코드값 대신 이름 — 한국어 질문과 맞추기 위해), 텍스트는 본문 청크(500자, 80자 겹침)를 더 넣는다.
- **Chroma 전환:** `ai/vector_store.py`의 `create_client`만 클라이언트를 만든다. `CHROMA_MODE=embedded`(로컬 폴더) ↔ `http`(Chroma 서버)는 `ai/.env` 값만 바꾼다. 컬렉션 메타데이터에 임베딩 모델을 남기고, 다른 모델로 열면 기동을 막는다(섞이면 검색이 조용히 틀린다).
- **임베딩:** `ai/embedding.py`의 `create_embedding_function`이 `EMBEDDING_MODEL` 이름으로 구현을 고른다. Qwen3-VL-Embedding은 문서는 그대로, 검색 질의에만 영어 지시문을 붙인다(Chroma가 질의 때 `embed_query`를 부른다). 지금은 텍스트만 넣는다 — 이미지 자체 임베딩은 아직 안 한다.

## 3. 모듈 관계

### 메뉴·권한 데이터

```
COM_CD_MST ─(code_group)─< COM_CD_DTL          MENU_GROUP 코드 = 사이드바 그룹, ROLE 코드 = 역할·등급
programs (program_id = 화면 id = 템플릿 이름)
   └─< user_program_permissions >─ users        (user_id, program_id) 유일
```

| 엔티티 | 핵심 |
| --- | --- |
| `Program` | `menuGroup`(비면 MNG), `icon`, 버튼 사용 여부 `searchYn~resetYn`, 기타 버튼 이름 `etc1Nm~etc5Nm`(이름이 있으면 쓴다) |
| `UserProgramPermission` | 행이 있으면 화면 접근 권한이 있다. 허용 버튼 `searchYn~etc5Yn` |
| `User` | `username`(로그인 아이디), `userNm`(표시 이름), `role`(공통코드 ROLE), `useYn` |
| `ComCdGroup` / `ComCd` | 마스터·디테일. 디테일이 마스터의 자연키(`codeGroup`)를 들고 있다 |

- 프로그램을 삭제하면 그 프로그램을 가리키는 권한 행을 먼저 지운다. 사용자를 삭제하면 그 사용자의 권한 행을 먼저 지운다. 공통코드 그룹을 삭제하면 딸린 코드를 먼저 지운다.
- 사용자별 권한 저장은 그 사용자의 권한을 **통째로 교체**한다. 벌크 DELETE 후 INSERT 하는데, 순서가 바뀌면 유일 제약을 위반하므로 벌크로 한다.

### 사용자 관리 제한 (`UserService`)

- 역할 등급은 공통코드 `ROLE`의 정렬순서다. 작을수록 높다.
- 자기 자신은 비밀번호만 바꿀 수 있고, 삭제할 수 없다.
- 자신보다 높은 역할의 계정은 저장·삭제할 수 없고, 자신보다 높은 역할을 줄 수도 없다.
- 응답 `editable`/`self`로 화면이 잠금 여부를 정한다.

### 화면 공통 JS

| 파일 | 공개 API | 역할 |
| --- | --- | --- |
| `grid.js` | `Grid` | 헤더·행 렌더, 입력 셀(`type`), `getRows`·`addRow`·`removeRows`, 우클릭 복사·엑셀, `virtual` 스크롤 |
| `search-form.js` | `SearchForm` | 조회영역 렌더, `values()`·`reset()`·`matches(row)` |
| `com-cd.js` | `ComCd` | 공통코드로 select 채우기(그룹별 캐시) |
| `page-buttons.js` | `PageButtons` | 권한에 따라 없을 수도 있는 공통 버튼에 핸들러 걸기 |
| `hotkeys.js` | — | F3 조회 / F4 신규 / F5 삭제 / F9 저장 / F12 초기화 |
| `tabs.js` | — | 홈 탭 셸 |
| `modal-drag.js` | — | `.modal` 제목줄 드래그 |
| `xlsx-writer.js`, `zip-writer.js` | `XlsxWriter`, `ZipWriter` | 엑셀 다운로드 |
| `trend-analysis.js` | `TrendAnalysis` | 분석 상태 뱃지, 결과 모달(재분석 포함), 끝나지 않은 행 자동 새로고침 |
| `code-highlight.js` | `CodeHighlight` | 코드 강조(참조 프로젝트에서 가져왔고, 아직 쓰는 화면이 없다) |
