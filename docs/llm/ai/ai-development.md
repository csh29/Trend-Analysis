# ai-development.md — AI 서비스(Python) 개발

`ai/`(Python 3.13 FastAPI) 실행, 설정, 구조, 코딩 규칙, 테스트. Spring과 주고받는 분석 흐름은 [`analysis-pipeline.md`](../features/analysis-pipeline.md)에 있다.

## 1. 실행

평소에는 `ai/start-ai.cmd`(기동)·`ai/stop-ai.cmd`(종료)·`ai/restart-ai.cmd`(종료 후 기동 — 프롬프트·코드 수정 뒤)를 쓴다(더블클릭 가능, 본체는 같은 이름의 `.ps1`). `.cmd`는 인자를 그대로 `.ps1`에 넘기므로 아무 말이나 붙이면 첫 매개변수(`-BindHost`)가 된다 — 옵션은 이름을 붙여 쓴다(`restart-ai.cmd -Port 8001`). 종료는 포트와 상관없이 `.ai-server.pid`의 프로세스도 끈다. 재기동 성공 여부는 종료 코드가 아니라 종료 뒤 포트가 비었는지로 본다.

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
.venv/Scripts/python -m uvicorn app.main:app --host 127.0.0.1 --port 8001
```

- 워커는 1개로 띄운다(`--workers`를 주지 않는다). **내장형 Chroma 폴더(`chroma_data/`)는 한 프로세스만 연다** — 조회도 다른 프로세스로 폴더를 직접 열지 말고 실행 중인 서비스 API로 한다(아래).
- 첫 색인 때 임베딩 모델 `Qwen/Qwen3-VL-Embedding-2B`(약 4GB)를 내려받는다(`~/.cache/huggingface`). 모델은 첫 색인·검색 때 올라가며 RAM을 약 4.5GB 쓴다.
- 리랭커 `RERANK_MODEL`(기본 `BAAI/bge-reranker-v2-m3`, 약 2.3GB)은 리랭킹을 켠 첫 검색 때 내려받고 올린다(RAM 약 2.3GB 더, 올리는 데 약 30초). 이 PC(RAM 16GB)에서는 임베딩 모델과 합쳐 약 7GB라 Spring·브라우저와 함께면 빠듯하다.
- torch는 CPU 빌드다(이 PC GPU는 VRAM 2GB라 모델이 안 올라간다). CPU 임베딩 시간은 글자 수에 따라 늘어 이 PC에서 100자 약 5초, 300자 약 14초, 500자(청크 최대 `CHUNK_SIZE`) 약 20초다(`GET /search` 질의 임베딩으로 잰 값, 모델을 처음 올리는 첫 호출은 약 1분 더). Spring의 AI 호출 대기 시간(3분)을 넘기면 색인 실패로 남으므로, 본문이 약 3,500자(500자 청크 약 9개)를 넘으면 색인 실패를 의심한다(제안값).

### 색인(Chroma) 데이터 조회

AI 서비스를 띄운 뒤 브라우저에서 http://127.0.0.1:8001/docs (FastAPI 기본 화면)를 열고 아래 API를 "Try it out"으로 부른다.

- `GET /index/stats`(컬렉션·모드·모델·청크 수), `GET /index/chunks`(문서별 청크·메타), `GET /index/doc-ids`(색인된 문서 ID — Spring 기동 정리용), `GET /search?q=`(질문과 비슷한 청크와 유사도, Claude 호출 없음 — RAG 기준 유사도를 정할 때 점수 분포를 본다. `hybrid=false`면 벡터만, `rerank=true`면 리랭커 점수 `rerank` 순).

- 서버형(`CHROMA_MODE=http`)으로 바꿔도 같은 API로 본다.

## 2. 설정 (`ai/.env`)

`ai/.env`(커밋 제외)를 읽고, 같은 이름의 환경변수가 있으면 그 값이 이긴다. 키·기본값·설명은 `ai/.env.example`(복사해서 쓴다)과 `app/core/config.py`에 있다. Claude API 키는 여기 두지 않는다(Spring에서 요청 헤더로 받는다).

- 내장형↔서버형 전환(`CHROMA_MODE`·`CHROMA_HOST`)의 시점·방법과 임베딩 모델(`EMBEDDING_MODEL`)을 바꿀 때 컬렉션(`CHROMA_COLLECTION`) 처리는 [`analysis-pipeline.md`](../features/analysis-pipeline.md)에 있다.

## 3. 파일 구성

**AI를 다루는 Python 코드(`app/`)와 Claude가 읽는 글(`prompts/`)을 나누고, 코드는 기능(프로세스)별 패키지로 묶는다**(사용자 결정).

```
ai/
├─ app/                    Python 코드 (실행: uvicorn app.main:app)
│  ├─ main.py              FastAPI 앱·라우트(/structure, /index, /interpret, /answer, /agent/step, /brief, /search, /health …)
│  ├─ core/                여러 기능이 같이 쓰는 것
│  │  ├─ config.py         ai/.env + 환경변수 설정
│  │  ├─ prompt_loader.py  prompts/ 아래 md 읽기(이름 = 상대 경로, 예: "structure/common")
│  │  └─ claude.py         Claude 호출·SDK 오류 분류(AiServiceError)·JSON 읽기·토큰 사용량
│  ├─ structuring/         P-03 정형화
│  │  ├─ structurer.py     스키마(공통코드 enum)·프롬프트 조립·호출
│  │  └─ image_prep.py     이미지 축소(긴 변 2576px·5MB, 여러 장이면 장마다 1568px·3MB)
│  ├─ indexing/            P-04 색인
│  │  ├─ indexer.py        청크 만들기·Chroma 저장(문서 ID 단위 갈아끼움)·게시일 숫자 메타
│  │  ├─ chunker.py        본문 청크 분할
│  │  ├─ embedding.py      임베딩 함수(Qwen3-VL / fastembed)
│  │  ├─ keyword_index.py  키워드 색인(BM25, 메모리) — 하이브리드 검색의 키워드 쪽
│  │  └─ vector_store.py   Chroma 클라이언트를 만드는 유일한 곳
│  └─ rag/                 P-06 검색·답변
│     ├─ interpret.py      질문 해석(작은 모델 — 패션 질문인지·유행 질문인지·카테고리·종류·기간, 이어 묻기면 질문 다시 쓰기)
│     ├─ answer.py         유행 질문(Spring 집계 + 예시 원문) / 검색(하이브리드 — 벡터 + 키워드 RRF, 카테고리·기간 필터, 최신성 가중치)·Claude 답변
│     ├─ reranker.py       리랭커(cross-encoder, 기본 bge-reranker-v2-m3) — 질문·청크 쌍을 0~1 점수로
│     ├─ agent.py          도구 경로 한 단계(유행 질문 — Claude가 도구를 고르고 Spring이 실행·반복)
│     └─ brief.py          기획 브리프 서술(8개 구성 JSON — 숫자는 Spring 집계)
├─ prompts/                Claude가 읽는 글(코드에 쓰지 않는다)
│  ├─ structure/           common → silhouette → text / image → article(본문+이미지 우선순위)
│  └─ rag/                 interpret.md(질문 해석) · answer.md(답변 모양·유행 판단·기간·근거 규칙)
├─ tests/                  단위 테스트(5절)
├─ start-ai.* / stop-ai.* / restart-ai.*  기동·종료·재기동 스크립트(ai 폴더에 둔다 — 더블클릭)
└─ requirements.txt        버전 고정. torch는 CPU 빌드
```

- 새 기능은 `app/<기능>/` 패키지로, 그 기능의 프롬프트는 `prompts/<기능>/`에 둔다. 두 기능 이상이 쓰는 것만 `core/`로 올린다.
- 모듈은 `from app.<패키지> import <모듈>`처럼 `app.`부터 쓴다. 실행·테스트는 항상 `ai` 폴더에서 한다(그래야 `app`을 찾는다).
- 경로 기준(`.env`, `chroma_data/`, `prompts/`)은 실행 위치가 아니라 `ai/`다(`config.BASE_DIR`, `prompt_loader.PROMPT_DIR`).

## 4. 코딩 규칙

- **Claude가 읽는 글은 코드에 쓰지 않고 `prompts/` 아래 md에 둔다.**
  - 역할 나누기: 판단 기준(무엇을 뽑고 무엇을 비울지)은 md, 출력 형식(필드·코드 enum)은 `build_schema`, 조립 틀(`<document>` 감싸기)은 코드.
  - 이미지·텍스트 둘 다에 해당하는 규칙은 `structure/common.md`에만 쓴다(양쪽에 복사하면 고칠 때 어긋난다). 한쪽 유형에만 해당하는 규칙은 그 유형 파일에.
  - md를 고치면 AI 서비스를 재기동한다(기동 때 한 번 읽는다). `## 키` 제목은 코드가 찾는 이름이라 바꾸지 않는다.
  - 개발자용 메모는 md 주석(`<!-- -->`)에 쓴다 — Claude에게 가지 않는다.
