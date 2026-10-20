# testing.md — 테스트

테스트 구성 · 검증 데이터 · 회귀 테스트.

## 1. 테스트 전략

```bash
./mvnw test
```

작업 디렉터리는 `backend/`다. JDK 17이 필요하다(`JAVA_HOME`).

| 테스트 | 범위 |
| --- | --- |
| `mvc/ViewControllerTest` | 화면: 미로그인 시 로그인 이동, 사이드바 메뉴·상단바 사용자 표시, 권한 없는 화면 403, 툴바 버튼 거르기, 잘못된 화면 id |
| `service/analysis/TrendResultValidatorTest` | 스키마 검증(순수): 공통코드 밖 값·빈 키워드·잘못된 감성 거부, 중복 제거, 키워드 8개 제한 |
| `service/analysis/AnalysisServiceTest` | P-03·P-04 흐름(AiClient 가짜): 성공 시 저장·색인, 검증 실패 재시도, 끝까지 실패면 저장 안 함, 재시도 불가 오류는 1회, 색인 실패 표시, 공통코드 허용값 전달 |
| `service/analysis/TokenUsageTest` | 토큰 사용량 읽기(빠진 필드는 0)·재시도 합계 |
| `service/analysis/AnalysisServiceNoKeyTest` | `claude.api.key`가 없으면 AI 서비스를 부르지 않고 실패 사유를 남김 |
| `controller/TextDocumentApiTest` | 텍스트 등록: 출처 기본값, 목록·결과·삭제(색인 삭제 호출), 빈 본문 400, 권한 403 |
| `controller/ImageApiTest` | 이미지 업로드: 여러 장 업로드·조회·원본·삭제, 경로 섞인 파일명 정리, 이미지가 아닌 파일이 섞이면 전부 거부, 빈 파일 400, 권한 없음 403. 업로드 폴더는 `target/test-uploads` |
| `controller/AdminApiTest` | 관리 API: 미로그인 401, 권한 없음 403, CSRF 없는 변경 403, 공통코드 저장·조회·삭제, 사용자 응답(비밀번호 미포함·`editable`/`self`), 역할 검증, 자기 삭제 금지, 권한 통째 교체 |

- 둘 다 `@SpringBootTest` + `@AutoConfigureMockMvc`로 전체 컨텍스트를 띄운다. 보안 필터·`DataInitializer`까지 실제와 같이 돈다.
- 로그인은 `@WithMockUser(username = "...")`로 흉내 낸다. 메뉴·권한은 그 username으로 DB를 조회하므로, **DB에 있는 사용자 이름**을 써야 권한이 붙는다. 권한 없음은 `nobody`처럼 없는 이름으로 확인한다.
- 상태 변경 요청에는 `.with(csrf())`를 붙인다. 빼면 403이 나는 것 자체를 검증하는 테스트도 있다.
- 판단 로직을 순수 클래스로 빼면 Spring 없는 단위 테스트를 붙인다.
- 화면 동작(그리드·탭·버튼)은 자동 테스트가 없다. jar로 띄워 브라우저에서 확인한다.
- 분석은 `@MockBean AiClient`로 AI 서비스를 바꿔 끼운다. 업로드·등록이 분석 대기열에 넣으므로, 업로드·등록하는 API 테스트에도 붙인다(빼면 실제 127.0.0.1:8001을 부른다).
  분석 흐름 테스트는 문서를 저장소에 바로 넣고 `analyze(id)`를 직접 부른다 — 등록 API로 넣으면 분석 스레드와 겹친다.

### Python (ai/)

```bash
.venv/Scripts/python -m unittest
```

`ai/` 폴더에서 돌린다. 네트워크·실제 Claude 호출이 없다(가짜 클라이언트·가짜 컬렉션).

| 테스트 | 범위 |
| --- | --- |
| `test_core.py` | 설정(.env·환경변수·모드 검증), **Chroma 내장형/서버형 선택**, 임베딩 구현 선택·질의 지시문(가짜 모델), 청크 분할, 이미지 축소·변환, 스키마 enum, Claude 오류 분류(거절·잘림·연결), 색인 청크(코드 이름 사용·갈아끼움) |
| `test_api.py` | FastAPI: 키 없음 401, 키 전달·응답에 키 미포함, 오류의 retryable·소모 토큰(usage) 전달, 빈 본문·깨진 이미지 400, 색인·삭제 |

