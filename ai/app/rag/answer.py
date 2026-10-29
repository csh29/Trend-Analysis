"""P-06 RAG 답변 — 근거를 골라 Claude가 답한다. 질문 해석(유행 질문인지·카테고리·기간)은 Spring이 해서 보낸다.

두 갈래(mode):
- TREND(유행 질문): 유행 = 많이 입어 대중화된 것(2026-10-22 사용자 정의)이라 문장 유사도로 고르지 않는다. Spring이 센 집계
  (아이템 종류·소재·컬러·실루엣별 원문 수·출처 수·직전 기간 대비)와, 집계 상위 값이 나온 예시 원문(doc_id)을 받아 답한다.
  예시 원문은 Chroma에서 doc_id로 꺼낸다.
- SEARCH(그 밖의 질문): 질문을 임베딩해 비슷한 청크를 찾는다. 유사도 = 1 - 코사인 거리.
  - 기준 유사도(min_score) 이상인 근거가 없으면 Claude를 부르지 않고 "근거 없음"(정의서 P-06 규칙).
  - 카테고리를 말했으면 그 카테고리 아이템이 있는 원문만(메타 cat_<코드>). 기간을 콕 집었으면 그 게시일 범위만.
  - 기간이 없으면 자르지 않고 후보를 넉넉히 모아 유사도 × 최신성(반감기 4주)으로 최근 자료를 앞세운다. 가장 최근 근거도
    8주가 넘으면(stale) 자료 시점을 밝히게 한다.
근거는 문서 단위로 묶어 번호를 붙인다(화면의 출처 하나 = 문서 하나). LangChain은 쓰지 않는다(2026-10-21 사용자 결정).
"""
import re
import logging
import time
from dataclasses import dataclass, field
from datetime import date

import anthropic

from app.core import prompt_loader
from app.core.claude import AiServiceError, create_message, parse_json, usage_of

log = logging.getLogger("trend-ai")

PROMPT = prompt_loader.load("rag/answer", ["system", "system_trend", "system_search", "period", "period_recent", "period_stale", "trend_header",
                                           "sources_header", "task", "task_trend"])
MAX_TOKENS = 4000
HALF_LIFE_DAYS = 28   # 최신성 가중치가 절반이 되는 날수 — "요즘"을 4주 단위로 본다
CANDIDATES = 30       # 최신성으로 다시 고르기 전에 유사도로 모으는 후보 청크 수
STALE_DAYS = 56       # 가장 최근 근거가 이보다 오래되면 "자료가 오래됐다"고 밝힌다
CHUNKS_PER_EXAMPLE = 2  # 유행 질문의 예시 원문마다 보내는 청크 수(요약 청크 + 본문 앞부분)


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


def example_documents(collection, doc_ids: list[str]) -> list[dict]:
    """유행 질문의 예시 원문 — Spring이 정한 순서대로 doc_id로 꺼낸다(유사도 없음). 색인이 없는 문서는 빠진다."""
    if not doc_ids:
        return []
    got = collection.get(where={"doc_id": {"$in": doc_ids}}, include=["documents", "metadatas"])
    by_doc: dict[str, dict] = {}
    for chunk_id, document, meta in zip(got["ids"], got["documents"], got["metadatas"]):
        doc_id = meta.get("doc_id") or chunk_id.split("#")[0]
        by_doc.setdefault(doc_id, {"doc_id": doc_id, "score": None, "metadata": meta, "chunks": []})["chunks"].append(
            (meta.get("chunk_index", 0), document))
    groups = []
    for doc_id in doc_ids:
        if doc_id in by_doc:
            group = by_doc[doc_id]
            group["chunks"] = [text for _, text in sorted(group["chunks"])][:CHUNKS_PER_EXAMPLE]
            group["n"] = len(groups) + 1
            groups.append(group)
    return groups




@dataclass
class Period:
    """Spring이 해석한 게시일 기간(양 끝 포함). explicit=질문이 정한 기간."""
    start: date
    end: date
    label: str
    explicit: bool


