# llm-development.md — 개발 규칙

**규범 문서** — 어떻게 작업해야 하는가. 코드를 쓰거나 고치기 전에 읽는다.
지금 무엇이 있는지는 [`llm-analysis.md`](llm-analysis.md)와 [`architecture.md`](architecture.md)에 있다. **먼저 그쪽을 읽고, 이미 있는 것을 다시 만들지 않는다.**

## 1. 코딩 규칙

### 빌드·실행

작업 디렉터리는 `backend/`, JDK는 17이다(`JAVA_HOME`).

```bash
./mvnw clean package
```

```bash
java -jar target/trend-analysis-0.0.1-SNAPSHOT.jar
```

- **`./mvnw spring-boot:run`은 쓰지 않는다.** 경로의 한글(`ai 과제`) 때문에 "Could not find or load main class"로 실패한다. 패키징한 jar로 실행하거나 IDE에서 `TrendAnalysisApplication`을 실행한다.
- `.mvn/wrapper/`는 git에서 제외돼 있다. 새로 클론한 곳에서는 `./mvnw`가 없으니 설치된 `mvn`이나 IDE로 빌드한다.
- 한글 경로는 셸에서 항상 큰따옴표로 감싼다. 파이썬은 `py`로 실행한다(`python`은 Store 스텁이라 exit 49로 죽는다).

### AI 서비스(ai/) 실행

가상환경은 `ai/.venv`다. 설정은 `ai/.env`(커밋 제외, `.env.example`을 복사)에 둔다.

평소에는 `ai/start-ai.cmd`(기동)·`ai/stop-ai.cmd`(종료)를 쓴다(더블클릭 가능, 본체는 같은 이름의 `.ps1`).
- 기동은 백그라운드로 띄우고 `/health`가 응답할 때까지 기다린다. 이미 떠 있으면 다시 띄우지 않는다. `.venv`가 없으면 만들고 패키지를 설치한다.
- 로그는 `ai/logs/ai-server.log`·`ai-server.err.log`(uvicorn 로그는 err 쪽), PID는 `ai/.ai-server.pid` — 둘 다 커밋 제외.
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
- torch는 CPU 빌드다. CPU에서는 청크 하나에 수 초 걸려 긴 텍스트 색인이 느리다 — Spring의 AI 호출 대기 시간(3분)을 넘기면 색인 실패로 남는다.
- Spring 쪽은 `application.properties`의 `claude.api.key`, `claude.model`, `ai.server.url`을 쓴다.

### 분석 로그

토큰은 과금 근거라 호출마다 남긴다. 키는 어느 로그에도 남기지 않는다.

| 위치 | 로그 |
| --- | --- |
| Spring `AnalysisService` | `분석 요청 N건 처리 시작 (전체 대기·처리 중 건수)`, `분석 시작 doc (남은 대기 건수)`, `Claude 토큰 doc attempt in/out/캐시`, `분석 완료·실패 doc 토큰(재시도 합계) \| 기동 후 누적 호출·토큰` |
| AI 서비스 `main.py`·`structurer.py` (`ai/logs/ai-server.err.log`) | `정형화 시작 doc 판단 후보(카테고리·소재·컬러·실루엣 코드 수)`, `Claude 응답 doc stop 소요 토큰`, `색인 완료 청크 수·임베딩 소요` |

- 대기 건수는 DB의 PENDING 수다(분석 스레드 2개가 같이 돌아도 맞다). 누적 토큰은 메모리 값이라 재기동하면 0부터다.
- Claude가 응답한 뒤 실패(거절·잘림·JSON 오류)해도 토큰은 과금된다. AI 서비스가 오류 본문 `detail.usage`로 넘기고 Spring 누적에 넣는다(`AiCallException.getUsage`).

### 레이어와 위치