- **Chroma 클라이언트는 `app/indexing/vector_store.py`의 `create_client`에서만 만든다.** 다른 모듈은 `get_collection()`만 쓴다. 내장형↔서버형 전환을 설정(`CHROMA_MODE`) 하나로 끝내기 위한 규칙이다.
- 패키지 버전은 `requirements.txt`에 고정한다. Chroma는 서버형으로 갈 때 서버와 같은 버전이어야 하고, 임베딩 결과가 라이브러리 버전에 따라 달라지면 기존 색인과 어긋난다.
- Claude 호출 방식(모델 ID, `output_config`, 오류 클래스)은 추측하지 말고 claude-api 스킬 문서로 확인한다. SDK는 `anthropic` 1.x다.
- 오류는 `AiServiceError`(`app/core/claude.py`)로 올리고 라우트가 `HTTPException(detail={"message", "retryable"})`로 돌려준다. Spring `AiClient`가 이 형식을 읽어 재시도 여부를 정한다.
- 보안(127.0.0.1 바인딩, Claude 키는 요청 헤더 `X-Claude-Api-Key`로만)은 [`analysis-pipeline.md`](../features/analysis-pipeline.md) 규칙을 따른다.

## 5. 테스트

```bash
.venv/Scripts/python -m unittest
```

`ai/` 폴더에서 돌린다(`tests/` 패키지를 찾는다). 네트워크·실제 Claude 호출·모델 다운로드가 없다(가짜 클라이언트·가짜 컬렉션·가짜 모델).

`test_core`(설정·Chroma·임베딩·정형화·색인), `test_rag`(P-06 해석·검색·답변), `test_api`(FastAPI 라우트).

| 변경 | 확인할 것 |
| --- | --- |
| `vector_store`·`config` | `CHROMA_MODE`만으로 내장형/서버형이 바뀜(코드 수정 없이) |
| `embedding` | 모델을 바꿨으면 재색인 뒤 `GET /search?q=`로 "가죽 아우터"·"청바지 트렌드"·"니트 유행"을 돌려, 상위 결과가 관련 원문인지와 관련/무관 질문의 점수 차이(`rag.min-score` 판단 근거)를 바꾸기 전 모델과 비교한다 |
| `structurer`·`indexer`·`prompts/structure` | [`analysis-pipeline.md`](../features/analysis-pipeline.md) 규칙의 "같이 고친다" 목록 |
| `rag`·`prompts/rag` | [`rag-search.md`](../features/rag-search.md) 테스트 절(프롬프트는 실제 호출로 확인) |
