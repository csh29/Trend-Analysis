# screen-framework.md — 화면 프레임워크

security-monitoring에서 옮겨 온 화면 공통 틀(탭 셸, 공통 fetch 래퍼, 그리드·조회영역·공통코드·버튼·차트 공통 JS).
코드: `templates/home.html`·`fragments/*`, `static/js/*`(각 파일 머리 주석에 사용법이 있다), `static/css/common-ui.css`.

## 새 화면 추가

1. `templates/program/<화면id>.html` — `com-cd-master-mng.html` 구조를 따른다. 첫 줄은 `fragments/page-toolbar`, `app-shell`에 `${param.embed}`면 `app-shell--embedded`.
2. 화면 API에 `@RequiresProgram("<화면id>")`.
3. 프로그램 관리에 같은 id로 등록하고 권한을 준다. 기본 화면이면 `DataInitializer`에 "없을 때만 넣기"로 추가한다(기존 DB의 admin에게는 자동으로 권한이 붙는다).

`ViewController`는 고치지 않는다. 화면 id는 `[a-z0-9-]+`만 된다(경로 조작 방지).

## 홈 대시보드

- 홈(`home.html`)은 탭 셸이면서 대시보드다: 트렌드 집계·분석 현황 카드(`HomeService`) + 트렌드 챗봇(대화형 RAG, [`rag-search.md`](rag-search.md)).
- **자동 새로고침**(1분, 끄고 켤 수 있음 — 상태는 그 브라우저 localStorage): 페이지를 다시 읽지 않고 `/`를 받아 **카드 영역(`.overview`)만** 바꿔 끼운다. 다시 읽으면 열린 탭과 챗봇 대화가 사라지기 때문이다. 홈이 보일 때만 돌고, 탭에서 돌아오거나 창이 다시 보일 때 주기가 지났으면 바로 갱신한다. 실패하면 이전 수치를 두고 "갱신 실패" 시각을 남긴다. 세션이 끝났으면 로그인으로 보낸다. security-monitoring 홈과 같은 방식이다.
- 카드에 새 수치를 넣으면 `.overview` 안에 둔다(밖에 두면 새로고침되지 않는다).

## 규칙 — 공통 모듈을 쓴다

- 그리드는 `Grid`로만 그린다. `tr`/`td`·입력 엘리먼트를 직접 만들지 않고 컬럼 `type`으로 선언한다. 값은 `Grid.getRows`로 읽는다.
- 조회영역은 `SearchForm`. 화면에서 거를 때는 필드 id를 행 데이터 키와 맞추고 `search.matches`를 쓴다. 공통코드 select가 있으면 첫 조회는 `search.ready`를 기다린다.
- select 옵션은 공통코드(`ComCd`, `optionsQuery`)에서. 코드값을 HTML에 하드코딩하지 않는다.
- 공통 버튼은 마크업에 쓰지 않는다. 서버가 권한대로 그리므로 `PageButtons.bind`로 핸들러만 건다(없는 버튼은 건너뛴다).
- fetch에 CSRF 헤더·스피너·401 처리를 붙이지 않는다 — 공통 래퍼(`loading-overlay`)가 한다. 자동 새로고침처럼 스피너가 거슬리면 `{ silent: true }`.
- 엑셀·복사는 그리드 우클릭에 이미 있다.
- 차트는 `LineChart`(SVG, 외부 라이브러리 없음). CDN 차트 라이브러리를 들이지 않는다.
- 스타일은 `common-ui.css` 클래스와 `:root` 변수. 화면 `<style>`에는 그 화면만 다른 값만.
- `var` 금지(`const`, 재대입만 `let`). 같은 코드가 두 번째로 필요하면 공통 모듈로 올린다.

## 주의점

- 날짜는 로컬 기준으로 만든다. `toISOString()`은 UTC라 한국 시간 오전 9시 전에 하루 밀린다.
- `LineChart`는 같은 x에서 값이 같은 시리즈를 세로로 3px씩 비켜 그린다(그대로 그리면 마지막 선만 보였다). 값은 바꾸지 않고, 정확한 값은 x칸 툴팁에 모두 나온다. 툴팁은 커스텀 div다 — SVG `<title>` 기본 툴팁은 1초 가까이 늦게 떠서 쓰지 않는다.
- 탭은 iframe(`?embed=1`)이다. 401 이동은 `window.top`으로 해야 탭 안에 로그인 화면이 뜨지 않는다.
- 화면 동작은 자동 테스트가 없다. jar로 띄워 로그인 → 탭 열기 → 조회, 콘솔 오류가 없는지 본다.
