"""P-06 질문 해석 — 작은 모델(Haiku)이 질문을 {패션 질문인지, 유행 질문인지, 카테고리, 아이템 종류, 기간}으로 바꾼다.

패션 질문이 아니면 Spring이 집계·검색·답변을 하지 않는다(2026-10-22 사용자 결정). 유행 질문은 유사도 검사를 거치지 않아
"요즘 유행하는 밈은?"도 옷 원문을 세서 답할 수 있었다. 판단은 같은 호출에서 칸 하나로 한다(호출을 늘리지 않는다).

챗봇의 이어 묻기("그럼 하의는?")를 알아듣게 앞 대화(최근 몇 건의 질문·해석)를 같이 받는다(2026-10-22 사용자 요청).
이어 묻기면 생략된 범위·기간을 앞 해석에서 채우고, 혼자서도 뜻이 통하는 질문으로 다시 써서(question) 돌려준다 —
Spring이 검색·답변에 그 질문을 쓴다. "그럼 하의는?"을 그대로 임베딩하면 엉뚱한 근거가 뽑힌다.

대상을 좁히는 속성 값(와이드 팬츠 → 하의 + 실루엣 WIDE, 크롭 아우터 → 아우터 + CROPPED)은 materials·colors·silhouettes로 돌려준다 —
질문 단어로 넘기면 글자 비교라 "와이드 데님 팬츠"가 빠졌다(2026-10-26). Spring 집계가 아이템의 그 값으로 거른다.
유행 질문이 주로 묻는 축(focus — 핏이면 실루엣)도 돌려준다. Spring이 판단 근거의 첫 막대·예시 원문을 그 축으로 고른다(2026-10-23).
시즌 표현(F/W·올가을…)은 시즌 코드(seasons)로도 돌려준다 — 게시일 기간만으로는 6개월 안의 S/S 자료가 섞인다(2026-10-23).
코드(카테고리·종류)로 옮기지 못한 패션 단어(무스탕·발마칸·고프코어…)는 terms로 따로 돌려준다(2026-10-23 사용자 결정).
코드가 없으면 해석이 그 단어를 버려 "무스탕 유행해?"가 "아우터 유행"으로 넓어졌다 — Spring이 키워드·아이템명에서 그 단어를 센다.

규칙(정규식)으로 하면 미리 넣은 표현만 알아들어서("요새 사람들 뭐 입어?" 같은 말을 놓친다) AI에게 맡긴다(2026-10-22 사용자
결정 A). 고를 수 있는 값은 공통코드 목록으로 주고, 날짜 형식·종류–카테고리 짝은 여기서 한 번 더 본다. 실패하면 Spring이 규칙으로 대신한다.

입력 토큰 최적화 2단계(2026-10-29 사용자 요청): 모델은 코드값 대신 이름(한국어)을 고르고 여기서 코드값으로 바꾼다(목록에서 영문 코드값이 빠진다).
디테일 목록은 질문에 디테일 말이 있을 때만 보낸다. 출력 스키마는 enum을 빼고(값 검사는 normalize), 기간 4칸 → period 1칸,
속성 6칸 → attributes 1칸, question은 앞 대화가 있을 때만 받는다. Spring에 돌려주는 결과(코드값·칸)는 그대로다.
"""
import logging
import re
import time
from dataclasses import dataclass, field
from datetime import date, timedelta

import anthropic

from app.core import prompt_loader
from app.core.claude import AiServiceError, create_message, parse_json, usage_of

log = logging.getLogger("trend-ai")

PROMPT = prompt_loader.load("rag/interpret", ["system", "followup", "task"])
MAX_TOKENS = 2000
MAX_HISTORY = 3  # 이어 묻기는 거의 바로 앞 질문을 가리킨다 — 많이 넣으면 엉뚱한 앞 질문에 이어 붙인다
MAX_QUESTION_LENGTH = 500
MAX_TERMS = 3
ATTRIBUTES = ["materials", "colors", "silhouettes", "genders", "patterns", "details"]  # 대상을 좁히는 속성 값 칸(genders: "여성 아우터" → WOMEN, patterns: "체크 셔츠" → CHECK)
# 유행 질문이 묻는 축 — Spring 집계 값 종류(TrendItemValue·TrendWeeklyStat.CATEGORY)와 같은 코드
FOCUS = ["ITEM_TYPE", "CATEGORY", "MATERIAL", "COLOR", "SILHOUETTE", "PATTERN", "DETAIL"]
# 증감을 묻는 유행 질문(급상승·감소·전월 대비) — Spring이 증감 순으로 세고 직전 대비 %를 붙인다(2026-10-28 사용자 요청)
CHANGE = ["UP", "DOWN", "BOTH"]
MAX_TERM_LENGTH = 20

