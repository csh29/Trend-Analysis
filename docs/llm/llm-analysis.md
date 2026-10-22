# llm-analysis.md — 전체 요약 · 문서 목차

**사실 문서** — 지금 시스템이 어떻게 되어 있는가. 작업 전에 먼저 읽고, 아래 목차에서 필요한 문서로 들어간다.
문서와 코드가 어긋나면 코드가 맞다. 확인한 자리에서 문서를 고친다.

## 1. 시스템 개요

패션 트렌드 분석 시스템. 기준 문서는 `C:\ai 과제\시스템 프로세스 정의서.pptx`(P-01~P-08)다.

- security-monitoring 프로젝트(`C:\ai 과제\security-monitoring\backend`)의 화면 프레임워크와 로그인·권한 구조를 그대로 옮겨 왔다. 같은 일을 다시 설계하지 말고 그쪽 방식을 따른다.
- 모듈 두 개: `backend/`(Spring Boot — 화면·API·RDB)와 `ai/`(Python FastAPI — Claude 정형화·Chroma 색인·RAG 답변). Spring이 AI 서비스를 HTTP로 부른다.

| 구분 | 내용 |
| --- | --- |
| 언어·프레임워크 | Java 17, Spring Boot 3.3.4 / Python 3.13, FastAPI |
| 빌드 | Maven (`backend/pom.xml`) / `ai/requirements.txt`(버전 고정, `ai/.venv`) |
| 기본 패키지 | `com.sjinc.trendanalysis` |
| 화면 | Thymeleaf 서버 렌더링 + 바닐라 JS 공통 모듈 |
| 정형 DB | H2 파일 DB `backend/data/trendAnalysis`, `ddl-auto=update` |
| 벡터 DB | Chroma — 지금 내장형(`ai/chroma_data/`), 시스템 오픈 때 서버형(설정 `CHROMA_MODE`만 바꿈) |
| LLM | Claude `claude-sonnet-5`(설정 `claude.model`), 키는 Spring `claude.api.key` |
| 임베딩 | `Qwen/Qwen3-VL-Embedding-2B`(sentence-transformers, 2048차원, 설정 `EMBEDDING_MODEL`) |
| 인증 | Spring Security 폼 로그인 + 프로그램(화면) 단위 권한 |

## 2. 프로세스 구현 현황

| 프로세스 | 상태 | 문서 |
| --- | --- | --- |
| P-01 수집 · P-02 전처리 | 대신 이미지 업로드·텍스트 등록 화면이 원문을 넣는다(원문 1건 = 본문 + 이미지 최대 5장) | [`features/document-upload.md`](features/document-upload.md) |
| P-03 AI 정형화 · P-04 저장·색인 | 구현 | [`features/analysis-pipeline.md`](features/analysis-pipeline.md) |
| P-05 트렌드 집계 | 구현(주간 언급량·전주/전월 대비·급상승, 트렌드 분석 화면) | [`features/trend-aggregation.md`](features/trend-aggregation.md) |
| P-06 RAG 검색 | 구현(홈 질문창 — 질문·답변·출처, 근거 없으면 차단. 만족도 평가는 P-08 때) | [`features/rag-search.md`](features/rag-search.md) |
| P-07 브리핑 · P-08 로그·KPI | 아직 없음 | — |

그 밖에 구현된 것: 화면 프레임워크, 로그인·권한, 관리 화면 5종, 홈 대시보드(트렌드 집계·분석 현황 카드 + RAG 질문창, `HomeService`).

## 3. 문서 목차

### 공통

| 문서 | 내용 |
| --- | --- |
| [`llm-development.md`](llm-development.md) | 모든 작업에 적용하는 규칙(작업 순서, 주석·문서, 시크릿, 체크리스트) |

### 영역별 개발 — 빌드·실행·구조·코딩 규칙·테스트

| 문서 | 내용 |
| --- | --- |
| [`backend/backend-development.md`](backend/backend-development.md) | Spring: 빌드·실행, 패키지 구조, 요청 흐름, 레이어·예외 처리, 공통 보안 원칙, 테스트 방법, 기본 데이터 |
| [`ai/ai-development.md`](ai/ai-development.md) | Python AI 서비스: 실행(start/stop), 설정(.env), 파일 구성, 코딩 규칙, 테스트 |

### 기능별 — 동작·데이터·API·규칙·테스트

| 문서 | 기능 |
| --- | --- |
| [`features/auth-permission.md`](features/auth-permission.md) | 로그인, 보안 필터, 프로그램 권한 검사, CSRF, 메뉴·버튼 권한 반영 |
| [`features/screen-framework.md`](features/screen-framework.md) | 탭 셸, 화면 그리는 과정, 공통 fetch 래퍼, 공통 JS, 새 화면 추가, 화면 코드 규칙 |
| [`features/admin-screens.md`](features/admin-screens.md) | 관리 화면 5종(프로그램·사용자·사용자별 권한·공통코드 마스터/디테일), 메뉴·권한 데이터 관계 |
| [`features/document-upload.md`](features/document-upload.md) | 이미지 업로드·텍스트 등록, 원문 문서, 출처 |
| [`features/analysis-pipeline.md`](features/analysis-pipeline.md) | P-03 정형화·P-04 저장·색인(Spring↔AI), 표준 코드, 임베딩·Chroma, 분석 로그 |
| [`features/trend-aggregation.md`](features/trend-aggregation.md) | P-05 주간 집계·급상승 판정 기준, 배치, 샘플 데이터 |
| [`features/rag-search.md`](features/rag-search.md) | P-06 RAG 검색: 근거 기준 유사도, 근거 없음 처리, 집계 덧붙이기 |

### 어떤 문서를 읽나

| 작업 | 읽을 문서 |
| --- | --- |
| Spring 코드 수정 | `backend/backend-development.md` + 해당 기능 문서 |
| `ai/` 코드 수정 | `ai/ai-development.md` + `features/analysis-pipeline.md` |
| 새 화면 추가 | `features/screen-framework.md` → `features/auth-permission.md` |
| 권한·로그인 | `features/auth-permission.md` |
| 분석 결과·토큰·임베딩 | `features/analysis-pipeline.md` |
| 집계·급상승·트렌드 화면 | `features/trend-aggregation.md` |
| RAG 질문·답변·근거 기준 | `features/rag-search.md` |
