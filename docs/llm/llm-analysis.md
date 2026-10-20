# llm-analysis.md — 전체 구조 요약

**사실 문서** — 지금 시스템이 어떻게 되어 있는가. 작업 전에 먼저 읽고, 필요한 상세 문서로 들어간다.
문서와 코드가 어긋나면 코드가 맞다. 확인한 자리에서 문서를 고친다.

## 1. 시스템 개요

패션 트렌드 분석 시스템. 기준 문서는 `C:\ai 과제\시스템 프로세스 정의서.pptx`(P-01~P-08)다.
지금 구현된 범위는 화면 프레임워크·로그인·권한·관리 화면 5종, 그리고 **P-03 AI 정형화·P-04 저장·색인**이다.
P-01 수집·P-02 전처리 대신 이미지 업로드·텍스트 등록 화면이 원문을 넣는다. P-05 이후는 아직 없다.

- security-monitoring 프로젝트(`C:\ai 과제\security-monitoring\backend`)의 화면 프레임워크와 로그인·권한 구조를 그대로 옮겨 왔다. 같은 일을 다시 설계하지 말고 그쪽 방식을 따른다.
- 모듈 두 개: `backend/`(Spring Boot — 화면·API·RDB)와 `ai/`(Python FastAPI — Claude 정형화·Chroma 색인). Spring이 AI 서비스를 HTTP로 부른다.

| 구분 | 내용 |
| --- | --- |
| 언어·프레임워크 | Java 17, Spring Boot 3.3.4 / Python 3.13, FastAPI |
| 빌드 | Maven (`backend/pom.xml`) / `ai/requirements.txt`(버전 고정, `ai/.venv`) |
| 기본 패키지 | `com.sjinc.trendanalysis` |
| 화면 | Thymeleaf 서버 렌더링 + 바닐라 JS 공통 모듈 |
| 정형 DB | H2 파일 DB `backend/data/trendAnalysis`, `ddl-auto=update` |
| 벡터 DB | Chroma — 지금 내장형(`ai/chroma_data/`), 시스템 오픈 때 서버형(설정 `CHROMA_MODE`만 바꿈) |
| LLM | Claude `claude-sonnet-5`(설정 `claude.model`), 키는 Spring `claude.api.key` |
| 임베딩 | `Qwen/Qwen3-VL-Embedding-2B`(sentence-transformers, 2048차원, 설정 `EMBEDDING_MODEL`). 다른 이름을 주면 fastembed로 돈다 |
| 인증 | Spring Security 폼 로그인 + 프로그램(화면) 단위 권한 |

## 2. 모듈 구성

### backend (Spring)

| 패키지 / 위치 | 역할 |
| --- | --- |
| `config` | `SecurityConfig`(보안 정책), `DataInitializer`(기본 데이터 시드), `ImageUploadConfig`(업로드 크기 상한), `AnalysisAsyncConfig`(분석 스레드 2개) |
| `security` | 프로그램 권한 검사: `RequiresProgram`, `RequiresProgramAuthorizationManager`, `ProgramAccessGuard`, `CsrfCookieFilter` |
| `mvc` | 화면 컨트롤러 `ViewController`, 공통 모델(`SidebarModelAdvice`, `TopbarModelAdvice`), 예외 변환(`ApiExceptionAdvice`, `UploadSizeExceptionAdvice`) |
| `controller` | REST API (`/api/**`) |
| `service` | 업무 로직 — `program`, `user`, `permission`, `comcd`, `image`, `analysis` |
| `service/analysis` | P-03·P-04: `DocumentService`(원문 등록·조회·삭제), `AnalysisService`(정형화 호출·검증·저장·색인), `TrendResultValidator`(스키마 검증, 순수 클래스), `AiClient`(FastAPI 호출), `TrendCodes`/`TrendCodeBook`(표준 코드) |
| `repository` | Spring Data JPA |
| `domain` | 엔티티 + `ProgramButton`(공통 버튼 종류) |
| `dto` | 화면·API 요청·응답 record |
| `templates/` | `home`(탭 셸), `login`, `fragments/*`(공통 조각), `program/*`(단위 화면) |
| `static/css`, `static/js` | 화면 공통 스타일·스크립트(`trend-analysis.js` — 분석 상태 뱃지·결과 모달) |

### ai (Python FastAPI)

| 파일 | 역할 |
| --- | --- |
| `main.py` | API: `POST /structure`(P-03), `POST /index`·`DELETE /index/{doc_id}`(P-04), `GET /health` |
| `structurer.py` | Claude Structured Output 호출. 카테고리·소재·컬러·실루엣은 요청에 온 공통코드 값만 enum으로 허용 |
| `image_prep.py` | 이미지를 긴 변 2576px·5MB 이하로 줄여 보냄(BMP 등은 JPEG로) |
| `indexer.py`, `chunker.py` | 청크 분할·Chroma 저장(문서 ID 단위로 갈아끼움) |
| `vector_store.py` | **Chroma 클라이언트를 만드는 유일한 곳** — `CHROMA_MODE`(embedded/http)로 내장형·서버형 선택 |
| `embedding.py` | 임베딩 함수(모델 이름으로 Qwen3-VL / fastembed 선택, Qwen은 질의에만 지시문) |
| `config.py` | `ai/.env` + 환경변수 설정 |