# ── 코드가 정하는 것(2026-10-29 입력 토큰 최적화 — 프롬프트에서 규칙을 빼고 여기서 보장한다) ──
SEASON_DAYS = 183  # 시즌 기사는 몇 달 앞서 나온다 — 시즌 표현은 오늘까지 183일(Spring QuestionInterpreter.SEASON_DAYS와 같다)
# 표준 기간 표현 — 공백을 뺀 질문에서 찾는다. 앞에 있을수록 먼저(Spring QuestionInterpreter.period와 같은 순서)
_RECENT_N = re.compile(r"(?:최근|지난)(\d{1,3})(일|주|개월|달)")
_PERIOD_WORDS = [("LAST_WEEK", r"지난주|저번주"), ("THIS_WEEK", r"이번주|금주"), ("LAST_MONTH", r"지난달|저번달|전월"),
                 ("THIS_MONTH", r"이번달|이달|금월"), ("LAST_YEAR", r"작년|지난해"), ("THIS_YEAR", r"올해|금년")]
_SEASON_EXPR = re.compile(r"올(봄|여름|가을|겨울)|이번(봄|여름|가을|겨울)|\d{2}(SS|FW|S/S|F/W)|(SS|FW|S/S|F/W)시즌", re.IGNORECASE)
# 기간 표현 바로 뒤에 붙으면 비교 기준이지 기간이 아니다("지난달 대비 급상승")
_COMPARE_AFTER = re.compile(r"(대비|보다|비해|에비해|와비교|과비교|랑비교|이랑비교)")
# 묻는 축의 값은 범위에 넣지 않는다 — 그 축은 세서 답한다("레더 유행해?"는 focus=MATERIAL, materials=[])
_FOCUS_ATTRIBUTE = {"MATERIAL": "materials", "COLOR": "colors", "SILHOUETTE": "silhouettes", "PATTERN": "patterns", "DETAIL": "details"}
# 종류 이름에 이미 든 소재 — 같이 고르면 범위가 이중으로 좁아진다("데님 팬츠"는 BOTTOM_DENIM이면 충분)
_TYPE_MATERIAL = {"BOTTOM_DENIM": "DENIM", "OUTER_DENIM_JACKET": "DENIM", "OUTER_LEATHER_JACKET": "LEATHER", "OUTER_FLEECE": "FLEECE",
                  "TOP_KNIT": "KNIT", "TOP_CARDIGAN": "KNIT"}
# terms에 넣지 않는 말 — 축 이름·일반어·시즌 표현(정확히 그 말일 때만. "레더 재킷" 같은 대상 이름은 남는다)
_NOT_TERMS = {"핏", "실루엣", "기장", "라인", "소재", "원단", "컬러", "색", "색상", "패턴", "무늬", "디테일", "포인트",
              "유행", "트렌드", "요즘", "최근", "옷", "스타일", "상품", "아이템", "인기", "패션", "코디",
              "f/w", "fw", "s/s", "ss", "봄", "여름", "가을", "겨울", "간절기", "환절기", "올가을", "올겨울", "올봄", "올여름"}
# 속성 칸의 목록 제목 — 모델은 attributes 한 칸에 이름을 고르고, 여기서 칸별 코드값으로 나눈다
_ATTRIBUTE_LABEL = {"materials": "소재", "colors": "컬러", "silhouettes": "실루엣", "genders": "라인", "patterns": "패턴", "details": "디테일"}
# 이 말이 질문에 있으면 디테일 목록(약 800토큰)을 보낸다 — 디테일 이름·같은 뜻 말(2글자 이상)이 질문에 있어도 보낸다
_DETAIL_WORDS = ("디테일", "포인트", "넥", "소매", "밑단", "여밈", "포켓", "카라", "칼라")
# 모델이 주는 기간 — "YYYY-MM-DD~YYYY-MM-DD 이름"
_PERIOD_TEXT = re.compile(r"(\d{4}-\d{2}-\d{2})\s*~\s*(\d{4}-\d{2}-\d{2})\s*(.*)")
# 시즌 약어는 늘 두 시즌이다 — 모델이 "26FW"를 겨울 하나로만 고르기도 해서(2026-10-29 회귀) 코드가 채운다
_SEASON_PAIRS = [(re.compile(r"(?<![A-Za-z])F/?W(?![A-Za-z])|가을\s*겨울", re.IGNORECASE), ("FALL", "WINTER")),
                 (re.compile(r"(?<![A-Za-z])S/?S(?![A-Za-z])|봄\s*여름", re.IGNORECASE), ("SPRING", "SUMMER"))]


