# analysis-pipeline.md — AI 분석 (P-03 정형화 · P-04 저장·색인)

원문 한 건을 Claude로 정형화(의류 아이템별 카테고리·소재·컬러·실루엣 + 문서 단위 키워드·감성·요약)하고, 검증해 RDB에 저장한 뒤 Chroma에 색인한다. Spring과 AI 서비스(`ai/`)에 걸친 기능이다.
코드: `service/analysis`(`AnalysisService` 흐름, `TrendResultValidator` 검증, `AiClient` 호출), `ai/structurer.py`·`indexer.py`·`embedding.py`·`vector_store.py`. 실행·설정은 [`ai-development.md`](../ai/ai-development.md).

## 결정 사항 (2026-10-20 사용자)

- AI 처리는 Python **FastAPI 서버**로 띄우고 Spring이 호출한다.
- LLM은 `claude-sonnet-5`(`claude.model`). 키는 Spring `claude.api.key` **한 곳**에만 둔다.
- 표준 코드(카테고리·소재·컬러·실루엣)는 일반 분류 **초안**이다. 확정 체계는 미정(정의서 미결 4번).
- **정형 결과는 의류 아이템 배열**(`items: [{category, item, materials, colors, silhouettes}]`)이다. 정의서 10번 형식(문서당 category 1개)에서 벗어난다 — 코디 사진은 아이템이 여럿이라 한 묶음으로 받으면 속성이 섞인다.
- **소재는 빼지 않되 엄격하게** 뽑는다: 이미지는 질감이 분명할 때만(레더 광택, 데님 워싱·결, 니트 짜임, 퍼, 패딩 퀼팅, 코듀로이 골), 텍스트는 본문에 그 아이템의 소재가 직접 나온 것만. 애매하면 빈 배열 — 틀린 소재보다 비는 게 낫다(정의서 P-05가 소재별 집계를 쓴다). 이미지 소재 언급량이 적게 잡히는 것은 의도된 것이다.
- **실루엣은 카테고리별 기준**(`ai/prompts/structure-silhouette.md`)으로 판단한다. 코드 10개를 핏(오버사이즈·레귤러·슬림)·기장(크롭·롱)·형태(박시·와이드·A·H라인·플레어) 세 축으로 나누고 축마다 최대 1개 — 같은 옷이 박시·오버사이즈·둘 다로 흩어지던 것을 막는다. 정답셋 라벨도 이 기준을 쓴다. 공통코드에 실루엣 코드를 더하면 이 md에도 기준을 적는다.
- **시즌**(공통코드 `TREND_SEASON`: 봄·여름·가을·겨울, 여러 개 가능)은 문서 단위다. 텍스트는 본문에 나온 시즌("올가을", "26SS")을 먼저, 없으면 아이템 두께로 판단하고, 이미지는 옷의 두께·레이어링으로 판단한다. 판단할 수 없으면 비운다(검증도 빈 값을 허용한다).
- Spring과 AI 서비스는 허용 코드 목록을 맞춰 보낸다 — 코드 그룹을 더하면 **둘 다 재기동**한다. 한쪽만 새 버전이면 요청 형식이 달라 분석이 전부 실패한다.
- **의류만** 뽑는다. 신발·가방·액세서리는 뺀다. 카테고리 코드 초안에서도 SHOES·BAG·ACC를 뺐다 — 이미 넣은 DB는 공통코드 관리에서 사용 안 함으로 바꾼다.
- 임베딩은 "일단" `Qwen/Qwen3-VL-Embedding-2B`. 한국어 질의 비교에서 3/3(mpnet 2/3, e5-large 1/3, Chroma 기본 영어 MiniLM 실패). 이 PC는 CPU로 돌아 느리다.
- Chroma는 지금 내장형, 시스템 오픈 때 서버형. 전환은 `ai/.env`의 `CHROMA_MODE`만 바꾼다.

## 규칙

