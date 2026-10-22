"""P-06 RAG 검색·답변 — 질문을 임베딩해 Chroma에서 근거 청크를 찾고, 근거가 기준 유사도 이상이면 Claude가 답한다.

- 유사도(score)는 1 - 코사인 거리다(컬렉션이 cosine 공간). 1에 가까울수록 비슷하다.
- 기준 유사도(min_score) 이상인 근거가 하나도 없으면 Claude를 부르지 않고 "근거 없음"으로 돌려준다(정의서 P-06 규칙).
- 근거는 문서 단위로 묶어 번호를 붙인다 — 화면은 문서(출처)를 보여 주므로, 같은 문서의 청크 두 개가 [1]·[2]로 갈리지 않게.
- 집계 수치는 Spring이 보낸다(trend_weekly_stats). 근거가 아니라 참고 수치로만 쓴다.
- 기간을 콕 집은 질문(period.py — 지난달·최근 3개월…)은 그 게시일 범위에서만 찾는다.
- "요즘"처럼 기간이 없는 질문은 자르지 않고, 기준 유사도를 넘은 후보를 넉넉히 모아 **유사도 × 최신성**(오늘부터 4주마다
  절반 — 반감기 4주)으로 다시 순위를 매겨 상위만 근거로 쓴다(2026-10-22 사용자 결정). 최근 관련 자료가 없으면 오래된 것이
  그대로 올라오므로, 근거 게시일 범위를 늘 알리고 가장 최근 근거도 8주가 넘으면(stale) 자료 시점을 밝히게 한다.
LangChain은 쓰지 않는다(2026-10-21 사용자 결정) — 검색 한 번, 답변 한 번이라 SDK 직접 호출로 충분하다.
"""
import logging
import time
from dataclasses import dataclass, field
from datetime import date

import anthropic

from app.rag import period as periods
from app.core import prompt_loader
from app.core.claude import AiServiceError, create_message, parse_json, usage_of

log = logging.getLogger("trend-ai")

PROMPT = prompt_loader.load("rag/answer", ["system", "period", "period_recent", "period_stale", "stats_header",
                                           "sources_header", "task"])
MAX_TOKENS = 4000
HALF_LIFE_DAYS = 28   # 최신성 가중치가 절반이 되는 날수 — "요즘"을 4주 단위로 본다
CANDIDATES = 30       # 최신성으로 다시 고르기 전에 유사도로 모으는 후보 청크 수
STALE_DAYS = 56       # 가장 최근 근거가 이보다 오래되면 "자료가 오래됐다"고 밝힌다


def search(collection, query: str, top_k: int, where: dict | None = None) -> list[dict]:
    """질문과 비슷한 청크(유사도 높은 순). 질의 임베딩은 Chroma가 embed_query(질의 지시문 포함)로 만든다. where는 메타 필터."""
    got = collection.query(query_texts=[query], n_results=top_k, where=where,
                           include=["documents", "metadatas", "distances"])
    hits = []
    for i, chunk_id in enumerate(got["ids"][0]):
        hits.append({
            "id": chunk_id,
            "document": got["documents"][0][i],
            "metadata": got["metadatas"][0][i],
            "score": round(1.0 - float(got["distances"][0][i]), 4),
        })
    return hits


def published_date(meta: dict) -> date | None:
    try:
        return date.fromisoformat(meta.get("published_at") or "")
    except ValueError:
        return None


def rank_by_recency(hits: list[dict], today: date) -> list[dict]:
    """유사도 × 0.5^(경과일/28)로 다시 줄 세운다(rank). 게시일을 모르면 가장 오래된 것으로 친다(가중치 0)."""
    for hit in hits:
        published = published_date(hit["metadata"])
        weight = 0.0 if published is None else 0.5 ** (max((today - published).days, 0) / HALF_LIFE_DAYS)
        hit["rank"] = round(hit["score"] * weight, 4)
    return sorted(hits, key=lambda h: h["rank"], reverse=True)


