# llm-analysis.md — 전체 구조 요약

**사실 문서** — 지금 시스템이 어떻게 되어 있는가. 작업 전에 먼저 읽고, 필요한 상세 문서로 들어간다.
문서와 코드가 어긋나면 코드가 맞다. 확인한 자리에서 문서를 고친다.

## 1. 시스템 개요

패션 트렌드 분석 시스템. 화면 프레임워크와 로그인·권한, 관리 화면 5종이 있고, 트렌드 분석 기능은 이미지 업로드 화면 하나뿐이다(분석 기능은 아직 없다).

- security-monitoring 프로젝트(`C:\ai 과제\security-monitoring\backend`)의 화면 프레임워크와 로그인·권한 구조를 그대로 옮겨 왔다. 같은 일을 다시 설계하지 말고 그쪽 방식을 따른다.
- 단일 모듈 `backend/`: Spring Boot 서버가 화면(Thymeleaf)과 REST API를 함께 제공한다.

| 구분 | 내용 |
| --- | --- |
| 언어·프레임워크 | Java 17, Spring Boot 3.3.4 |
| 빌드 | Maven (`backend/pom.xml`) |
| 기본 패키지 | `com.sjinc.trendanalysis` |
| 화면 | Thymeleaf 서버 렌더링 + 바닐라 JS 공통 모듈 |
| DB | H2 파일 DB `backend/data/trendAnalysis`, `ddl-auto=update` |
| 인증 | Spring Security 폼 로그인 + 프로그램(화면) 단위 권한 |

## 2. 모듈 구성

| 패키지 / 위치 | 역할 |
| --- | --- |
| `config` | `SecurityConfig`(보안 정책), `DataInitializer`(기본 데이터 시드), `ImageUploadConfig`(업로드 크기 상한) |
| `security` | 프로그램 권한 검사: `RequiresProgram`, `RequiresProgramAuthorizationManager`, `ProgramAccessGuard`, `CsrfCookieFilter` |
| `mvc` | 화면 컨트롤러 `ViewController`, 모든 화면 공통 모델(`SidebarModelAdvice`, `TopbarModelAdvice`), API 예외 변환 `ApiExceptionAdvice` |
| `controller` | REST API (`/api/**`) |
| `service` | 업무 로직 — `program`, `user`, `permission`, `comcd`, `image` |
| `repository` | Spring Data JPA |
| `domain` | 엔티티 + `ProgramButton`(공통 버튼 종류) |
| `dto` | 화면·API 요청·응답 record |
| `templates/` | `home`(탭 셸), `login`, `fragments/*`(공통 조각), `program/*`(단위 화면) |
| `static/css`, `static/js` | 화면 공통 스타일·스크립트 |

### 트렌드 분석 화면

| 화면 (programId) | API | 권한 |
| --- | --- | --- |
| 이미지 업로드 (`img-upload`) | `/api/images` (목록·업로드·삭제), `/api/images/{id}/file` (원본) | `img-upload` |

- 여러 장을 한 번에 올린다(최대 50장, 한 장 20MB, 요청 합계 200MB — `image.upload.*`로 바꿀 수 있다).
- 파일은 디스크 `image.upload.dir`(기본 `backend/uploads/images`)에 `UUID.확장자`로, 메타정보는 `uploaded_images` 테이블에 둔다.
- 형식은 파일 앞부분 시그니처로 판별한다(`ImageType`: jpg·png·gif·webp·bmp, SVG 제외). 한 장이라도 이미지가 아니면 아무것도 저장하지 않는다.
- 툴바: 조회·삭제·초기화와 기타1 "업로드"(저장과 동작이 달라 기타 버튼으로 둔다).

### 관리 화면

| 화면 (programId) | API | 권한 |
| --- | --- | --- |
| 프로그램 관리 (`program-mng`) | `/api/programs` | 조회: `program-mng` 또는 `user-permission-mng` / 변경: `program-mng` |
| 사용자 관리 (`user-mng`) | `/api/users` | 조회: `user-mng` 또는 `user-permission-mng` / 변경: `user-mng` |
| 사용자별 권한관리 (`user-permission-mng`) | `/api/permissions/{userId}` | `user-permission-mng` |
| 공통코드마스터 관리 (`com-cd-master-mng`) | `/api/com-cd-groups` | 조회: 두 공통코드 화면 중 하나 / 변경: `com-cd-master-mng` |
| 공통코드 관리 (`com-cd-mng`) | `/api/com-cds` | 사용중 코드 조회: 로그인만 / `includeInactive`·변경: `com-cd-mng` |

`/api/users/me/password`(본인 비밀번호 변경)는 로그인만 요구한다.

### 기본 데이터 (`DataInitializer`, 없을 때만 넣음)

- 공통코드: `ROLE`(ADMIN 1, USER 2 — 정렬순서가 역할 등급), `MENU_GROUP`(TREND "트렌드 분석" 1, MNG "관리" 99)
- 프로그램: 이미지 업로드(`menuGroup=TREND`), 관리 화면 5종(`menuGroup=MNG`)
- 사용자: `admin`(ADMIN). 사용자가 0명일 때만 만든다. 비밀번호는 무작위로 만들고 기동 로그에 한 번만 출력한다. 모든 프로그램 권한을 받는다.
- 이미 있는 값은 덮어쓰지 않는다(화면에서 사람이 고쳤을 수 있다).

## 3. 상세 문서 목차

| 문서 | 내용 |
| --- | --- |
| [`architecture.md`](architecture.md) | 요청 처리 흐름, 화면 프레임워크, 메뉴·권한 데이터 관계 |
| [`llm-development.md`](llm-development.md) | 코딩 규칙 · 테스트 · 예외 처리 · 보안 원칙 (규범) |
| [`testing.md`](testing.md) | 테스트 구성 · 검증 데이터 · 회귀 테스트 |
