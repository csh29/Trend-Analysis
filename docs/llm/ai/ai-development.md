# ai-development.md — AI 서비스(Python) 개발

`ai/`(Python 3.13 FastAPI) 실행, 설정, 구조, 코딩 규칙, 테스트. Spring과 주고받는 분석 흐름은 [`analysis-pipeline.md`](../features/analysis-pipeline.md)에 있다.

## 1. 실행

평소에는 `ai/start-ai.cmd`(기동)·`ai/stop-ai.cmd`(종료)를 쓴다(더블클릭 가능, 본체는 같은 이름의 `.ps1`).

- 기동은 백그라운드로 띄우고 `/health`가 응답할 때까지 기다린다. 이미 떠 있으면 다시 띄우지 않는다. `.venv`가 없으면 만들고 패키지를 설치한다.
- 로그는 `ai/logs/ai-server.log`·`ai-server.err.log`(uvicorn·분석 로그는 err 쪽), PID는 `ai/.ai-server.pid` — 둘 다 커밋 제외. 기동할 때 이전 로그는 `ai/logs/archive/`에 날짜를 붙여 옮긴다(최근 20개).
- 가상환경 `python.exe`는 실제 파이썬을 자식으로 띄운다. 그래서 종료는 프로세스 트리째(`taskkill /T`) 하고, PID 파일이 없으면 포트 8001을 듣는 이 폴더의 uvicorn만 골라 끈다.
- `.ps1`은 UTF-8 BOM, `.cmd`는 CP949로 저장한다(PowerShell 5.1·cmd가 BOM 없는 UTF-8 한글을 깨뜨린다).

직접 띄울 때(`ai` 폴더):

```bash
py -m venv .venv
```

```bash
.venv/Scripts/python -m pip install -r requirements.txt
```

```bash
.venv/Scripts/python -m uvicorn main:app --host 127.0.0.1 --port 8001
```

- 워커는 1개로 띄운다(`--workers`를 주지 않는다). 내장형 Chroma 폴더는 한 프로세스만 열어야 안전하다.
- 첫 색인 때 임베딩 모델 `Qwen/Qwen3-VL-Embedding-2B`(약 4GB)를 내려받는다(`~/.cache/huggingface`). 모델은 첫 색인·검색 때 올라가며 RAM을 약 4.5GB 쓴다.
- torch는 CPU 빌드다(이 PC GPU는 VRAM 2GB라 모델이 안 올라간다). CPU에서는 청크 하나에 수 초 걸려 긴 텍스트 색인이 느리다 — Spring의 AI 호출 대기 시간(3분)을 넘기면 색인 실패로 남는다.

### 색인(Chroma) 데이터 조회

AI 서비스를 띄운 뒤 브라우저에서 http://127.0.0.1:8001/docs (FastAPI 기본 화면)를 열고 아래 API를 "Try it out"으로 부른다.

| API | 내용 |
| --- | --- |
| `GET /index/stats` | 컬렉션 이름, 내장형/서버형, 임베딩 모델, 청크 수 |
| `GET /index/chunks?doc_id=&limit=20&offset=0&with_embedding=false` | 청크 id(`문서ID#순번`), 청크 문장, 메타(doc_id·유형·매체·게시일·URL·카테고리·순번). `with_embedding=true`면 벡터 차원과 앞 8개 값 |

- 실행 중인 서비스를 통해 읽는다. 내장형 Chroma 폴더(`chroma_data/`)를 다른 프로세스로 직접 열지 않는다(한 프로세스만 열어야 안전하다).
- 서버형(`CHROMA_MODE=http`)으로 바꿔도 같은 API로 본다.

## 2. 설정 (`ai/.env`)

`ai/.env`(커밋 제외, `.env.example`을 복사)를 읽고, 같은 이름의 환경변수가 있으면 그 값이 이긴다. Claude API 키는 여기 두지 않는다(Spring에서 요청 헤더로 받는다).

| 키 | 기본값 | 용도 |
| --- | --- | --- |
| `CHROMA_MODE` | `embedded` | `embedded`(로컬 폴더) / `http`(Chroma 서버). 시스템 오픈 때 `http`로 바꾼다 |
| `CHROMA_PATH` | `./chroma_data` | 내장형 폴더(`ai/` 기준) |
| `CHROMA_HOST`·`CHROMA_PORT` | `localhost`·`8000` | 서버형 주소 |
| `CHROMA_COLLECTION` | `trend_chunks_qwen3vl` | 컬렉션 이름(3자 이상) |
| `EMBEDDING_MODEL` | `Qwen/Qwen3-VL-Embedding-2B` | `Qwen/Qwen3-VL-Embedding-*`은 sentence-transformers, 그 밖의 이름은 fastembed로 돈다 |
| `CHUNK_SIZE`·`CHUNK_OVERLAP` | `500`·`80` | 본문 청크 분할(문자 수) |

## 3. 파일 구성