def group_by_document(hits: list[dict]) -> list[dict]:
    """청크를 문서별로 묶는다(순위 — 최신성 순위가 있으면 그것, 없으면 유사도 — 높은 순). 번호(n)는 1부터 — Claude가 인용하는 번호다."""
    groups: dict[str, dict] = {}
    for hit in hits:
        doc_id = hit["metadata"].get("doc_id") or hit["id"].split("#")[0]
        group = groups.setdefault(doc_id, {"doc_id": doc_id, "score": hit["score"], "order": hit.get("rank", hit["score"]),
                                           "metadata": hit["metadata"], "chunks": []})
        group["score"] = max(group["score"], hit["score"])
        group["order"] = max(group["order"], hit.get("rank", hit["score"]))
        group["chunks"].append(hit["document"])
    ordered = sorted(groups.values(), key=lambda g: g["order"], reverse=True)
    for n, group in enumerate(ordered, start=1):
        group["n"] = n
    return ordered


@dataclass
class StatRow:
    type_label: str  # 키워드·아이템·소재…
    label: str
    count: int
    prev_count: int
    prev_rate: float | None
    surge: bool
    is_new: bool


@dataclass
class AnswerInput:
    question: str
    model: str
    top_k: int
    min_score: float
    stats_week: str | None = None
    stats: list[StatRow] = field(default_factory=list)
    today: date = field(default_factory=date.today)  # Spring이 서버 날짜를 보낸다 — "요즘"의 기준


def build_schema() -> dict:
    """결론 한 줄(summary) + 글머리 줄(points, 줄마다 근거 번호). 한 덩어리 글에 [1][2]가 섞이면 읽기 어려워서 나눴다."""
    point = {
        "type": "object",
        "properties": {
            "text": {"type": "string"},
            "sources": {"type": "array", "items": {"type": "integer"}},  # 이 줄을 뒷받침하는 근거 번호
        },
        "required": ["text", "sources"],
        "additionalProperties": False,
    }
    return {
        "type": "object",
        "properties": {
            "summary": {"type": "string"},
            "points": {"type": "array", "items": point},
            "grounded": {"type": "boolean"},
        },
        "required": ["summary", "points", "grounded"],
        "additionalProperties": False,
    }


def _stats_text(data: AnswerInput) -> str:
    if not data.stats:
        return ""
    lines = []
    for s in data.stats:
        rate = "-" if s.prev_rate is None else f"{s.prev_rate:+.1f}%"
        flag = " 급상승" if s.surge else (" 신규" if s.is_new else "")
        lines.append(f"- {s.type_label} {s.label}: {s.count}건 (전주 {s.prev_count}건, 전주 대비 {rate}){flag}")
    return PROMPT["stats_header"].replace("{week}", data.stats_week or "-") + "\n" + "\n".join(lines)


def _sources_text(groups: list[dict]) -> str:
    blocks = []
    for g in groups:
        meta = g["metadata"]
        kind = "이미지 분석 요약" if meta.get("doc_type") == "IMAGE" else "텍스트"
        head = f'<source n="{g["n"]}" type="{kind}" media="{meta.get("media", "")}" date="{meta.get("published_at", "")}">'
        blocks.append(head + "\n" + "\n---\n".join(g["chunks"]) + "\n</source>")
    return PROMPT["sources_header"] + "\n" + "\n".join(blocks)


def _period_text(data: AnswerInput, info: dict) -> str:
    key = "period" if info["explicit"] else ("period_stale" if info["stale"] else "period_recent")
    text = PROMPT[key]
    for name, value in (("today", data.today.isoformat()), ("label", info["label"]), ("from", info["from"] or "-"),
                        ("to", info["to"] or "-")):
        text = text.replace("{" + name + "}", value)
    return "<period>\n" + text + "\n</period>"


def period_info(period: periods.Period, groups: list[dict], today: date) -> dict:
    """화면·프롬프트에 알릴 기간. 기간을 콕 집은 질문은 그 범위, 아니면 근거로 쓴 원문의 게시일 범위(stale: 가장 최근도 8주 넘음)."""
    if period.explicit:
        return {"from": period.start.isoformat(), "to": period.end.isoformat(), "label": period.label,
                "explicit": True, "stale": False}
    dates = sorted(d for d in (published_date(g["metadata"]) for g in groups) if d)
    newest = dates[-1] if dates else None
    return {"from": dates[0].isoformat() if dates else None, "to": newest.isoformat() if newest else None,
            "label": period.label, "explicit": False,
            "stale": newest is None or (today - newest).days > STALE_DAYS}


