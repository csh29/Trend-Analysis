# llm-analysis.md — 전체 요약

**사실 문서** — 지금 시스템이 어떻게 되어 있는가. 작업 전에 먼저 읽는다. 어떤 문서로 들어갈지는 루트 [`CLAUDE.md`](../../CLAUDE.md)의 문서 표에서 고른다.
문서와 코드가 어긋나면 코드가 맞다. 확인한 자리에서 문서를 고친다.

## 1. 시스템 개요

패션 트렌드 분석 시스템. 기준 문서는 `C:\ai 과제\시스템 프로세스 정의서.pptx`(P-01~P-08)다.

- 화면 프레임워크(탭 셸·공통 JS)와 로그인·권한 구조는 security-monitoring 프로젝트(`C:\ai 과제\security-monitoring\backend`)의 것을 그대로 쓴다. 이 구조를 바꾸거나 새로 설계하지 말고 기존 코드를 따른다. 원본 레포는 이 레포에 없는 화면 패턴을 새로 만들 때만 참고한다.
- 모듈 두 개: `backend/`(Spring Boot — 화면·API·RDB)와 `ai/`(Python FastAPI — Claude 정형화·Chroma 색인·RAG 답변). Spring이 AI 서비스를 HTTP로 부른다.

| 구분 | 내용 |
| --- | --- |
| 언어·프레임워크 | Java 17, Spring Boot 3.3.4 / Python 3.13, FastAPI |
| 빌드 | Maven (`backend/pom.xml`) / `ai/requirements.txt`(버전 고정, `ai/.venv`) |
| 기본 패키지 | `com.sjinc.trendanalysis` |
| 화면 | Thymeleaf 서버 렌더링 + 바닐라 JS 공통 모듈 |
| 정형 DB | H2 파일 DB `backend/data/trendAnalysis`, `ddl-auto=update` |
| 벡터 DB | Chroma 내장형(`ai/chroma_data/`). 서버형 전환 기준·방법은 [`features/analysis-pipeline.md`](features/analysis-pipeline.md) |
| LLM | Claude `claude-sonnet-5`(정형화·답변, 설정 `claude.model`), `claude-haiku-5-5`(질문 해석, 설정 `claude.interpret-model`), 키는 Spring `claude.api.key` |
| 임베딩 | `Qwen/Qwen3-VL-Embedding-2B`(sentence-transformers, 2048차원, 설정 `EMBEDDING_MODEL`) |
| 인증 | Spring Security 폼 로그인 + 프로그램(화면) 단위 권한 |

## 2. 구현 현황

| 범위 | 상태 | 문서 |
| --- | --- | --- |
| P-01 수집 · P-02 전처리 | 대신 이미지 업로드·텍스트 등록 화면이 원문을 넣는다(원문 1건 = 본문 + 이미지 최대 5장) | [`features/document-upload.md`](features/document-upload.md) |
| P-03 AI 정형화 · P-04 저장·색인 | 구현 | [`features/analysis-pipeline.md`](features/analysis-pipeline.md) |
| P-05 트렌드 집계 | 구현(주간 언급량·전주/전월 대비·급상승, 트렌드 분석 화면) | [`features/trend-aggregation.md`](features/trend-aggregation.md) |
| P-06 RAG 검색 | 구현(홈 트렌드 챗봇 — AI 질문 해석·이어 묻기, 유행 질문은 Claude가 집계 도구를 불러 답하고(실패하면 집계 한 번), 그 밖은 근거 검색. 근거 없으면 차단. 만족도 평가는 P-08에서) | [`features/rag-search.md`](features/rag-search.md) |
| P-07 브리핑 · P-08 로그·KPI | 없음 | — |
| 화면 프레임워크 · 홈 대시보드 | 구현(탭 셸, 트렌드 집계·분석 현황 카드 + 트렌드 챗봇, `HomeService`) | [`features/screen-framework.md`](features/screen-framework.md) |
| 로그인·권한 | 구현 | [`features/auth-permission.md`](features/auth-permission.md) |
| 관리 화면 5종 | 구현 | [`features/admin-screens.md`](features/admin-screens.md) |
