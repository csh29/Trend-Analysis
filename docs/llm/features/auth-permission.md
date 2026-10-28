# auth-permission.md — 로그인 · 권한

폼 로그인과 프로그램(화면) 단위 권한. 구조를 다시 설계하지 않는다([`llm-analysis.md`](../llm-analysis.md) 1절).
코드: `config/SecurityConfig`, `security/*`, `mvc/ViewController`, `service/user/CustomUserDetailsService`.

## 규칙

아래 규칙은 각각 실제 취약점을 막는 장치다. 편의를 위해 되돌리지 않는다.

- **기본 거부(deny by default).** 허용 목록 `PUBLIC_URLS`(`/login`·정적 파일 `/css/**`·`/js/**`·`/favicon.ico`·`/error`)에 없는 요청은 로그인과 프로그램 권한 확인을 통과해야만 허용한다 — `anyRequest().access(requiresProgramAuthorizationManager)`. 판단은 `RequiresProgramAuthorizationManager`가 하고, URL 패턴을 `SecurityConfig`에 다시 나열하지 않는다.
- **새 API에는 `@RequiresProgram("<화면id>")`을 붙인다.** 안 붙이면 로그인한 전원에게 열린다 — 구조가 막아 주지 않는 유일한 지점이다. 여러 화면이 쓰는 조회 API는 `{"a", "b"}`로 나열한다.
  - 예외(일부러 로그인만): 홈 챗봇 `/api/rag/**`(홈은 프로그램이 아니다), 본인 비밀번호 변경 `/api/users/me/password`·본인 홈 배치 `/api/users/me/home-layout`(모든 사용자가 쓰고, 대상은 Principal이라 남의 것을 건드릴 수 없다). 이유는 컨트롤러 주석에 있다 — "누락"으로 보고 붙이지 않는다. 이런 API의 테스트는 403 대신 미로그인 401을 본다.
- `@PreAuthorize`는 요청 값에 따라 필요한 권한이 달라질 때만 쓴다(현재 `ComCdController.getCodes`의 `includeInactive` 하나).
- **화면(`/program/**`)도 같은 검사를 한다**(`ViewController`). 메뉴에서만 숨기면 주소로 열린다.
- 권한 거부는 응답을 직접 만들지 않고 `AccessDeniedException`을 던진다(화면·API 403, 미로그인 API 401·화면은 로그인 이동).
- 툴바 버튼 = 프로그램이 쓰는 버튼 ∩ 사용자에게 허용된 버튼. 화면 마크업에 버튼을 쓰지 않는다.
- 비밀번호는 BCrypt로만 저장하고 응답에 넣지 않는다. 본인 비밀번호 변경은 현재 비밀번호를 다시 확인한다(열린 화면·탈취한 세션으로 계정을 가져가지 못하게).
- 초기 관리자 비밀번호는 기동 때 무작위로 만들어 로그에 한 번만 찍는다. 소스·로그인 화면에 기본 비밀번호를 두지 않는다. 잃어버렸을 때는 [`admin-screens.md`](admin-screens.md).
- **H2 콘솔(`spring.h2.console.enabled`)은 로컬에서 필요할 때만 켜고 쓰고 나면 끈다.** 콘솔은 `CREATE ALIAS`로 원격 코드 실행이 가능하다. 켜도 앱 로그인은 필요하지만 로그인한 누구나 SQL을 실행할 수 있고, 켜 둔 동안 `/h2-console/**`는 CSRF 검증에서 빠진다.

## 주의점

- **CSRF는 `/api/**`에도 건다.** 토큰은 `XSRF-TOKEN` 쿠키(HttpOnly 아님) → 공통 fetch 래퍼가 `X-XSRF-TOKEN` 헤더로 보낸다. 빼면 악성 페이지가 로그인한 관리자 브라우저로 변경 요청을 보낼 수 있다.
- `CsrfCookieFilter`가 없으면 로그인 직후 첫 POST가 쿠키 없이 403 난다.
- 미로그인 API는 302가 아니라 **401**로 끝낸다. 302면 fetch가 로그인 페이지 HTML을 200으로 받아 JSON 파싱 오류가 난다. 401은 fetch 래퍼가 창 전체를 로그인으로 보낸다.
- `/error`를 `PUBLIC_URLS`에서 빼면 401·404가 로그인 리다이렉트로 바뀐다.
- `frameOptions(sameOrigin)`은 홈 탭(iframe)에 필요하다.
- 컨트롤러가 아닌 경로(H2 콘솔 등)는 로그인만 요구한다.

## 확인할 것

- `SecurityConfig`·`security`를 고치면: 미로그인 화면 → 로그인 이동, API → 401, 권한 없음 403, CSRF 없는 POST 403.
- 새 API마다 정상 응답 + 권한 없는 사용자 403 테스트를 붙인다(`nobody`처럼 DB에 없는 이름). 일부러 로그인만 요구하는 API는 미로그인 401.
