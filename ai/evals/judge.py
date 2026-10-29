"""P-06 챗봇 평가 채점 — RagEvalRunner가 남긴 답(…-answers.json)을 질문 세트의 요건별로 예/아니오 판정한다.

채점 모델은 답과 함께 그 답의 근거(판단 근거 집계·출처 원문 요약)를 본다 — "근거에 없는 값을 지어냈는지"를 근거와 대조해야 해서다.
요건은 질문 세트의 common_expect(공통) + expect(질문별). 해당 없는 요건(근거 없음으로 끝난 답의 출처 요건 등)은 na로 둔다.
도구 사용 구조로 바꾸기 전후를 같은 질문·요건·채점으로 비교한다(2026-10-23 사용자 결정).

사용(ai 폴더에서):
  .venv/Scripts/python evals/judge.py evals/results/baseline-answers.json [--out evals/results/baseline-scores.json]
Claude API 키는 backend의 application-local.properties(claude.api.key)에서 읽고 출력하지 않는다.
"""
import argparse
import json
import sys
import time
from pathlib import Path

AI_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(AI_DIR))
from app.core.claude import create_message, parse_json, usage_of  # noqa: E402

QUESTIONS = AI_DIR / "evals" / "rag_questions.json"
PROPS = AI_DIR.parent / "backend" / "src" / "main" / "resources" / "application-local.properties"
MODEL = "claude-sonnet-5"
CHUNK_CHARS = 700  # 출처 원문 요약은 이만큼만 — 지어낸 값 대조에는 요약·키워드·아이템 줄이면 된다

SYSTEM = """당신은 패션 트렌드 챗봇의 답을 채점하는 평가자다. 사용자는 상품기획·MD·디자이너다.
질문, 챗봇이 받은 근거(판단 근거 집계·출처 원문 요약·해석), 챗봇의 답, 요건 목록을 받는다. 요건마다 verdict를 정한다.

- pass: 답이 그 요건을 분명히 만족한다.
- fail: 만족하지 않는다. 일부만 만족하면 fail이다(예: 5개를 요구했는데 이유 없이 3개만, 기간을 밝히라는데 안 밝힘).
- na: 이 답에는 해당하지 않는다(예: 답이 근거 없음으로 끝났는데 "출처가 붙는다" 요건, 패션 외 질문 안내에 "기간을 밝힌다" 요건).
- "지어낸 값이 없다"는 답의 수치·컬러·아이템·브랜드가 근거(집계·출처)에 있는지 대조해서 판정한다. 근거에 없는 값이 하나라도 있으면 fail.
- 자료가 모자라 요건을 다 채우지 못했어도, 답이 모자라다고 분명히 밝혔고 그 요건이 "모자라면 밝힌다"를 허용하면 pass다. 그 밖의 요건은 채우지 못했으면 fail이다.
- 답의 문장만 보고 판정한다. 챗봇이 할 수 있었을 일을 짐작해 점수를 주지 않는다.
- reason은 한국어 한 문장으로 판정 근거를 쓴다."""


def api_key() -> str:
    for line in PROPS.read_text(encoding="utf-8").splitlines():
        if line.startswith("claude.api.key="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("application-local.properties에 claude.api.key가 없습니다.")


def evidence_text(answer: dict, tool_results: list | None = None) -> str:
    """채점 모델에 줄 근거 — 해석·기간·판단 근거 집계·도구 조회 결과·출처. 도구 경로 답은 화면의 판단 근거(첫 집계 상위 5개)보다
    많은 집계(상위 10개·다른 기간·주별 추이)를 읽고 답하므로, 그 글(toolResults)을 같이 줘야 "지어낸 수치"로 잘못 채점하지 않는다."""
    lines = []
    interp = answer.get("interpretation") or {}
    lines.append(f"해석: 패션={interp.get('fashion')} 유행={interp.get('trend')} 카테고리={interp.get('categoryName')} "
                 f"종류={interp.get('itemTypeName')} 다시 쓴 질문={interp.get('resolvedQuestion')} (해석 주체 {interp.get('by')})")
    period = answer.get("period") or {}
    if period:
        lines.append(f"근거 기간: {period.get('label')} {period.get('from')} ~ {period.get('to')}"
                     + (" (오래된 자료)" if period.get("stale") else ""))
    facts = answer.get("facts")
    if facts:
        lines.append(f"판단 근거 집계 — 대상 {facts.get('scopeLabel')} · 원문 {facts.get('docCount')}건 · 매체 {facts.get('mediaCount')}곳 "
                     f"(직전 {facts.get('prevDocCount')}건)")
        for d in facts.get("dimensions") or []:
            rows = []
            for r in d.get("rows") or []:
                extra = r.get("names") or r.get("items") or []
                rows.append(f"{r['label']} {r['count']}건·매체 {r['mediaCount']}곳·직전 {r['prevCount']}건"
                            + (f"({', '.join(extra)})" if extra else ""))
            lines.append(f"  [{d.get('label')}] " + " / ".join(rows))
        for t in facts.get("terms") or []:
            lines.append(f"  [질문 단어] {t['term']} {t['count']}건·매체 {t['mediaCount']}곳 (나온 표현: {', '.join(t.get('expressions') or [])})")
    for i, text in enumerate(tool_results or [], 1):
        lines.append(f"도구 조회 결과 {i}(답이 읽은 집계 — 이 수치도 근거다):\n{text}")
    for s in answer.get("sources") or []:
        chunk = (s.get("chunk") or "").replace("\n", " / ")[:CHUNK_CHARS]
        lines.append(f"출처 [{s['n']}] {s.get('title')} · {s.get('media')} · {s.get('statDate')}: {chunk}")
    if not answer.get("sources"):
        lines.append("출처: 없음")
    return "\n".join(lines)


def answer_text(answer: dict) -> str:
    if not answer.get("grounded"):
        return f"(근거 없음으로 끝남 — 사유 {answer.get('reason')}) {answer.get('answer')}"
    lines = [f"결론: {answer.get('answer')}"]
    for p in answer.get("points") or []:
        refs = ", ".join(f"[{n}]" for n in p.get("sources") or [])
        lines.append(f"- {p['text']} {refs}".rstrip())
    if answer.get("body"):  # 자유 형식 본문(표·목록) — [n]이 출처 번호다
        lines.append(answer["body"])
    return "\n".join(lines)


def schema(count: int) -> dict:
    return {
        "type": "object",
        "properties": {
            "verdicts": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "index": {"type": "integer"},
                        "verdict": {"type": "string", "enum": ["pass", "fail", "na"]},
                        "reason": {"type": "string"},
                    },
                    "required": ["index", "verdict", "reason"],
                    "additionalProperties": False,
                },
            }
        },
        "required": ["verdicts"],
        "additionalProperties": False,
    }