- 화면은 `mvc/ViewController`, 데이터는 `controller`(REST) → `service` → `repository`로 흐른다. 컨트롤러에 업무 로직을 넣지 않는다.
- 패키지는 기능별 하위 패키지로 나눈다(`service/user`, `dto/comcd` 등). 이름과 역할이 어긋나면 그때 고친다.
- 요청·응답은 `dto`의 record로 받는다. 응답에 엔티티를 그대로 내보낼 때는 민감 필드가 없는지 확인한다. `User`는 반드시 `UserResponse`로 내보낸다(비밀번호 해시).
- 판단 로직이 커지면 Spring 없이 테스트할 수 있는 순수 클래스로 뺀다.
- 아직 한 번뿐인 것을 미리 추상화하지 않는다(구현체 하나뿐인 인터페이스, "나중을 위한" 옵션).

### 새 화면 추가

1. `templates/program/<화면id>.html`을 만든다. 화면 id는 소문자·숫자·하이픈만 쓴다. `com-cd-master-mng.html`의 구조를 따른다.
   - 첫 줄은 `<section th:replace="~{fragments/page-toolbar :: toolbar}"></section>`이다.
   - `app-shell`에 `th:classappend="${param.embed} ? 'app-shell--embedded'"`를 붙인다(탭 안에서 사이드바·상단바 숨김).
2. 화면 API를 만들고 컨트롤러에 `@RequiresProgram("<화면id>")`을 붙인다.
3. 프로그램 관리에 같은 id로 등록하고(메뉴 그룹·아이콘·사용 버튼), 사용자별 권한관리에서 권한을 준다.
   기본으로 있어야 하는 화면이면 `DataInitializer`에 "없을 때만 넣기"로 추가한다.

### AI 분석 코드

- **Chroma 클라이언트는 `ai/vector_store.py`의 `create_client`에서만 만든다.** 다른 모듈은 `get_collection()`만 쓴다. 내장형↔서버형 전환을 설정(`CHROMA_MODE`) 하나로 끝내기 위한 규칙이다.
- 임베딩 모델을 바꾸면 기존 컬렉션과 섞이지 않게 `CHROMA_COLLECTION`을 새로 주거나 재색인한다(같은 컬렉션으로 열면 기동이 막힌다).
- 트렌드 항목에 새 분류 축을 더하면 공통코드 그룹(`TrendCodes`)·Claude 스키마(`structurer.build_schema`)·검증(`TrendResultValidator`)·색인 문장(`indexer.summary_chunk`)을 함께 고친다. 한 곳만 고치면 검증에서 전부 실패한다.
- 정형화 결과는 `TrendResultValidator`를 통과해야 저장한다(프로세스 정의서 P-03 규칙). Structured Output이 형식을 맞춰 줘도 생략하지 않는다.
- 출처를 LLM에게 만들게 하지 않는다. 원문 문서 값만 쓴다.
- Claude 호출 방식(모델 ID, `output_config`, 오류 클래스)은 추측하지 말고 claude-api 스킬 문서로 확인한다. 이 PC의 SDK는 `anthropic` 1.x다.

### 화면 코드 — 공통 모듈을 쓴다

- 그리드: `Grid.renderHeader` / `Grid.render`. `tr`/`td`를 직접 만들지 않는다. 값은 `Grid.getRows(tbody)`로 읽고(`_isNew`, `_selected`), 신규 행은 `Grid.addRow`, 저장 전 행 삭제는 `Grid.removeRows`로 한다.
- 입력 셀은 컬럼 `type`(`text`/`number`/`select`/`checkbox`/`row-select`)으로 만든다. 입력 엘리먼트 생성 함수를 화면마다 만들지 않는다.
- 조회영역: `SearchForm.render`에 필드 정의를 넘긴다. 화면에서 거를 때는 필드 id를 행 데이터 키와 맞추고 `filter(search.matches)`를 쓴다.
- select 옵션: `ComCd.fillSelect(el, 'GROUP')` 또는 컬럼·필드의 `optionsQuery`. 코드값을 HTML에 하드코딩하지 않는다.
- 공통 버튼은 마크업에 쓰지 않는다. 서버가 권한대로 그리므로 `PageButtons.bind({ btnSearch: ..., btnSave: ... })`로만 핸들러를 건다.
- fetch에 CSRF 헤더·스피너·401 처리를 직접 붙이지 않는다. 공통 래퍼가 한다.
- 엑셀·복사는 grid 우클릭 메뉴에 이미 있다. 따로 만들지 않는다.
- 스타일은 `common-ui.css`의 클래스와 `:root` 변수를 쓴다. 화면 `<style>`에는 그 화면에서만 다른 값만 둔다.
- JS에서 `var`를 쓰지 않는다(`const`, 재대입할 때만 `let`).
- 같은 코드가 두 번째로 필요해지면 화면에 복사하지 말고 공통 모듈(`static/js`, `common-ui.css`)로 올린다.

