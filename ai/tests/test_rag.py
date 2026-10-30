"""P-06 RAG 테스트(네트워크·실제 Claude·Chroma 없음). 실행: .venv\\Scripts\\python -m unittest"""
import dataclasses
import json
import unittest
from datetime import date
from types import SimpleNamespace
from unittest import mock

from fastapi.testclient import TestClient

from app.indexing import indexer, keyword_index
from app import main
from app.rag import answer as rag
from tests.test_core import FakeMessages, fake_response


class FakeQueryCollection:
    """collection.query/get 결과(유사도 = 1 - 거리)를 정해 둔다. where는 쓰는 것만 흉내 낸다:
    $and, 값 비교($gte·$lte), 같음(cat_OUTER: True), $in(doc_id)."""

    def __init__(self, rows):  # rows: (chunk_id, document, metadata, distance)
        self.rows, self.queries, self.gets = rows, [], []

    @classmethod
    def _match(cls, meta, where):
        if not where:
            return True
        conditions = where["$and"] if "$and" in where else [where]
        for cond in conditions:
            (key, rule), = cond.items()
            value = meta.get(key)
            if isinstance(rule, dict):
                if "$gte" in rule and (value or 0) < rule["$gte"] or "$lte" in rule and (value or 0) > rule["$lte"]:
                    return False
                if "$in" in rule and value not in rule["$in"]:
                    return False
            elif value != rule:
                return False
        return True

    def _embed(self, input, is_query=False):
        return [[1.0] for _ in input]

    def query(self, query_embeddings, n_results, include, where=None, ids=None):
        self.queries.append((query_embeddings, n_results, where) if ids is None else ("ids", ids))
        rows = [r for r in self.rows if self._match(r[2], where) and (ids is None or r[0] in ids)][:n_results]
        return {"ids": [[r[0] for r in rows]], "documents": [[r[1] for r in rows]],
                "metadatas": [[r[2] for r in rows]], "distances": [[r[3] for r in rows]]}

    def get(self, where=None, include=None, ids=None):
        self.gets.append(where)
        rows = [r for r in self.rows if self._match(r[2], where)]
        return {"ids": [r[0] for r in rows], "documents": [r[1] for r in rows], "metadatas": [r[2] for r in rows]}


def meta(doc_id, doc_type="IMAGE", published="2026-10-20", categories=("BOTTOM",), chunk_index=0):
    return {"doc_id": doc_id, "doc_type": doc_type, "media": "직접 업로드", "published_at": published,
            "published_num": indexer.date_number(published), "chunk_index": chunk_index,
            **indexer.category_flags(list(categories))}


ROWS = [
    ("IMG-1#0", "블랙 와이드 트랙 팬츠", meta("IMG-1"), 0.25),
    ("TXT-1#1", "와이드 팬츠가 유행", meta("TXT-1", "TEXT"), 0.30),
    ("TXT-1#0", "요약: 와이드 팬츠", meta("TXT-1", "TEXT"), 0.35),
    ("IMG-2#0", "패딩 재킷", meta("IMG-2"), 0.55),
]


TODAY = date(2026, 10, 22)


def data(min_score=0.6, question="와이드 팬츠 유행?", **kwargs):
    return rag.AnswerInput(question=question, model="claude-sonnet-5", top_k=8, min_score=min_score,
                           today=TODAY, **kwargs)


LAST_WEEK = rag.Period(date(2026, 10, 12), date(2026, 10, 18), "지난주", True)


OLD_ROWS = [("TXT-OLD#0", "와이드 팬츠가 유행", meta("TXT-OLD", "TEXT", "2026-06-01"), 0.25)]
GOOD = json.dumps({"summary": "와이드 팬츠", "grounded": True, "points": [{"text": "줄", "sources": [1]}]})


class DateNumberTest(unittest.TestCase):
    """기간 해석은 Spring(QuestionInterpreter)이 한다. 여기서는 Chroma 필터용 게시일 숫자만."""

    def test_date_number(self):
        self.assertEqual(20261022, indexer.date_number("2026-10-22"))
        self.assertEqual(0, indexer.date_number(""))
        self.assertEqual(0, indexer.date_number("unknown"))


class HybridSearchTest(unittest.TestCase):
    """키워드(BM25)를 RRF로 합친다 — 후보를 넓히고 순서를 바꿀 뿐, score는 언제나 유사도다."""
    ROWS = [
        ("A#0", "블랙 팬츠", meta("A"), 0.20),
        ("B#0", "패딩 재킷", meta("B"), 0.25),
        ("C#0", "무스탕 재킷", meta("C", categories=("OUTER",)), 0.30),
    ]

    def test_keyword_only_chunk_joins_with_its_similarity(self):
        collection = FakeQueryCollection(self.ROWS)
        hits = rag.search(collection, "무스탕", 2, hybrid=True)
        self.assertEqual(["A#0", "C#0"], [h["id"] for h in hits])  # 벡터 상위 2개 밖의 C가 키워드로 들어온다
        self.assertEqual(0.7, hits[1]["score"])                     # 같은 질의 벡터로 구한 유사도
        self.assertEqual(("ids", ["C#0"]), collection.queries[1])

    def test_keyword_side_keeps_meta_filter(self):
        collection = FakeQueryCollection(self.ROWS)
        hits = rag.search(collection, "무스탕", 2, {"cat_BOTTOM": True}, hybrid=True)
        self.assertNotIn("C#0", [h["id"] for h in hits])
        self.assertEqual(1, len(collection.queries))

    def test_vector_only_when_off(self):
        collection = FakeQueryCollection(self.ROWS)
        hits = rag.search(collection, "무스탕", 2, hybrid=False)
        self.assertEqual(["A#0", "B#0"], [h["id"] for h in hits])
        self.assertNotIn("fused", hits[0])

    def test_tokens_ignore_spaces(self):
        self.assertEqual(keyword_index.tokens("와이드팬츠"), keyword_index.tokens("와이드 팬츠!"))

    def test_index_is_rebuilt_after_invalidate(self):
        collection = FakeQueryCollection(self.ROWS)
        keyword_index.get(collection)
        keyword_index.get(collection)
        self.assertEqual(1, len(collection.gets))  # 바뀌지 않았으면 다시 만들지 않는다
        keyword_index.invalidate()
        keyword_index.get(collection)
        self.assertEqual(2, len(collection.gets))


