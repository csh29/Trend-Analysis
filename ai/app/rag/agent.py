"""P-06 도구 경로(유행 질문 전부, 2026-10-29 사용자 결정) — Claude 한 단계 호출만 한다.

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
     "description": "범위의 원문을 세어 축별 상위 값(유행점수 순 — 원문 수·출처 수·직전 기간 대비 증감률·유행점수)과 예시 원문을 돌려준다. "
                    "period가 없으면 최근 4주, seasons를 주면 그 시즌 원문만. 둘 다 없으면 최근 4주의 지금 시즌 원문만(적으면 시즌 구분 없이 — 결과의 [시즌] 줄).focus를 주면 그 축이 첫 축이다. top은 축마다 받을 값 개수(기본 10).",
     "input_schema": {"type": "object", "properties": {
         **_SCOPE,
         "focus": {"type": "string", "enum": interpret.FOCUS, "description": "주로 볼 축"},
         "seasons": _CODES,
         "period": _PERIOD,
         "change": {"type": "string", "enum": interpret.CHANGE, "description": "증감 순으로 볼 때"},
         "top": {"type": "integer", "minimum": 1, "maximum": 15},
     }}},
    {"name": "trend_series",
     "description": "범위 안에서 값(axis + value 또는 values 최대 5개, 없으면 범위 전체)의 주별 원문 수를 최근 weeks주(최대 26) 동안 센다. "
                    "주마다 그 주 전체 분석 원문 수도 준다 — 수집량이 다른 주끼리는 건수가 아니라 전체 대비 비율로 비교한다. "
                    "여러 값의 흐름은 values로 한 번에 센다.",
     "input_schema": {"type": "object", "properties": {
         **_SCOPE,
         "axis": {"type": "string", "enum": interpret.FOCUS, "description": "센 값의 축(value·values와 함께)"},
         "value": {"type": "string", "description": "센 값의 코드(예: OUTER_PADDING, BROWN)"},
         "values": {"type": "array", "items": {"type": "string"}, "maxItems": 5, "description": "센 값의 코드 여러 개(최대 5)"},
         "weeks": {"type": "integer", "minimum": 2, "maximum": 26},
     }, "required": ["weeks"]}},
    {"name": "search_documents",
     "description": "질문과 비슷한 원문(기사·사진 분석 요약)을 유사도로 찾아 [n] 번호와 함께 돌려준다. 이유·배경·스타일링·사례처럼 "
                    "집계 수로는 답할 수 없는 내용의 근거를 찾을 때 쓴다. 수를 세는 도구가 아니다.",
     "input_schema": {"type": "object", "properties": {
         "query": {"type": "string", "description": "찾을 내용을 한 문장으로(예: 와이드 팬츠가 유행하는 이유·스타일링)"},
         "category": {"type": "string", "description": "카테고리 코드로 좁힐 때(없으면 생략)"},
     }, "required": ["query"]}},
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
    cache: bool = False  # 규칙·도구 정의를 캐시(같은 질문의 둘째 단계부터 — 셋째 단계부터 캐시를 읽는다)


def system_prompt(data: AgentStepInput) -> list[dict]:
    parts = [PROMPT["system"], rag.PROMPT["system"], rag.PROMPT["system_trend"]]
    if data.hints is not None:
        parts.append(interpret.code_values_text(data.hints))
    block = {"type": "text", "text": "\n\n".join(parts)}
    if data.cache:  # 캐시 순서는 tools → system이라 이 표시 하나로 도구 정의까지 캐시된다
        block["cache_control"] = {"type": "ephemeral"}
    return [block]


def task_text(today: date) -> str:
    return PROMPT["task"].replace("{today}", today.isoformat())


def step(data: AgentStepInput, api_key: str, client: anthropic.Anthropic | None = None) -> dict:
    """{stop_reason, content(응답 블록 — text·tool_use), usage}. 도구 실행은 호출한 쪽(Spring)이 한다."""
    started = time.monotonic()
    # effort medium — 지정하지 않으면 기본(high)으로 길게 생각해 답 한 단계가 40초를 넘었다(2026-10-29)
    response = create_message(client, api_key, model=data.model, max_tokens=MAX_TOKENS, system=system_prompt(data),
                              messages=data.messages, tools=TOOLS, output_config={"effort": "medium"})
    usage = usage_of(response, time.monotonic() - started)
    content = [block.model_dump(exclude_none=True) for block in response.content]
    calls = [b.get("name") for b in content if b.get("type") == "tool_use"]
    log.info("도구 경로 한 단계 model=%s stop=%s 소요=%.1fs 도구=%s 토큰 in=%d out=%d 캐시 생성=%d 읽기=%d", data.model, response.stop_reason,
             usage["elapsed_ms"] / 1000, calls or "-", usage["input_tokens"], usage["output_tokens"],
             usage["cache_creation_input_tokens"], usage["cache_read_input_tokens"])
    return {"stop_reason": response.stop_reason, "content": content, "usage": usage}