### 주석·문서

- 주석은 "무엇"이 아니라 **"왜"**를 쓴다. 1~3줄로 쓰고, 긴 경위는 문서에 남긴다.
- 주석·로그·커밋 메시지는 한국어로 쓴다.
- 구조를 바꿨으면 `docs/llm/`과 `backend/README.md`를 같이 고친다.

### 기본 데이터

- `DataInitializer`는 항목별로 **없을 때만** 넣는다. 이미 있는 값을 덮어쓰지 않는다(화면에서 사람이 고친 값).
- 기존 DB에도 들어가야 하는 데이터는 "사용자 0명일 때"가 아니라 그 항목의 존재 여부로 판단한다.

## 2. 테스트

자세한 내용은 [`testing.md`](testing.md)에 있다.

- 변경을 마치기 전에 `./mvnw test`를 통과시킨다.
- API를 만들면 정상 동작과 함께 **권한 없음(403)·미로그인(401)** 케이스를 테스트한다.
- 화면 변경은 jar로 띄워서 실제 브라우저에서 확인한다.

## 3. 예외 처리

- 입력 검증 실패와 업무 규칙 위반은 서비스에서 `IllegalArgumentException`(한국어 사유)을 던진다. `ApiExceptionAdvice`가 400으로 바꾸고 사유 문구를 응답 본문에 담는다. 화면에서 사유를 보여주려면 `res.text()`를 읽는다(상단바 비밀번호 변경이 그렇게 한다).
- `ApiExceptionAdvice`는 `@RestController`에만 적용된다. 화면 컨트롤러 오류를 문자열로 내보내지 않기 위해서다.
- 권한 거부는 직접 응답을 만들지 않고 `AccessDeniedException`을 던진다. Spring Security가 처리한다(화면 403, API 403, 미로그인은 401·로그인 이동).
- 존재하지 않는 대상을 수정·삭제하려 하면 조용히 넘어가지 않고 `IllegalArgumentException`을 던진다.
- 외부 호출 실패를 삼키지 않는다. 건별로 건너뛸 때는 건너뛴 사실을 결과로 올려 화면이 알 수 있게 한다. "없음"과 "조회 실패"를 구분할 수 있어야 한다.
- 외부 HTTP 호출을 `@Transactional` 안에 두지 않는다. 조회·외부 호출을 먼저 끝내고 쓰기만 트랜잭션에서 한다(`AnalysisService`가 그 예다).
- 분석 실패는 예외로 끝내지 않고 문서의 `status=FAILED`·`failReason`으로 남긴다. 화면이 그 사유를 보여주고 재분석하게 한다. 재시도할 수 있는 오류(연결 실패·429·5xx·형식 오류)와 없는 오류(키 오류·거절)를 구분한다(`AiCallException.retryable`, FastAPI 오류 본문 `{"detail": {"message", "retryable"}}`).
- AI 서비스 호출은 `AiClient`만 한다. HTTP 클라이언트는 java.net.http + HTTP/1.1이다 — `HttpURLConnection`은 401 본문을 버리고, HTTP/2 업그레이드는 uvicorn이 본문을 받지 못해 422가 났다.

## 4. 보안 원칙

아래는 각각 실제 취약점을 막기 위한 장치다. **편의를 위해 되돌리지 않는다.**