class FakeReranker:
    """질문 낱말이 든 청크에 높은 점수 — 리랭커 모델 없이 순서·기준만 본다."""

    def __init__(self, word):
        self.word, self.calls = word, []

    def score(self, query, documents):
        self.calls.append((query, list(documents)))
        return [0.9 if self.word in d else 0.01 for d in documents]


class RerankTest(unittest.TestCase):
    """리랭킹을 켜면 순서와 근거 기준이 리랭커 점수(rerank)로 바뀐다. 유사도(score)는 그대로 남는다."""

    def test_rerank_orders_by_reranker_score(self):
        with mock.patch.object(rag.reranker, "get", return_value=FakeReranker("패딩")):
            hits = rag.rerank("q", rag.search(FakeQueryCollection(ROWS), "q", 4, hybrid=False))
        self.assertEqual("IMG-2#0", hits[0]["id"])  # 유사도는 꼴찌(0.45)지만 리랭커가 1위로
        self.assertEqual(0.45, hits[0]["score"])
        self.assertEqual(0.9, hits[0]["rerank"])

    def test_answer_gates_on_reranker_score(self):
        body = json.dumps({"summary": "패딩", "grounded": True, "points": [{"text": "줄", "sources": [1]}]})
        messages = FakeMessages(fake_response(body))
        with mock.patch.object(rag.reranker, "get", return_value=FakeReranker("패딩")):
            result = rag.answer(FakeQueryCollection(ROWS), data(min_score=0.1, rerank=True), "k",
                                client=SimpleNamespace(messages=messages))
        sent = messages.calls[0]["messages"][0]["content"]
        self.assertIn("패딩 재킷", sent)            # 유사도 0.45라 예전 기준(0.6)이면 빠졌을 청크
        self.assertNotIn("와이드 팬츠가 유행", sent)  # 유사도는 높아도 리랭커 기준 미달
        self.assertEqual(0.9, result["sources"][0]["score"])
        self.assertEqual(0.9, result["best_score"])

    def test_no_evidence_when_reranker_rejects_all(self):
        messages = FakeMessages(fake_response("{}"))
        with mock.patch.object(rag.reranker, "get", return_value=FakeReranker("없는말")):
            result = rag.answer(FakeQueryCollection(ROWS), data(min_score=0.1, rerank=True), "k",
                                client=SimpleNamespace(messages=messages))
        self.assertEqual("NO_EVIDENCE", result["reason"])
        self.assertEqual([], messages.calls)

    def test_reranker_not_loaded_when_off(self):
        with mock.patch.object(rag.reranker, "get") as get:
            rag.answer(FakeQueryCollection(ROWS), data(min_score=0.9), "k",
                       client=SimpleNamespace(messages=FakeMessages(fake_response("{}"))))
        get.assert_not_called()

    def test_search_api_reranks_when_asked(self):
        with mock.patch.object(main.vector_store, "get_collection", return_value=FakeQueryCollection(ROWS)),                 mock.patch.object(rag.reranker, "get", return_value=FakeReranker("패딩")):
            res = TestClient(main.app).get("/search", params={"q": "q", "top_k": 2, "rerank": "true"})
        self.assertEqual(200, res.status_code)
        self.assertEqual(["IMG-2#0", 0.9], [res.json()[0]["id"], res.json()[0]["rerank"]])
        self.assertEqual(2, len(res.json()))


