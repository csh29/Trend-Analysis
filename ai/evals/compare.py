"""P-06 챗봇 평가 비교 — judge.py가 남긴 점수 파일 두 개(전·후)를 나란히 놓는다. API를 부르지 않는다.

사용(ai 폴더에서):
  .venv/Scripts/python evals/compare.py evals/results/baseline-scores-v2.json evals/results/quickfix-scores.json
같은 질문 세트·같은 요건 문구로 채점한 파일끼리만 비교한다(문구가 다르면 점수가 달라진다 — 2026-10-23 기간 요건 문구 수정).
"""
import argparse
import json
from pathlib import Path

FABRICATION = "답의 수치·값은"  # 공통 요건 중 지어낸 값 요건의 앞부분


def load(path: str) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def fmt(v) -> str:
    return "-" if v is None else f"{v:.2f}"


def delta(a, b) -> str:
    return "" if a is None or b is None else f"{b - a:+.2f}"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("before")
    parser.add_argument("after")
    args = parser.parse_args()
    a, b = load(args.before), load(args.after)
    sa, sb = a["summary"], b["summary"]

    print(f"전: {args.before}\n후: {args.after}\n")
    print("| 구분 | 전 | 후 | 차이 |\n| --- | --- | --- | --- |")
    rows = [("전체", sa["overall"], sb["overall"]), ("질문별 요건", sa["overallSpecific"], sb["overallSpecific"])]
    rows += [(f"팀 {t}", sa["byTeam"].get(t), sb["byTeam"].get(t)) for t in sb["byTeam"]]
    rows += [(f"예상 {e} (질문별)", sa["byExpected"].get(e), sb["byExpected"].get(e)) for e in ("ok", "partial", "no")]
    rows += [(f"공통: {c[:18]}…", sa["commonRequirements"].get(c), sb["commonRequirements"].get(c)) for c in sb["commonRequirements"]]
    for name, x, y in rows:
        print(f"| {name} | {fmt(x)} | {fmt(y)} | {delta(x, y)} |")

    def fabricated(scores: dict) -> list[str]:
        return [r["id"] for r in scores["results"]
                if any(i["requirement"].startswith(FABRICATION) and i["verdict"] == "fail" for i in r["items"])]

    print(f"\n지어낸 값 fail: 전 {fabricated(a)} → 후 {fabricated(b)}")

    before = {r["id"]: r for r in a["results"]}
    print("\n| 문항 | 팀 | 전 | 후 | 차이 | 후에 남은 fail |\n| --- | --- | --- | --- | --- | --- |")
    for r in b["results"]:
        p = before.get(r["id"], {})
        fails = "; ".join(i["requirement"][:22] for i in r["items"] if i["verdict"] == "fail")
        print(f"| {r['id']} | {r['team']} | {fmt(p.get('score'))} | {fmt(r['score'])} | {delta(p.get('score'), r['score'])} | {fails} |")


if __name__ == "__main__":
    main()
