# CLAUDE.md

이 파일은 **어떤 문서를 읽을지 고르는 라우터**다. 실제 내용은 `docs/llm/` 아래 문서에 나뉘어 있다.

## docs/llm/ — 공통 · 영역별 개발 · 기능별 문서

| 구분 | 문서 | 내용 |
| --- | --- | --- |
| 공통 | [`docs/llm/llm-analysis.md`](docs/llm/llm-analysis.md) | 전체 구조 요약 · 프로세스 구현 현황 · 문서 목차 |
| 공통 | [`docs/llm/llm-development.md`](docs/llm/llm-development.md) | 모든 작업에 적용하는 규칙(작업 순서 · 주석·문서 · 시크릿 · 체크리스트) |
| backend 개발 | [`docs/llm/backend/backend-development.md`](docs/llm/backend/backend-development.md) | Spring 빌드·실행 · 구조 · 레이어·예외 처리 · 공통 보안 원칙 · 테스트 |
| ai 개발 | [`docs/llm/ai/ai-development.md`](docs/llm/ai/ai-development.md) | Python AI 서비스 실행(start/stop) · 설정 · 파일 구성 · 코딩 규칙 · 테스트 |
| 기능 | [`docs/llm/features/auth-permission.md`](docs/llm/features/auth-permission.md) | 로그인 · 보안 필터 · 프로그램 권한 · CSRF |
| 기능 | [`docs/llm/features/screen-framework.md`](docs/llm/features/screen-framework.md) | 탭 셸 · 공통 fetch · 공통 JS · 새 화면 추가 |
| 기능 | [`docs/llm/features/admin-screens.md`](docs/llm/features/admin-screens.md) | 관리 화면 5종 · 메뉴·권한·공통코드 데이터 |
| 기능 | [`docs/llm/features/document-upload.md`](docs/llm/features/document-upload.md) | 이미지 업로드 · 텍스트 등록 · 원문 문서 |
| 기능 | [`docs/llm/features/analysis-pipeline.md`](docs/llm/features/analysis-pipeline.md) | P-03 정형화 · P-04 저장·색인 · 임베딩·Chroma · 분석 로그 |
| 기능 | [`docs/llm/features/trend-aggregation.md`](docs/llm/features/trend-aggregation.md) | P-05 주간 집계 · 급상승 기준 · 배치 · 샘플 데이터 |

작업을 시작할 때 `llm-analysis.md`를 먼저 읽고, 고칠 영역(backend / ai) 문서와 해당 기능 문서로 들어간다.
코드를 쓰거나 고칠 때는 `llm-development.md`도 읽는다.

## 문서와 무관하게 항상 지키는 것

- **파이썬은 `py`로 실행한다.** 이 PC의 `python` / `python3`은 Microsoft Store 스텁이라 exit 49로 죽는다.
- **한글이 포함된 경로는 큰따옴표로 감싼다**(`C:\ai 과제\...`).
- **주석·로그·커밋 메시지는 한국어로 쓴다.**
- 기능을 바꿨으면 해당 기능 문서(`docs/llm/features/`)를, 영역 공통을 바꿨으면 영역 문서를 고치고, `backend/README.md`도 같이 본다.

## 그 외 문서

- [`backend/README.md`](backend/README.md) — 사람이 읽는 프로젝트 소개(개발 환경, 의존성, 화면 프레임워크).