class RagTest(unittest.TestCase):
    def test_search_turns_distance_into_score(self):
        hits = rag.search(FakeQueryCollection(ROWS), "q", 2)
        self.assertEqual([0.75, 0.7], [h["score"] for h in hits])

    def test_group_by_document_merges_chunks_and_numbers_by_score(self):
        groups = rag.group_by_document(rag.search(FakeQueryCollection(ROWS), "q", 4))  # 필터 없이 전부
        self.assertEqual(["IMG-1", "TXT-1", "IMG-2"], [g["doc_id"] for g in groups])
        self.assertEqual([1, 2, 3], [g["n"] for g in groups])
        self.assertEqual(2, len(groups[1]["chunks"]))

    def test_no_evidence_skips_claude(self):
        messages = FakeMessages(fake_response("{}"))
        result = rag.answer(FakeQueryCollection(ROWS), data(min_score=0.9), "k",
                            client=SimpleNamespace(messages=messages))
        self.assertFalse(result["grounded"])
        self.assertEqual("NO_EVIDENCE", result["reason"])
        self.assertEqual(0.75, result["best_score"])
        self.assertEqual([], messages.calls)  # 근거가 없으면 Claude를 부르지 않는다

    def test_body_citations_are_cleaned_and_count_as_sources(self):
        # 자유 형식 본문(표 등)의 [n]도 출처가 된다 — 없는 번호는 지우고, 본문만 있어도 근거가 있는 답이다
        groups = [{"n": 1, "doc_id": "A", "score": 0.7, "chunks": ["a"]}, {"n": 2, "doc_id": "B", "score": 0.6, "chunks": ["b"]}]
        result = rag._answer_from({"summary": "요약", "points": [], "grounded": True,
                                   "body": "| 축 | 핵심 | 근거 |\n|---|---|---|\n| 컬러 | 블랙 3건 | [2][9] |"}, groups)
        self.assertTrue(result["grounded"])
        self.assertEqual(["B"], [s["doc_id"] for s in result["sources"]])
        self.assertIn("[2]", result["body"])
        self.assertNotIn("[9]", result["body"])
        self.assertIn("body", rag.build_schema()["required"])

    def test_lines_citing_only_unknown_numbers_are_dropped(self):
        # 없는 번호만 인용한 줄은 번호만 지우면 "인용 없는 한계 문장"으로 남는다 — 줄째 뺀다(2026-10-29 진단 F2)
        groups = [{"n": 1, "doc_id": "A", "score": 0.7, "chunks": ["a"]}]
        result = rag._answer_from({"summary": "요약", "grounded": True, "points": [
            {"text": "패딩: 블랙이 많다", "sources": [1]},
            {"text": "레더 재킷: 지어낸 줄", "sources": [9]},
            {"text": "자료가 적어 참고로만 본다.", "sources": []}],
            "body": "| 아이템 | 근거 |\n|---|---|\n| 패딩 | [1] |\n| 지어낸 행 | [7] |\n한계 문장."}, groups)
        self.assertEqual(["패딩: 블랙이 많다", "자료가 적어 참고로만 본다."], [p["text"] for p in result["points"]])
        self.assertIn("| 패딩 | [1] |", result["body"])
        self.assertNotIn("지어낸 행", result["body"])
        self.assertIn("| 아이템 | 근거 |", result["body"])
        self.assertIn("한계 문장.", result["body"])

    @mock.patch.object(rag, "settings", dataclasses.replace(rag.settings, hybrid_search=False))  # 번호 순서는 유사도 순으로 본다
    def test_only_passed_sources_go_to_claude_and_cited_ones_come_back(self):
        body = json.dumps({"summary": "와이드 팬츠가 많이 보인다.", "grounded": True, "points": [
            {"text": "트랙 팬츠: 블랙 와이드가 반복된다.", "sources": [2, 2, 9]},
            {"text": "  ", "sources": [1]},  # 빈 줄은 버린다
            {"text": "키워드 와이드 팬츠는 급상승이다.", "sources": []}]})
        messages = FakeMessages(fake_response(body))
        result = rag.answer(FakeQueryCollection(ROWS), data(), "k", client=SimpleNamespace(messages=messages))

        sent = messages.calls[0]["messages"][0]["content"]
        self.assertIn('<source n="1" type="이미지 분석 요약"', sent)
        self.assertIn("와이드 팬츠가 유행", sent)
        self.assertNotIn("패딩 재킷", sent)  # 기준(0.6) 미달 청크는 보내지 않는다
        self.assertTrue(result["grounded"])
        self.assertEqual(["TXT-1"], [s["doc_id"] for s in result["sources"]])  # 중복·없는 번호는 뺀다
        self.assertEqual(2, result["sources"][0]["n"])
        self.assertEqual("와이드 팬츠가 많이 보인다.", result["answer"])
        self.assertEqual([{"text": "트랙 팬츠: 블랙 와이드가 반복된다.", "sources": [2]},
                          {"text": "키워드 와이드 팬츠는 급상승이다.", "sources": []}], result["points"])

    def test_model_declined_is_not_grounded(self):
        body = json.dumps({"summary": "근거가 팬츠뿐이라 가방은 답할 수 없다.", "points": [], "grounded": False})
        result = rag.answer(FakeQueryCollection(ROWS), data(), "k",
                            client=SimpleNamespace(messages=FakeMessages(fake_response(body))))
        self.assertFalse(result["grounded"])
        self.assertEqual("MODEL_DECLINED", result["reason"])

    def test_grounded_without_citation_is_not_grounded(self):
        body = json.dumps({"summary": "유행한다.", "points": [{"text": "유행한다.", "sources": []}], "grounded": True})
        result = rag.answer(FakeQueryCollection(ROWS), data(), "k",
                            client=SimpleNamespace(messages=FakeMessages(fake_response(body))))
        self.assertFalse(result["grounded"])  # 출처 없는 답은 내보내지 않는다

    def test_recency_weight_halves_every_four_weeks(self):
        hits = [{"score": 0.8, "metadata": meta("NOW", published="2026-10-22")},
                {"score": 0.8, "metadata": meta("4W", published="2026-09-24")},
                {"score": 0.8, "metadata": meta("NO_DATE", published="")}]
        ranked = rag.rank_by_recency(hits, TODAY)
        self.assertEqual([0.8, 0.4, 0.0], [h["rank"] for h in ranked])

    def test_vague_question_puts_recent_relevant_documents_first(self):
        # 더 비슷하지만 8주 전 자료(유사도 0.80 → 순위 0.20)보다 이번 주 자료(0.70 → 0.70)가 앞선다. 기간으로 자르지는 않는다
        rows = [("OLD#0", "와이드 팬츠 오래된 기사", meta("OLD", "TEXT", "2026-08-27"), 0.20),
                ("NEW#0", "와이드 팬츠 이번 주", meta("NEW", "TEXT", "2026-10-20"), 0.30),
                ("FAR#0", "관련 없음", meta("FAR", "TEXT", "2026-10-21"), 0.60)]
        collection = FakeQueryCollection(rows)
        messages = FakeMessages(fake_response(GOOD))
        result = rag.answer(collection, data(), "k", client=SimpleNamespace(messages=messages))

        self.assertIsNone(collection.queries[0][2])           # 게시일로 자르지 않는다
        self.assertEqual(30, collection.queries[0][1])        # 후보를 넉넉히 모은다
        sent = messages.calls[0]["messages"][0]["content"]
        self.assertLess(sent.index("이번 주"), sent.index("오래된 기사"))
        self.assertIn('<source n="1" type="텍스트" media="직접 업로드" date="2026-10-20">', sent)
        self.assertNotIn("관련 없음", sent)                   # 최신이어도 기준 유사도 미달은 안 쓴다
        self.assertIn("근거 게시일 2026-08-27 ~ 2026-10-20", sent)
        self.assertEqual({"from": "2026-08-27", "to": "2026-10-20", "label": "최근 자료 우선", "explicit": False,
                          "stale": False}, result["period"])

    def test_only_old_evidence_is_marked_stale(self):
        messages = FakeMessages(fake_response(GOOD))
        result = rag.answer(FakeQueryCollection(OLD_ROWS), data(), "k", client=SimpleNamespace(messages=messages))
        self.assertTrue(result["grounded"])                   # 오래된 것이라도 관련 있으면 답한다
        self.assertTrue(result["period"]["stale"])
        self.assertIn("오래됐다", messages.calls[0]["messages"][0]["content"])

    def test_explicit_period_from_spring_is_cut_and_not_widened(self):
        collection = FakeQueryCollection(OLD_ROWS)
        messages = FakeMessages(fake_response(GOOD))
        result = rag.answer(collection, data(question="지난주 와이드 팬츠", period=LAST_WEEK), "k",
                            client=SimpleNamespace(messages=messages))
        self.assertEqual(1, len(collection.queries))
        self.assertEqual({"published_num": {"$gte": 20261012}}, collection.queries[0][2]["$and"][0])
        self.assertEqual("NO_EVIDENCE", result["reason"])
        self.assertEqual("지난주", result["period"]["label"])
        self.assertEqual([], messages.calls)

    def test_category_filters_search(self):
        rows = [("OUT#0", "와이드 팬츠와 재킷", meta("OUT", categories=("OUTER", "BOTTOM")), 0.25),
                ("BOT#0", "와이드 팬츠", meta("BOT", categories=("BOTTOM",)), 0.20)]
        collection = FakeQueryCollection(rows)
        messages = FakeMessages(fake_response(GOOD))
        rag.answer(collection, data(category="OUTER"), "k", client=SimpleNamespace(messages=messages))
        self.assertEqual({"cat_OUTER": True}, collection.queries[0][2])
        sent = messages.calls[0]["messages"][0]["content"]
        self.assertIn("와이드 팬츠와 재킷", sent)
        self.assertNotIn('"BOT"', sent)
        self.assertNotIn("<source n=\"2\"", sent)  # 하의만 있는 원문은 빠진다

    def test_trend_mode_uses_spring_facts_and_example_documents(self):
        rows = [("IMG-9#0", "데님 트러커 재킷 요약", meta("IMG-9", categories=("OUTER",)), 0.9),
                ("TXT-5#1", "본문 둘째", meta("TXT-5", "TEXT", categories=("OUTER",), chunk_index=1), 0.9),
                ("TXT-5#0", "블루종 기사 요약", meta("TXT-5", "TEXT", categories=("OUTER",)), 0.9),
                ("IMG-1#0", "관련 없음", meta("IMG-1"), 0.1)]
        collection = FakeQueryCollection(rows)
        body = json.dumps({"summary": "데님 재킷이 5건으로 가장 많다.", "grounded": True,
                           "points": [{"text": "데님 재킷: 5건(매체 1곳)", "sources": [2]}]})
        messages = FakeMessages(fake_response(body))
        period = rag.Period(date(2026, 9, 25), TODAY, "최근 4주", False)
        result = rag.answer(collection, data(question="요즘 유행하는 아우터는?", mode="TREND", category="OUTER", period=period,
                                             trend_facts=["[아이템 종류] 데님 재킷 5건 · 매체 1곳 · 직전 1건"],
                                             evidence_doc_ids=["TXT-5", "IMG-9", "NO-INDEX"]), "k",
                            client=SimpleNamespace(messages=messages))

        self.assertEqual([], collection.queries)  # 유행 질문은 문장 유사도로 고르지 않는다
        sent = messages.calls[0]["messages"][0]["content"]
        self.assertIn("<trend_facts>", sent)
        self.assertIn("데님 재킷 5건 · 매체 1곳", sent)
        self.assertIn("집계 기간: 최근 4주 (2026-09-25 ~ 2026-10-22)", sent)
        self.assertLess(sent.index("블루종 기사 요약"), sent.index("본문 둘째"))  # 청크 순서대로
        self.assertLess(sent.index('<source n="1"'), sent.index("데님 트러커 재킷 요약"))  # Spring이 정한 순서(TXT-5가 1번)
        self.assertNotIn("관련 없음", sent)
        self.assertTrue(result["grounded"])
        self.assertEqual(["IMG-9"], [s["doc_id"] for s in result["sources"]])
        self.assertIsNone(result["sources"][0]["score"])  # 유사도로 고른 게 아니다
        self.assertEqual("최근 4주", result["period"]["label"])

    def test_trend_mode_without_indexed_examples_is_no_evidence(self):
        messages = FakeMessages(fake_response(GOOD))
        period = rag.Period(date(2026, 9, 25), TODAY, "최근 4주", False)
        result = rag.answer(FakeQueryCollection([]), data(mode="TREND", period=period, evidence_doc_ids=["X"]), "k",
                            client=SimpleNamespace(messages=messages))
        self.assertEqual("NO_EVIDENCE", result["reason"])
        self.assertEqual([], messages.calls)

    def test_backfill_adds_date_numbers_and_category_flags_without_reembedding(self):
        class Store:
            updated = None

            def get(self, include):
                return {"ids": ["A#0", "B#0"], "metadatas": [
                    {"published_at": "2026-10-01", "categories": "OUTER,BOTTOM"},
                    {"published_at": "2026-10-02", "published_num": 20261002, "cat_TOP": True, "meta_v": 2}]}

            def update(self, ids, metadatas):
                Store.updated = (ids, metadatas)

        self.assertEqual(1, indexer.backfill_date_numbers(Store()))
        self.assertEqual((["A#0"], [{"published_at": "2026-10-01", "categories": "OUTER,BOTTOM", "published_num": 20261001,
                                     "cat_OUTER": True, "cat_BOTTOM": True, "meta_v": 2}]), Store.updated)

    def test_prompt_sections_loaded(self):
        self.assertIn("{today}", rag.PROMPT["period"])

    def test_system_prompt_sends_only_rules_for_the_mode(self):
        # 유행 질문엔 유행 규칙만, 검색 질문엔 검색 규칙만 — 공통 규칙은 둘 다
        trend, search = rag.system_prompt("TREND"), rag.system_prompt("SEARCH")
        for s in (trend, search):
            self.assertIn("지어내지 않는다", s)
        self.assertIn("<trend_facts>를 받았다", trend)
        self.assertNotIn("근거 원문만 받았다", trend)
        self.assertIn("근거 원문만 받았다", search)
        self.assertNotIn("[주요 아이템", search)


