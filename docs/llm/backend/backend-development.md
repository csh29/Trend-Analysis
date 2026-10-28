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
- `mvnw` 스크립트는 git에 있지만 `.mvn/wrapper/`는 `.gitignore` 대상이라, 새로 클론한 곳에서는 `./mvnw`가 실행되지 않는다. 설치된 `mvn`이나 IDE로 빌드한다.
- IDE로 앱을 띄워 둔 채 같은 폴더에서 `package`하면 `target/classes`가 바뀌어 떠 있는 앱이 ClassNotFound로 깨질 수 있다. 확인용 기동은 jar를 복사해 다른 포트(`--server.port=8081`)로 띄운다.

### 앱 기동 확인

http://localhost:8080 에 접속해 로그인한다. 분석까지 보려면 AI 서비스도 띄운다([`ai-development.md`](../ai/ai-development.md)).
기동 로그는 한글이 시스템 인코딩(MS949)으로 찍혀서 UTF-8 grep에 걸리지 않을 수 있다.
초기 관리자 비밀번호를 잃어버렸을 때는 [`admin-screens.md`](../features/admin-screens.md).

## 2. 구조

```
브라우저 ── 화면 요청 ──▶ SecurityFilterChain ──▶ ViewController ──▶ Thymeleaf (templates/)
        └─ fetch /api/** ─▶ SecurityFilterChain ──▶ controller ──▶ service ──▶ repository ──▶ H2
                                                                     └─ AiClient ──▶ AI 서비스(FastAPI)
```

패키지 → 기능 문서: `security` → auth-permission, `mvc`·`templates/`·`static/`·`service/home` → screen-framework, `service/program`·`user`·`permission`·`comcd` → admin-screens, `service/image`·`service/analysis`(`DocumentService`) → document-upload, `service/analysis` → analysis-pipeline, `service/trend` → trend-aggregation, `service/rag` → rag-search. `config`는 클래스별로: `SecurityConfig` → auth-permission, `DataInitializer` → admin-screens, `ImageUploadConfig` → document-upload, `AnalysisAsyncConfig` → analysis-pipeline, `SchedulingConfig`·`SampleDataLoader` → trend-aggregation, `RagEvalRunner`·`FactsCheckRunner` → rag-search. `service/user/HomeLayoutService` → screen-framework.

- **화면(`mvc`)과 API(`controller`)는 분리돼 있다.** 화면은 HTML 껍데기만 그리고, 데이터는 화면 JS가 `/api/**`를 fetch해서 채운다.

## 3. 코딩 규칙

### 레이어

- 화면은 `mvc/ViewController`, 데이터는 `controller`(REST) → `service` → `repository`로 흐른다. 컨트롤러에 업무 로직을 넣지 않는다.
- 패키지는 기능별 하위 패키지로 나눈다(`service/user`, `dto/comcd` 등). 작업 중인 코드의 이름·위치가 역할과 어긋나면 그 작업 범위 안에서만 고친다(범위 밖 리팩터링은 하지 않는다).
- **새 API의 요청·응답은 `dto`의 record**로 한다. 엔티티를 그대로 내보내는 기존 API를 고칠 때는 민감 필드가 없는지 확인한다(`User` → `UserResponse`, [`admin-screens.md`](../features/admin-screens.md)).
- 엔티티 컬럼 이름에 DB 예약어(`value`, `user` 등)를 쓰지 않는다 — H2 DDL이 깨진다(`TrendItemValue.item_value`, 테이블 `users`).
- 컬럼 기본값은 `@ColumnDefault`로 준다. `columnDefinition`에 `default ... not null`까지 넣으면 `ddl-auto=update`의 ALTER 문이 깨진다(`User.useYn`).

### 예외 처리