@dataclass
class CodeHint:
    value: str
    name: str
    words: str = ""  # 카테고리 일상 표현(공통코드 속성)


@dataclass
class Turn:
    """앞 질문 한 건(다시 쓴 질문)과 그 해석. 기간은 콕 집은 질문만 채운다."""
    question: str
    trend: bool = False
    category: str = ""
    item_type: str = ""
    period_label: str = ""
    period_from: str = ""
    period_to: str = ""


@dataclass
class InterpretInput:
    question: str
    model: str
    today: date
    categories: list[CodeHint]
    item_types: list[CodeHint]
    history: list[Turn] = field(default_factory=list)  # 오래된 것 → 최근 순
    seasons: list[CodeHint] = field(default_factory=list)  # 시즌 코드(SPRING…) — 없으면(예전 Spring) seasons 칸을 받지 않는다
    # 대상을 좁히는 속성 값 후보(소재·컬러·실루엣 코드) — 없으면(예전 Spring) 그 칸을 받지 않는다
    materials: list[CodeHint] = field(default_factory=list)
    colors: list[CodeHint] = field(default_factory=list)
    silhouettes: list[CodeHint] = field(default_factory=list)
    genders: list[CodeHint] = field(default_factory=list)
    patterns: list[CodeHint] = field(default_factory=list)
    details: list[CodeHint] = field(default_factory=list)


def build_schema(data: InterpretInput) -> dict:
    """출력 스키마 — 칸 하나에 약 25토큰, enum은 값마다 더 든다(2026-10-29 측정: 카테고리 enum 85·축 110·시즌 86토큰).
    그래서 enum 없이 문자열로 받고 값 검사·코드 변환은 normalize가 한다. 고를 값은 규칙·허용 값 목록에 있다."""
    properties = {
        "fashion": {"type": "boolean"},  # 패션·트렌드 질문인지 — 아니면 Spring이 답하지 않는다
        "trend": {"type": "boolean"},
        "category": {"type": "string"},  # 카테고리 이름
        "item_type": {"type": "string"},  # 종류 이름
        "period": {"type": "string"},  # "YYYY-MM-DD~YYYY-MM-DD 이름" — 표준 표현(지난달·시즌…)은 코드가 계산해 덮어쓴다
        # 카테고리·종류 이름으로 옮기지 못한 패션 단어(무스탕·발마칸…) — Spring이 키워드·아이템명에서 센다. 개수 제한은 normalize가 한다
        "terms": {"type": "array", "items": {"type": "string"}},
        "focus": {"type": "string"},  # 유행 질문이 묻는 축(FOCUS, 없으면 "")
        "change": {"type": "string"},  # 증감을 묻는지(CHANGE, 없으면 "")
    }
    if data.history:  # 혼자서도 뜻이 통하게 다시 쓴 질문 — 앞 대화가 없으면 쓰지 않으므로 받지 않는다
        properties = {"question": {"type": "string"}, **properties}
    if data.seasons:  # 질문이 가리키는 시즌 이름 — 유행 집계가 원문의 시즌 값으로 거른다
        properties["seasons"] = {"type": "array", "items": {"type": "string"}}
    if _attribute_groups(data):  # 대상을 좁히는 속성 값 이름(소재·컬러·실루엣…) — normalize가 칸별 코드로 나눈다
        properties["attributes"] = {"type": "array", "items": {"type": "string"}}
    return {"type": "object", "properties": properties, "required": list(properties), "additionalProperties": False}


