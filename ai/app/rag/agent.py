"""P-06 도구 경로(유행 질문 전부, 2026-10-29 사용자 결정) — Claude 한 단계 호출만 한다.

반복(도구 실행·상한 6번·실패 시 되돌리기)은 Spring(AgentService)이 한다 — 집계 데이터가 Spring DB에 있어서, 도구는 Spring이
실행하고 결과를 다음 단계 messages로 넘긴다. 여기서는 system(도구 규칙 + 답변 규칙 + 허용 코드)·도구 정의를 붙여 한 번 부르고
응답 블록을 그대로 돌려준다. 숫자는 도구(Spring 집계 코드)가 센다.

입력 토큰(2026-10-30 사용자 요청 — 한 단계 고정 약 10,100토큰): 허용 값은 영문 코드값 없이 이름만 싣고(도구 입력도 이름 — Spring
AgentService.Codes.resolve가 코드값으로 바꾼다), 디테일 목록은 질문에 디테일 말이 있을 때만 싣는다(질문 해석과 같은 기준).
도구 설명은 결과 형식·기본값만 짧게 쓴다.
"""
import logging
import time
from dataclasses import dataclass, field, replace
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
    "category": {"type": "string", "description": "카테고리 이름"},
    "item_type": {"type": "string", "description": "아이템 종류 이름"},
    "materials": _CODES, "colors": _CODES, "silhouettes": _CODES, "patterns": _CODES, "details": _CODES, "genders": _CODES,
    "terms": {"type": "array", "items": {"type": "string"}, "description": "목록에 없는 패션 단어(무스탕·발마칸…)"},
    "media": {"type": "array", "items": {"type": "string"}, "description": "매체 이름(그 매체 원문만 셀 때 — 예: 엘르코리아)"},
}
TOOLS = [
    {"name": "count_trend",
     "description": "범위의 원문을 세어 축별 상위 값(유행점수 순, change를 주면 증감 순)과 예시 원문 [n]을 돌려준다. "
                    "기간은 period, 없으면 최근 4주. seasons를 주면 그 시즌 원문만, 둘 다 없으면 지금 시즌(결과의 [시즌] 줄). "
                    "focus는 첫 축, top은 축마다 값 개수(기본 10).",
     "input_schema": {"type": "object", "properties": {
         **_SCOPE,
         "focus": {"type": "string", "enum": interpret.FOCUS, "description": "주로 볼 축"},
         "seasons": _CODES,
         "period": _PERIOD,
         "change": {"type": "string", "enum": interpret.CHANGE, "description": "증감 순으로 볼 때"},
         "top": {"type": "integer", "minimum": 1, "maximum": 15},
     }}},
    {"name": "trend_series",
     "description": "값(axis + value 또는 values 최대 5개, 없으면 범위 전체)의 최근 weeks주 주별 원문 수와 그 주 전체 원문 수. "
                    "여러 값은 values로 한 번에 센다.",
     "input_schema": {"type": "object", "properties": {
         **_SCOPE,
         "axis": {"type": "string", "enum": interpret.FOCUS, "description": "센 값의 축(value·values와 함께)"},
         "value": {"type": "string", "description": "센 값의 이름(예: 패딩, 브라운)"},
         "values": {"type": "array", "items": {"type": "string"}, "maxItems": 5},
         "weeks": {"type": "integer", "minimum": 2, "maximum": 26},
     }, "required": ["weeks"]}},
    {"name": "search_documents",
     "description": "질문과 비슷한 원문(기사·사진 분석 요약)을 [n] 번호와 함께 찾는다. 이유·배경·스타일링·사례의 근거용 — 수를 세지 않는다.",
     "input_schema": {"type": "object", "properties": {
         "query": {"type": "string", "description": "찾을 내용 한 문장"},
         "category": {"type": "string", "description": "카테고리 이름으로 좁힐 때"},
     }, "required": ["query"]}},
    {"name": "final_answer",
     "description": "최종 답(답변 규칙대로). 근거는 도구 결과의 [n] 번호.",
     "input_schema": rag.build_schema()},
]


@dataclass
class AgentStepInput:
    model: str
    today: date
    messages: list[dict]
    hints: interpret.InterpretInput = None  # 허용 값 목록(카테고리·종류·속성 이름)
    cache: bool = False  # 규칙·도구 정의를 캐시(같은 질문의 둘째 단계부터 — 셋째 단계부터 캐시를 읽는다)


def system_prompt(data: AgentStepInput) -> list[dict]:
    parts = [PROMPT["system"], rag.PROMPT["system"], rag.PROMPT["system_trend"]]
    if data.hints is not None:
        # 이름만 싣는다. 디테일 목록은 질문(첫 메시지)에 디테일 말이 있을 때만 — 같은 질문의 단계끼리는 같아서 캐시가 깨지지 않는다
        hints = data.hints if data.hints.question else replace(data.hints, question=_first_text(data.messages))
        parts.append(interpret.allowed_values_text(hints))
    block = {"type": "text", "text": "\n\n".join(parts)}
    if data.cache:  # 캐시 순서는 tools → system이라 이 표시 하나로 도구 정의까지 캐시된다
        block["cache_control"] = {"type": "ephemeral"}
    return [block]


def _first_text(messages: list[dict]) -> str:
    """첫 user 메시지의 글(질문·미리 센 집계) — 디테일 목록을 보낼지 정할 때 쓴다."""
    if not messages:
        return ""
    content = messages[0].get("content")
    if isinstance(content, str):
        return content
    return " ".join(b.get("text", "") for b in content or [] if isinstance(b, dict))


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
