"""챗봇 유행 집계 검증 — FactsCheckRunner가 뽑은 판단 근거(results/facts-check.json)를 H2 파일 DB를 SQL로 직접 센 값과 맞댄다.

집계는 계산이라 하나라도 다르면 버그다. 이 스크립트는 Spring 코드를 거치지 않고 정의(rag-search.md "유행 집계")대로 다시 센다:
- 원문 = 분석 완료(DONE), 날짜 = 게시일(없으면 업로드일). 기간 안 원문 수 / 서로 다른 매체 수 / 직전 같은 길이 기간 원문 수.
- 범위: 카테고리·종류·속성 조건(종류 안 OR, 종류끼리 AND)이 맞는 의류 아이템이 하나라도 있는 원문. 시즌은 원문 단위.
- 값마다: 범위에 맞는 아이템 중 그 값을 가진 것이 있는 원문 수. 상위 5개(유행점수 → 원문 수 → 매체 수 → 코드값 순).
  유행점수 = 출처 수 + 0.5 × (원문 수 − 출처 수). 출처는 매체명, 직접 업로드·직접 등록·매체 없음은 원문마다 다른 출처.
  증감 조건(change)이 있으면 UP은 늘어난 값만, DOWN은 줄어든 값만(이번 0건 포함), BOTH는 달라진 값 — 증감 폭 순, 같으면 위 순서.
- 질문 단어: 낱말이 모두 들어간 키워드·아이템명이 있는 원문(범위 무관, 시즌은 따름). 포인트는 아이템명에 그 말이 든 옷의 값.

사용(Spring 앱을 내린 상태 — H2 파일 잠금):
  backend jar를 --spring.profiles.active=local --server.port=0 --rag.facts-check.cases=ai/evals/facts_cases.json
      --rag.facts-check.out=ai/evals/results/facts-check.json 로 띄운 뒤
  py ai/evals/facts_check.py                 # 전체 조건 대조
  py ai/evals/facts_check.py --docs F02 F15  # 그 조건의 값마다 SQL로 센 원문 목록(이번·직전)까지 — 챗봇 답의 N건을 눈으로 확인
챗봇 답 하나를 확인하려면 답의 해석(범위·속성·시즌·기간·증감)을 facts_cases.json에 조건으로 넣고 위 순서로 돌린다.
DB는 앱과 같은 H2 2.5.252(pom의 h2.version)·JDK 17로 읽기 전용으로 연다. 접속 정보는 application-local.properties에서 읽고 출력하지 않는다. Claude를 부르지 않는다.
"""
import argparse
import json
import subprocess
from collections import defaultdict
from datetime import date, datetime, timedelta
from pathlib import Path

AI_DIR = Path(__file__).resolve().parents[1]
BACKEND = AI_DIR.parent / "backend"
CASES = AI_DIR / "evals" / "facts_cases.json"
RESULT = AI_DIR / "evals" / "results" / "facts-check.json"
TOP = 5
ATTRS = ("MATERIAL", "COLOR", "SILHOUETTE")
NO_SOURCE = ("", "직접 업로드", "직접 등록")  # 출처를 모르는 매체값 — 원문마다 다른 출처(TrendFactsService.sourceKey)
GROUPS = {"CATEGORY": "TREND_CATEGORY", "ITEM_TYPE": "TREND_ITEM", "MATERIAL": "TREND_MATERIAL", "COLOR": "TREND_COLOR",
          "PATTERN": "TREND_PATTERN", "SILHOUETTE": "TREND_SILHOUETTE", "DETAIL": "TREND_DETAIL"}


def query(sql: str) -> list[list[str]]:
    props = dict(l.split("=", 1) for l in (BACKEND / r"src\main\resources\application-local.properties").read_text(encoding="utf-8").splitlines()
                 if "=" in l and not l.startswith("#"))
    jar = Path.home() / r".m2\repository\com\h2database\h2\2.5.252\h2-2.5.252.jar"
    url = "jdbc:h2:file:" + str(BACKEND / "data" / "trendAnalysis") + ";ACCESS_MODE_DATA=r"
    r = subprocess.run([r"C:\Program Files\Java\jdk-17\bin\java.exe", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-cp", str(jar), "org.h2.tools.Shell", "-url", url,
                        "-user", props["spring.datasource.username"].strip(), "-password", props.get("spring.datasource.password", "").strip(),
                        "-sql", sql], capture_output=True, text=True, encoding="utf-8")
    if r.returncode:
        raise SystemExit("DB 조회 실패(앱이 떠 있으면 파일이 잠겨 있다)")
    rows = []
    for line in r.stdout.splitlines():
        parts = [p.strip() for p in line.split("\t")]
        if len(parts) > 1:
            rows.append(parts)
    return rows


