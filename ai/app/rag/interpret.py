"""P-06 질문 해석 — 작은 모델(Haiku)이 질문을 {유행 질문인지, 카테고리, 아이템 종류, 기간}으로 바꾼다.

규칙(정규식)으로 하면 미리 넣은 표현만 알아들어서("요새 사람들 뭐 입어?" 같은 말을 놓친다) AI에게 맡긴다(2026-10-22 사용자
결정 A). 고를 수 있는 값은 공통코드로 묶고(enum), 날짜 형식·종류–카테고리 짝은 여기서 한 번 더 본다. 실패하면 Spring이 규칙으로 대신한다.
"""
import logging
import time
from dataclasses import dataclass
from datetime import date

import anthropic

from app.core import prompt_loader
from app.core.claude import AiServiceError, create_message, parse_json, usage_of

log = logging.getLogger("trend-ai")

PROMPT = prompt_loader.load("rag/interpret", ["system", "task"])
MAX_TOKENS = 2000


@dataclass
class CodeHint:
    value: str
    name: str
    words: str = ""  # 카테고리 일상 표현(공통코드 속성)


@dataclass
class InterpretInput:
    question: str
    model: str
    today: date
    categories: list[CodeHint]
    item_types: list[CodeHint]


def build_schema(data: InterpretInput) -> dict:
    return {
        "type": "object",
        "properties": {
            "trend": {"type": "boolean"},
            "category": {"type": "string", "enum": [""] + [c.value for c in data.categories]},
            "item_type": {"type": "string", "enum": [""] + [t.value for t in data.item_types]},
            "has_period": {"type": "boolean"},
            "period_from": {"type": "string"},
            "period_to": {"type": "string"},
            "period_label": {"type": "string"},
        },
        "required": ["trend", "category", "item_type", "has_period", "period_from", "period_to", "period_label"],
        "additionalProperties": False,
    }


def build_content(data: InterpretInput) -> str:
    categories = ", ".join(f"{c.value}={c.name}" + (f"({c.words})" if c.words else "") for c in data.categories)
    item_types = ", ".join(f"{t.value}={t.name}" for t in data.item_types)
    return (f"오늘: {data.today.isoformat()} ({'월화수목금토일'[data.today.weekday()]}요일)\n"
            f"허용 값(코드값=이름):\n- category: {categories}\n- item_type: {item_types}\n\n"
            f"<question>\n{data.question}\n</question>\n\n{PROMPT['task']}")


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
    return {"trend": bool(result.get("trend")), "category": category, "item_type": item_type, "period": period}


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
    log.info("질문 해석 model=%s 소요=%.1fs 유행=%s 카테고리=%s 종류=%s 기간=%s 토큰 in=%d out=%d", data.model,
             usage["elapsed_ms"] / 1000, result["trend"], result["category"], result["item_type"],
             result["period"]["label"] if result["period"] else "-", usage["input_tokens"], usage["output_tokens"])
    return {**result, "usage": usage}
