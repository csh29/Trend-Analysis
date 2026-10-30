# trend-analysis backend
패션 트렌드 분석 시스템 — Spring Boot 서버 + 화면(Thymeleaf).

## 개발 환경

| 구분 | 내용 |
| --- | --- |
| Language | Java 17 |
| Framework | Spring Boot 3.3.4 |
| Build Tool | Maven |
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
java -jar target/trend-analysis-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

또는 IDE에서 `TrendAnalysisApplication`을 실행한다(실행 설정에 `local` 프로파일).
실행 후 http://localhost:8080 으로 접속한다. 빌드·실행 주의점(`spring-boot:run` 불가, 새 클론에서 `mvnw` 불가 등)은 [`docs/llm/backend/backend-development.md`](../docs/llm/backend/backend-development.md).

## 화면 프레임워크

| 위치 | 역할 |
| --- | --- |
| `templates/home.html` | 홈 대시보드(요약 카드 + 트렌드 챗봇, "홈 편집"으로 패널 배치를 바꿔 계정별 저장) 겸 탭 셸. 사이드바 메뉴를 iframe 탭(`?embed=1`)으로 연다(`js/tabs.js`) |
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
| `static/js/line-chart.js` | 추이 차트(SVG, 외부 라이브러리 없음) |
| `static/js/modal-drag.js`, `code-highlight.js` | 모달 끌어 옮기기, 코드 조각 구문 강조 |

## 트렌드 분석 화면

| 화면 | 템플릿 | API |
| --- | --- | --- |
| 이미지 업로드 | `program/img-upload.html` | `/api/images` |
| 텍스트 등록 | `program/text-upload.html` | `/api/texts` (본문 + 첨부 이미지 최대 5장) |
| 트렌드 분석 | `program/trend-analysis.html` | `/api/trends` |
| 분석 결과 조회 | `program/analysis-result.html` | `/api/analysis-results` (조회 조건 카테고리·종류, 100건씩 페이지, 사진은 행을 눌러 팝업에서만) |

이미지는 여러 장을 한 번에 올린다(끌어다 놓기 또는 선택, 한 장 20MB·한 번에 50장·합계 200MB).
파일은 `backend/uploads/images/`(설정 `image.upload.dir`)에 저장되며 git에 올리지 않는다.
기사·잡지처럼 본문에 사진이 딸린 자료는 텍스트 등록에서 이미지를 같이 첨부한다(원문 1건 = 본문 + 이미지). 본문과 사진을 한 번에 분석하고 한 건으로 센다.

### AI 분석 (P-03 정형화 · P-04 색인)

이미지·텍스트를 올리면 뒤에서 Claude가 카테고리·키워드·소재·컬러·실루엣을 뽑고(정형 JSON), 결과는 RDB에,
청크 임베딩은 Chroma에 저장된다. 목록의 "분석" 칸에 상태가 나오고, 행을 누르면 결과·재분석이 뜬다.
AI 처리는 Python 서비스(`../ai`, FastAPI)가 하고 Spring이 호출한다 — 분석하려면 둘 다 띄운다.

`src/main/resources/application-local.properties`(`local` 프로파일, 커밋 제외)에 넣을 값:

| 키 | 용도 |
| --- | --- |
| `claude.api.key` | Claude API 키(이 파일은 커밋하지 않는다) |
| `claude.model` | 챗봇 답변·도구 경로 모델, 기본 `claude-sonnet-5` |
| `claude.structure-model` | 원문 분석(정형화) 모델, 기본 `claude-sonnet-5-5` |
| `claude.interpret-model` | 홈 질문 해석용 작은 모델, 기본 `claude-haiku-5-5` |
| `ai.server.url` | AI 서비스 주소, 기본 `http://127.0.0.1:8001` |
| `ai.analysis.max-attempts` | 정형화 재시도 상한, 기본 3 |

AI 서비스는 `ai/start-ai.cmd`로 띄우고 `ai/stop-ai.cmd`로 끄며, 프롬프트·코드를 고친 뒤에는 `ai/restart-ai.cmd`로 다시 띄운다(더블클릭 가능). 처음 실행하면 가상환경과 패키지를 설치한다.
로그는 `ai/logs/`에 남는다. 직접 띄울 때는 `ai` 폴더에서:

```bash
.venv/Scripts/python -m uvicorn app.main:app --host 127.0.0.1 --port 8001
```

임베딩은 `Qwen/Qwen3-VL-Embedding-2B`(CPU, RAM 약 4.5GB)이고 첫 색인 때 모델(약 4GB)을 내려받는다.
Chroma는 내장형(`ai/chroma_data/`)이다. 서버형 전환 기준·방법은 [`docs/llm/features/analysis-pipeline.md`](../docs/llm/features/analysis-pipeline.md).

### 트렌드 집계 (P-05)

트렌드 분석 화면에서 주별 언급량 추이(차트), 전주·전월 대비 증감률, 급상승(전주 대비 +20% 이상)·신규를 보고, 행을 누르면 근거 원문을 본다.
집계는 매일 새벽 3시와 기동 직후에 돌고, 바로 보려면 화면의 "집계"를 누른다.
주는 ERP처럼 **마감**한다 — 매주 월요일 새벽에 지난주까지 자동 마감하고(화면 "마감"으로 먼저 할 수도 있다), 마감한 주는 다시 집계하지 않는다. "마감 취소"는 권한자만 사유를 남기고 가장 최근 마감 주부터 한다.

| 키 | 용도 |
| --- | --- |
| `trend.surge.rate` | 급상승 기준(전주 대비 %), 기본 20 |
| `trend.surge.min-count` | 급상승 최소 언급량, 기본 1 |
| `trend.aggregate.cron` | 집계 시각, 기본 `0 0 3 * * *` |
| `trend.close.cron` | 자동 마감 시각(지난주까지), 기본 `0 30 3 * * MON` |
| `trend.close.auto` | 자동 마감(기동 때 따라잡기 포함), 기본 true |

확인용 샘플(6주 분량 텍스트 37건, 실제 Claude 분석을 거친다)은 `--trend.sample.load=true`로 한 번 띄우면 들어간다.

### RAG 검색 (P-06)

홈 대시보드(`home.html`)는 트렌드 집계·분석 현황 카드와 그 아래 **트렌드 챗봇**(대화형)으로 되어 있다. 작은 모델이 질문을 해석해(패션 질문인지·유행 질문인지·범위·기간, 앞 질문에 이어 묻기) 유행 질문은 Claude가 집계 도구로 원문 수·매체 수를 세어, 그 밖의 질문은 비슷한 원문을 찾아 Claude가 답하고 출처(매체·날짜·원문 링크 또는 사진)를 붙인다. API는 `/api/rag`(로그인만 필요).
패션·트렌드 질문이 아니면 답하지 않고, 근거가 기준 유사도에 못 미치면 Claude를 부르지 않고 "근거 자료 없음"으로 답한다. 자세한 규칙은 [`docs/llm/features/rag-search.md`](../docs/llm/features/rag-search.md).

| 키 | 용도 |
| --- | --- |
| `rag.min-score` | 리랭킹을 껐을 때 근거로 쓰는 최소 유사도(0~1), 기본 0.60. 리랭킹 켜기/끄기와 리랭커 기준은 관리 > 공통코드 `RAG_SEARCH` |
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
| 챗봇 질문 로그 | `program/rag-log.html` | `/api/rag-logs` (질문·답·경로·토큰·👍👎, 보관 180일) |

초기 관리자(`admin`)의 비밀번호는 최초 기동 때 무작위로 만들어 서버 로그에 한 번만 찍는다. 잃어버렸을 때는 [`docs/llm/features/admin-screens.md`](../docs/llm/features/admin-screens.md).

### 화면 추가

`templates/program/<화면id>.html`을 만들고 프로그램 관리에 같은 id로 등록한 뒤 사용자에게 권한을 주면 `/program/<화면id>`로 열린다. 절차와 규칙은 [`docs/llm/features/screen-framework.md`](../docs/llm/features/screen-framework.md).

## 로그인·권한

Spring Security 폼 로그인(`/login`)과 프로그램(화면) 단위 권한이다. 사이드바에는 권한이 있는 프로그램만, 툴바에는 허용된 버튼만 보이고, 권한 없는 화면·API는 403이다. 규칙은 [`docs/llm/features/auth-permission.md`](../docs/llm/features/auth-permission.md), 사용자 관리 제한은 [`docs/llm/features/admin-screens.md`](../docs/llm/features/admin-screens.md).
