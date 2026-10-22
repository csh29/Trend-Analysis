"""P-03 AI 정형화 — 원문 한 건(본문·이미지 N장 중 있는 것)을 Claude Structured Output으로 트렌드 항목 JSON 하나로 바꾼다.

원문 1건 = 본문 + 이미지(2026-10-21 사용자 결정). 기사·잡지·인스타 게시물은 본문과 사진이 한 묶음이라, 따로 분석하면
같은 트렌드가 여러 번 세지고 본문의 소재·시즌이 사진 분석에 반영되지 않는다. 그래서 한 번의 호출에 같이 보낸다.

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

from app.core import prompt_loader
from app.core.claude import create_message, parse_json, usage_of

log = logging.getLogger("trend-ai")

SENTIMENTS = ["positive", "neutral", "negative"]
MAX_TOKENS = 16000

# Claude가 읽는 글(규칙·지시문)은 prompts/structure/*.md에 있다. 여기서는 그 글을 조립만 한다.
# 공통 규칙 + 원문에 있는 것(본문·이미지)의 규칙만 보낸다 — 사진만 있는 자료에 텍스트 기준("본문에 나온 소재만")이 섞이지 않게.
COMMON = prompt_loader.load("structure/common", ["system", "allowed_values_header"])
# 실루엣 코드는 핏·기장·형태가 한 목록에 섞여 있어 기준 없이 두면 같은 옷이 매번 다르게 나온다 — 카테고리별 기준
SILHOUETTE = prompt_loader.load("structure/silhouette", ["system"])
IMAGE = prompt_loader.load("structure/image", ["system", "task"])
TEXT = prompt_loader.load("structure/text", ["system", "title_label", "task"])
# 본문과 이미지가 같이 오면 두 기준을 다 보내고, 부딪칠 때의 우선순위를 마지막에 붙인다
ARTICLE = prompt_loader.load("structure/article", ["system", "task"])


def system_prompt(data: "StructureInput") -> str:
    parts = [COMMON["system"], SILHOUETTE["system"]]
    if data.has_text:
        parts.append(TEXT["system"])
    if data.images:
        parts.append(IMAGE["system"])
    if data.has_text and data.images:
        parts.append(ARTICLE["system"])
    return "\n\n".join(parts)


@dataclass
class CodeOption:
    value: str
    name: str


@dataclass
class ImageInput:
    data: str  # base64(image_prep.prepare를 거친 것)
    media_type: str


@dataclass
class StructureInput:
    doc_id: str
    doc_type: str  # IMAGE(본문 없음) | TEXT(본문 있음 — 이미지는 있어도 없어도 된다)
    model: str
    title: str | None
    text: str | None
    images: list[ImageInput]
    categories: list[CodeOption]
    item_types: list[CodeOption]  # 코드값 = 카테고리_종류(OUTER_PADDING) — 유행 집계용
    materials: list[CodeOption]
    colors: list[CodeOption]
    silhouettes: list[CodeOption]
    seasons: list[CodeOption]

    @property
    def has_text(self) -> bool:
        return bool(self.text and self.text.strip())


def build_schema(data: StructureInput) -> dict:
    def enum_array(options: list[CodeOption]) -> dict:
        return {"type": "array", "items": {"type": "string", "enum": [o.value for o in options]}}

    # 아이템 수(최대 5)는 스키마로 걸 수 없다(배열 길이 제약 미지원) — 프롬프트로 지시하고 Spring 검증이 막는다
    item = {
        "type": "object",
        "properties": {
            "category": {"type": "string", "enum": [o.value for o in data.categories]},
            "item": {"type": "string"},
            # 카테고리와 맞는지(OUTER면 OUTER_…)는 스키마로 묶을 수 없어 Spring 검증이 본다
            "type": {"type": "string", "enum": [o.value for o in data.item_types]},
            "materials": enum_array(data.materials),
            "colors": enum_array(data.colors),
            "silhouettes": enum_array(data.silhouettes),
        },
        "required": ["category", "item", "type", "materials", "colors", "silhouettes"],
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
        line("type", data.item_types),
        line("materials", data.materials),
        line("colors", data.colors),
        line("silhouettes", data.silhouettes),
        line("seasons", data.seasons),
    ])


def build_content(data: StructureInput) -> list[dict]:
    content: list[dict] = []
    # 자료(이미지·본문)를 앞에, 지시문은 마지막에 둔다
    for image in data.images:
        content.append({"type": "image", "source": {"type": "base64", "media_type": image.media_type, "data": image.data}})
    if data.has_text:
        title = f"{TEXT['title_label']} {data.title}\n" if data.title else ""
        content.append({"type": "text", "text": f"<document>\n{title}{data.text}\n</document>"})
    if data.has_text and data.images:
        task = ARTICLE["task"]
    elif data.images:
        task = IMAGE["task"]
    else:
        task = TEXT["task"]
    content.append({"type": "text", "text": f"{_allowed_values_text(data)}\n\n{task}"})
    return content


def candidate_counts(data: StructureInput) -> str:
    """Claude가 판단할 후보(허용 코드) 건수 — 로그용."""
    return (f"본문 {len(data.text or '')}자·이미지 {len(data.images)}장 / 카테고리 {len(data.categories)}·종류 {len(data.item_types)}·소재 {len(data.materials)}·컬러 {len(data.colors)}·"
            f"실루엣 {len(data.silhouettes)}·시즌 {len(data.seasons)}")


def structure(data: StructureInput, api_key: str, client: anthropic.Anthropic | None = None) -> tuple[dict, dict]:
    """(정형 결과, 사용량). 테스트는 client를 바꿔 끼운다."""
    started = time.monotonic()
    response = create_message(client, api_key,
                              model=data.model,
                              max_tokens=MAX_TOKENS,
                              system=system_prompt(data),
                              messages=[{"role": "user", "content": build_content(data)}],
                              output_config={"format": {"type": "json_schema", "schema": build_schema(data)},
                                             "effort": "medium"})
    usage = usage_of(response, time.monotonic() - started)
    log.info("Claude 응답 doc_id=%s model=%s stop=%s 소요=%.1fs 토큰 in=%d out=%d (캐시 생성 %d·읽기 %d)",
             data.doc_id, data.model, response.stop_reason, usage["elapsed_ms"] / 1000, usage["input_tokens"],
             usage["output_tokens"], usage["cache_creation_input_tokens"], usage["cache_read_input_tokens"])
    return parse_json(response, usage), usage