- **검증을 통과한 결과만 저장한다**(정의서 P-03). Structured Output이 형식을 맞춰 줘도 `TrendResultValidator`를 생략하지 않는다.
- **표준 코드 값만 저장한다.** 분석할 때마다 사용중 공통코드를 읽어 Claude 스키마 enum으로 넘긴다 — 공통코드를 바꾸면 재기동 없이 다음 분석부터 적용. 코드 이름의 괄호("레더(가죽)")는 다른 표기를 그 코드로 옮기는 힌트다.
- **출처는 LLM이 만들지 않는다.** Spring이 원문 문서 값을 정형 JSON에 붙인다(정의서 10번 슬라이드 형식).
- **결과 형식·분류 축을 바꾸면 같이 고친다:** `ai/prompts/structure-*.md`(판단 규칙 — 공통·이미지·텍스트), `structurer.build_schema`, `TrendResultValidator`, `AnalysisService`(저장·정형 JSON), `indexer.summary_chunk`, `TrendItem.garments`, 집계(`TrendAggregationService.mentions`)와 근거 원문(`TrendQueryService.mentions`), 결과 모달(`trend-analysis.js`). 한 곳만 고치면 검증에서 전부 실패하거나 집계가 조용히 틀린다. **AI 서비스도 재기동**한다 — 옛 스키마로 떠 있으면 새 검증이 모든 결과를 거부한다.
- 아이템 수 상한(5)은 스키마로 걸 수 없다(구조화 출력은 배열 길이 제약 미지원). 프롬프트로 지시하고 검증에서 앞 5개만 남긴다.
- 저장: 아이템 단위 값(아이템명 `ITEM`·소재·컬러·실루엣)은 `trend_item_values`의 `garment_no`·`category`로 묶는다. `trend_items.category`는 대표(첫) 아이템이다 — NOT NULL 컬럼이라 엔티티에서 빼면 `ddl-auto=update`가 컬럼을 지우지 않아 저장이 깨진다.
- 아이템 배열 이전 결과(`garment_no` 없음)는 `TrendItem.garments()`가 문서 카테고리로 아이템 하나를 만들어 보여 준다. 재분석하면 새 형식이 된다.
- 실패는 예외로 끝내지 않고 문서 `FAILED`·사유로 남겨 화면에서 재분석하게 한다. 재시도 가능(연결·429·5xx·형식 오류)과 불가(키 오류·거절)를 구분한다 — FastAPI 오류 본문 `{"detail": {"message", "retryable", "usage"?}}`.
- **Claude 키는 요청 헤더로만** AI 서비스에 넘긴다. 로그·응답·예외 메시지에 넣지 않는다. AI 서비스는 127.0.0.1에만 연다(인증 없는 내부 API — 다른 서버로 분리하면 내부 토큰·TLS부터).
- 외부 호출은 트랜잭션 밖, 트렌드 항목 저장만 짧은 트랜잭션.
- 색인 청크: 문서마다 정형 결과를 **코드값 대신 이름**으로 쓴 요약 청크 1개(한국어 질문과 맞추려고) + 텍스트는 본문 청크(500자/80자 겹침). 재분석이면 그 문서 청크를 갈아끼운다.
- 임베딩은 문서는 그대로, **질의에만 영어 지시문**을 붙인다(Qwen 모델 카드 권장, Chroma가 질의 때 `embed_query`를 부른다).
- 컬렉션에 임베딩 모델을 기록하고, 다른 모델로 열면 기동을 막는다(섞이면 검색이 조용히 틀린다). 모델을 바꾸면 새 컬렉션 + 재분석.
- 토큰은 과금 근거라 호출마다 로그에 남긴다. 응답 뒤 실패(거절·잘림·JSON 오류)도 토큰이 과금되므로 오류 본문 `usage`로 넘겨 누적에 넣는다.

## 주의점

- 서버가 분석 도중 내려가면 PENDING·PROCESSING이 남는다 — 기동 직후 다시 대기열에 넣는다.
- 분석 스레드는 2개뿐이다(Claude 429·내장형 Chroma 동시 쓰기를 줄이려고). 나머지는 대기열에서 기다린다.
- `AiClient`는 java.net.http + **HTTP/1.1**이다. `HttpURLConnection`은 401 본문(실패 사유)을 버렸고, HTTP/2 업그레이드는 uvicorn이 본문을 못 받아 422가 났다. 요청 본문은 chunked로 간다(Content-Length 없음).
- CPU 임베딩은 느려서 긴 텍스트 색인이 Spring 응답 대기(3분)를 넘기면 "색인 실패"로 남는다.
- 저해상도 이미지는 소재(데님 결 등)를 놓친다. 판단 근거는 지금 저장하지 않는다.
- 이미지 자체 임베딩은 아직 안 한다(요약 문장만 색인).
- 누적 토큰은 메모리 값이라 재기동하면 0부터다.

## 확인할 것

- 분석 흐름 테스트는 문서를 저장소에 바로 넣고 `analyze(id)`를 직접 부른다(등록 API로 넣으면 분석 스레드와 겹친다).
- 연결 확인: 일부러 틀린 키로 문서를 올려 "Claude API 키가 올바르지 않습니다"로 실패하면 Spring → FastAPI → Claude까지 연결된 것이다. "AI 서비스에 연결하지 못했습니다"면 `ai/start-ai.cmd`.
- 색인 데이터는 AI 서비스의 `/docs` → `GET /index/stats`·`/index/chunks`로 본다.