@dataclass
class AnswerInput:
    question: str
    model: str
    top_k: int
    min_score: float
    today: date = field(default_factory=date.today)  # Spring 서버 날짜 — 최신성·기간의 기준
    mode: str = "SEARCH"                              # SEARCH | TREND
    category: str | None = None                       # 질문의 카테고리 코드(SEARCH에서 거른다)
    period: Period | None = None                      # SEARCH: 콕 집은 기간, TREND: 집계 기간
    trend_facts: list[str] = field(default_factory=list)  # TREND: Spring이 센 집계(한 줄씩)
    evidence_doc_ids: list[str] = field(default_factory=list)  # TREND: 집계 상위 값이 나온 예시 원문


CITE = re.compile(r"\[(\d{1,6})\]")


def build_schema() -> dict:
    """결론 한 줄(summary) + 글머리 줄(points, 줄마다 근거 번호) 또는 자유 형식 본문(body — 표·번호 목록·문단, 줄·행마다 [n]).
    질문이 형식(표로·문단으로·3줄로…)을 요구하거나 표가 나은 답(비교·브리프)이면 body를 쓰고 points는 비운다(2026-10-28 사용자 요청)."""
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
            "body": {"type": "string"},  # 자유 형식 본문(마크다운 일부). 쓰지 않으면 ""
            "grounded": {"type": "boolean"},
        },
        "required": ["summary", "points", "body", "grounded"],
        "additionalProperties": False,
    }


def where_filter(data: AnswerInput) -> dict | None:
    """SEARCH의 Chroma 메타 필터 — 카테고리(cat_<코드>), 콕 집은 기간(published_num 20261022)."""
    conditions = []
    if data.category:
        conditions.append({f"cat_{data.category}": True})
    if data.period and data.period.explicit:
        conditions.append({"published_num": {"$gte": int(data.period.start.strftime("%Y%m%d"))}})
        conditions.append({"published_num": {"$lte": int(data.period.end.strftime("%Y%m%d"))}})
    if not conditions:
        return None
    return conditions[0] if len(conditions) == 1 else {"$and": conditions}


def _sources_text(groups: list[dict]) -> str:
    blocks = []
    for g in groups:
        meta = g["metadata"]
        kind = "이미지 분석 요약" if meta.get("doc_type") == "IMAGE" else "텍스트"
        head = f'<source n="{g["n"]}" type="{kind}" media="{meta.get("media", "")}" date="{meta.get("published_at", "")}">'
        blocks.append(head + "\n" + "\n---\n".join(g["chunks"]) + "\n</source>")
    return PROMPT["sources_header"] + "\n" + "\n".join(blocks)


def _fill(template: str, values: dict) -> str:
    for name, value in values.items():
        template = template.replace("{" + name + "}", value)
    return template


def _period_text(data: AnswerInput, info: dict) -> str:
    key = "period" if info["explicit"] else ("period_stale" if info["stale"] else "period_recent")
    text = _fill(PROMPT[key], {"today": data.today.isoformat(), "label": info["label"], "from": info["from"] or "-",
                               "to": info["to"] or "-"})
    return "<period>\n" + text + "\n</period>"


def period_info(data: AnswerInput, groups: list[dict]) -> dict:
    """SEARCH에서 알릴 기간. 콕 집은 기간이면 그 범위, 아니면 근거로 쓴 원문의 게시일 범위(stale: 가장 최근도 8주 넘음)."""
    if data.period and data.period.explicit:
        return {"from": data.period.start.isoformat(), "to": data.period.end.isoformat(), "label": data.period.label,
                "explicit": True, "stale": False}
    dates = sorted(d for d in (published_date(g["metadata"]) for g in groups) if d)
    newest = dates[-1] if dates else None
    return {"from": dates[0].isoformat() if dates else None, "to": newest.isoformat() if newest else None,
            "label": "최근 자료 우선", "explicit": False,
            "stale": newest is None or (data.today - newest).days > STALE_DAYS}


