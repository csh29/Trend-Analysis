"""P-03 정형화 정확도 — 정답셋(structure_gold.json, status=reviewed)의 사진을 실제 정형화(Claude)로 돌려 아이템별로 맞댄다.

아이템 맞대기: 정답 아이템마다 같은 카테고리의 예측 아이템 중 종류가 같은 것을 먼저, 없으면 같은 카테고리 아무거나 하나(한 번씩만).
- 채점은 엑셀 정답셋에 적힌 옷만 한다(2026-10-28 사용자 결정) — 정답은 사진마다 판매 상품만 적어서, 같이 입은 옷을 더 찾은 예측은
  틀림이 아니다. 짝이 안 된 예측은 extra로 남기기만 하고 점수에 넣지 않는다.
- 아이템: 찾음(recall). 종류: 짝지은 것 중 종류가 같은 비율.
- 소재·컬러·실루엣: 짝지은 아이템끼리 값 목록을 비교(정답에 있는데 없음 = 놓침, 없는데 있음 = 과잉) — 정밀도·재현율·완전일치.
Spring 검증(실루엣 축마다 1개·카테고리 맞추기·5개 자르기)은 거치지 않은 Claude 원 결과다 — 실제 저장값과 조금 다를 수 있다.
코드 목록은 TrendCodes.java 초안(SEED)에서 읽고, 이미지라 소재는 속성 IMAGE인 것만 보낸다(실제 분석과 같다). 정답 소재도 그 소재만 채점한다.
정답셋은 사람이 엑셀로 단 라벨을 gold_from_excel.py로 옮긴 것이다.

사용(ai 폴더에서, 사진 1장에 약 $0.01 — 73장이면 약 $0.5):
  .venv/Scripts/python evals/structure_eval.py [--label baseline] [--ids G001,G002]
  정답만 고쳤으면 Claude를 다시 부르지 않고: --rescore evals/results/structure-baseline.json --label baseline-v2
결과: evals/results/structure-<label>.json(커밋 제외). Claude API 키는 backend의 application-local.properties에서 읽고 출력하지 않는다.
"""
import argparse
import io
import json
import mimetypes
import re
import sys
import time
from pathlib import Path

from PIL import Image

AI_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(AI_DIR))
from app.structuring import image_prep  # noqa: E402
from app.structuring import structurer as st  # noqa: E402

GOLD = AI_DIR / "evals" / "structure_gold.json"
GOLD_DIR = AI_DIR / "evals" / "gold"
RESULTS = AI_DIR / "evals" / "results"
BACKEND = AI_DIR.parent / "backend"
MODEL = "claude-sonnet-5"
ATTRS = ("materials", "colors", "silhouettes", "genders", "patterns", "details")  # genders·patterns는 정답에 칸이 있는 아이템만 채점한다


