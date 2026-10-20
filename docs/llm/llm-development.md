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
- 외부 HTTP 호출을 `@Transactional` 안에 두지 않는다. 조회·외부 호출을 먼저 끝내고 쓰기만 트랜잭션에서 한다.

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
| 역할은 공통코드 `ROLE` 값만, 자기보다 높은 역할 부여·상위 계정 수정 금지, 자기 자신 삭제 금지 | 권한 상승을 막고, 마지막 관리자가 자기 계정을 지워 복구 불가가 되는 것을 막는다 |

### 시크릿

`application*.properties`는 `.gitignore`에 있다. 비밀번호·API 키를 커밋하지 않는다. 문서에는 설정 키 이름과 용도만 적는다.

## 5. 작업 체크리스트

- [ ] 이미 있는 공통 모듈(`Grid`, `SearchForm`, `ComCd`, `PageButtons`, `common-ui.css`)을 썼는가?
- [ ] 새 API에 `@RequiresProgram`을 붙였는가?
- [ ] 새 화면을 프로그램으로 등록하고 권한을 줬는가? 기본 화면이면 `DataInitializer`에 넣었는가?
- [ ] 업무 오류를 `IllegalArgumentException`(한국어 사유)으로 던졌는가?
- [ ] `./mvnw test`가 통과하는가? 권한 케이스 테스트를 붙였는가?
- [ ] 구조를 바꿨으면 `docs/llm/`과 `backend/README.md`를 고쳤는가?