def build_content(data: AnswerInput, groups: list[dict], info: dict | None = None) -> str:
    parts = [_period_text(data, info)] if info else []
    parts.append(_sources_text(groups))
    stats = _stats_text(data)
    if stats:
        parts.append(stats)
    parts.append(f"<question>\n{data.question}\n</question>")
    parts.append(PROMPT["task"])
    return "\n\n".join(parts)


def answer(collection, data: AnswerInput, api_key: str, client: anthropic.Anthropic | None = None) -> dict:
    """{grounded, answer(결론 한 줄), points[{text, sources}], sources[인용한 문서], best_score, usage, reason, period}."""
    period = periods.resolve(data.question, data.today)
    if period.explicit:
        hits = search(collection, data.question, data.top_k, periods.where(period))
        passed = [h for h in hits if h["score"] >= data.min_score]
    else:
        # 기간이 없으면 자르지 않고 후보를 넉넉히 모아, 관련 있는 것(기준 유사도 이상) 중 최근 것을 앞세운다
        hits = search(collection, data.question, max(CANDIDATES, data.top_k))
        passed = rank_by_recency([h for h in hits if h["score"] >= data.min_score], data.today)[:data.top_k]
    best = max((h["score"] for h in hits), default=None)
    groups = group_by_document(passed)
    info = period_info(period, groups, data.today)
    log.info("RAG 검색 질문=%d자 기간=%s(%s~%s)%s 후보 %d개 기준 %.2f 통과 %d개 최고 %s", len(data.question), period.label,
             info["from"], info["to"], " 오래된 자료" if passed and info["stale"] else "", len(hits), data.min_score,
             len(passed), "-" if best is None else f"{best:.3f}")
    if not passed:
        # 근거가 없으면 Claude를 부르지 않는다 — 지어낸 답을 막고 토큰도 쓰지 않는다
        return {"grounded": False, "answer": None, "points": [], "sources": [], "best_score": best, "usage": None,
                "reason": "NO_EVIDENCE", "period": info}

    started = time.monotonic()
    response = create_message(client, api_key,
                              model=data.model,
                              max_tokens=MAX_TOKENS,
                              system=PROMPT["system"],
                              messages=[{"role": "user", "content": build_content(data, groups, info)}],
                              output_config={"format": {"type": "json_schema", "schema": build_schema()},
                                             "effort": "medium"})
    usage = usage_of(response, time.monotonic() - started)
    log.info("RAG 답변 model=%s stop=%s 소요=%.1fs 근거 문서 %d개 토큰 in=%d out=%d", data.model, response.stop_reason,
             usage["elapsed_ms"] / 1000, len(groups), usage["input_tokens"], usage["output_tokens"])
    result = parse_json(response, usage)

    by_n = {g["n"]: g for g in groups}
    # 줄마다 근거 번호를 정리한다(없는 번호·중복 제거). 출처 목록은 줄들이 인용한 번호를 처음 나온 순서로
    points = []
    for point in result.get("points") or []:
        text = (point.get("text") or "").strip()
        if text:
            points.append({"text": text, "sources": [n for n in dict.fromkeys(point.get("sources") or []) if n in by_n]})
    cited = [by_n[n] for n in dict.fromkeys(n for p in points for n in p["sources"])]
    grounded = bool(result.get("grounded")) and bool(cited)
    sources = [{"n": g["n"], "doc_id": g["doc_id"], "score": g["score"], "chunk": g["chunks"][0]} for g in cited]
    return {"grounded": grounded, "answer": (result.get("summary") or "").strip(), "points": points if grounded else [],
            "sources": sources, "best_score": best, "usage": usage, "reason": None if grounded else "MODEL_DECLINED",
            "period": info}


def ensure_question(question: str, max_length: int) -> str:
    text = (question or "").strip()
    if not text:
        raise AiServiceError("질문이 비어 있습니다.", retryable=False, status_code=400)
    if len(text) > max_length:
        raise AiServiceError(f"질문은 {max_length}자 이하로 쓰세요.", retryable=False, status_code=400)
    return text
