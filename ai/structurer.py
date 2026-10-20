"""P-03 AI 정형화 — 문서(이미지 또는 텍스트) 한 건을 Claude Structured Output으로 트렌드 항목 JSON 하나로 바꾼다.

의류 아이템마다 카테고리·소재·컬러·실루엣을 따로 받는다(items 배열) — 코디 사진 한 장에 상의·하의가 같이 나오는데
문서 한 묶음으로 받으면 "그레이 니트"가 하의 트렌드로도 잡힌다(2026-10-20 사용자 결정: 아이템 배열, 의류만).
코드 값은 Spring이 보낸 공통코드 안에서만 고르게 스키마의 enum으로 묶는다 — 가죽/레더 같은 표기 흔들림을 막는 장치다.
출처(source)는 LLM이 만들지 않는다. Spring이 원문 문서의 값을 붙인다.
"""
import json
import logging
import time
from dataclasses import dataclass

import anthropic

import prompt_loader

log = logging.getLogger("trend-ai")

SENTIMENTS = ["positive", "neutral", "negative"]
MAX_TOKENS = 16000

# Claude가 읽는 글(규칙·지시문)은 prompts/structure-*.md에 있다. 여기서는 그 글을 조립만 한다.
# 공통 규칙 + 자료 유형 규칙만 보낸다 — 이미지 분석에 텍스트 기준("본문에 나온 소재만")이 섞이지 않게.
COMMON = prompt_loader.load("structure-common", ["system", "allowed_values_header"])
# 실루엣 코드는 핏·기장·형태가 한 목록에 섞여 있어 기준 없이 두면 같은 옷이 매번 다르게 나온다 — 카테고리별 기준
SILHOUETTE = prompt_loader.load("structure-silhouette", ["system"])
BY_TYPE = {
    "IMAGE": prompt_loader.load("structure-image", ["system", "task"]),
    "TEXT": prompt_loader.load("structure-text", ["system", "title_label", "task"]),
}


def system_prompt(doc_type: str) -> str:
    return "\n\n".join([COMMON["system"], SILHOUETTE["system"], BY_TYPE[doc_type]["system"]])


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
    seasons: list[CodeOption]


def build_schema(data: StructureInput) -> dict:
    def enum_array(options: list[CodeOption]) -> dict:
        return {"type": "array", "items": {"type": "string", "enum": [o.value for o in options]}}

    # 아이템 수(최대 5)는 스키마로 걸 수 없다(배열 길이 제약 미지원) — 프롬프트로 지시하고 Spring 검증이 막는다
    item = {
        "type": "object",
        "properties": {
            "category": {"type": "string", "enum": [o.value for o in data.categories]},
            "item": {"type": "string"},
            "materials": enum_array(data.materials),
            "colors": enum_array(data.colors),
            "silhouettes": enum_array(data.silhouettes),
        },
        "required": ["category", "item", "materials", "colors", "silhouettes"],
        "additionalProperties": False,
    }
    return {
        "type": "object",
        "properties": {
            "items": {"type": "array", "items": item},
            "keywords": {"type": "array", "items": {"type": "string"}},
            "seasons": enum_array(data.seasons),  # 문서 단위 — 이 자료가 다루는 트렌드의 시즌
            "sentiment": {"type": "string", "enum": SENTIMENTS},
            "summary": {"type": "string"},
        },
        "required": ["items", "keywords", "seasons", "sentiment", "summary"],
        "additionalProperties": False,
    }


def _allowed_values_text(data: StructureInput) -> str:
    def line(label: str, options: list[CodeOption]) -> str:
        return f"- {label}: " + ", ".join(f"{o.value}={o.name}" for o in options)

    return COMMON["allowed_values_header"] + "\n" + "\n".join([
        line("category", data.categories),
        line("materials", data.materials),
        line("colors", data.colors),
        line("silhouettes", data.silhouettes),
        line("seasons", data.seasons),
    ])


def build_content(data: StructureInput) -> list[dict]:
    content: list[dict] = []
    if data.doc_type == "IMAGE":
        content.append({
            "type": "image",
            "source": {"type": "base64", "media_type": data.image_media_type, "data": data.image_base64},
        })
        task = BY_TYPE["IMAGE"]["task"]
    else:
        title = f"{BY_TYPE['TEXT']['title_label']} {data.title}\n" if data.title else ""
        content.append({"type": "text", "text": f"<document>\n{title}{data.text}\n</document>"})
        task = BY_TYPE["TEXT"]["task"]
    content.append({"type": "text", "text": f"{_allowed_values_text(data)}\n\n{task}"})
    return content


def candidate_counts(data: StructureInput) -> str:
    """Claude가 판단할 후보(허용 코드) 건수 — 로그용."""
    return (f"카테고리 {len(data.categories)}·소재 {len(data.materials)}·컬러 {len(data.colors)}·"
            f"실루엣 {len(data.silhouettes)}·시즌 {len(data.seasons)}")


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
            system=system_prompt(data.doc_type),
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