class RagApiTest(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(main.app)

    def test_answer_without_key_is_401(self):
        res = self.client.post("/answer", json={"question": "q", "model": "m", "min_score": 0.6})
        self.assertEqual(401, res.status_code)

    def test_blank_question_is_400(self):
        res = self.client.post("/answer", headers={"X-Claude-Api-Key": "k"},
                               json={"question": "  ", "model": "m", "min_score": 0.6})
        self.assertEqual(400, res.status_code)

    def test_brief_writes_sections_and_needs_key(self):
        # 기획 브리프 — 집계 줄·예시 원문을 받아 8개 구성 JSON. 숫자는 Spring 집계가 그리므로 스키마는 문장 칸뿐이다
        from app.rag import brief
        required = brief.SCHEMA["required"]
        for key in ("summary", "items", "colors", "fit", "materials", "evidence", "proposals", "actions"):
            self.assertIn(key, required)
        self.assertFalse(brief.SCHEMA["additionalProperties"])
        body = {"question": "26FW 기획 브리프", "model": "m", "data": ["[컬러] 블랙 10건"], "sources": [{"n": 1, "title": "t"}]}
        self.assertEqual(401, self.client.post("/brief", json=body).status_code)
        with mock.patch.object(main.briefing, "write", return_value={"brief": {"title": "t"}, "usage": {}}) as w:
            res = self.client.post("/brief", headers={"X-Claude-Api-Key": "secret"}, json=body)
        self.assertEqual(200, res.status_code)
        self.assertEqual("t", res.json()["brief"]["title"])
        self.assertEqual(["[컬러] 블랙 10건"], w.call_args.args[0].data)
        self.assertIn("[컬러] 블랙 10건", brief.build_content(w.call_args.args[0]))

    def test_agent_step_returns_blocks_and_needs_key(self):
        # 도구 경로 한 단계 — Claude 응답 블록을 그대로 돌려준다(도구 실행·반복은 Spring). 도구 3개와 허용 코드가 붙는다
        res = self.client.post("/agent/step", json={"model": "m", "messages": [{"role": "user", "content": "q"}]})
        self.assertEqual(401, res.status_code)
        from app.rag import agent
        reply = mock.Mock(stop_reason="tool_use", content=[mock.Mock(model_dump=lambda exclude_none: {
            "type": "tool_use", "id": "t1", "name": "count_trend", "input": {"category": "OUTER"}})],
            usage=mock.Mock(input_tokens=10, output_tokens=5, cache_creation_input_tokens=0, cache_read_input_tokens=0))
        with mock.patch.object(agent, "create_message", return_value=reply) as call:
            res = self.client.post("/agent/step", headers={"X-Claude-Api-Key": "secret"},
                                   json={"model": "m", "today": "2026-10-28", "messages": [{"role": "user", "content": "q"}],
                                         "categories": [{"value": "OUTER", "name": "아우터"}], "item_types": [{"value": "OUTER_PADDING", "name": "패딩"}]})
        self.assertEqual(200, res.status_code)
        self.assertEqual("count_trend", res.json()["content"][0]["name"])
        kwargs = call.call_args.kwargs
        self.assertEqual(["count_trend", "trend_series", "search_documents", "final_answer"], [t["name"] for t in kwargs["tools"]])
        self.assertIn("OUTER=아우터", kwargs["system"][0]["text"])
        self.assertNotIn("cache_control", kwargs["system"][0])  # 첫 단계는 캐시를 만들지 않는다
        self.assertEqual("medium", kwargs["output_config"]["effort"])
        with mock.patch.object(agent, "create_message", return_value=reply) as call:
            self.client.post("/agent/step", headers={"X-Claude-Api-Key": "secret"},
                             json={"model": "m", "messages": [{"role": "user", "content": "q"}], "cache": True})
        self.assertEqual({"type": "ephemeral"}, call.call_args.kwargs["system"][0]["cache_control"])
        self.assertIn("2026-10-28", self.client.get("/agent/task", params={"today": "2026-10-28"}).json()["text"])

    def test_answer_passes_key(self):
        with mock.patch.object(main.rag, "answer", return_value={"grounded": False}) as a, \
                mock.patch.object(main.vector_store, "get_collection", return_value=None):
            res = self.client.post("/answer", headers={"X-Claude-Api-Key": "secret"},
                                   json={"question": "와이드 팬츠", "model": "m", "min_score": 0.6})
        self.assertEqual(200, res.status_code)
        self.assertEqual("secret", a.call_args.args[2])
        self.assertEqual("와이드 팬츠", a.call_args.args[1].question)
        self.assertNotIn("secret", res.text)

    def test_answer_accepts_spring_interpretation(self):
        with mock.patch.object(main.rag, "answer", return_value={"grounded": False}) as a, \
                mock.patch.object(main.vector_store, "get_collection", return_value=None):
            self.client.post("/answer", headers={"X-Claude-Api-Key": "k"}, json={
                "question": "지난주 유행 아우터", "model": "m", "min_score": 0.6, "mode": "TREND", "category": "OUTER",
                "period": {"from": "2026-10-12", "to": "2026-10-18", "label": "지난주", "explicit": True},
                "trend_facts": ["[아이템 종류] 패딩 3건"], "evidence_doc_ids": ["IMG-1"]})
        sent = a.call_args.args[1]
        self.assertEqual(("TREND", "OUTER", date(2026, 10, 12), True), (sent.mode, sent.category, sent.period.start,
                                                                        sent.period.explicit))
        self.assertEqual(["IMG-1"], sent.evidence_doc_ids)


class InterpretTest(unittest.TestCase):
    """질문 해석(작은 모델) — 고를 값은 공통코드 enum, 결과는 한 번 더 정리한다."""

    def setUp(self):
        from app.rag import interpret
        self.interpret = interpret
        self.data = interpret.InterpretInput(
            question="지난달 유행한 패딩은?", model="claude-haiku-5-5", today=TODAY,
            categories=[interpret.CodeHint("OUTER", "아우터", "재킷,점퍼"), interpret.CodeHint("BOTTOM", "하의", "바지")],
            item_types=[interpret.CodeHint("OUTER_PADDING", "패딩(숏패딩·푸퍼)"), interpret.CodeHint("BOTTOM_DENIM", "데님 팬츠")])

    def test_schema_limits_codes_and_prompt_lists_hints(self):
        schema = self.interpret.build_schema(self.data)
        # enum 없이 이름으로 받는다(2026-10-29 — enum은 값마다 토큰이 든다). 목록 밖 값은 normalize가 버린다
        self.assertNotIn("enum", schema["properties"]["category"])
        self.assertNotIn("enum", schema["properties"]["item_type"])
        self.assertNotIn("question", schema["properties"])  # 앞 대화가 없으면 다시 쓴 질문을 받지 않는다
        system = self.interpret.system_prompt(self.data)
        self.assertIn("- category: 아우터(재킷,점퍼), 하의(바지)", system[1]["text"])  # 허용 값은 이름만(영문 코드값 없음)
        self.assertNotIn("OUTER", system[1]["text"])
        self.assertNotIn("cache_control", system[1])  # 캐시는 걸지 않는다(사용자 결정)
        self.assertNotIn("이어 묻기(<previous>", system[0]["text"])  # 앞 대화가 없으면 이어 묻기 규칙을 보내지 않는다
        with_history = self.interpret.InterpretInput(**{**self.data.__dict__, "history": [self.interpret.Turn("요즘 유행하는 아우터는?", True, "OUTER")]})
        self.assertIn("이어 묻기(<previous>", self.interpret.system_prompt(with_history)[0]["text"])
        content = self.interpret.build_content(self.data)
        self.assertNotIn("아우터(재킷", content)
        self.assertIn("오늘: 2026-10-22 (목요일)", content)
        r = self.interpret.normalize({"trend": True, "category": "", "item_type": "SHOES_SNEAKER", "has_period": False}, self.data)
        self.assertIsNone(r["item_type"])

    def test_normalize_item_type_wins_and_bad_periods_are_dropped(self):
        n = self.interpret.normalize
        data = self.interpret.InterpretInput(**{**self.data.__dict__, "question": "9월에 유행한 패딩은?"})  # 표준 표현이 없으면 모델 기간
        r = n({"trend": True, "category": "하의", "item_type": "패딩", "period": "2026-09-01~2026-09-30 지난달(9월)"}, data)
        self.assertEqual(("OUTER", "OUTER_PADDING"), (r["category"], r["item_type"]))
        self.assertEqual({"from": "2026-09-01", "to": "2026-09-30", "label": "지난달(9월)", "explicit": True}, r["period"])
        for bad in ("2026-09-30~2026-09-01 x", "2026-10-01~2026-12-31 x", "어제~오늘", "2026-09-31~2026-10-01"):
            r = n({"trend": False, "category": "", "item_type": "", "period": bad}, data)
            self.assertIsNone(r["period"], bad)
            self.assertIsNone(r["category"])

    def test_standard_period_is_computed_by_code(self):
        # 표준 기간 표현은 코드가 계산한다 — 비교 기준("대비·보다")은 기간이 아니고, 두 해·해+시즌은 모델에 맡긴다
        p = self.interpret.standard_period
        self.assertEqual("COMPARE", p("지난달 대비 급상승한 아이템은?", TODAY))
        self.assertEqual("COMPARE", p("작년보다 늘어난 컬러", TODAY))
        self.assertEqual(("2026-09-01", "2026-09-30"), (p("지난달은?", TODAY)["from"], p("지난달은?", TODAY)["to"]))
        self.assertEqual(("2026-10-12", "2026-10-18"), (p("지난주 인기 아이템", TODAY)["from"], p("지난주 인기 아이템", TODAY)["to"]))
        self.assertEqual("2026-09-23", p("최근 30일 컬러", TODAY)["from"])
        season = p("26FW 니트 특징은?", TODAY)
        self.assertEqual(("2026-04-23", "2026-10-22", "최근 6개월(시즌 표현)"), (season["from"], season["to"], season["label"]))
        self.assertIsNone(p("요즘 유행하는 아우터는?", TODAY))
        self.assertIsNone(p("올해 F/W랑 작년 F/W 컬러 비교", TODAY))
        self.assertIsNone(p("작년 F/W 컬러", TODAY))
        # 비교 기준만 있으면 모델이 기간을 줘도 쓰지 않는다
        data = self.interpret.InterpretInput(**{**self.data.__dict__, "question": "지난달 대비 급상승한 아이템은?"})
        r = self.interpret.normalize({"trend": True, "period": "2026-09-01~2026-09-30 지난달", "change": "UP"}, data)
        self.assertIsNone(r["period"])
        self.assertEqual("UP", r["change"])

    def test_code_rules_moved_out_of_prompt(self):
        # 판단이 필요 없는 규칙은 코드가 보장한다(2026-10-29 입력 토큰 최적화)
        I = self.interpret
        data = I.InterpretInput(**{**self.data.__dict__, "question": "데님 팬츠 컬러",
                                   "materials": [I.CodeHint("DENIM", "데님"), I.CodeHint("LEATHER", "레더")],
                                   "colors": [I.CodeHint("BLACK", "블랙")]})
        # 패션 외 질문 — 나머지 칸은 비운다
        r = I.normalize({"fashion": False, "trend": True, "category": "OUTER", "terms": ["밈"], "focus": "COLOR"}, data)
        self.assertEqual((False, False, None, [], None), (r["fashion"], r["trend"], r["category"], r["terms"], r["focus"]))
        # 묻는 축의 값·종류 이름에 든 소재는 뺀다
        r = I.normalize({"trend": True, "item_type": "데님 팬츠", "attributes": ["데님", "레더", "블랙"], "focus": "COLOR"}, data)
        self.assertEqual(["LEATHER"], r["materials"])
        self.assertEqual([], r["colors"])
        # 앞 대화가 없으면 질문 그대로, terms의 축 이름·일반어·카테고리 말은 거른다(대상 이름은 남는다)
        r = I.normalize({"trend": True, "question": "다르게 쓴 질문", "terms": ["컬러", "유행", "아우터", "재킷", "무스탕", "레더 재킷"]}, data)
        self.assertEqual("데님 팬츠 컬러", r["question"])
        self.assertEqual(["무스탕", "레더 재킷"], r["terms"])

    def test_seasons_are_optional_and_limited_to_codes(self):
        # 시즌 코드를 받으면 스키마·프롬프트에 넣고 허용 코드만 남긴다. 예전 Spring(시즌 안 보냄)이면 칸이 없다
        self.assertNotIn("seasons", self.interpret.build_schema(self.data)["properties"])
        data = self.interpret.InterpretInput(
            question="26FW 핵심 컬러는?", model="m", today=TODAY, categories=self.data.categories, item_types=self.data.item_types,
            seasons=[self.interpret.CodeHint("SPRING", "봄"), self.interpret.CodeHint("FALL", "가을"), self.interpret.CodeHint("WINTER", "겨울")])
        schema = self.interpret.build_schema(data)
        self.assertEqual({"type": "array", "items": {"type": "string"}}, schema["properties"]["seasons"])
        self.assertIn("seasons", schema["required"])
        self.assertIn("- seasons: 봄, 가을, 겨울", self.interpret.allowed_values_text(data))
        r = self.interpret.normalize({"trend": True, "category": "", "item_type": "",
                                      "seasons": ["겨울", "여름", "가을", "WINTER"]}, data)
        self.assertEqual(["FALL", "WINTER"], r["seasons"])
        # 시즌 약어는 코드가 채운다 — "26FW"에 모델이 겨울만 골라도 가을·겨울(목록에 없는 SUMMER는 빠진다)
        self.assertEqual(["FALL", "WINTER"], self.interpret.normalize({"trend": True, "seasons": ["겨울"]}, data)["seasons"])
        self.assertEqual(["SPRING"], self.interpret.normalize(
            {"trend": True, "seasons": []}, self.interpret.InterpretInput(**{**data.__dict__, "question": "27SS 컬러"}))["seasons"])

    def test_attribute_filters_are_optional_and_limited_to_codes(self):
        # 대상을 좁히는 속성 값 — 코드를 받으면 칸이 생기고 허용 코드만 남는다. 예전 Spring(안 보냄)이면 칸이 없다
        self.assertNotIn("attributes", self.interpret.build_schema(self.data)["properties"])
        data = self.interpret.InterpretInput(
            question="와이드 팬츠 유행해?", model="m", today=TODAY, categories=self.data.categories, item_types=self.data.item_types,
            silhouettes=[self.interpret.CodeHint("WIDE", "와이드"), self.interpret.CodeHint("CROPPED", "크롭")],
            colors=[self.interpret.CodeHint("BLACK", "블랙")])
        schema = self.interpret.build_schema(data)
        # 속성 값은 attributes 한 칸에 이름으로 — normalize가 칸별 코드로 나눈다. 후보가 없는 칸은 목록에 싣지 않는다
        self.assertEqual({"type": "array", "items": {"type": "string"}}, schema["properties"]["attributes"])
        text = self.interpret.allowed_values_text(data)
        self.assertIn("- attributes(실루엣): 와이드, 크롭", text)
        self.assertNotIn("attributes(소재)", text)
        r = self.interpret.normalize({"trend": True, "category": "하의", "item_type": "",
                                      "attributes": ["슬림", "와이드", "WIDE"]}, data)
        self.assertEqual((["WIDE"], [], []), (r["silhouettes"], r["colors"], r["materials"]))

    def test_names_map_to_codes_and_details_list_is_sent_only_when_asked(self):
        # 이름·괄호 안 같은 뜻 말·코드값 모두 코드값으로. 디테일 목록은 질문에 디테일 말이 있을 때만(종류 이름 속 말은 빼고 본다)
        I = self.interpret
        details = [I.CodeHint("NECK_HALF", "하프넥(하이넥·모크넥)"), I.CodeHint("DECO_POCKET", "포켓 강조(카고·패치)"),
                   I.CodeHint("NECK_HOOD", "후드")]
        items = self.data.item_types + [I.CodeHint("BOTTOM_CARGO", "카고 팬츠"), I.CodeHint("TOP_HOODIE", "후드티")]

        def data(q):
            return I.InterpretInput(question=q, model="m", today=TODAY, categories=self.data.categories, item_types=items,
                                    colors=[I.CodeHint("BLACK", "블랙")], patterns=[I.CodeHint("CHECK", "체크(깅엄·타탄)")],
                                    details=details)
        self.assertEqual(["colors", "patterns"], I._attribute_groups(data("요즘 유행하는 아우터는?")))
        self.assertEqual(["colors", "patterns"], I._attribute_groups(data("카고 팬츠 유행해?")))  # "카고"는 종류 이름 속 말
        self.assertEqual(["colors", "patterns"], I._attribute_groups(data("후드티 인기야?")))
        self.assertIn("details", I._attribute_groups(data("모크넥 니트 유행해?")))
        self.assertIn("details", I._attribute_groups(data("요즘 디테일 트렌드는?")))
        self.assertNotIn("attributes(디테일)", I.allowed_values_text(data("요즘 유행하는 아우터는?")))
        r = I.normalize({"trend": True, "category": "바지", "item_type": "숏패딩", "attributes": ["깅엄", "BLACK", "하이넥"]},
                        data("깅엄 숏패딩"))
        self.assertEqual(("OUTER", "OUTER_PADDING", ["CHECK"], ["BLACK"]), (r["category"], r["item_type"], r["patterns"], r["colors"]))
        self.assertEqual([], r["details"])  # 디테일 목록을 안 보냈으면 디테일 값도 받지 않는다
        r = I.normalize({"trend": True, "category": "바지", "item_type": "", "attributes": ["모크넥"]}, data("모크넥 바지"))
        self.assertEqual(("BOTTOM", ["NECK_HALF"]), (r["category"], r["details"]))

    def test_focus_is_limited_to_axes(self):
        # 묻는 축 — 정해진 값만, 아니면 None
        n = self.interpret.normalize
        base = {"trend": True, "category": "", "item_type": "", "has_period": False}
        self.assertEqual("SILHOUETTE", n({**base, "focus": "SILHOUETTE"}, self.data)["focus"])
        self.assertIsNone(n({**base, "focus": ""}, self.data)["focus"])
        self.assertIsNone(n({**base, "focus": "KEYWORD"}, self.data)["focus"])
        self.assertNotIn("enum", self.interpret.build_schema(self.data)["properties"]["focus"])  # 값 검사는 normalize

    def test_change_is_only_for_trend_questions(self):
        # 증감을 묻는지 — 정해진 값만, 유행 질문이 아니면 None
        n = self.interpret.normalize
        base = {"trend": True, "category": "", "item_type": "", "has_period": False}
        self.assertEqual("UP", n({**base, "change": "UP"}, self.data)["change"])
        self.assertIsNone(n({**base, "change": ""}, self.data)["change"])
        self.assertIsNone(n({**base, "change": "SIDEWAYS"}, self.data)["change"])
        self.assertIsNone(n({**base, "trend": False, "change": "UP"}, self.data)["change"])
        self.assertIn("change", self.interpret.build_schema(self.data)["required"])

    def test_terms_are_cleaned_deduped_and_capped(self):
        # 코드로 못 옮긴 단어 — 공백 정리, 공백·대소문자만 다른 중복·빈 값·너무 긴 값은 빼고 3개까지. 빠지면 빈 배열
        n = self.interpret.normalize
        r = n({"trend": True, "category": "OUTER", "item_type": "", "has_period": False,
               "terms": [" 무스탕 ", "무 스탕", "", "가" * 21, "발마칸  코트", "고프코어", "카고 포켓"]}, self.data)
        self.assertEqual(["무스탕", "발마칸 코트", "고프코어"], r["terms"])
        self.assertEqual([], n({"trend": False, "category": "", "item_type": "", "has_period": False}, self.data)["terms"])
        self.assertIn("terms", self.interpret.build_schema(self.data)["required"])

    def test_history_goes_into_prompt_and_rewritten_question_comes_back(self):
        # 이어 묻기 — 앞 질문·해석을 프롬프트에 넣고, 다시 쓴 질문을 돌려준다(비었거나 너무 길면 원래 질문)
        data = self.interpret.InterpretInput(
            question="그럼 하의는?", model="m", today=TODAY, categories=self.data.categories, item_types=self.data.item_types,
            history=[self.interpret.Turn("요즘 유행하는 아우터는?", True, "OUTER"),
                     self.interpret.Turn("지난달 유행한 패딩은?", True, "OUTER", "OUTER_PADDING", "지난달(9월)", "2026-09-01", "2026-09-30")])
        content = self.interpret.build_content(data)
        self.assertIn("<previous>", content)
        self.assertLess(content.index("요즘 유행하는 아우터는?"), content.index("지난달 유행한 패딩은?"))  # 마지막이 바로 앞
        self.assertIn("category=아우터, item_type=패딩, 기간=지난달(9월)(2026-09-01~2026-09-30)", content)  # 코드값은 이름으로
        self.assertLess(content.index("</previous>"), content.index("<question>"))
        self.assertNotIn("<previous>", self.interpret.build_content(self.data))  # 앞 대화가 없으면 없다
        self.assertIn("question", self.interpret.build_schema(data)["required"])
        n = self.interpret.normalize
        self.assertEqual("요즘 유행하는 하의는?", n({"question": " 요즘 유행하는 하의는? "}, data)["question"])
        self.assertEqual("그럼 하의는?", n({"question": ""}, data)["question"])
        self.assertEqual("그럼 하의는?", n({"question": "가" * 501}, data)["question"])

    def test_api_passes_recent_history_only(self):
        seen = {}

        def fake_interpret(data, key):
            seen["history"] = data.history
            return {"question": data.question, "fashion": True, "trend": True, "category": None, "item_type": None,
                    "period": None, "usage": {}}

        history = [{"question": f"질문{i}", "trend": True, "category": "OUTER"} for i in range(5)] + [{"question": "  "}]
        with mock.patch.object(main.interpreter, "interpret", fake_interpret):
            res = TestClient(main.app).post("/interpret", headers={"X-Claude-Api-Key": "k"}, json={
                "question": "그럼 하의는?", "model": "m", "history": history})
        self.assertEqual(200, res.status_code, res.text)
        # 최근 3건만(빈 질문은 버린다) — 오래된 질문에 이어 붙이지 않게
        self.assertEqual(["질문3", "질문4"], [t.question for t in seen["history"]])

    def test_fashion_flag(self):
        # 패션 질문이 아니면 Spring이 답하지 않는다 — 칸이 빠졌으면 패션 질문으로 본다(잘못 막지 않게)
        schema = self.interpret.build_schema(self.data)
        self.assertIn("fashion", schema["required"])
        n = self.interpret.normalize
        self.assertFalse(n({"fashion": False, "trend": True}, self.data)["fashion"])
        self.assertTrue(n({"fashion": True}, self.data)["fashion"])
        self.assertTrue(n({"trend": True}, self.data)["fashion"])

    def test_interpret_calls_small_model_with_low_effort(self):
        body = json.dumps({"fashion": True, "trend": True, "category": "아우터", "item_type": "", "period": "", "terms": [],
                           "focus": "", "change": ""})
        messages = FakeMessages(fake_response(body))
        result = self.interpret.interpret(self.data, "k", client=SimpleNamespace(messages=messages))
        self.assertEqual("claude-haiku-5-5", messages.calls[0]["model"])
        self.assertEqual("low", messages.calls[0]["output_config"]["effort"])
        self.assertTrue(result["trend"])
        self.assertEqual("OUTER", result["category"])  # 이름 → 코드값
        # 질문의 "지난달"은 모델이 has_period=false로 줘도 코드가 계산한다(표준 기간 표현 — 2026-10-29)
        self.assertEqual({"from": "2026-09-01", "to": "2026-09-30", "label": "지난달(9월)", "explicit": True}, result["period"])
        self.assertIn("usage", result)

    def test_api_requires_key(self):
        res = TestClient(main.app).post("/interpret", json={"question": "q", "model": "m"})
        self.assertEqual(401, res.status_code)

    def test_api_accepts_null_words(self):
        # 공통코드 속성이 비면 Spring이 words: null로 보낸다 — 422로 거부해 늘 규칙 해석으로 빠졌다(2026-10-22)
        seen = {}

        def fake_interpret(data, key):
            seen["categories"] = data.categories
            return {"trend": True, "category": None, "item_type": None, "period": None, "usage": {}}

        with mock.patch.object(main.interpreter, "interpret", fake_interpret):
            res = TestClient(main.app).post("/interpret", headers={"X-Claude-Api-Key": "k"}, json={
                "question": "요즘 유행하는 아우터는?", "model": "m",
                "categories": [{"value": "OUTER", "name": "아우터", "words": None}],
                "item_types": [{"value": "OUTER_PADDING", "name": "패딩", "words": None}]})
        self.assertEqual(200, res.status_code, res.text)
        self.assertEqual("", seen["categories"][0].words)
