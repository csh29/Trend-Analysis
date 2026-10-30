"""P-06 검색 평가 — 질문마다 AI 서비스 /search(Claude 없음, 로컬 임베딩)로 찾은 문서를 정답 문서와 맞댄다. 무료.

정답 문서: search_questions.json의 rule을 DB 분석 결과(의류 아이템)에 적용해 정한다(facts_check.load로 읽기 — Spring 앱을 내린 상태).
지표(문서 단위 — 한 문서의 청크 여럿은 가장 높은 점수 하나로):
- Recall@k: 정답 중 상위 k에 든 비율. Precision@k: 상위 k 중 정답 비율(정답이 k보다 적으면 정답 수로 나눈다). MRR: 첫 정답 순위의 역수.
- 점수 분포: 정답 문서 점수 / 정답 질문의 정답 밖 문서 점수 / 자료 없는 주제(negative) 1위 점수 — 기준 유사도 0.60이 이들을 가르는지 본다.
순위는 /search가 준 순서(하이브리드면 RRF)를 따른다. 챗봇 검색 답변은 여기에 최신성 가중치·기간 거르기를 더한다 — 여기는 빼고 본다.

사용: py ai/evals/search_eval.py [--k 8] [--vector] (AI 서비스 8001이 떠 있어야 한다). --vector는 설정과 상관없이 벡터만 —
하이브리드와 비교할 때 둘 다 돌려 search-eval-vector.json과 search-eval-hybrid.json을 맞댄다.
"""
import argparse
import json
import statistics
import urllib.parse
import urllib.request
from pathlib import Path

import facts_check

EVALS = Path(__file__).resolve().parent
QUESTIONS = EVALS / "search_questions.json"
RESULTS = EVALS / "results"
AI = "http://127.0.0.1:8001"
MIN_SCORE = 0.60


def relevant(rule: dict, docs: dict) -> set[str]:
    def text_hit(texts):
        return any(facts_check.compact(w) in facts_check.compact(t) for w in rule["text"] for t in texts)

    hits = set()
    for doc_id, d in docs.items():
        for g in d["g"].values():
            ok = ((not rule.get("types") or set(g["ITEM_TYPE"]) & set(rule["types"]))
                  and (not rule.get("category") or g["CATEGORY"] == [rule["category"]])
                  and (not rule.get("materials") or set(g["MATERIAL"]) & set(rule["materials"]))
                  and (not rule.get("silhouettes") or set(g["SILHOUETTE"]) & set(rule["silhouettes"]))
                  and (not rule.get("text") or text_hit(g["ITEM"] + d["kw"])))
            if ok:
                hits.add(doc_id)
                break
    return hits


def search(q: str, hybrid: bool) -> list[tuple[str, float]]:
    """문서 순위(처음 나온 청크 순서)와 문서별 가장 높은 유사도."""
    params = {"q": q, "top_k": 50, "hybrid": str(hybrid).lower()}
    with urllib.request.urlopen(f"{AI}/search?{urllib.parse.urlencode(params)}", timeout=120) as r:
        chunks = json.loads(r.read().decode("utf-8"))
    best: dict[str, float] = {}
    for c in chunks.get("results", chunks) if isinstance(chunks, dict) else chunks:
        doc_id, score = c["doc_id"] if "doc_id" in c else c["metadata"]["doc_id"], c["score"]
        best[doc_id] = max(best.get(doc_id, 0.0), score)
    return list(best.items())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--k", type=int, default=8, help="챗봇 검색과 같은 상위 개수(rag.top-k 기본 8)")
    parser.add_argument("--vector", action="store_true", help="벡터만(하이브리드 끔)")
    args = parser.parse_args()
    mode = "vector" if args.vector else "hybrid"
    k = args.k
    docs, _ = facts_check.load()
    rows, rel_scores, other_scores, neg_top = [], [], [], []
    for q in json.loads(QUESTIONS.read_text(encoding="utf-8"))["questions"]:
        ranked = search(q["q"], not args.vector)
        if q.get("negative"):
            top = max((sc for _, sc in ranked), default=0.0)
            neg_top.append(top)
            rows.append({"id": q["id"], "q": q["q"], "negative": True, "top": ranked[:3]})
            print(f"{q['id']} {q['q']} — 자료 없는 주제, 1위 {top:.3f} {'(기준 이상!)' if top >= MIN_SCORE else ''}")
            continue
        gold = relevant(q["rule"], docs)
        if not gold:
            rows.append({"id": q["id"], "q": q["q"], "gold": [], "skipped": "정답 문서 없음"})
            print(f"{q['id']} {q['q']} — 정답 문서 없음(건너뜀)")
            continue
        ids = [d for d, _ in ranked]
        top_k = ids[:k]
        found = [d for d in top_k if d in gold]
        first = next((i + 1 for i, d in enumerate(ids) if d in gold), None)
        recall, precision = len(found) / len(gold), len(found) / min(k, len(gold))
        rel_scores += [s for d, s in ranked if d in gold]
        other_scores += [s for d, s in ranked[:k] if d not in gold]
        missing = sorted(gold - set(top_k))
        intruders = [(d, round(s, 3)) for d, s in ranked[:k] if d not in gold][:3]
        rows.append({"id": q["id"], "q": q["q"], "gold": sorted(gold), "recall": recall, "precision": precision,
                     "rr": 1 / first if first else 0.0, "missing": missing, "intruders": intruders,
                     "ranked": [(d, round(s, 3)) for d, s in ranked[:k]]})
        print(f"{q['id']} {q['q']} — 정답 {len(gold)} · R@{k} {recall:.2f} · P@{k} {precision:.2f} · 첫 정답 {first}위"
              + (f" · 못 찾음 {missing}" if missing else "") + (f" · 정답 밖 상위 {intruders}" if intruders else ""))

    scored = [r for r in rows if "recall" in r]

    def dist(xs):
        if not xs:
            return "-"
        xs = sorted(xs)
        return (f"{len(xs)}개 · 최저 {xs[0]:.3f} · 중앙 {statistics.median(xs):.3f} · 최고 {xs[-1]:.3f} · "
                f"{MIN_SCORE:.2f} 이상 {sum(x >= MIN_SCORE for x in xs) / len(xs):.0%}")

    summary = {
        "mode": mode, "questions": len(scored), "k": k,
        "recall": round(statistics.mean(r["recall"] for r in scored), 3),
        "precision": round(statistics.mean(r["precision"] for r in scored), 3),
        "mrr": round(statistics.mean(r["rr"] for r in scored), 3),
    }
    print(f"\n[{mode}] 질문 {summary['questions']}개 · Recall@{k} {summary['recall']} · Precision@{k} {summary['precision']} · MRR {summary['mrr']}")
    print("점수 분포(문서별 최고 청크 점수)")
    print("  정답 문서        :", dist(rel_scores))
    print(f"  정답 밖 상위 {k}개  :", dist(other_scores))
    print("  자료 없는 주제 1위:", dist(neg_top))
    RESULTS.mkdir(exist_ok=True)
    out = RESULTS / f"search-eval-{mode}.json"
    out.write_text(json.dumps({"summary": summary, "rows": rows, "relevant_scores": rel_scores, "other_scores": other_scores,
                               "negative_top": neg_top}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"저장: {out}")


if __name__ == "__main__":
    main()
