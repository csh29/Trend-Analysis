"""P-06 질문 해석 — 작은 모델(Haiku)이 질문을 {패션 질문인지, 유행 질문인지, 카테고리, 아이템 종류, 기간}으로 바꾼다.

패션 질문이 아니면 Spring이 집계·검색·답변을 하지 않는다(2026-10-22 사용자 결정). 유행 질문은 유사도 검사를 거치지 않아
"요즘 유행하는 밈은?"도 옷 원문을 세서 답할 수 있었다. 판단은 같은 호출에서 칸 하나로 한다(호출을 늘리지 않는다).

챗봇의 이어 묻기("그럼 하의는?")를 알아듣게 앞 대화(최근 몇 건의 질문·해석)를 같이 받는다(2026-10-22 사용자 요청).
이어 묻기면 생략된 범위·기간을 앞 해석에서 채우고, 혼자서도 뜻이 통하는 질문으로 다시 써서(question) 돌려준다 —
Spring이 검색·답변에 그 질문을 쓴다. "그럼 하의는?"을 그대로 임베딩하면 엉뚱한 근거가 뽑힌다.

규칙(정규식)으로 하면 미리 넣은 표현만 알아들어서("요새 사람들 뭐 입어?" 같은 말을 놓친다) AI에게 맡긴다(2026-10-22 사용자
결정 A). 고를 수 있는 값은 공통코드로 묶고(enum), 날짜 형식·종류–카테고리 짝은 여기서 한 번 더 본다. 실패하면 Spring이 규칙으로 대신한다.
"""
import logging
import time
from dataclasses import dataclass, field
from datetime import date

import anthropic

from app.core import prompt_loader
from app.core.claude import AiServiceError, create_message, parse_json, usage_of

log = logging.getLogger("trend-ai")

PROMPT = prompt_loader.load("rag/interpret", ["system", "task"])
MAX_TOKENS = 2000
MAX_HISTORY = 3  # 이어 묻기는 거의 바로 앞 질문을 가리킨다 — 많이 넣으면 엉뚱한 앞 질문에 이어 붙인다
MAX_QUESTION_LENGTH = 500


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


def build_schema(data: InterpretInput) -> dict:
    return {
        "type": "object",
        "properties": {
            # 혼자서도 뜻이 통하게 다시 쓴 질문 — 이어 묻기("그럼 하의는?")면 앞 대화를 채운다. Spring이 검색·답변에 이 질문을 쓴다
            "question": {"type": "string"},
            "fashion": {"type": "boolean"},  # 패션·트렌드 질문인지 — 아니면 Spring이 답하지 않는다
            "trend": {"type": "boolean"},
            "category": {"type": "string", "enum": [""] + [c.value for c in data.categories]},
            "item_type": {"type": "string", "enum": [""] + [t.value for t in data.item_types]},
            "has_period": {"type": "boolean"},
            "period_from": {"type": "string"},
            "period_to": {"type": "string"},
            "period_label": {"type": "string"},
        },
        "required": ["question", "fashion", "trend", "category", "item_type", "has_period", "period_from", "period_to", "period_label"],
        "additionalProperties": False,
    }


def build_content(data: InterpretInput) -> str:
    categories = ", ".join(f"{c.value}={c.name}" + (f"({c.words})" if c.words else "") for c in data.categories)
    item_types = ", ".join(f"{t.value}={t.name}" for t in data.item_types)
    return (f"오늘: {data.today.isoformat()} ({'월화수목금토일'[data.today.weekday()]}요일)\n"
            f"허용 값(코드값=이름):\n- category: {categories}\n- item_type: {item_types}\n\n"
            f"{_history_text(data.history)}<question>\n{data.question}\n</question>\n\n{PROMPT['task']}")


def _history_text(history: list[Turn]) -> str:
    """앞 대화 — 질문과 그때의 해석. 마지막 줄이 바로 앞 질문이다."""
    if not history:
        return ""
    lines = []
    for i, t in enumerate(history[-MAX_HISTORY:], 1):
        period = f"{t.period_label}({t.period_from}~{t.period_to})" if t.period_label else "-"
        lines.append(f"{i}. {t.question} → 유행 질문={'예' if t.trend else '아니오'}, category={t.category or '-'}, "
                     f"item_type={t.item_type or '-'}, 기간={period}")
    return "<previous>\n" + "\n".join(lines) + "\n</previous>\n\n"


def _date(value: str) -> date | None:
    try:
        return date.fromisoformat((value or "").strip())
    except ValueError:
        return None


def normalize(result: dict, data: InterpretInput) -> dict:
    """모델 결과를 정리한다 — 종류의 카테고리를 맞추고, 기간은 날짜가 바르고 from ≤ to ≤ 오늘일 때만 쓴다."""
    item_type = result.get("item_type") or None
    category = result.get("category") or None
    if item_type:
        category = item_type.split("_", 1)[0]  # 종류가 이긴다(코드값 앞부분 = 카테고리)
    period = None
    if result.get("has_period"):
        start, end = _date(result.get("period_from")), _date(result.get("period_to"))
        if start and end and start <= end and end <= data.today:
            period = {"from": start.isoformat(), "to": end.isoformat(),
                      "label": (result.get("period_label") or "").strip() or f"{start} ~ {end}", "explicit": True}
    # 다시 쓴 질문이 비었거나 너무 길면 원래 질문을 쓴다
    question = (result.get("question") or "").strip()
    if not question or len(question) > MAX_QUESTION_LENGTH:
        question = data.question
    # fashion이 빠졌으면 패션 질문으로 본다 — 잘못 막는 것보다 근거 없음으로 끝나는 편이 낫다
    return {"question": question, "fashion": result.get("fashion", True) is not False, "trend": bool(result.get("trend")),
            "category": category, "item_type": item_type, "period": period}


def interpret(data: InterpretInput, api_key: str, client: anthropic.Anthropic | None = None) -> dict:
    """{trend, category, item_type, period, usage}. Claude 호출 실패는 AiServiceError(Spring이 규칙으로 대신한다)."""
    if not data.categories or not data.item_types:
        raise AiServiceError("해석에 쓸 공통코드가 없습니다.", retryable=False, status_code=400)
    started = time.monotonic()
    response = create_message(client, api_key,
                              model=data.model,
                              max_tokens=MAX_TOKENS,
                              system=PROMPT["system"],
                              messages=[{"role": "user", "content": build_content(data)}],
                              output_config={"format": {"type": "json_schema", "schema": build_schema(data)},
                                             "effort": "low"})
    usage = usage_of(response, time.monotonic() - started)
    result = normalize(parse_json(response, usage), data)
    log.info("질문 해석 model=%s 소요=%.1fs 앞 대화=%d건 다시 씀=%s 패션=%s 유행=%s 카테고리=%s 종류=%s 기간=%s 토큰 in=%d out=%d",
             data.model, usage["elapsed_ms"] / 1000, len(data.history), result["question"] != data.question,
             result["fashion"], result["trend"], result["category"], result["item_type"],
             result["period"]["label"] if result["period"] else "-", usage["input_tokens"], usage["output_tokens"])
    return {**result, "usage": usage}