def _attribute_groups(data: InterpretInput) -> list[str]:
    """이번 질문에 보낼 속성 칸 — 후보가 있는 칸, 디테일은 질문에 디테일 말이 있을 때만."""
    return [key for key in ATTRIBUTES if getattr(data, key) and (key != "details" or _wants_details(data))]


def _wants_details(data: InterpretInput) -> bool:
    """질문(이어 묻기면 앞 질문까지)에 디테일 말이 있는지 — "디테일·포인트" 또는 디테일 이름·같은 뜻 말(2글자 이상)."""
    text = _norm(" ".join([data.question] + [t.question for t in data.history[-MAX_HISTORY:]]))
    # 종류 이름은 먼저 지운다 — "카고 팬츠"·"후드티"·"셔츠"가 디테일 같은 뜻 말(카고·후드·셔츠 칼라)에 걸리지 않게
    for k in sorted({k for o in data.item_types for k in [*_name_keys(o)[0], *_name_keys(o)[1]] if len(k) >= 2}, key=len, reverse=True):
        text = text.replace(k, " ")
    if any(w in text for w in _DETAIL_WORDS):
        return True
    return any(len(k) >= 2 and k in text for o in data.details for k in _name_keys(o)[1])


def _norm(text: str) -> str:
    return re.sub(r"\s+", "", text or "").lower()


def _main_name(o: CodeHint) -> str:
    return o.name.split("(", 1)[0].strip()


def _name_keys(o: CodeHint) -> tuple[list[str], list[str]]:
    """(정식 키, 같은 뜻 키) — 정식: 코드값·이름 전체·괄호 앞 이름. 같은 뜻: "·"로 나뉜 이름 조각·괄호 안 말·카테고리 일상 표현."""
    main = _main_name(o)
    inner = o.name[len(main):].strip().strip("()")
    primary = [_norm(o.value), _norm(o.name), _norm(main)]
    aliases = [*main.split("·"), *re.split(r"[·,]", inner), *(o.words or "").split(",")]
    return primary, [k for k in (_norm(a) for a in aliases) if k]


def _lookup(options: list[CodeHint]) -> dict[str, str]:
    """이름(공백·대소문자 무시) → 코드값. 정식 키가 같은 뜻 키보다 먼저다."""
    table = {}
    for o in options:
        for k in _name_keys(o)[0]:
            table.setdefault(k, o.value)
    for o in options:
        for k in _name_keys(o)[1]:
            table.setdefault(k, o.value)
    return table


def allowed_values_text(data: InterpretInput) -> str:
    """허용 값 목록(이름) — 영문 코드값은 싣지 않는다(2026-10-29, 모델은 이름을 고르고 normalize가 코드값으로 바꾼다)."""
    categories = ", ".join(c.name + (f"({c.words})" if c.words else "") for c in data.categories)
    lines = [f"- category: {categories}", "- item_type: " + ", ".join(t.name for t in data.item_types)]
    if data.seasons:
        lines.append("- seasons: " + ", ".join(s.name for s in data.seasons))
    lines += [f"- attributes({_ATTRIBUTE_LABEL[key]}): " + ", ".join(o.name for o in getattr(data, key)) for key in _attribute_groups(data)]
    return "허용 값(이름 그대로 고른다. 괄호 안은 같은 뜻의 말):\n" + "\n".join(lines)


def code_values_text(data: InterpretInput) -> str:
    """허용 코드 목록(코드값=이름, 디테일까지 전부) — 도구 경로(agent)용. 도구 인자는 코드값이라 이 형식을 그대로 쓴다."""
    categories = ", ".join(f"{c.value}={c.name}" + (f"({c.words})" if c.words else "") for c in data.categories)
    item_types = ", ".join(f"{t.value}={t.name}" for t in data.item_types)
    seasons = f"\n- seasons: " + ", ".join(f"{s.value}={s.name}" for s in data.seasons) if data.seasons else ""
    seasons += "".join(f"\n- {key}: " + ", ".join(f"{o.value}={o.name}" for o in getattr(data, key))
                       for key in ATTRIBUTES if getattr(data, key))
    return f"허용 값(코드값=이름):\n- category: {categories}\n- item_type: {item_types}{seasons}"