## 3. 화면

### 트렌드 분석

| 화면 (programId) | API | 권한 |
| --- | --- | --- |
| 이미지 업로드 (`img-upload`) | `/api/images`(목록·업로드·삭제), `/{id}/file`(원본), `/{id}/analysis`(결과), `/{id}/reanalyze` | `img-upload` |
| 텍스트 등록 (`text-upload`) | `/api/texts`(목록·등록·삭제), `/{id}/analysis`, `/{id}/reanalyze` | `text-upload` |

- 이미지: 한 번에 최대 50장, 한 장 20MB, 요청 합계 200MB(`image.upload.*`). 파일은 `image.upload.dir`(기본 `backend/uploads/images`)에 `UUID.확장자`로 둔다. 형식은 파일 시그니처로 판별한다(`ImageType`: jpg·png·gif·webp·bmp, SVG 제외).
- 텍스트: 본문 붙여넣기 또는 .txt(브라우저에서 읽어 본문 칸에 넣음), 최대 50,000자.
- 등록하면 원문 문서(`source_documents`)가 생기고 분석 대기열에 들어간다. 목록의 "분석" 칸이 대기 → 분석 중 → 완료/실패로 바뀌며, 끝나지 않은 행이 있으면 5초마다 조용히 다시 읽는다. 행을 누르면 결과 모달(정형 결과·정형 JSON·실패 사유·재분석)이 뜬다.
- **출처:** 지금은 테스트 단계라 업로드 이미지는 매체명 "직접 업로드"·게시일=업로드일·URL=이미지 주소로 대신한다. 텍스트는 입력값을 쓰고, 비우면 "직접 등록"·오늘이다(2026-10-20 사용자 결정).

### 관리

| 화면 (programId) | API | 권한 |
| --- | --- | --- |
| 프로그램 관리 (`program-mng`) | `/api/programs` | 조회: `program-mng` 또는 `user-permission-mng` / 변경: `program-mng` |
| 사용자 관리 (`user-mng`) | `/api/users` | 조회: `user-mng` 또는 `user-permission-mng` / 변경: `user-mng` |
| 사용자별 권한관리 (`user-permission-mng`) | `/api/permissions/{userId}` | `user-permission-mng` |
| 공통코드마스터 관리 (`com-cd-master-mng`) | `/api/com-cd-groups` | 조회: 두 공통코드 화면 중 하나 / 변경: `com-cd-master-mng` |
| 공통코드 관리 (`com-cd-mng`) | `/api/com-cds` | 사용중 코드 조회: 로그인만 / `includeInactive`·변경: `com-cd-mng` |

`/api/users/me/password`(본인 비밀번호 변경)는 로그인만 요구한다.

## 4. 데이터

| 테이블 | 내용 | 프로세스 |
| --- | --- | --- |
| `uploaded_images` | 업로드 이미지 메타(파일은 디스크) | — |
| `source_documents` | 원문 문서: 문서 ID(`IMG-yyyyMMdd-NNNNNN`/`TXT-…`), 유형, 본문, 이미지 id, 출처(매체·게시일·URL), 처리 상태(PENDING/PROCESSING/DONE/FAILED), 시도 횟수, 실패 사유 | P-01·02 대체 |
| `trend_items` | 문서당 하나: 카테고리, 감성, 요약, 정형 JSON 전체(출처 포함), 모델 | P-03 |
| `trend_item_values` | 키워드·소재·컬러·실루엣을 한 행씩(`value_type`, `item_value`) — P-05가 값별 문서 수를 셀 수 있게 | P-03 |
| Chroma `trend_chunks_qwen3vl` | 청크 텍스트·임베딩·메타(doc_id, 매체, 게시일, URL, 카테고리). 문서마다 요약 청크 1개 + 텍스트는 본문 청크 | P-04 |

### 기본 데이터 (`DataInitializer`, 없을 때만 넣음)

- 공통코드: `ROLE`(ADMIN 1, USER 2 — 정렬순서가 역할 등급), `MENU_GROUP`(TREND "트렌드 분석" 1, MNG "관리" 99), 트렌드 표준 코드 초안 `TREND_CATEGORY`(9)·`TREND_MATERIAL`(16)·`TREND_COLOR`(18)·`TREND_SILHOUETTE`(10)
- 프로그램: 이미지 업로드·텍스트 등록(`menuGroup=TREND`), 관리 화면 5종(`menuGroup=MNG`)
- 사용자: `admin`(ADMIN). 사용자가 0명일 때만 만든다. 비밀번호는 무작위로 만들고 기동 로그에 한 번만 출력한다. 모든 프로그램 권한을 받는다.
- 이미 있는 값은 덮어쓰지 않는다(화면에서 사람이 고쳤을 수 있다).

## 5. 상세 문서 목차

| 문서 | 내용 |
| --- | --- |
| [`architecture.md`](architecture.md) | 요청 처리 흐름, 분석(P-03·P-04) 흐름, 화면 프레임워크, 메뉴·권한 데이터 관계 |
| [`llm-development.md`](llm-development.md) | 코딩 규칙 · 테스트 · 예외 처리 · 보안 원칙 (규범) |
| [`testing.md`](testing.md) | 테스트 구성 · 검증 데이터 · 회귀 테스트 |