| 파일 | 역할 |
| --- | --- |
| `main.py` | API: `POST /structure`(P-03), `POST /index`·`DELETE /index/{doc_id}`(P-04), `GET /index/stats`·`GET /index/chunks`(색인 조회, 읽기 전용), `GET /health` |
| `prompts/structure-common.md`·`-silhouette.md`·`-image.md`·`-text.md` | **Claude가 읽는 글**. 공통 → 실루엣 기준 → 자료 유형 규칙 순으로 붙여 보낸다(이미지 분석에 텍스트 기준을 보내지 않는다). `## 키` 구역 단위 |
| `prompt_loader.py` | `prompts/*.md` 읽기. 구역이 빠지면 기동 실패 |
| `structurer.py` | Claude Structured Output 호출. 카테고리·소재·컬러·실루엣은 요청에 온 공통코드 값만 enum으로 허용 |
| `image_prep.py` | 이미지를 긴 변 2576px·5MB 이하로 줄여 보냄(BMP 등은 JPEG로) |
| `indexer.py`, `chunker.py` | 청크 분할·Chroma 저장(문서 ID 단위로 갈아끼움) |
| `vector_store.py` | **Chroma 클라이언트를 만드는 유일한 곳** — `CHROMA_MODE`로 내장형·서버형 선택 |
| `embedding.py` | 임베딩 함수(모델 이름으로 Qwen3-VL / fastembed 선택, Qwen은 질의에만 지시문) |
| `config.py` | `ai/.env` + 환경변수 설정 |
| `start-ai.*`, `stop-ai.*` | 기동·종료 스크립트 |
| `requirements.txt` | 버전 고정. torch는 CPU 빌드(`--extra-index-url .../whl/cpu`) |

## 4. 코딩 규칙

- **Claude가 읽는 글은 코드에 쓰지 않고 `prompts/*.md`에 둔다.** 이미지·텍스트 둘 다에 해당하는 규칙은 `structure-common.md`에만 쓴다(양쪽에 복사하면 고칠 때 어긋난다). 한쪽 유형에만 해당하는 규칙은 그 유형 파일에 쓴다. 판단 기준(무엇을 뽑고 무엇을 비울지)은 md, 출력 형식(필드·코드 enum)은 `build_schema`, 조립 틀(`<document>` 감싸기)은 코드다. md를 고치면 AI 서비스를 재기동한다(기동 때 한 번 읽는다). `## 키` 제목은 코드가 찾는 이름이라 바꾸지 않는다. md 주석(`<!-- -->`)은 보내지 않는다.
- **Chroma 클라이언트는 `vector_store.py`의 `create_client`에서만 만든다.** 다른 모듈은 `get_collection()`만 쓴다. 내장형↔서버형 전환을 설정(`CHROMA_MODE`) 하나로 끝내기 위한 규칙이다.
- 임베딩 모델을 바꾸면 기존 컬렉션과 섞이지 않게 `CHROMA_COLLECTION`을 새로 주거나 재색인한다(같은 컬렉션으로 열면 기동이 막힌다).
- 패키지 버전은 `requirements.txt`에 고정한다. Chroma는 서버형으로 갈 때 서버와 같은 버전이어야 하고, 임베딩 결과가 라이브러리 버전에 따라 달라지면 기존 색인과 어긋난다.
- Claude 호출 방식(모델 ID, `output_config`, 오류 클래스)은 추측하지 말고 claude-api 스킬 문서로 확인한다. SDK는 `anthropic` 1.x다.
- 오류는 `HTTPException(detail={"message", "retryable"})`로 돌려준다. Spring `AiClient`가 이 형식을 읽어 재시도 여부를 정한다.
- 보안: AI 서비스는 127.0.0.1에만 연다(인증 없는 내부 API). Claude API 키는 요청 헤더(`X-Claude-Api-Key`)로만 받고 저장·로그·응답에 남기지 않는다.

## 5. 테스트

```bash
.venv/Scripts/python -m unittest
```

`ai/` 폴더에서 돌린다. 네트워크·실제 Claude 호출·모델 다운로드가 없다(가짜 클라이언트·가짜 컬렉션·가짜 모델).

| 테스트 | 범위 |
| --- | --- |
| `test_core.py` | 설정(.env·환경변수·모드 검증), **Chroma 내장형/서버형 선택**, 임베딩 구현 선택·질의 지시문, 청크 분할, 이미지 축소·변환, 스키마 enum, Claude 오류 분류(거절·잘림·연결)·토큰 사용량, 색인 청크(코드 이름 사용·갈아끼움) |
| `test_api.py` | FastAPI: 키 없음 401, 키 전달·응답에 키 미포함, 오류의 retryable·소모 토큰(usage) 전달, 색인 조회(읽기 전용·벡터는 앞부분만), 빈 본문·깨진 이미지 400, 색인·삭제 |

| 변경 | 확인할 것 |
| --- | --- |
| `vector_store`·`config` | `CHROMA_MODE`만으로 내장형/서버형이 바뀜(코드 수정 없이) |
| `embedding` | 모델을 바꿨으면 실제 모델로 한국어 질의 비교("가죽 아우터", "청바지 트렌드", "니트 유행")를 다시 돌린다 |
| `structurer`·`indexer` | [`analysis-pipeline.md`](../features/analysis-pipeline.md) 회귀 항목 |