def system_prompt(data: InterpretInput) -> list[dict]:
    """규칙 + 허용 코드 목록. 캐시는 걸지 않는다(2026-10-28 사용자 결정 — 챗봇은 5분 안에 이어 묻는 일이 많지 않을 것이라
    캐시를 만드는 1.25배 비용이 손해가 될 수 있다). 날짜·앞 대화·질문처럼 바뀌는 값은 user에 둔다.
    이어 묻기 규칙(followup)은 앞 대화가 있을 때만 붙인다 — 첫 질문에는 쓰이지 않아 약 670토큰을 아낀다(2026-10-29 사용자 결정)."""
    rules = PROMPT["system"] + ("\n\n" + PROMPT["followup"] if data.history else "")
    return [
        {"type": "text", "text": rules},
        {"type": "text", "text": allowed_values_text(data)},
    ]


def build_content(data: InterpretInput) -> str:
    return (f"오늘: {data.today.isoformat()} ({'월화수목금토일'[data.today.weekday()]}요일)\n\n"
            f"{_history_text(data.history, data.categories, data.item_types)}<question>\n{data.question}\n</question>\n\n{PROMPT['task']}")


def _history_text(history: list[Turn], categories: list[CodeHint] = (), item_types: list[CodeHint] = ()) -> str:
    """앞 대화 — 질문과 그때의 해석(코드값은 이름으로). 마지막 줄이 바로 앞 질문이다."""
    if not history:
        return ""
    lines = []
    for i, t in enumerate(history[-MAX_HISTORY:], 1):
        period = f"{t.period_label}({t.period_from}~{t.period_to})" if t.period_label else "-"
        lines.append(f"{i}. {t.question} → 유행 질문={'예' if t.trend else '아니오'}, category={_name_of(t.category, categories)}, "
                     f"item_type={_name_of(t.item_type, item_types)}, 기간={period}")
    return "<previous>\n" + "\n".join(lines) + "\n</previous>\n\n"


def _name_of(value: str, options) -> str:
    """코드값 → 괄호 앞 이름(목록에 없으면 코드값 그대로, 비면 "-")."""
    return next((_main_name(o) for o in options if o.value == value), value) if value else "-"


def _date(value: str) -> date | None:
    try:
        return date.fromisoformat((value or "").strip())
    except ValueError:
        return None


def standard_period(text: str, today: date):
    """질문의 표준 기간 표현을 코드로 계산한다 — {from,to,label,explicit}, 비교 기준만 있으면 "COMPARE", 표준 표현이 없으면 None.
    모델이 날짜를 계산하던 것을 옮겼다(2026-10-29) — 같은 말이면 늘 같은 날짜(모델은 "26FW"를 183일이 아닌 날로 잡기도 했다)."""
    s = re.sub(r"\s+", "", text or "")
    compare = False

    def free(m):  # 이 표현 바로 뒤가 "대비·보다·비해"면 비교 기준이다
        nonlocal compare
        if _COMPARE_AFTER.match(s, m.end()):
            compare = True
            return False
        return True

    def period(start, end, label):
        return {"from": start.isoformat(), "to": end.isoformat(), "label": label, "explicit": True}

    for m in _RECENT_N.finditer(s):
        if free(m):
            n, unit = int(m.group(1)), m.group(2)
            days = max(1, min(n if unit == "일" else n * 7 if unit == "주" else n * 30, 3 * 365))
            return period(today - timedelta(days=days - 1), today, f"최근 {n}{unit}")
    monday = today - timedelta(days=today.weekday())
    month_start = today.replace(day=1)
    found = [kind for kind, pattern in _PERIOD_WORDS for m in re.finditer(pattern, s) if free(m)]
    season = any(free(m) for m in _SEASON_EXPR.finditer(s)) or bool(re.search(r"(SS|FW|S/S|F/W)", s, re.IGNORECASE))
    near = [k for k in found if k in ("LAST_WEEK", "THIS_WEEK", "LAST_MONTH", "THIS_MONTH")]
    years = sorted({k for k in found if k in ("LAST_YEAR", "THIS_YEAR")})
    if near:
        kind = near[0]
        if kind == "LAST_WEEK":
            start = monday - timedelta(days=7)
            return period(start, monday - timedelta(days=1), f"지난주({start:%m/%d}~{monday - timedelta(days=1):%m/%d})")
        if kind == "THIS_WEEK":
            return period(monday, today, "이번 주")
        if kind == "LAST_MONTH":
            end = month_start - timedelta(days=1)
            return period(end.replace(day=1), end, f"지난달({end.month}월)")
        return period(month_start, today, f"이번 달({today.month}월)")
    if len(years) > 1 or (years and season):
        return None  # 두 해를 비교하거나 "작년 F/W"처럼 해와 시즌이 겹치면 한 기간으로 못 정한다 — 모델 값(또는 도구 경로)에 맡긴다
    if years == ["LAST_YEAR"]:
        return period(date(today.year - 1, 1, 1), date(today.year - 1, 12, 31), f"작년({today.year - 1}년)")
    if years == ["THIS_YEAR"]:
        return period(date(today.year, 1, 1), today, f"올해({today.year}년)")
    if any(free(m) for m in _SEASON_EXPR.finditer(s)):
        return period(today - timedelta(days=SEASON_DAYS - 1), today, "최근 6개월(시즌 표현)")
    return "COMPARE" if compare else None