## 2. 검증 데이터

- 테스트는 클래스마다 **인메모리 H2**를 쓴다(`@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:<이름>")`). 파일 DB(`backend/data/`)를 건드리지 않는다.
  새 테스트 클래스도 이 속성을 붙이고 이름을 다르게 준다. 같은 이름을 쓰면 컨텍스트 캐시가 DB를 공유한다.
- 기본 데이터는 `DataInitializer`가 넣은 것을 그대로 쓴다.
  - `admin`(id 1, ADMIN, 관리 화면 5종 모든 버튼 권한)
  - 공통코드 `ROLE`(ADMIN·USER), `MENU_GROUP`(MNG)
  - `user-permission-mng`는 신규·삭제 버튼을 쓰지 않는다(버튼 거르기 확인용).
- 같은 클래스의 테스트는 DB를 공유하고 실행 순서가 정해져 있지 않다. 그래서:
  - `admin`의 권한·역할을 바꾸지 않는다. 바꿔야 하면 테스트 안에서 사용자를 새로 만들어 쓴다(`사용자별_권한을_통째로_교체한다` 참고).
  - 만든 데이터는 다른 테스트와 겹치지 않는 값(아이디·코드 그룹)으로 만든다.

## 3. 회귀 테스트

변경 종류별로 아래가 깨지지 않았는지 본다.

| 변경 | 확인할 것 |
| --- | --- |
| `SecurityConfig`·`security` 패키지 | 미로그인 화면 → 로그인 이동, API → 401, 권한 없음 403, CSRF 없는 POST 403 |
| 새 API | 정상 응답 + 권한 없는 사용자 403 테스트 추가(`@RequiresProgram` 누락 방지) |
| `ProgramService`(메뉴·버튼) | 사이드바 그룹·메뉴, 툴바 버튼 = 프로그램 사용 버튼 ∩ 사용자 허용 버튼 |
| `UserService` | 비밀번호 미노출, 역할 검증, 자기 삭제 금지, 상위 역할 제한 |
| `DataInitializer` | 새 DB에서 기동 성공, 기존 DB에서 재기동 시 중복 삽입 없음(유일 제약 위반 없이 기동) |
| `ImageService`·`ImageType` | 이미지가 아닌 파일(확장자만 바꾼 것 포함) 거부, 거부 시 파일·DB에 아무것도 남지 않음, 원본 응답의 Content-Type |
| `AnalysisService`·`TrendResultValidator`·`structurer`·`indexer` | 검증을 통과한 결과만 저장, 재시도 가능/불가 구분, 출처가 JSON에 붙음, 재분석 시 갈아끼움 |
| `vector_store`·`config` | `CHROMA_MODE`만으로 내장형/서버형이 바뀜(코드 수정 없이) |
| 템플릿·공통 JS | jar 기동 후 로그인 → 각 탭이 열리고 그리드가 조회되는지, 콘솔 오류가 없는지 |

### 앱 기동 확인

```bash
./mvnw -DskipTests package
```

```bash
java -jar target/trend-analysis-0.0.1-SNAPSHOT.jar
```

http://localhost:8080 에 접속해 로그인한다. 분석까지 보려면 AI 서비스(`ai/`)도 띄운다(llm-development.md "AI 서비스 실행").
연결만 확인할 때는 일부러 틀린 키(`--claude.api.key=...`)로 띄우면 된다 — 문서가 "Claude API 키가 올바르지 않습니다"로 실패하면 Spring → FastAPI → Claude까지 연결된 것이다. 기동 로그는 한글이 시스템 인코딩(MS949)으로 찍혀서 UTF-8 grep에 걸리지 않을 수 있다.
초기 관리자 비밀번호를 모르면 `backend/data/`를 지우고 다시 기동한다. 로그에 새 비밀번호가 찍히고, 데이터는 초기화된다.
