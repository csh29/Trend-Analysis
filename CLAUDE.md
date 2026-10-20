# CLAUDE.md

이 파일은 **어떤 문서를 읽을지 고르는 라우터**다. 실제 내용은 `docs/llm/` 아래 문서에 나뉘어 있다.

## docs/llm/ — 분석·설계·기능별 상세 문서

| 문서 | 내용 |
| --- | --- |
| [`docs/llm/llm-analysis.md`](docs/llm/llm-analysis.md) | 전체 구조 요약 및 상세 문서 목차 |
| [`docs/llm/llm-development.md`](docs/llm/llm-development.md) | 코딩 규칙 · 테스트 · 예외 처리 · 보안 원칙 |
| [`docs/llm/architecture.md`](docs/llm/architecture.md) | 시스템 구조·데이터 흐름·모듈 관계 |
| [`docs/llm/testing.md`](docs/llm/testing.md) | 테스트 전략·검증 데이터·회귀 테스트 |

작업을 시작할 때 `llm-analysis.md`를 먼저 읽고, 거기 목차에서 필요한 상세 문서로 들어간다.
코드를 쓰거나 고칠 때는 `llm-development.md`도 읽는다.

## 문서와 무관하게 항상 지키는 것

- **파이썬은 `py`로 실행한다.** 이 PC의 `python` / `python3`은 Microsoft Store 스텁이라 exit 49로 죽는다.
- **한글이 포함된 경로는 큰따옴표로 감싼다**(`C:\ai 과제\...`).
- **주석·로그·커밋 메시지는 한국어로 쓴다.**
- 구조를 바꿨으면 해당 문서(`docs/llm/`)와 `backend/README.md`를 같이 고친다.

## 그 외 문서

- [`backend/README.md`](backend/README.md) — 사람이 읽는 프로젝트 소개(개발 환경, 의존성, 화면 프레임워크).