def normalize(result: dict, data: InterpretInput) -> dict:
    """모델 결과를 정리한다 — 판단이 필요 없는 규칙은 여기서 보장한다(프롬프트에서 뺐다, 2026-10-29).
    종류의 카테고리, 표준 기간 계산(없으면 모델 기간을 날짜가 바를 때만), 패션 외 질문 비우기, 묻는 축 값·종류에 든 소재 빼기,
    앞 대화가 없으면 질문 그대로, terms의 축 이름·일반어 거르기."""
    # fashion이 빠졌으면 패션 질문으로 본다 — 잘못 막는 것보다 근거 없음으로 끝나는 편이 낫다
    fashion = result.get("fashion", True) is not False
    # 다시 쓴 질문 — 앞 대화가 없으면 원래 질문 그대로(다시 쓸 일이 없다). 비었거나 너무 길면 원래 질문
    question = (result.get("question") or "").strip()
    if not data.history or not question or len(question) > MAX_QUESTION_LENGTH:
        question = data.question
    if not fashion:  # 패션 질문이 아니면 나머지 칸은 쓰지 않는다
        return {"question": question, "fashion": False, "trend": False, "category": None, "item_type": None, "period": None,
                "terms": [], "seasons": [], "focus": None, "change": None, **{key: [] for key in ATTRIBUTES}}
    item_type = _pick(result.get("item_type"), data.item_types)
    category = _pick(result.get("category"), data.categories)
    if item_type:
        category = item_type.split("_", 1)[0]  # 종류가 이긴다(코드값 앞부분 = 카테고리)
    # 기간 — 표준 표현은 코드가 계산하고(이어 묻기면 앞 대화로 채워 다시 쓴 질문에서 찾는다), 비교 기준뿐이면 기간 없음,
    # 표준 표현이 없을 때만 모델 기간("YYYY-MM-DD~YYYY-MM-DD 이름", 날짜가 바르고 from ≤ to ≤ 오늘일 때)
    std = standard_period(question, data.today)
    period = None
    if isinstance(std, dict):
        period = std
    elif std is None:
        m = _PERIOD_TEXT.search(str(result.get("period") or ""))
        start, end = (_date(m.group(1)), _date(m.group(2))) if m else (None, None)
        if start and end and start <= end and end <= data.today:
            period = {"from": start.isoformat(), "to": end.isoformat(),
                      "label": m.group(3).strip() or f"{start} ~ {end}", "explicit": True}
    focus = result.get("focus") if result.get("focus") in FOCUS else None
    attributes = _attributes(result.get("attributes"), data)
    if focus in _FOCUS_ATTRIBUTE:
        attributes[_FOCUS_ATTRIBUTE[focus]] = []
    if item_type in _TYPE_MATERIAL:
        attributes["materials"] = [m for m in attributes["materials"] if m != _TYPE_MATERIAL[item_type]]
    return {"question": question, "fashion": True, "trend": bool(result.get("trend")),
            "category": category, "item_type": item_type, "period": period, "terms": _terms(result.get("terms"), data),
            "seasons": _season_codes(result.get("seasons"), question, data), "focus": focus,
            "change": result.get("change") if result.get("trend") and result.get("change") in CHANGE else None,
            **attributes}


def _pick(raw, options: list[CodeHint]) -> str | None:
    """이름(또는 코드값·같은 뜻 말) → 허용 코드값. 목록 밖이면 None."""
    return _lookup(options).get(_norm(str(raw))) if raw else None