| 규칙 | 이유 |
| --- | --- |
| `anyRequest().access(requiresProgramAuthorizationManager)` 유지 | `PUBLIC_URLS` 외에는 전부 로그인을 요구한다(fail closed). URL 패턴을 다시 나열하지 않는다 |
| 관리·업무 API에 `@RequiresProgram` 부착 | **안 붙이면 로그인한 전원에게 열린다.** 구조가 막아주지 않는 유일한 지점이다 |
| `@PreAuthorize`는 `@RequiresProgram`으로 표현할 수 없을 때만 | 현재 예외는 `ComCdController.getCodes`(`includeInactive` 값에 따라 필요한 권한이 다름) 하나 |
| `/api/**`도 CSRF 검증 | 빼면 로그인한 관리자가 악성 페이지를 여는 것만으로 변경 요청이 대신 날아간다 |
| 화면(`/program/**`)도 프로그램 권한 검사 | API만 막으면 "메뉴에는 없는데 주소로는 열린다" |
| 화면 id는 `[a-z0-9-]+`만 허용 | 경로 값을 템플릿 경로에 붙이므로 `..`·`/`로 바깥 템플릿을 렌더링하지 못하게 막는다 |
| `spring.h2.console.enabled=false` 유지 | H2 콘솔은 `CREATE ALIAS`로 원격 코드 실행이 가능하다 |
| 비밀번호는 BCrypt로만 저장하고, 응답에 넣지 않음 | 해시가 유출되면 오프라인 공격 대상이 된다 |
| 초기 관리자 비밀번호는 기동 시 무작위 생성, 로그에 한 번만 출력 | 소스에 평문 비밀번호를 두지 않는다. 로그인 화면에 자동 입력값도 두지 않는다 |
| 본인 비밀번호 변경은 현재 비밀번호를 다시 확인 | 열린 화면·탈취한 세션만으로 계정을 가져가지 못하게 한다 |
| 업로드 파일은 내용(시그니처)으로 형식을 정하고, 저장 이름은 서버가 만든 UUID만 쓴다 | 확장자·Content-Type은 바꿔 보낼 수 있다. 사용자 파일명을 경로에 쓰면 경로 조작·덮어쓰기가 된다. SVG는 같은 출처에서 열면 스크립트가 실행되므로 받지 않는다 |
| Claude API 키는 Spring `claude.api.key` 한 곳에만 두고 AI 서비스에는 요청 헤더로만 넘긴다. 로그·응답·예외 메시지에 넣지 않는다 | 키를 두 군데 두면 교체할 때 어긋나고, 노출 지점이 늘어난다 |
| AI 서비스(FastAPI)는 127.0.0.1에만 연다 | 지금은 인증이 없는 내부 API다. 다른 서버로 분리할 때는 내부 토큰·TLS를 먼저 붙인다 |
| 역할은 공통코드 `ROLE` 값만, 자기보다 높은 역할 부여·상위 계정 수정 금지, 자기 자신 삭제 금지 | 권한 상승을 막고, 마지막 관리자가 자기 계정을 지워 복구 불가가 되는 것을 막는다 |

### 시크릿

`application*.properties`는 `.gitignore`에 있다. 비밀번호·API 키를 커밋하지 않는다. 문서에는 설정 키 이름과 용도만 적는다.

## 5. 작업 체크리스트

- [ ] 이미 있는 공통 모듈(`Grid`, `SearchForm`, `ComCd`, `PageButtons`, `common-ui.css`)을 썼는가?
- [ ] 새 API에 `@RequiresProgram`을 붙였는가?
- [ ] 새 화면을 프로그램으로 등록하고 권한을 줬는가? 기본 화면이면 `DataInitializer`에 넣었는가?
- [ ] 업무 오류를 `IllegalArgumentException`(한국어 사유)으로 던졌는가?
- [ ] `./mvnw test`가 통과하는가? 권한 케이스 테스트를 붙였는가?
- [ ] `ai/`를 고쳤으면 `.venv/Scripts/python -m unittest`가 통과하는가?
- [ ] 구조를 바꿨으면 `docs/llm/`과 `backend/README.md`를 고쳤는가?
