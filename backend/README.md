# trend-analysis backend
패션 트렌드 분석 시스템 — Spring Boot 서버 + 화면(Thymeleaf).

## 개발 환경

| 구분 | 내용 |
| --- | --- |
| Language | Java 17 |
| Framework | Spring Boot 3.3.4 |
| Build Tool | Maven (Maven Wrapper 포함, `./mvnw` \| `mvnw.cmd`) |
| Group / Artifact | `org.example` / `trend-analysis` |
| 기본 패키지 | `com.sjinc.trendanalysis` |
| View | Thymeleaf (서버 렌더링) |
| DB | H2 파일 DB (`backend/data/trendAnalysis.mv.db`, `ddl-auto=update`) |

### 주요 의존성

| 의존성 | 용도 |
| --- | --- |
| `spring-boot-starter-web` | REST API + 화면 서빙 |
| `spring-boot-starter-data-jpa` | 엔티티 / Repository |
| `spring-boot-starter-thymeleaf` | 화면 템플릿 |
| `spring-boot-starter-validation` | 요청 값 검증 |
| `spring-boot-starter-security` | 폼 로그인, 프로그램별 권한 검사, 비밀번호 BCrypt 해시 |
| `h2` *(runtime)* | 파일 DB |
| `spring-boot-devtools` *(runtime, optional)* | 로컬 자동 재시작 |
| `lombok` *(optional)* | 보일러플레이트 코드 축소 |
| `spring-boot-starter-test` *(test)* | JUnit 5 / Mockito / AssertJ |
| `spring-security-test` *(test)* | `@WithMockUser`, `csrf()` |

## 빌드 & 실행

```bash
./mvnw clean package
```

```bash
./mvnw spring-boot:run
```

실행 후 http://localhost:8080 으로 접속한다.

## 화면 프레임워크

security-monitoring 프로젝트의 화면 공통 틀을 그대로 가져왔다.

| 위치 | 역할 |
| --- | --- |
| `templates/home.html` | 홈 대시보드(요약 카드 + RAG 질문창) 겸 탭 셸. 사이드바 메뉴를 iframe 탭(`?embed=1`)으로 연다(`js/tabs.js`) |
| `templates/fragments/sidebar.html` | 사이드바. 메뉴는 프로그램 관리(DB)와 공통코드 `MENU_GROUP`이 정한다(`SidebarModelAdvice`) |
| `templates/login.html` | 로그인 화면 |
| `templates/fragments/topbar.html` | 상단바(로고·사이드바 접기·탭 목록·로그인 사용자·비밀번호 변경·로그아웃) |
| `templates/fragments/page-toolbar.html` | 화면 첫 줄(프로그램명 + 공통 버튼) |
| `templates/fragments/loading-overlay.html` | 공통 fetch 래퍼(로딩 스피너) + 공통 스크립트 로드 |
| `static/css/common-ui.css`, `grid.css` | 공통 레이아웃·그리드 모양 |
| `static/js/grid.js`, `search-form.js` | 그리드·조회영역 렌더러 |
| `static/js/page-buttons.js`, `hotkeys.js` | 공통 버튼 핸들러, 펑션키(F3 조회/F4 신규/F5 삭제/F9 저장/F12 초기화) |
| `static/js/xlsx-writer.js`, `zip-writer.js` | 그리드 우클릭 엑셀 다운로드 |
| `static/js/com-cd.js` | 공통코드 select 채우기(`/api/com-cds`) |

## 트렌드 분석 화면

| 화면 | 템플릿 | API |
| --- | --- | --- |
| 이미지 업로드 | `program/img-upload.html` | `/api/images` |
| 텍스트 등록 | `program/text-upload.html` | `/api/texts` (본문 + 첨부 이미지 최대 5장) |
| 트렌드 분석 | `program/trend-analysis.html` | `/api/trends` |

이미지는 여러 장을 한 번에 올린다(끌어다 놓기 또는 선택, 한 장 20MB·한 번에 50장·합계 200MB).
파일은 `backend/uploads/images/`(설정 `image.upload.dir`)에 저장되며 git에 올리지 않는다.
기사·잡지처럼 본문에 사진이 딸린 자료는 텍스트 등록에서 이미지를 같이 첨부한다(원문 1건 = 본문 + 이미지). 본문과 사진을 한 번에 분석하고 한 건으로 센다.

### AI 분석 (P-03 정형화 · P-04 색인)

이미지·텍스트를 올리면 뒤에서 Claude가 카테고리·키워드·소재·컬러·실루엣을 뽑고(정형 JSON), 결과는 RDB에,
청크 임베딩은 Chroma에 저장된다. 목록의 "분석" 칸에 상태가 나오고, 행을 누르면 결과·재분석이 뜬다.
AI 처리는 Python 서비스(`../ai`, FastAPI)가 하고 Spring이 호출한다 — 분석하려면 둘 다 띄운다.

`application.properties`에 넣을 값:

| 키 | 용도 |
| --- | --- |
| `claude.api.key` | Claude API 키(이 파일은 커밋하지 않는다) |
| `claude.model` | 기본 `claude-sonnet-5` |
| `ai.server.url` | AI 서비스 주소, 기본 `http://127.0.0.1:8001` |
| `ai.analysis.max-attempts` | 정형화 재시도 상한, 기본 3 |

AI 서비스는 `ai/start-ai.cmd`로 띄우고 `ai/stop-ai.cmd`로 끈다(더블클릭 가능). 처음 실행하면 가상환경과 패키지를 설치한다.
로그는 `ai/logs/`에 남는다. 직접 띄울 때는 `ai` 폴더에서:

```bash
.venv/Scripts/python -m uvicorn app.main:app --host 127.0.0.1 --port 8001
```

임베딩은 `Qwen/Qwen3-VL-Embedding-2B`(CPU, RAM 약 4.5GB)이고 첫 색인 때 모델(약 4GB)을 내려받는다.
Chroma는 지금 내장형(`ai/chroma_data/`)이다. 시스템 오픈 때 `ai/.env`의 `CHROMA_MODE=http`와 `CHROMA_HOST`만 바꾸면 서버형으로 전환된다.

### 트렌드 집계 (P-05)

트렌드 분석 화면에서 주별 언급량 추이(차트), 전주·전월 대비 증감률, 급상승(전주 대비 +20% 이상)·신규를 보고, 행을 누르면 근거 원문을 본다.
집계는 매일 새벽 3시와 기동 직후에 돌고, 바로 보려면 화면의 "집계"를 누른다.

| 키 | 용도 |
| --- | --- |
| `trend.surge.rate` | 급상승 기준(전주 대비 %), 기본 20 |
| `trend.surge.min-count` | 급상승 최소 언급량, 기본 1 |
| `trend.aggregate.cron` | 집계 시각, 기본 `0 0 3 * * *` |

확인용 샘플(6주 분량 텍스트 37건, 실제 Claude 분석을 거친다)은 `--trend.sample.load=true`로 한 번 띄우면 들어간다.

### RAG 검색 (P-06)

홈 대시보드(`home.html`)는 트렌드 집계·분석 현황 카드와 그 아래 질문창으로 되어 있다. 질문하면 분석이 끝난 자료 중 비슷한 원문을 찾아 Claude가 답하고, 답변 아래에 출처(매체·날짜·원문 링크)를 보여 준다. API는 `/api/rag`(로그인만 필요).
비슷한 원문이 기준 유사도에 못 미치면 Claude를 부르지 않고 "근거 자료 없음"으로 답한다. 기간을 말하지 않으면("요즘" 포함) 관련 자료 중 최근 것을 앞세워 답하고(4주마다 비중 절반), 지난달·최근 3개월처럼 기간을 쓰면 그 기간 자료만 쓴다.

| 키 | 용도 |
| --- | --- |
| `rag.min-score` | 근거로 쓰는 최소 유사도(0~1), 기본 0.60(임시값) |
| `rag.top-k` | 찾는 청크 수, 기본 8 |

## 관리 화면

사이드바 "관리" 그룹. 기동 시 `DataInitializer`가 없을 때만 심는다.

| 화면 | 템플릿 | API |
| --- | --- | --- |
| 프로그램 관리 | `program/program-mng.html` | `/api/programs` |
| 사용자 관리 | `program/user-mng.html` | `/api/users` |
| 사용자별 권한관리 | `program/user-permission-mng.html` | `/api/permissions/{userId}` |
| 공통코드마스터 관리 | `program/com-cd-master-mng.html` | `/api/com-cd-groups` |
| 공통코드 관리 | `program/com-cd-mng.html` | `/api/com-cds` |

초기 관리자(`admin`)의 비밀번호는 최초 기동 때 무작위로 만들어 서버 로그에 한 번만 찍는다.
잃어버렸으면 `backend/data/`를 지우고 다시 기동한다(데이터가 모두 초기화된다).

### 화면 추가

1. `templates/program/<화면id>.html`을 만든다(`program/com-cd-master-mng.html`처럼 조회영역+그리드+공통 버튼 구조).
2. 프로그램 관리 화면에서 같은 프로그램ID로 등록한다(메뉴 그룹·아이콘·사용 버튼 지정).

3. 사용자별 권한관리에서 쓸 사용자에게 그 프로그램 권한을 준다.

`/program/<화면id>`로 열리며, `ViewController`는 고치지 않는다. 새 API는 컨트롤러에 `@RequiresProgram("<화면id>")`을 붙인다.

## 로그인·권한

security-monitoring의 로그인 프로세스를 그대로 따른다.

- **로그인:** Spring Security 폼 로그인(`/login`). 계정은 `users` 테이블, 비밀번호는 BCrypt(`CustomUserDetailsService`). 사용여부 N 계정은 로그인할 수 없다.
- **메뉴·버튼:** 사이드바에는 로그인 사용자가 권한을 가진 프로그램만, 툴바에는 "프로그램이 쓰는 버튼 ∩ 사용자에게 허용된 버튼"만 보인다(사용자별 권한관리).
- **화면 접근:** 프로그램으로 등록된 화면은 그 권한이 없으면 403(`ViewController`).
- **API 접근:** 허용 목록(`/login`, 정적 파일, `/error`) 외 요청은 `RequiresProgramAuthorizationManager`가 로그인과 `@RequiresProgram` 권한으로 판단한다. 미로그인 API는 401, 화면은 로그인 페이지로 보낸다.
- **CSRF:** `XSRF-TOKEN` 쿠키를 공통 fetch 래퍼(`loading-overlay.html`)가 `X-XSRF-TOKEN` 헤더로 실어 보낸다.
- **사용자 관리 제한:** 자기 자신은 비밀번호만 바꿀 수 있고 삭제할 수 없다. 자신보다 높은 역할(공통코드 `ROLE` 정렬순서)의 계정은 저장·삭제할 수 없고, 그런 역할을 줄 수도 없다.
