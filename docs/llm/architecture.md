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
| `code-highlight.js` | `CodeHighlight` | 코드 강조(참조 프로젝트에서 가져왔고, 아직 쓰는 화면이 없다) |