def judge_one(key: str, question: dict, row: dict, common: list[str]) -> tuple[list[dict], dict]:
    requirements = [("공통", r) for r in common] + [("질문", r) for r in question["expect"]]
    if "answer" not in row:
        verdicts = [{"index": i, "verdict": "fail", "reason": f"답을 받지 못했다: {row.get('error')}"}
                    for i in range(len(requirements))]
        return attach(verdicts, requirements), {}
    answer = row["answer"]
    req_text = "\n".join(f"{i}. ({kind}) {text}" for i, (kind, text) in enumerate(requirements))
    content = (f"<question>\n{question['question']}\n</question>\n\n<evidence>\n{evidence_text(answer, row.get("toolResults"))}\n</evidence>\n\n"
               f"<answer>\n{answer_text(answer)}\n</answer>\n\n<requirements>\n{req_text}\n</requirements>\n\n"
               f"요건 {len(requirements)}개 모두(index 0~{len(requirements) - 1})에 verdict를 정하라.")
    started = time.monotonic()
    response = create_message(None, key, model=MODEL, max_tokens=4000, system=SYSTEM,
                              messages=[{"role": "user", "content": content}],
                              output_config={"format": {"type": "json_schema", "schema": schema(len(requirements))},
                                             "effort": "medium"})
    usage = usage_of(response, time.monotonic() - started)
    verdicts = {v["index"]: v for v in parse_json(response, usage)["verdicts"]}
    filled = [verdicts.get(i, {"index": i, "verdict": "fail", "reason": "채점 모델이 판정을 빠뜨렸다"})
              for i in range(len(requirements))]
    return attach(filled, requirements), usage


def attach(verdicts: list[dict], requirements: list[tuple[str, str]]) -> list[dict]:
    return [{"kind": kind, "requirement": text, "verdict": v["verdict"], "reason": v["reason"]}
            for v, (kind, text) in zip(verdicts, requirements)]


def score(items: list[dict]) -> float | None:
    judged = [i for i in items if i["verdict"] != "na"]
    return round(sum(i["verdict"] == "pass" for i in judged) / len(judged), 3) if judged else None


def mean(values: list) -> float | None:
    values = [v for v in values if v is not None]
    return round(sum(values) / len(values), 3) if values else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("answers")
    parser.add_argument("--out")
    args = parser.parse_args()
    answers_path = Path(args.answers)
    out_path = Path(args.out) if args.out else answers_path.with_name(answers_path.name.replace("-answers", "-scores"))

    qset = json.loads(QUESTIONS.read_text(encoding="utf-8"))
    by_id = {q["id"]: q for q in qset["questions"]}
    rows = json.loads(answers_path.read_text(encoding="utf-8"))["results"]
    key = api_key()

    results, tokens = [], {"input_tokens": 0, "output_tokens": 0}
    for row in rows:
        q = by_id[row["id"]]
        items, usage = judge_one(key, q, row, qset["common_expect"])
        for k in tokens:
            tokens[k] += usage.get(k, 0)
        specific = [i for i in items if i["kind"] == "질문"]
        results.append({"id": q["id"], "team": q["team"], "question": q["question"], "expected": q["current"],
                        "grounded": (row.get("answer") or {}).get("grounded"), "elapsedMs": row.get("elapsedMs"),
                        "score": score(items), "specificScore": score(specific), "items": items})
        print(f"{q['id']} {q['team']:<8} 기대={q['current']:<7} 점수={score(items)} (질문별 {score(specific)})", flush=True)

    summary = {
        "overall": mean([r["score"] for r in results]),
        "overallSpecific": mean([r["specificScore"] for r in results]),
        "byTeam": {t: mean([r["score"] for r in results if r["team"] == t]) for t in sorted({r["team"] for r in results})},
        "byExpected": {e: mean([r["specificScore"] for r in results if r["expected"] == e]) for e in ("ok", "partial", "no")},
        "commonRequirements": {c: mean([1.0 if i["verdict"] == "pass" else 0.0 for r in results for i in r["items"]
                                        if i["requirement"] == c and i["verdict"] != "na"]) for c in qset["common_expect"]},
        "judgeTokens": tokens,
    }
    out_path.write_text(json.dumps({"answers": str(answers_path), "model": MODEL, "summary": summary, "results": results},
                                   ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"저장: {out_path}")


if __name__ == "__main__":
    main()