- 입력 검증 실패와 업무 규칙 위반은 서비스에서 `IllegalArgumentException`(한국어 사유)을 던진다. `ApiExceptionAdvice`가 400으로 바꾸고 사유 문구를 응답 본문에 담는다. 화면에서 사유를 보여주려면 `res.text()`를 읽는다.
- `ApiExceptionAdvice`는 `@RestController`에만 적용된다. 화면 컨트롤러 오류를 문자열로 내보내지 않기 위해서다.
- AI 서비스 호출 실패(`AiCallException`)는 502와 **일반 안내 문구만** 내보낸다. 예외 메시지에는 서버 주소·실행 스크립트·설정 키 같은 내부 정보가 들어 있어 로그에만 남긴다(분석 실패 사유는 관리 화면 기록이라 따로 저장한다).
- 존재하지 않는 대상을 수정·삭제하려 하면 조용히 넘어가지 않고 `IllegalArgumentException`을 던진다.
- 외부 HTTP 호출을 `@Transactional` 안에 두지 않는다. 조회·외부 호출을 먼저 끝내고 쓰기만 트랜잭션에서 한다(`AnalysisService`가 그 예다).

### 보안 원칙

기본 거부·`@RequiresProgram`·`@PreAuthorize` 예외·CSRF·화면 권한 검사·H2 콘솔 규칙은 [`auth-permission.md`](../features/auth-permission.md)에만 있다. 화면 id 형식(`[a-z0-9-]+`)은 [`screen-framework.md`](../features/screen-framework.md).

## 4. 기본 데이터 (`DataInitializer`)

넣는 규칙("없을 때만", 존재 여부 판단)과 넣는 내용(공통코드·프로그램·관리자)은 [`admin-screens.md`](../features/admin-screens.md), 트렌드 표준 코드는 [`analysis-pipeline.md`](../features/analysis-pipeline.md).

## 5. 테스트

```bash
./mvnw test
```

- **앱을 IDE로 띄워 둔 채 테스트할 때는 `./mvnw test -DbuildDir=target-test`**로 빌드 폴더를 나눈다. `target/classes`를 다시 컴파일하면 devtools가 떠 있는 앱을 재시작해 세션이 풀린다(로그인 화면으로 튕긴다).
- 변경을 마치기 전에 통과시킨다. 기능별로 고칠 때 확인할 것은 각 기능 문서의 "확인할 것"·"테스트" 절에 있다. 그 절이 없는 기능 문서는 "주의점"에 적는다.
- `@SpringBootTest` + `@AutoConfigureMockMvc`로 전체 컨텍스트를 띄운다. 보안 필터·`DataInitializer`까지 실제와 같이 돈다.
- 로그인은 `@WithMockUser(username = "...")`로 흉내 낸다. 메뉴·권한은 그 username으로 DB를 조회하므로, **DB에 있는 사용자 이름**을 써야 권한이 붙는다. 권한 없음은 `nobody`처럼 없는 이름으로 확인한다.
- 상태 변경 요청에는 `.with(csrf())`를 붙인다. 빼면 403이 나는 것 자체를 검증하는 테스트도 있다.
- 판단 로직을 순수 클래스로 빼면 Spring 없는 단위 테스트를 붙인다.
- 화면 동작(그리드·탭·버튼)은 자동 테스트가 없다. jar로 띄워 브라우저에서 확인한다.
- AI 서비스를 부르는 흐름(업로드·등록 포함)은 `@MockBean AiClient`로 바꿔 끼운다(이유는 [`document-upload.md`](../features/document-upload.md) 주의점).
- **기동 때 도는 리스너(`ApplicationReadyEvent`)도 테스트에서 돈다.** 실제 AI 서비스나 외부 데이터를 바꾸는 리스너는 테스트 전용 설정 `src/test/resources/config/application.properties`(테스트에만 들어간다)로 끈다(예: `index.sync.enabled=false` — 메모리 DB에는 문서가 없어 기동 색인 정리가 떠 있는 AI 서비스의 실제 색인을 지운다).

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
| `SecurityConfig`·`security` 패키지·새 API | [`auth-permission.md`](../features/auth-permission.md) "확인할 것" |
| `DataInitializer` | [`admin-screens.md`](../features/admin-screens.md) "확인할 것" |
| 엔티티(`ddl-auto=update`) | 새 파일 DB와 기존 파일 DB 양쪽에서 기동 시 DDL 오류가 없는지 |
| 템플릿·공통 JS | jar 기동 후 로그인 → 각 탭이 열리고 그리드가 조회되는지, 콘솔 오류가 없는지 |