def _picks(raw, options: list[CodeHint]) -> list[str]:
    """이름 목록 → 허용 코드값만, 중복 없이 코드 목록 순서로."""
    table = _lookup(options)
    picked = {table.get(_norm(str(r))) for r in raw} if isinstance(raw, list) else set()
    return [o.value for o in options if o.value in picked]


def _season_codes(raw, question: str, data: InterpretInput) -> list[str]:
    """모델이 고른 시즌 + 질문의 시즌 약어(F/W → 가을·겨울, S/S → 봄·여름). 허용 코드만, 코드 목록 순서로."""
    picked = set(_picks(raw, data.seasons))
    for pattern, codes in _SEASON_PAIRS:
        if pattern.search(question or ""):
            picked.update(codes)
    return [s.value for s in data.seasons if s.value in picked]


def _attributes(raw, data: InterpretInput) -> dict[str, list[str]]:
    """attributes(이름 한 칸) → 칸별 코드값. 보낸 칸에서만 찾고, 칸끼리 이름이 겹치면 정식 이름 쪽·앞 칸이 먼저다."""
    groups = _attribute_groups(data)
    primary = {key: {k for o in getattr(data, key) for k in _name_keys(o)[0]} for key in groups}
    tables = {key: _lookup(getattr(data, key)) for key in groups}
    picked = {key: set() for key in ATTRIBUTES}
    for r in raw if isinstance(raw, list) else []:
        k = _norm(str(r))
        key = next((g for g in groups if k in primary[g]), None) or next((g for g in groups if k in tables[g]), None)
        if key:
            picked[key].add(tables[key][k])
    return {key: [o.value for o in getattr(data, key) if o.value in picked[key]] for key in ATTRIBUTES}


def _terms(raw, data: InterpretInput | None = None) -> list[str]:
    """빈 값·너무 긴 값·중복(공백 무시)·축 이름·일반어·시즌 표현·카테고리 말을 빼고 앞에서 MAX_TERMS개."""
    blocked = set(_NOT_TERMS)
    for c in (data.categories if data else []):  # 카테고리 이름과 일상 표현(아우터·겉옷·바지…)
        blocked.add(c.name.replace(" ", "").lower())
        blocked.update(w.strip().replace(" ", "").lower() for w in (c.words or "").split(",") if w.strip())
    terms, seen = [], set()
    for t in raw if isinstance(raw, list) else []:
        t = " ".join(str(t).split())
        key = t.replace(" ", "").lower()
        if t and len(t) <= MAX_TERM_LENGTH and key not in seen and key not in blocked:
            seen.add(key)
            terms.append(t)
    return terms[:MAX_TERMS]


def interpret(data: InterpretInput, api_key: str, client: anthropic.Anthropic | None = None) -> dict:
    """{question, fashion, trend, category, item_type, period, terms, seasons, focus, change, materials, colors, silhouettes, genders, patterns, details, usage}. Claude 호출 실패는 AiServiceError(Spring이 규칙으로 대신한다)."""
    if not data.categories or not data.item_types:
        raise AiServiceError("해석에 쓸 공통코드가 없습니다.", retryable=False, status_code=400)
    started = time.monotonic()
    response = create_message(client, api_key,
                              model=data.model,
                              max_tokens=MAX_TOKENS,
                              system=system_prompt(data),
                              messages=[{"role": "user", "content": build_content(data)}],
                              output_config={"format": {"type": "json_schema", "schema": build_schema(data)},
                                             "effort": "low"})
    usage = usage_of(response, time.monotonic() - started)
    result = normalize(parse_json(response, usage), data)
    log.info("질문 해석 model=%s 소요=%.1fs 앞 대화=%d건 다시 씀=%s 패션=%s 유행=%s 카테고리=%s 종류=%s 속성=%s 축=%s 증감=%s 단어=%s 시즌=%s 기간=%s 토큰 in=%d out=%d",
             data.model, usage["elapsed_ms"] / 1000, len(data.history), result["question"] != data.question,
             result["fashion"], result["trend"], result["category"], result["item_type"], [v for k in ATTRIBUTES for v in result[k]], result["focus"], result["change"], result["terms"], result["seasons"],
             result["period"]["label"] if result["period"] else "-", usage["input_tokens"], usage["output_tokens"])
    return {**result, "usage": usage}
