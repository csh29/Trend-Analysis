"""P-06 도구 경로(혼합형 2단계, 2026-10-28 사용자 결정) — Claude 한 단계 호출만 한다.

반복(도구 실행·상한 6번·실패 시 되돌리기)은 Spring(AgentService)이 한다 — 집계 데이터가 Spring DB에 있어서, 도구는 Spring이
실행하고 결과를 다음 단계 messages로 넘긴다. 여기서는 system(도구 규칙 + 답변 규칙 + 허용 코드)·도구 정의를 붙여 한 번 부르고
응답 블록을 그대로 돌려준다. 숫자는 도구(Spring 집계 코드)가 센다.
"""
import logging
import time
from dataclasses import dataclass, field
from datetime import date

import anthropic

from app.core import prompt_loader
from app.core.claude import create_message, usage_of
from app.rag import answer as rag
from app.rag import interpret

log = logging.getLogger("trend-ai")

PROMPT = prompt_loader.load("rag/agent", ["system", "task"])
MAX_TOKENS = 4000

_PERIOD = {"type": "object", "properties": {"from": {"type": "string", "description": "YYYY-MM-DD"},
                                            "to": {"type": "string", "description": "YYYY-MM-DD"},
                                            "label": {"type": "string", "description": "짧은 한국어 이름(예: 올해 F/W)"}},
           "required": ["from", "to"]}
_CODES = {"type": "array", "items": {"type": "string"}}
_SCOPE = {
    "category": {"type": "string", "description": "카테고리 코드(없으면 생략)"},
    "item_type": {"type": "string", "description": "아이템 종류 코드(없으면 생략)"},
    "materials": _CODES, "colors": _CODES, "silhouettes": _CODES, "patterns": _CODES, "details": _CODES, "genders": _CODES,
    "terms": {"type": "array", "items": {"type": "string"}, "description": "코드로 못 옮긴 패션 단어(무스탕·발마칸…)"},
}
TOOLS = [
    {"name": "count_trend",
     "description": "범위의 원문을 세어 축별 상위 값(원문 수·매체 수·직전 기간 대비 증감률·유행점수)과 예시 원문을 돌려준다. "
                    "period가 없으면 최근 4주, seasons를 주면 그 시즌 원문만. focus를 주면 그 축이 첫 축이다.",
     "input_schema": {"type": "object", "properties": {
         **_SCOPE,
         "focus": {"type": "string", "enum": interpret.FOCUS, "description": "주로 볼 축"},
         "seasons": _CODES,
         "period": _PERIOD,
         "change": {"type": "string", "enum": interpret.CHANGE, "description": "증감 순으로 볼 때"},
     }}},
    {"name": "trend_series",
     "description": "범위 안에서 값 하나(axis + value, 없으면 범위 전체)의 주별 원문 수를 최근 weeks주 동안 센다.",
     "input_schema": {"type": "object", "properties": {
         **_SCOPE,
         "axis": {"type": "string", "enum": interpret.FOCUS, "description": "센 값의 축(value와 함께)"},
         "value": {"type": "string", "description": "센 값의 코드(예: OUTER_PADDING, BROWN)"},
         "weeks": {"type": "integer", "minimum": 2, "maximum": 12},
     }, "required": ["weeks"]}},
    {"name": "final_answer",
     "description": "최종 답. 답변 규칙대로 summary(결론 한 줄)와 points(글머리 줄) 또는 body(표·목록·문단)를 쓰고, 근거는 도구 결과의 [n] 번호.",
     "input_schema": rag.build_schema()},
]


@dataclass
class AgentStepInput:
    model: str
    today: date
    messages: list[dict]
    hints: interpret.InterpretInput = None  # 허용 코드 목록(카테고리·종류·속성 코드와 이름)


def system_prompt(data: AgentStepInput) -> str:
    parts = [PROMPT["system"], rag.PROMPT["system"], rag.PROMPT["system_trend"]]
    if data.hints is not None:
        parts.append(interpret.allowed_values_text(data.hints))
    return "\n\n".join(parts)


def task_text(today: date) -> str:
    return PROMPT["task"].replace("{today}", today.isoformat())


def step(data: AgentStepInput, api_key: str, client: anthropic.Anthropic | None = None) -> dict:
    """{stop_reason, content(응답 블록 — text·tool_use), usage}. 도구 실행은 호출한 쪽(Spring)이 한다."""
    started = time.monotonic()
    response = create_message(client, api_key, model=data.model, max_tokens=MAX_TOKENS, system=system_prompt(data),
                              messages=data.messages, tools=TOOLS)
    usage = usage_of(response, time.monotonic() - started)
    content = [block.model_dump(exclude_none=True) for block in response.content]
    calls = [b.get("name") for b in content if b.get("type") == "tool_use"]
    log.info("도구 경로 한 단계 model=%s stop=%s 소요=%.1fs 도구=%s 토큰 in=%d out=%d", data.model, response.stop_reason,
             usage["elapsed_ms"] / 1000, calls or "-", usage["input_tokens"], usage["output_tokens"])
    return {"stop_reason": response.stop_reason, "content": content, "usage": usage}