def load():
    docs = {}
    for doc_id, day, media in query(
            "SELECT DOC_ID || CHAR(9) || COALESCE(CAST(PUBLISHED_AT AS VARCHAR), CAST(CAST(REGISTERED_AT AS DATE) AS VARCHAR)) || CHAR(9) || COALESCE(MEDIA, '')"
            " FROM SOURCE_DOCUMENTS WHERE STATUS = 'DONE'"):
        docs[doc_id] = {"date": date.fromisoformat(day), "media": media,
                        "source": "doc:" + doc_id if media in NO_SOURCE else media, "kw": [], "season": [], "g": defaultdict(lambda: defaultdict(list))}
    for doc_id, vtype, gno, cat, value in query(
            "SELECT d.DOC_ID || CHAR(9) || v.VALUE_TYPE || CHAR(9) || COALESCE(CAST(v.GARMENT_NO AS VARCHAR), '-') || CHAR(9) || COALESCE(v.CATEGORY, '-')"
            " || CHAR(9) || v.ITEM_VALUE FROM SOURCE_DOCUMENTS d JOIN TREND_ITEMS t ON t.DOCUMENT_ID = d.DOCUMENT_ID"
            " JOIN TREND_ITEM_VALUES v ON v.TREND_ITEM_ID = t.TREND_ITEM_ID WHERE d.STATUS = 'DONE' ORDER BY v.SORT_ORDER"):
        d = docs.get(doc_id)
        if d is None:
            continue
        if vtype == "KEYWORD":
            d["kw"].append(value)
        elif vtype == "SEASON":
            d["season"].append(value)
        elif gno != "-":
            g = d["g"][int(gno)]
            g[vtype].append(value)
            g["CATEGORY"] = [cat]
    names = {(grp, val): name for grp, val, name in query(
        "SELECT CODE_GROUP || CHAR(9) || CODE_VALUE || CHAR(9) || CODE_NAME FROM COM_CD_DTL")}
    return docs, names


def short(name: str) -> str:
    i = name.find("(")
    return name[:i].strip() if i > 0 else name


def compact(s: str) -> str:
    return "".join(s.split()).lower()


def rank(tallies: dict, change: str | None) -> list:
    """TrendFactsService.rank와 같은 순서의 상위 값 [(값, tally)]."""
    def by_score(e):
        t = e[1]
        n, src = len(t["now"]), len(t["src"])
        return (-(src + 0.5 * (n - src)), -n, -len(t["media"]), e[0])

    if not change:
        return sorted(((v, t) for v, t in tallies.items() if t["now"]), key=by_score)[:TOP]

    def delta(e):
        return len(e[1]["now"]) - len(e[1]["prev"])

    keep = {"UP": lambda d: d > 0, "DOWN": lambda d: d < 0}.get(change, lambda d: d != 0)
    return sorted((e for e in tallies.items() if keep(delta(e))), key=lambda e: (-abs(delta(e)), by_score(e)))[:TOP]


