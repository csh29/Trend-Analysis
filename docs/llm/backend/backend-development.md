# backend-development.md — Spring 개발

`backend/`(Spring Boot) 빌드·실행, 구조, 코딩 규칙, 테스트. 기능별 동작은 `../features/` 문서에 있다.

## 1. 빌드·실행

작업 디렉터리는 `backend/`, JDK는 17이다(`JAVA_HOME`).

```bash
./mvnw clean package
```

```bash
java -jar target/trend-analysis-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

- **설정은 `src/main/resources/application-local.properties` 하나**(커밋 제외)이고 `local` 프로파일일 때만 읽힌다. IDE 실행 설정에는 프로파일이 들어 있지만, jar로 띄울 때는 `--spring.profiles.active=local`을 꼭 붙인다 — 빼면 DB 주소·`claude.api.key`를 못 읽는다. 설정 키 목록은 `backend/README.md`에 있다.
- **`./mvnw spring-boot:run`은 쓰지 않는다.** 경로의 한글(`ai 과제`) 때문에 "Could not find or load main class"로 실패한다. 패키징한 jar로 실행하거나 IDE에서 `TrendAnalysisApplication`을 실행한다.
- `mvnw` 스크립트는 git에 있지만 `.mvn/wrapper/`는 제외돼 있어, 새로 클론한 곳에서는 `./mvnw`가 실행되지 않는다. 설치된 `mvn`이나 IDE로 빌드한다.
- IDE로 앱을 띄워 둔 채 같은 폴더에서 `package`하면 `target/classes`가 바뀌어 떠 있는 앱이 ClassNotFound로 깨질 수 있다. 확인용 기동은 jar를 복사해 다른 포트(`--server.port=8081`)로 띄운다.

### 앱 기동 확인

http://localhost:8080 에 접속해 로그인한다. 분석까지 보려면 AI 서비스도 띄운다([`ai-development.md`](../ai/ai-development.md)).
기동 로그는 한글이 시스템 인코딩(MS949)으로 찍혀서 UTF-8 grep에 걸리지 않을 수 있다.
초기 관리자 비밀번호를 모르면 `backend/data/`를 지우고 다시 기동한다. 로그에 새 비밀번호가 찍히고, 데이터는 초기화된다.

## 2. 구조

```
브라우저 ── 화면 요청 ──▶ SecurityFilterChain ──▶ ViewController ──▶ Thymeleaf (templates/)
        └─ fetch /api/** ─▶ SecurityFilterChain ──▶ controller ──▶ service ──▶ repository ──▶ H2
                                                                     └─ AiClient ──▶ AI 서비스(FastAPI)
```

패키지 → 기능 문서: `security` → auth-permission, `mvc`·`templates/`·`static/`·`service/home` → screen-framework, `service/program`·`user`·`permission`·`comcd` → admin-screens, `service/image`·`service/analysis`(`DocumentService`) → document-upload, `service/analysis` → analysis-pipeline, `service/trend` → trend-aggregation, `service/rag` → rag-search. `config`(보안·기본 데이터·업로드·분석 스레드·스케줄)는 각 기능.

- **화면(`mvc`)과 API(`controller`)는 분리돼 있다.** 화면은 HTML 껍데기만 그리고, 데이터는 화면 JS가 `/api/**`를 fetch해서 채운다.

## 3. 코딩 규칙

### 레이어

- 화면은 `mvc/ViewController`, 데이터는 `controller`(REST) → `service` → `repository`로 흐른다. 컨트롤러에 업무 로직을 넣지 않는다.
- 패키지는 기능별 하위 패키지로 나눈다(`service/user`, `dto/comcd` 등). 작업 중인 코드의 이름·위치가 역할과 어긋나면 그 작업 범위 안에서만 고친다(범위 밖 리팩터링은 하지 않는다).
- **새 API의 요청·응답은 `dto`의 record**로 한다. 예전에 엔티티를 그대로 내보내는 API를 고칠 때는 민감 필드가 없는지 확인한다. `User`는 반드시 `UserResponse`로 내보낸다(비밀번호 해시).
- 엔티티 컬럼 이름에 DB 예약어(`value`, `user` 등)를 쓰지 않는다 — H2 DDL이 깨진다(`TrendItemValue.item_value`, 테이블 `users`).
- 컬럼 기본값은 `@ColumnDefault`로 준다. `columnDefinition`에 `default ... not null`까지 넣으면 `ddl-auto=update`의 ALTER 문이 깨진다(`User.useYn`).

### 예외 처리

- 입력 검증 실패와 업무 규칙 위반은 서비스에서 `IllegalArgumentException`(한국어 사유)을 던진다. `ApiExceptionAdvice`가 400으로 바꾸고 사유 문구를 응답 본문에 담는다. 화면에서 사유를 보여주려면 `res.text()`를 읽는다.
- `ApiExceptionAdvice`는 `@RestController`에만 적용된다. 화면 컨트롤러 오류를 문자열로 내보내지 않기 위해서다.
- 권한 거부는 직접 응답을 만들지 않고 `AccessDeniedException`을 던진다. Spring Security가 처리한다(화면 403, API 403, 미로그인은 401·로그인 이동).
- 존재하지 않는 대상을 수정·삭제하려 하면 조용히 넘어가지 않고 `IllegalArgumentException`을 던진다.
- 외부 HTTP 호출을 `@Transactional` 안에 두지 않는다. 조회·외부 호출을 먼저 끝내고 쓰기만 트랜잭션에서 한다(`AnalysisService`가 그 예다).

### 공통 보안 원칙

아래는 각각 실제 취약점을 막기 위한 장치다. **편의를 위해 되돌리지 않는다.** 기능에 딸린 규칙은 각 기능 문서에 있다.

| 규칙 | 이유 |
| --- | --- |
| `anyRequest().access(requiresProgramAuthorizationManager)` 유지 | `PUBLIC_URLS` 외에는 전부 로그인을 요구한다(fail closed). URL 패턴을 다시 나열하지 않는다 |
| 관리·업무 API에 `@RequiresProgram` 부착 | **안 붙이면 로그인한 전원에게 열린다.** 구조가 막아주지 않는 유일한 지점이다 |
| `@PreAuthorize`는 `@RequiresProgram`으로 표현할 수 없을 때만 | 현재 예외는 `ComCdController.getCodes`(`includeInactive` 값에 따라 필요한 권한이 다름) 하나 |
| `/api/**`도 CSRF 검증 | 빼면 로그인한 관리자가 악성 페이지를 여는 것만으로 변경 요청이 대신 날아간다 |
| 화면(`/program/**`)도 프로그램 권한 검사 | API만 막으면 "메뉴에는 없는데 주소로는 열린다" |
| 화면 id는 `[a-z0-9-]+`만 허용 | 경로 값을 템플릿 경로에 붙이므로 `..`·`/`로 바깥 템플릿을 렌더링하지 못하게 막는다 |
| H2 콘솔(`spring.h2.console.enabled`)은 로컬에서 필요할 때만 켜고 쓰고 나면 끈다 | 콘솔은 `CREATE ALIAS`로 원격 코드 실행이 가능하다. 켜도 앱 로그인은 필요하지만(PUBLIC_URLS 아님) 로그인한 누구나 SQL을 실행할 수 있다. 켜 둔 동안 `/h2-console/**`는 CSRF 검증에서 빠진다 |

## 4. 기본 데이터 (`DataInitializer`)

- 항목별로 **없을 때만** 넣는다. 이미 있는 값을 덮어쓰지 않는다(화면에서 사람이 고친 값).
- 기존 DB에도 들어가야 하는 데이터는 "사용자 0명일 때"가 아니라 그 항목의 존재 여부로 판단한다.
- 무엇을 넣는지는 각 기능 문서에 있다(공통코드·프로그램·관리자 → [`admin-screens.md`](../features/admin-screens.md), 트렌드 표준 코드 → [`analysis-pipeline.md`](../features/analysis-pipeline.md)).

## 5. 테스트

```bash
./mvnw test
```

- 변경을 마치기 전에 통과시킨다. 기능별로 고칠 때 확인할 것은 각 기능 문서의 "확인할 것" 또는 "테스트" 절에 있다(없는 문서도 있다).
- `@SpringBootTest` + `@AutoConfigureMockMvc`로 전체 컨텍스트를 띄운다. 보안 필터·`DataInitializer`까지 실제와 같이 돈다.
- 로그인은 `@WithMockUser(username = "...")`로 흉내 낸다. 메뉴·권한은 그 username으로 DB를 조회하므로, **DB에 있는 사용자 이름**을 써야 권한이 붙는다. 권한 없음은 `nobody`처럼 없는 이름으로 확인한다.
- 상태 변경 요청에는 `.with(csrf())`를 붙인다. 빼면 403이 나는 것 자체를 검증하는 테스트도 있다.
- 판단 로직을 순수 클래스로 빼면 Spring 없는 단위 테스트를 붙인다.
- 화면 동작(그리드·탭·버튼)은 자동 테스트가 없다. jar로 띄워 브라우저에서 확인한다.
- AI 서비스를 부르는 흐름(업로드·등록 포함)은 `@MockBean AiClient`로 바꿔 끼운다. 빼면 실제 127.0.0.1:8001을 부른다.

### 검증 데이터

- 테스트는 클래스마다 **인메모리 H2**를 쓴다(`@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:<이름>")`). 파일 DB(`backend/data/`)를 건드리지 않는다.
  새 테스트 클래스도 이 속성을 붙이고 이름을 다르게 준다. 같은 이름을 쓰면 컨텍스트 캐시가 DB를 공유한다.
- 기본 데이터는 `DataInitializer`가 넣은 것을 그대로 쓴다.
  - `admin`(id 1, ADMIN, 모든 프로그램 모든 버튼 권한)
  - 공통코드 `ROLE`(ADMIN·USER), `MENU_GROUP`(TREND·MNG), `TREND_*`, `RAG_TREND_WORD`
  - `user-permission-mng`는 신규·삭제 버튼을 쓰지 않는다(버튼 거르기 확인용).
- 같은 클래스의 테스트는 DB를 공유하고 실행 순서가 정해져 있지 않다. 그래서:
  - `admin`의 권한·역할을 바꾸지 않는다. 바꿔야 하면 테스트 안에서 사용자를 새로 만들어 쓴다.
  - 만든 데이터는 다른 테스트와 겹치지 않는 값(아이디·코드 그룹)으로 만든다.

### 공통 회귀 항목

| 변경 | 확인할 것 |
| --- | --- |
| `SecurityConfig`·`security` 패키지 | 미로그인 화면 → 로그인 이동, API → 401, 권한 없음 403, CSRF 없는 POST 403 |
| 새 API | 정상 응답 + 권한 없는 사용자 403 테스트 추가(`@RequiresProgram` 누락 방지). 일부러 로그인만 요구하는 API는 미로그인 401([`auth-permission.md`](../features/auth-permission.md)) |
| `DataInitializer` | 새 DB에서 기동 성공, 기존 DB에서 재기동 시 중복 삽입 없음(유일 제약 위반 없이 기동) |
| 엔티티(`ddl-auto=update`) | 새 파일 DB와 기존 파일 DB 양쪽에서 기동 시 DDL 오류가 없는지 |
| 템플릿·공통 JS | jar 기동 후 로그인 → 각 탭이 열리고 그리드가 조회되는지, 콘솔 오류가 없는지 |