def api_key() -> str:
    props = BACKEND / "src" / "main" / "resources" / "application-local.properties"
    for line in props.read_text(encoding="utf-8").splitlines():
        if line.startswith("claude.api.key="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("application-local.properties에 claude.api.key가 없습니다.")


def seed_codes() -> dict:
    java = next(BACKEND.glob("src/main/java/**/TrendCodes.java")).read_text(encoding="utf-8")

    def group(name: str) -> list[tuple[str, str, str]]:
        i = java.index(name + ", List.of(", java.index("SEED"))
        j = re.compile(r"\}\)[,)]").search(java, i).end()
        # 세 번째 값은 문자열("재킷,점퍼") 또는 상수(ATTR_IMAGE) — 코드마다 자기 속성만 읽는다
        return re.findall(r'new String\[\]\{"([^"]+)", "([^"]+)"(?:, ("[^"]*"|[A-Z_]+))?\}', java[i:j])

    def opts(rows):
        return [st.CodeOption(v, n) for v, n, _ in rows]

    # 디테일 초안은 SEED 밖 DETAIL_SEED에 따로 있다(Map.of 10쌍 제한)
    i = java.index("DETAIL_SEED = List.of(")
    detail_rows = re.findall(r'new String\[\]\{"([^"]+)", "([^"]+)"(?:, ("[^"]*"|[A-Z_]+))?\}', java[i:java.index("SEED = withDetail", i)])
    image_materials = [st.CodeOption(v, n) for v, n, a in group("MATERIAL") if a == "ATTR_IMAGE"]
    return dict(categories=opts(group("CATEGORY")), item_types=opts(group("ITEM_TYPE")), materials=image_materials,
                colors=opts(group("COLOR")), silhouettes=opts(group("SILHOUETTE")), seasons=opts(group("SEASON")),
                genders=opts(group("GENDER")), patterns=opts(group("PATTERN")), details=opts(detail_rows))


def match(gold: list[dict], pred: list[dict]) -> list[tuple[dict, dict | None]]:
    """정답 아이템마다 예측 하나(같은 카테고리, 종류가 같은 것 먼저). 남은 예측은 채점하지 않는다(extra)."""
    left = list(pred)
    pairs = []
    for g in gold:
        same_cat = [p for p in left if p.get("category") == g["category"]]
        best = next((p for p in same_cat if p.get("type") == g["type"]), same_cat[0] if same_cat else None)
        if best is not None:
            left.remove(best)
        pairs.append((g, best))
    return pairs


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--label", default="run")
    parser.add_argument("--ids", help="쉼표로 구분한 정답 ID만")
    parser.add_argument("--rescore", help="저장된 결과 파일의 예측으로 다시 채점(Claude를 부르지 않는다 — 정답을 고쳤을 때)")
    args = parser.parse_args()
    only = set(args.ids.split(",")) if args.ids else None

    gold = [i for i in json.loads(GOLD.read_text(encoding="utf-8"))["items"]
            if i["status"] == "reviewed" and (only is None or i["id"] in only)]
    saved = {i["id"]: [pr["pred"] for pr in i["pairs"] if pr["pred"]] + i["extra"]
             for i in json.loads(Path(args.rescore).read_text(encoding="utf-8"))["items"]} if args.rescore else None
    codes = seed_codes()
    key = None if saved is not None else api_key()
    # 정답 소재는 사람이 안 소재 전부(면·폴리에스터 등) — 이미지 정형화가 고를 수 있는 소재(속성 IMAGE)만 채점한다
    image_materials = {c.value for c in codes["materials"]}
    tally = {"gold": 0, "pred": 0, "matched": 0, "type_ok": 0}
    attr = {a: {"tp": 0, "fp": 0, "fn": 0, "exact": 0, "n": 0} for a in ATTRS}
    tokens = {"input_tokens": 0, "output_tokens": 0, "cache_read_input_tokens": 0}
    rows = []
    for item in gold:
        started = time.monotonic()
        if saved is not None:
            result, usage = {"items": saved[item["id"]], "summary": None}, {}
        else:
            path = GOLD_DIR / item["image"]
            raw = path.read_bytes()
            # 확장자가 실제 형식과 다른 파일이 있다(.jpg인데 webp) — 앱(ImageType.detect)처럼 내용으로 정한다
            fmt = (Image.open(io.BytesIO(raw)).format or "").lower()
            media_type = {"jpeg": "image/jpeg", "png": "image/png", "gif": "image/gif", "webp": "image/webp"}.get(fmt) \
                or mimetypes.guess_type(path.name)[0] or "image/png"
            data, mt = image_prep.prepare(raw, media_type)
            result, usage = st.structure(st.StructureInput(item["id"], "IMAGE", MODEL, None, None, [st.ImageInput(data, mt)], **codes), key)
        for k in tokens:
            tokens[k] += usage.get(k, 0)
        pred = result.get("items") or []
        pairs = match(item["garments"], pred)
        tally["gold"] += len(item["garments"])
        tally["pred"] += len(pred)
        detail = []
        for g, p in pairs:
            row = {"gold": {k: g.get(k) for k in ("category", "type", *ATTRS)}, "pred": p}
            if p is not None:
                tally["matched"] += 1
                tally["type_ok"] += p.get("type") == g["type"]
                for a in ATTRS:
                    if g.get(a) is None:  # 정답에서 판단하지 않은 칸(일부만 보임 등·정답이 아직 없는 칸) — 채점하지 않는다
                        continue
                    gs, ps = set(g[a]), set(p.get(a) or [])
                    if a == "materials":
                        gs &= image_materials
                    attr[a]["tp"] += len(gs & ps)
                    attr[a]["fp"] += len(ps - gs)
                    attr[a]["fn"] += len(gs - ps)
                    attr[a]["exact"] += gs == ps
                    attr[a]["n"] += 1
            detail.append(row)
        extra = [p for p in pred if all(p is not q for _, q in pairs)]
        rows.append({"id": item["id"], "elapsed_s": round(time.monotonic() - started, 1), "pairs": detail, "extra": extra,
                     "summary": result.get("summary")})
        miss = sum(p is None for _, p in pairs)
        wrong = [f"{g['type']}→{p.get('type')}" for g, p in pairs if p and p.get("type") != g["type"]]
        print(f"{item['id']} 정답 {len(item['garments'])} 예측 {len(pred)} 놓침 {miss} 더 찾음 {len(extra)}(채점 안 함) 종류 틀림 {wrong}", flush=True)

    def ratio(a, b):
        return round(a / b, 3) if b else None

    summary = {
        "garment_recall": ratio(tally["matched"], tally["gold"]),
        "type_accuracy": ratio(tally["type_ok"], tally["matched"]),
        **{a: {"precision": ratio(v["tp"], v["tp"] + v["fp"]), "recall": ratio(v["tp"], v["tp"] + v["fn"]),
               "exact": ratio(v["exact"], v["n"])} for a, v in attr.items()},
        "counts": tally, "tokens": tokens,
    }
    RESULTS.mkdir(exist_ok=True)
    out = RESULTS / f"structure-{args.label}.json"
    out.write_text(json.dumps({"model": MODEL, "summary": summary, "items": rows}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"저장: {out}")


if __name__ == "__main__":
    main()