def count(case: dict, spring: dict, docs: dict, names: dict, show_docs: bool = False) -> list[str]:
    """다른 점 목록(같으면 빈 목록). show_docs면 값마다 센 원문 목록을 찍는다."""
    diffs = []
    per = spring["period"]
    frm, to = date.fromisoformat(per["from"]), date.fromisoformat(per["to"])
    today = date.fromisoformat(CASE_ROOT["today"])
    # 기간 자체도 정의대로인지 — 지정 기간 > 시즌 6개월 > 최근 4주(그 안에 범위 원문이 없으면 가장 최근 자료 4주로 옮긴다)
    if case.get("from"):
        expect = (date.fromisoformat(case["from"]), date.fromisoformat(case["to"]))
    elif case.get("seasons"):
        expect = (today - timedelta(days=182), today)
    else:
        expect = (today - timedelta(days=27), today)
    if (frm, to) != expect and "가장 최근" not in per["label"]:
        diffs.append(f"기간 {frm}~{to} (정의상 {expect[0]}~{expect[1]})")
    prev_from = frm - (to - frm + timedelta(days=1))
    seasons, filters = set(case.get("seasons") or []), case.get("filters") or {}

    def match(g):
        return ((not case.get("category") or g["CATEGORY"] == [case["category"]])
                and (not case.get("itemType") or g["ITEM_TYPE"][:1] == [case["itemType"]])
                and all(set(g[t]) & set(vals) for t, vals in filters.items()))

    scope_now, scope_media, scope_prev = set(), set(), set()
    tally = defaultdict(lambda: defaultdict(lambda: {"now": set(), "media": set(), "src": set(), "prev": set()}))
    for doc_id, d in docs.items():
        if not (prev_from <= d["date"] <= to) or (seasons and not seasons & set(d["season"])):
            continue
        gs = [g for g in d["g"].values() if match(g)]
        if not gs:
            continue
        cur = d["date"] >= frm
        (scope_now if cur else scope_prev).add(doc_id)
        if cur:
            scope_media.add(d["media"])
        for dim in spring["dimensions"]:
            vt = dim["valueType"]
            for v in {v for g in gs for v in g[vt]}:
                t = tally[vt][v]
                if cur:
                    t["now"].add(doc_id)
                    t["media"].add(d["media"])
                    t["src"].add(d["source"])
                else:
                    t["prev"].add(doc_id)
    for key, mine in (("docCount", len(scope_now)), ("mediaCount", len(scope_media)), ("prevDocCount", len(scope_prev))):
        if spring[key] != mine:
            diffs.append(f"{key} 챗봇 {spring[key]} / SQL {mine}")
    for dim in spring["dimensions"]:
        vt = dim["valueType"]
        ranked = rank(tally[vt], case.get("change"))
        mine = [(v, len(t["now"]), len(t["media"]), len(t["prev"])) for v, t in ranked]
        theirs = [(r["value"], r["count"], r["mediaCount"], r["prevCount"]) for r in dim["rows"]]
        if mine != theirs:
            diffs.append(f"[{dim['label']}] 챗봇 {theirs} / SQL {mine}")
        if show_docs:
            print(f"     [{dim['label']}]")
            for v, t in ranked:
                print(f"       {short(names.get((GROUPS.get(vt, ''), v), v))}({v}) 이번 {len(t['now'])}건 · 직전 {len(t['prev'])}건")
                for label, ids in (("이번", t["now"]), ("직전", t["prev"])):
                    for i in sorted(ids, key=lambda i: (docs[i]["date"], i), reverse=True):
                        print(f"         {label} {i} · {docs[i]['date']} · {docs[i]['media'] or '-'}")

    for tr in spring["terms"]:
        keys = [compact(k) for k in tr["term"].split()]

        def hit(text):
            return all(k in compact(text) for k in keys)

        now, media, prev = set(), set(), set()
        pts = {a: defaultdict(lambda: [set(), set()]) for a in ("COLOR", "MATERIAL", "SILHOUETTE")}
        for doc_id, d in docs.items():
            if not (prev_from <= d["date"] <= to) or (seasons and not seasons & set(d["season"])):
                continue
            texts = d["kw"] + [g["ITEM"][0] for g in d["g"].values() if g["ITEM"]]
            if not any(hit(t) for t in texts):
                continue
            cur = d["date"] >= frm
            (now if cur else prev).add(doc_id)
            if cur:
                media.add(d["media"])
            for g in d["g"].values():
                if g["ITEM"] and hit(g["ITEM"][0]):
                    for a in pts:
                        for v in g[a]:
                            pts[a][v][0 if cur else 1].add(doc_id)
        if (tr["count"], tr["mediaCount"], tr["prevCount"]) != (len(now), len(media), len(prev)):
            diffs.append(f"질문 단어 {tr['term']} 챗봇 {tr['count']}/{tr['mediaCount']}/{tr['prevCount']} / SQL {len(now)}/{len(media)}/{len(prev)}")
        group = {"COLOR": "TREND_COLOR", "MATERIAL": "TREND_MATERIAL", "SILHOUETTE": "TREND_SILHOUETTE"}
        for p in tr["points"]:
            a = next(k for k, lab in (("COLOR", "컬러"), ("MATERIAL", "소재"), ("SILHOUETTE", "실루엣")) if lab == p["label"])
            mine = {short(names.get((group[a], v), v)): (len(s[0]), len(s[1])) for v, s in pts[a].items() if s[0]}
            for v in p["values"]:
                if mine.get(v["name"]) != (v["count"], v["prevCount"]):
                    diffs.append(f"질문 단어 {tr['term']} {p['label']} {v['name']} 챗봇 {v['count']}(직전 {v['prevCount']}) / SQL {mine.get(v['name'])}")
    return diffs


CASE_ROOT = json.loads(CASES.read_text(encoding="utf-8"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--docs", nargs="+", metavar="ID", help="이 조건들은 값마다 센 원문 목록도 찍는다")
    args = ap.parse_args()
    docs, names = load()
    spring = {r["id"]: r["facts"] for r in json.loads(RESULT.read_text(encoding="utf-8"))["results"]}
    bad = 0
    for case in CASE_ROOT["cases"]:
        f = spring[case["id"]]
        head = f"{case['id']} {case['note']} — {f['scopeLabel']} · {f['period']['label']} {f['period']['from']}~{f['period']['to']} · 원문 {f['docCount']}건"
        show = bool(args.docs) and case["id"] in args.docs
        if show:
            print("     " + head)
        diffs = count(case, f, docs, names, show)
        bad += bool(diffs)
        print(("OK   " if not diffs else "DIFF ") + head)
        for d in diffs:
            print("     - " + d)
    print(f"\n{len(CASE_ROOT['cases'])}건 중 다름 {bad}건 (DB 원문 {len(docs)}건, {datetime.now():%Y-%m-%d %H:%M})")


if __name__ == "__main__":
    main()
