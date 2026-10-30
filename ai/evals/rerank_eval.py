"""P-06 리랭커 평가 — search_eval과 같은 질문·정답 문서로, 하이브리드 후보를 리랭커로 다시 매긴 순위와 점수 분포를 본다. 무료.

후보: AI 서비스 /search(하이브리드, top_k=--candidates)로 받아 results/rerank-candidates.json에 둔다 — 모델끼리 같은 후보로
맞대고, 다시 돌릴 때 AI 서비스 없이도 된다(--refresh면 새로 받는다).
지표(문서 단위 — 한 문서의 청크 여럿은 가장 높은 점수 하나로): Recall@k·Precision@k·MRR(search_eval과 같다) +
근거 기준 시뮬레이션 — 기준값마다 "정답 질문 중 정답 문서가 하나라도 통과한 비율"과 "자료 없는 주제 중 무언가 통과한 수".
같은 표를 코사인 유사도로도 내서 비교한다.

사용(ai 폴더, 리랭커는 torch가 있는 가상환경으로): .venv/Scripts/python evals/rerank_eval.py --model BAAI/bge-reranker-v2-m3
정답 문서를 DB에서 읽으므로 Spring 앱을 내린 상태여야 한다(facts_check — H2 파일 잠금).
"""
import argparse
import json
import statistics
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

EVALS = Path(__file__).resolve().parent
sys.path.insert(0, str(EVALS.parent))

import facts_check  # noqa: E402
from search_eval import AI, QUESTIONS, RESULTS, relevant  # noqa: E402
from app.rag.reranker import create_reranker  # noqa: E402

CANDIDATES_FILE = RESULTS / "rerank-candidates.json"
THRESHOLDS = {"cosine": [0.50, 0.55, 0.58, 0.60, 0.62], "rerank": [0.05, 0.1, 0.2, 0.3, 0.5, 0.7]}


def fetch_candidates(questions: list[dict], n: int) -> dict:
    out = {}
    for q in questions:
        params = {"q": q["q"], "top_k": n, "hybrid": "true"}
        with urllib.request.urlopen(f"{AI}/search?{urllib.parse.urlencode(params)}", timeout=300) as r:
            out[q["id"]] = [{"id": c["id"], "doc_id": c["metadata"]["doc_id"], "document": c["document"], "score": c["score"]}
                            for c in json.loads(r.read().decode("utf-8"))]
    return out


def doc_ranking(chunks: list[dict], key: str) -> list[tuple[str, float]]:
    best: dict[str, float] = {}
    for c in chunks:
        best[c["doc_id"]] = max(best.get(c["doc_id"], -1.0), c[key])
    return sorted(best.items(), key=lambda e: -e[1])


def evaluate(rows: list[dict], key: str, k: int) -> dict:
    scored, rel, other, neg = [], [], [], []
    for row in rows:
        ranked = doc_ranking(row["chunks"], key)
        if row["negative"]:
            neg.append(ranked[0][1] if ranked else 0.0)
            continue
        gold = set(row["gold"])
        ids = [d for d, _ in ranked]
        found = [d for d in ids[:k] if d in gold]
        first = next((i + 1 for i, d in enumerate(ids) if d in gold), None)
        scored.append({"id": row["id"], "recall": len(found) / len(gold), "precision": len(found) / min(k, len(gold)),
                       "rr": 1 / first if first else 0.0, "best_gold": max((s for d, s in ranked if d in gold), default=0.0)})
        rel += [s for d, s in ranked if d in gold]
        other += [s for d, s in ranked[:k] if d not in gold]
    gate = {t: {"gold_pass": sum(r["best_gold"] >= t for r in scored) / len(scored), "negative_pass": sum(x >= t for x in neg)}
            for t in THRESHOLDS["cosine" if key == "score" else "rerank"]}
    return {"recall": statistics.mean(r["recall"] for r in scored), "precision": statistics.mean(r["precision"] for r in scored),
            "mrr": statistics.mean(r["rr"] for r in scored), "relevant": rel, "other": other, "negative_top": neg,
            "gate": gate, "per_question": scored}


def dist(xs: list[float]) -> str:
    if not xs:
        return "-"
    xs = sorted(xs)
    return f"{len(xs)}개 · 최저 {xs[0]:.3f} · 하위10% {xs[len(xs) // 10]:.3f} · 중앙 {statistics.median(xs):.3f} · 최고 {xs[-1]:.3f}"


def report(name: str, result: dict, k: int) -> None:
    print(f"\n[{name}] Recall@{k} {result['recall']:.3f} · Precision@{k} {result['precision']:.3f} · MRR {result['mrr']:.3f}")
    print("  정답 문서        :", dist(result["relevant"]))
    print(f"  정답 밖 상위 {k}개  :", dist(result["other"]))
    print("  자료 없는 주제 1위:", dist(result["negative_top"]))
    for t, g in result["gate"].items():
        print(f"  기준 {t:.2f}: 정답 질문 통과 {g['gold_pass']:.0%} · 자료 없는 주제 통과 {g['negative_pass']}개")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True, help="리랭커 모델(Hugging Face 이름)")
    parser.add_argument("--k", type=int, default=8)
    parser.add_argument("--candidates", type=int, default=30, help="리랭커에 넣는 후보 청크 수(챗봇 CANDIDATES와 같게)")
    parser.add_argument("--refresh", action="store_true", help="후보를 AI 서비스에서 새로 받는다")
    args = parser.parse_args()

    questions = json.loads(QUESTIONS.read_text(encoding="utf-8"))["questions"]
    if args.refresh or not CANDIDATES_FILE.is_file():
        RESULTS.mkdir(exist_ok=True)
        CANDIDATES_FILE.write_text(json.dumps(fetch_candidates(questions, args.candidates), ensure_ascii=False), encoding="utf-8")
    candidates = json.loads(CANDIDATES_FILE.read_text(encoding="utf-8"))
    docs, _ = facts_check.load()

    reranker = create_reranker(args.model)
    started = time.perf_counter()
    reranker.score("워밍업", ["모델을 올린다"])
    print(f"모델 올림 {time.perf_counter() - started:.1f}초")
    rows, seconds = [], []
    for q in questions:
        chunks = candidates[q["id"]]
        gold = [] if q.get("negative") else sorted(relevant(q["rule"], docs))
        if not q.get("negative") and not gold:
            continue
        started = time.perf_counter()
        for c, s in zip(chunks, reranker.score(q["q"], [c["document"] for c in chunks])):
            c["rerank"] = round(s, 4)
        seconds.append(time.perf_counter() - started)
        rows.append({"id": q["id"], "q": q["q"], "negative": bool(q.get("negative")), "gold": gold, "chunks": chunks})
        print(f"{q['id']} {q['q']} — {seconds[-1]:.1f}초 · 1위 {max((c['rerank'] for c in chunks), default=0):.3f}")

    print(f"\n질문당 리랭킹 {statistics.mean(seconds):.1f}초(후보 {args.candidates}개, 최대 {max(seconds):.1f}초)")
    base, reranked = evaluate(rows, "score", args.k), evaluate(rows, "rerank", args.k)
    report("코사인 유사도(같은 후보)", base, args.k)
    report(f"리랭커 {args.model}", reranked, args.k)
    out = RESULTS / f"rerank-eval-{args.model.split('/')[-1]}.json"
    out.write_text(json.dumps({"model": args.model, "seconds": seconds, "cosine": base, "rerank": reranked, "rows": rows},
                              ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"저장: {out}")


if __name__ == "__main__":
    main()