def build_content(data: AnswerInput, groups: list[dict], info: dict | None = None) -> str:
    parts = [_period_text(data, info)] if info else []
    parts.append(_sources_text(groups))
    parts.append(f"<question>\n{data.question}\n</question>")
    parts.append(PROMPT["task"])
    return "\n\n".join(parts)


def build_trend_content(data: AnswerInput, groups: list[dict]) -> str:
    p = data.period
    header = _fill(PROMPT["trend_header"], {"today": data.today.isoformat(), "label": p.label,
                                            "from": p.start.isoformat(), "to": p.end.isoformat()})
    parts = ["<trend_facts>\n" + header + "\n" + "\n".join(data.trend_facts) + "\n</trend_facts>",
             _sources_text(groups), f"<question>\n{data.question}\n</question>", PROMPT["task_trend"]]
    return "\n\n".join(parts)


def system_prompt(mode: str) -> str:
    """공통 규칙 + 그 갈래의 규칙만 — 유행 질문에 검색 규칙을, 검색 질문에 유행 규칙을 보내지 않는다(입력 토큰 절약, 2026-10-26)."""
    return PROMPT["system"] + "\n\n" + PROMPT["system_trend" if mode == "TREND" else "system_search"]


def _call_claude(data: AnswerInput, content: str, api_key: str, client, groups: list[dict]) -> tuple[dict, dict]:
    started = time.monotonic()
    response = create_message(client, api_key,
                              model=data.model,
                              max_tokens=MAX_TOKENS,
                              system=system_prompt(data.mode),
                              messages=[{"role": "user", "content": content}],
                              output_config={"format": {"type": "json_schema", "schema": build_schema()},
                                             "effort": "medium"})
    usage = usage_of(response, time.monotonic() - started)
    log.info("RAG 답변 mode=%s model=%s stop=%s 소요=%.1fs 근거 문서 %d개 토큰 in=%d out=%d", data.mode, data.model,
             response.stop_reason, usage["elapsed_ms"] / 1000, len(groups), usage["input_tokens"], usage["output_tokens"])
    return parse_json(response, usage), usage


def _clean_body(body: str, by_n: dict) -> str:
    """본문의 없는 [n]을 지우고, 인용한 번호가 모두 없는 번호인 줄은 줄째 뺀다 — 번호만 지우면 지어낸 인용으로 쓴 문장이
    근거 없이 남았다(2026-10-29 진단 F2). 처음부터 번호가 없는 줄(표 머리·한계 문장)은 둔다."""
    kept = []
    for line in body.splitlines():
        cited = [int(n) for n in CITE.findall(line)]
        if cited and not any(n in by_n for n in cited):
            log.info("인용 번호가 모두 없는 번호라 본문 줄을 뺍니다(인용 %s)", cited)
            continue
        kept.append(CITE.sub(lambda m: m.group(0) if int(m.group(1)) in by_n else "", line))
    return "\n".join(kept).strip()


def _answer_from(result: dict, groups: list[dict]) -> dict:
    """줄마다 근거 번호를 정리한다(없는 번호·중복 제거). 출처 목록은 줄들이 인용한 번호를 처음 나온 순서로.
    인용한 번호가 모두 없는 번호인 줄은 뺀다 — 번호만 지우면 Spring이 "처음부터 인용 없는 한계 문장"으로 보고 남겼다(2026-10-29 진단 F2)."""
    by_n = {g["n"]: g for g in groups}
    points = []
    for point in result.get("points") or []:
        text = (point.get("text") or "").strip()
        cited = list(dict.fromkeys(point.get("sources") or []))
        valid = [n for n in cited if n in by_n]
        if cited and not valid:
            log.info("인용 번호가 모두 없는 번호라 줄을 뺍니다(인용 %s): %s", cited, text)
            continue
        if text:
            points.append({"text": text, "sources": valid})
    body = _clean_body((result.get("body") or "").strip(), by_n)
    body_cites = [int(n) for n in CITE.findall(body)]
    cited = [by_n[n] for n in dict.fromkeys([n for p in points for n in p["sources"]] + body_cites)]
    grounded = bool(result.get("grounded")) and bool(cited)
    sources = [{"n": g["n"], "doc_id": g["doc_id"], "score": g["score"], "chunk": g["chunks"][0]} for g in cited]
    return {"grounded": grounded, "answer": (result.get("summary") or "").strip(), "points": points if grounded else [],
            "body": body if grounded else "", "sources": sources, "reason": None if grounded else "MODEL_DECLINED"}


