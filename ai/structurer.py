"""P-03 AI 정형화 — 문서(이미지 또는 텍스트) 한 건을 Claude Structured Output으로 트렌드 항목 JSON 하나로 바꾼다.

카테고리·소재·컬러·실루엣은 Spring이 보낸 공통코드 값 안에서만 고르게 스키마의 enum으로 묶는다 — 가죽/레더 같은
표기 흔들림을 막는 장치다. 출처(source)는 LLM이 만들지 않는다. Spring이 원문 문서의 값을 붙인다.
"""
import json
import logging
import time
from dataclasses import dataclass

import anthropic

log = logging.getLogger("trend-ai")

SENTIMENTS = ["positive", "neutral", "negative"]
MAX_TOKENS = 16000

SYSTEM_PROMPT = """당신은 패션 트렌드 분석가다. 주어진 패션 자료(이미지 또는 텍스트) 한 건에서 트렌드 항목을 뽑아 정해진 JSON으로 답한다.

규칙:
- category, materials, colors, silhouettes는 "허용 값" 목록의 코드값만 쓴다. 목록에 없는 표현은 가장 가까운 코드로 옮기고, 맞는 것이 없으면 category는 ETC, 나머지는 빈 배열로 둔다.
- 자료에서 확인되는 것만 쓴다. 이미지라면 실제로 보이는 것, 텍스트라면 본문에 나온 것만 쓰고 추측으로 채우지 않는다.
- keywords는 트렌드를 나타내는 핵심어 1~8개를 한국어로 쓴다(예: "레더 재킷", "뉴트로"). 브랜드명은 트렌드의 핵심일 때만 넣는다.
- sentiment는 자료가 그 트렌드를 다루는 태도다(positive / neutral / negative).
- summary는 한국어 1~2문장으로 무엇이 유행하는지 쓴다."""


class StructuringError(Exception):
    """정형화 실패. retryable이면 Spring이 다시 시도해 볼 만하다(일시적 오류·형식 오류)."""

    def __init__(self, message: str, retryable: bool, status_code: int = 502, usage: dict | None = None):
        super().__init__(message)
        self.retryable = retryable
        self.status_code = status_code
        self.usage = usage  # Claude가 응답은 했지만 쓸 수 없던 경우(거절·잘림·JSON 오류)에도 토큰은 과금된다


@dataclass
class CodeOption:
    value: str
    name: str


@dataclass
class StructureInput:
    doc_id: str
    doc_type: str  # IMAGE | TEXT
    model: str
    title: str | None
    text: str | None
    image_base64: str | None
    image_media_type: str | None
    categories: list[CodeOption]
    materials: list[CodeOption]
    colors: list[CodeOption]
    silhouettes: list[CodeOption]


def build_schema(data: StructureInput) -> dict:
    def enum_array(options: list[CodeOption]) -> dict:
        return {"type": "array", "items": {"type": "string", "enum": [o.value for o in options]}}

    return {
        "type": "object",
        "properties": {
            "category": {"type": "string", "enum": [o.value for o in data.categories]},
            "keywords": {"type": "array", "items": {"type": "string"}},
            "materials": enum_array(data.materials),
            "colors": enum_array(data.colors),
            "silhouettes": enum_array(data.silhouettes),
            "sentiment": {"type": "string", "enum": SENTIMENTS},
            "summary": {"type": "string"},
        },
        "required": ["category", "keywords", "materials", "colors", "silhouettes", "sentiment", "summary"],
        "additionalProperties": False,
    }


def _allowed_values_text(data: StructureInput) -> str:
    def line(label: str, options: list[CodeOption]) -> str:
        return f"- {label}: " + ", ".join(f"{o.value}={o.name}" for o in options)

    return "허용 값(코드값=이름):\n" + "\n".join([
        line("category", data.categories),
        line("materials", data.materials),
        line("colors", data.colors),
        line("silhouettes", data.silhouettes),
    ])


