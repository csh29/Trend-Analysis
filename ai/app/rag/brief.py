"""P-06 기획 브리프 — Spring이 센 집계·예시 원문으로 8개 구성의 서술만 JSON으로 받는다(2026-10-29 사용자 요청).

차트·건수·출처 분포는 Spring 집계를 화면이 그대로 그린다. Claude는 숫자를 만들지 않고 문장(핵심 트렌드·시사점·제안·과제)만 쓴다.
"""
import logging
import time
from dataclasses import dataclass, field
from datetime import date

import anthropic

from app.core import prompt_loader
from app.core.claude import create_message, parse_json, usage_of

log = logging.getLogger("trend-ai")

PROMPT = prompt_loader.load("rag/brief", ["system", "task"])
MAX_TOKENS = 6000


def _list(desc: str) -> dict:
    return {"type": "array", "items": {"type": "string"}, "description": desc}


def _text(desc: str) -> dict:
    return {"type": "string", "description": desc}


def _obj(props: dict) -> dict:
    return {"type": "object", "properties": props, "required": list(props), "additionalProperties": False}


SCHEMA = _obj({
    "title": _text("브리프 제목(예: 2026 F/W 아우터 트렌드 기획 브리프)"),
    "season": _text("시즌 및 분석 대상(예: 2026 F/W · 아우터 · 여성복). 자료에 있는 범위·기간으로"),
    "summary": _obj({
        "trends": _list("핵심 트렌드 3~5개 — 한 줄씩"),
        "change": _text("가장 주목할 만한 변화 한두 문장(직전 기간 대비)"),
        "implications": _list("상품 기획 관점의 핵심 시사점 2~4개"),
    }),
    "items": _obj({
        "insight": _text("아이템 트렌드 요약 한두 문장 — 카테고리별로 어디에 관심이 모이는지"),
        "highlights": _list("눈여겨볼 아이템 2~4개 — '아이템: 특징(예시 원문에 보이는 디자인)'"),
    }),
    "colors": _obj({
        "insight": _text("컬러 요약 한두 문장 — 주요 컬러와 변화"),
        "highlights": _list("주요 컬러별 활용 아이템 2~4개 — '컬러: 많이 쓰인 아이템'"),
        "combos": _list("컬러 조합·배색 방향 1~3개"),
    }),
    "fit": _obj({
        "insight": _text("핏·실루엣 요약 한두 문장"),
        "fits": _list("핏·기장·형태 변화 2~4개"),
        "details": _list("넥라인·포켓·여밈·짜임 등 디테일 2~4개"),
        "combos": _list("아이템별 주요 핏·디자인 조합 1~3개"),
    }),
    "materials": _obj({
        "insight": _text("소재·패턴 요약 한두 문장"),
        "materials": _list("소재별 관심도·활용 2~4개"),
        "patterns": _list("패턴 변화 1~3개(무지가 대부분이면 그렇게)"),
        "pairings": _list("소재와 아이템의 결합 1~3개"),
    }),
    "evidence": _obj({
        "cautions": _list("데이터 주의사항 2~4개 — 표본 크기·출처 쏠림·기간·이미지 판독 한계 등"),
    }),
    "proposals": _obj({
        "priority_items": _list("우선 검토할 아이템 2~4개(가설)"),
        "combos": _list("추천 컬러·소재·핏 조합 2~3개"),
        "basic_vs_trend": _text("기본 상품과 트렌드 상품의 구성 방향 한두 문장"),
        "tests": _list("추가 조사·테스트가 필요한 상품 1~3개"),
        "uncertainty": _text("제안의 근거와 불확실성 한두 문장"),
    }),
    "actions": _obj({
        "review": _list("먼저 검토할 상품과 디자인 방향 2~3개"),
        "data_needed": _list("추가로 수집할 데이터 2~3개"),
        "followups": _list("후속 분석 과제 1~3개"),
        "checks": _list("기획 담당자가 확인할 사항 2~3개"),
    }),
})


@dataclass
class BriefInput:
    question: str
    model: str
    today: date
    data: list[str]                                  # Spring 집계 줄(범위·카테고리별 아이템·늘어난 값·출처 분포)
    sources: list[dict] = field(default_factory=list)  # {n, title, media, date, summary}


def build_content(data: BriefInput) -> str:
    sources = "\n".join(f"[{s.get('n')}] {s.get('title') or ''} · {s.get('media') or ''} · {s.get('date') or ''} — {s.get('summary') or ''}"
                        for s in data.sources)
    return (f"오늘: {data.today.isoformat()}\n\n<question>\n{data.question}\n</question>\n\n"
            f"<data>\n" + "\n".join(data.data) + f"\n</data>\n\n<sources>\n{sources or '없음'}\n</sources>\n\n{PROMPT['task']}")


def write(data: BriefInput, api_key: str, client: anthropic.Anthropic | None = None) -> dict:
    """{brief(8개 구성 JSON), usage}. Claude 호출 실패는 AiServiceError."""
    started = time.monotonic()
    response = create_message(client, api_key, model=data.model, max_tokens=MAX_TOKENS, system=PROMPT["system"],
                              messages=[{"role": "user", "content": build_content(data)}],
                              output_config={"format": {"type": "json_schema", "schema": SCHEMA}, "effort": "medium"})
    usage = usage_of(response, time.monotonic() - started)
    brief = parse_json(response, usage)
    log.info("기획 브리프 model=%s stop=%s 소요=%.1fs 집계 %d줄 예시 %d건 토큰 in=%d out=%d", data.model, response.stop_reason,
             usage["elapsed_ms"] / 1000, len(data.data), len(data.sources), usage["input_tokens"], usage["output_tokens"])
    return {"brief": brief, "usage": usage}