def answer(collection, data: AnswerInput, api_key: str, client: anthropic.Anthropic | None = None) -> dict:
    """{grounded, answer(결론 한 줄), points[{text, sources}], sources[인용한 문서], best_score, usage, reason, period}."""
    if data.mode == "TREND":
        return _answer_trend(collection, data, api_key, client)

    where = where_filter(data)
    if data.period and data.period.explicit:
        hits = search(collection, data.question, data.top_k, where)
        passed = [h for h in hits if h["score"] >= data.min_score]
    else:
        # 기간이 없으면 자르지 않고 후보를 넉넉히 모아, 관련 있는 것(기준 유사도 이상) 중 최근 것을 앞세운다
        hits = search(collection, data.question, max(CANDIDATES, data.top_k), where)
        passed = rank_by_recency([h for h in hits if h["score"] >= data.min_score], data.today)[:data.top_k]
    best = max((h["score"] for h in hits), default=None)
    groups = group_by_document(passed)
    info = period_info(data, groups)
    log.info("RAG 검색 질문=%d자 카테고리=%s 기간=%s(%s~%s)%s 후보 %d개 기준 %.2f 통과 %d개 최고 %s", len(data.question),
             data.category or "-", info["label"], info["from"], info["to"], " 오래된 자료" if passed and info["stale"] else "",
             len(hits), data.min_score, len(passed), "-" if best is None else f"{best:.3f}")
    if not passed:
        # 근거가 없으면 Claude를 부르지 않는다 — 지어낸 답을 막고 토큰도 쓰지 않는다
        return {"grounded": False, "answer": None, "points": [], "sources": [], "best_score": best, "usage": None,
                "reason": "NO_EVIDENCE", "period": info}

    result, usage = _call_claude(data, build_content(data, groups, info), api_key, client, groups)
    return {**_answer_from(result, groups), "best_score": best, "usage": usage, "period": info}


def _answer_trend(collection, data: AnswerInput, api_key: str, client) -> dict:
    """유행 질문 — 집계(Spring)가 근거, 예시 원문은 출처. 집계가 비었으면 Spring이 이미 막는다."""
    if data.period is None:
        raise AiServiceError("유행 질문에 집계 기간이 없습니다.", retryable=False, status_code=400)
    groups = example_documents(collection, data.evidence_doc_ids)
    info = {"from": data.period.start.isoformat(), "to": data.period.end.isoformat(), "label": data.period.label,
            "explicit": data.period.explicit, "stale": False}
    log.info("RAG 유행 질문=%d자 기간=%s(%s~%s) 집계 %d줄 예시 원문 %d/%d개", len(data.question), data.period.label,
             info["from"], info["to"], len(data.trend_facts), len(groups), len(data.evidence_doc_ids))
    if not groups:
        return {"grounded": False, "answer": None, "points": [], "sources": [], "best_score": None, "usage": None,
                "reason": "NO_EVIDENCE", "period": info}
    result, usage = _call_claude(data, build_trend_content(data, groups), api_key, client, groups)
    return {**_answer_from(result, groups), "best_score": None, "usage": usage, "period": info}


def ensure_question(question: str, max_length: int) -> str:
    text = (question or "").strip()
    if not text:
        raise AiServiceError("질문이 비어 있습니다.", retryable=False, status_code=400)
    if len(text) > max_length:
        raise AiServiceError(f"질문은 {max_length}자 이하로 쓰세요.", retryable=False, status_code=400)
    return text