def build_content(data: StructureInput) -> list[dict]:
    content: list[dict] = []
    if data.doc_type == "IMAGE":
        content.append({
            "type": "image",
            "source": {"type": "base64", "media_type": data.image_media_type, "data": data.image_base64},
        })
        task = "이 패션 이미지에서 트렌드 항목을 뽑아라."
    else:
        title = f"제목: {data.title}\n" if data.title else ""
        content.append({"type": "text", "text": f"<document>\n{title}{data.text}\n</document>"})
        task = "위 문서에서 트렌드 항목을 뽑아라."
    content.append({"type": "text", "text": f"{_allowed_values_text(data)}\n\n{task}"})
    return content


def candidate_counts(data: StructureInput) -> str:
    """Claude가 판단할 후보(허용 코드) 건수 — 로그용."""
    return (f"카테고리 {len(data.categories)}·소재 {len(data.materials)}·컬러 {len(data.colors)}·"
            f"실루엣 {len(data.silhouettes)}")


def usage_of(response, elapsed: float) -> dict:
    usage = response.usage
    return {
        "input_tokens": usage.input_tokens,
        "output_tokens": usage.output_tokens,
        "cache_creation_input_tokens": getattr(usage, "cache_creation_input_tokens", None) or 0,
        "cache_read_input_tokens": getattr(usage, "cache_read_input_tokens", None) or 0,
        "elapsed_ms": int(elapsed * 1000),
    }


def structure(data: StructureInput, api_key: str, client: anthropic.Anthropic | None = None) -> tuple[dict, dict]:
    """(정형 결과, 사용량). 테스트는 client를 바꿔 끼운다."""
    if client is None:
        client = anthropic.Anthropic(api_key=api_key, max_retries=2, timeout=120.0)
    started = time.monotonic()
    try:
        response = client.messages.create(
            model=data.model,
            max_tokens=MAX_TOKENS,
            system=SYSTEM_PROMPT,
            messages=[{"role": "user", "content": build_content(data)}],
            output_config={"format": {"type": "json_schema", "schema": build_schema(data)}, "effort": "medium"},
        )
    except anthropic.AuthenticationError as e:
        raise StructuringError("Claude API 키가 올바르지 않습니다(claude.api.key).", retryable=False, status_code=401) from e
    except anthropic.BadRequestError as e:
        raise StructuringError(f"Claude가 요청을 거부했습니다: {e.message}", retryable=False, status_code=400) from e
    except (anthropic.RateLimitError, anthropic.APIConnectionError, anthropic.InternalServerError) as e:
        raise StructuringError(f"Claude 호출 일시 오류: {e}", retryable=True) from e
    except anthropic.APIStatusError as e:
        raise StructuringError(f"Claude 호출 실패({e.status_code}): {e.message}", retryable=e.status_code >= 500) from e

    usage = usage_of(response, time.monotonic() - started)
    log.info("Claude 응답 doc_id=%s model=%s stop=%s 소요=%.1fs 토큰 in=%d out=%d (캐시 생성 %d·읽기 %d)",
             data.doc_id, data.model, response.stop_reason, usage["elapsed_ms"] / 1000, usage["input_tokens"],
             usage["output_tokens"], usage["cache_creation_input_tokens"], usage["cache_read_input_tokens"])

    if response.stop_reason == "refusal":
        category = response.stop_details.category if response.stop_details else None
        raise StructuringError(f"Claude가 응답을 거절했습니다(분류: {category}).", retryable=False, status_code=422,
                               usage=usage)
    if response.stop_reason == "max_tokens":
        raise StructuringError("응답이 길이 상한에서 잘렸습니다.", retryable=True, status_code=422, usage=usage)

    text = next((block.text for block in response.content if block.type == "text"), None)
    if text is None:
        raise StructuringError("응답에 JSON 본문이 없습니다.", retryable=True, status_code=422, usage=usage)
    try:
        result = json.loads(text)
    except json.JSONDecodeError as e:
        raise StructuringError(f"응답 JSON을 읽지 못했습니다: {e}", retryable=True, status_code=422,
                               usage=usage) from e
    return result, usage
