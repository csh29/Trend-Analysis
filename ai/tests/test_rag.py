"""P-06 RAG 테스트(네트워크·실제 Claude·Chroma 없음). 실행: .venv\\Scripts\\python -m unittest"""
import json
import unittest
from datetime import date
from types import SimpleNamespace
from unittest import mock

from fastapi.testclient import TestClient

from app.indexing import indexer
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

    def query(self, query_texts, n_results, include, where=None):
        self.queries.append((query_texts, n_results, where))
        rows = [r for r in self.rows if self._match(r[2], where)][:n_results]
        return {"ids": [[r[0] for r in rows]], "documents": [[r[1] for r in rows]],
                "metadatas": [[r[2] for r in rows]], "distances": [[r[3] for r in rows]]}

    def get(self, where=None, include=None):
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
                           stats_week="2026-10-19",
                           stats=[rag.StatRow("키워드", "와이드 팬츠", 9, 3, 200.0, True, False)], today=TODAY, **kwargs)


LAST_WEEK = rag.Period(date(2026, 10, 12), date(2026, 10, 18), "지난주", True)


OLD_ROWS = [("TXT-OLD#0", "와이드 팬츠가 유행", meta("TXT-OLD", "TEXT", "2026-06-01"), 0.25)]
GOOD = json.dumps({"summary": "와이드 팬츠", "grounded": True, "points": [{"text": "줄", "sources": [1]}]})


class DateNumberTest(unittest.TestCase):
    """기간 해석은 Spring(QuestionInterpreter)이 한다. 여기서는 Chroma 필터용 게시일 숫자만."""

    def test_date_number(self):
        self.assertEqual(20261022, indexer.date_number("2026-10-22"))
        self.assertEqual(0, indexer.date_number(""))
        self.assertEqual(0, indexer.date_number("unknown"))


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
        self.assertIn("키워드 와이드 팬츠: 9건 (전주 3건, 전주 대비 +200.0%) 급상승", sent)
        self.assertIn("2026-10-19", sent)
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
        self.assertIn("{week}", rag.PROMPT["stats_header"])


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

    def test_answer_passes_key(self):
        with mock.patch.object(main.rag, "answer", return_value={"grounded": False}) as a, \
                mock.patch.object(main.vector_store, "get_collection", return_value=None):
            res = self.client.post("/answer", headers={"X-Claude-Api-Key": "secret"},
                                   json={"question": "와이드 팬츠", "model": "m", "min_score": 0.6,
                                         "stats": [{"type_label": "키워드", "label": "와이드 팬츠", "count": 3}]})
        self.assertEqual(200, res.status_code)
        self.assertEqual("secret", a.call_args.args[2])
        self.assertEqual("와이드 팬츠", a.call_args.args[1].stats[0].label)
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
        self.assertEqual(["", "OUTER", "BOTTOM"], schema["properties"]["category"]["enum"])
        self.assertEqual(["", "OUTER_PADDING", "BOTTOM_DENIM"], schema["properties"]["item_type"]["enum"])
        content = self.interpret.build_content(self.data)
        self.assertIn("OUTER=아우터(재킷,점퍼)", content)
        self.assertIn("오늘: 2026-10-22 (목요일)", content)

    def test_normalize_item_type_wins_and_bad_periods_are_dropped(self):
        n = self.interpret.normalize
        r = n({"trend": True, "category": "BOTTOM", "item_type": "OUTER_PADDING", "has_period": True,
               "period_from": "2026-09-01", "period_to": "2026-09-30", "period_label": "지난달(9월)"}, self.data)
        self.assertEqual(("OUTER", "OUTER_PADDING"), (r["category"], r["item_type"]))
        self.assertEqual({"from": "2026-09-01", "to": "2026-09-30", "label": "지난달(9월)", "explicit": True}, r["period"])
        for bad in (("2026-09-30", "2026-09-01"), ("2026-10-01", "2026-12-31"), ("어제", "오늘")):
            r = n({"trend": False, "category": "", "item_type": "", "has_period": True,
                   "period_from": bad[0], "period_to": bad[1], "period_label": "x"}, self.data)
            self.assertIsNone(r["period"], bad)
            self.assertIsNone(r["category"])

    def test_interpret_calls_small_model_with_low_effort(self):
        body = json.dumps({"trend": True, "category": "OUTER", "item_type": "", "has_period": False,
                           "period_from": "", "period_to": "", "period_label": ""})
        messages = FakeMessages(fake_response(body))
        result = self.interpret.interpret(self.data, "k", client=SimpleNamespace(messages=messages))
        self.assertEqual("claude-haiku-5-5", messages.calls[0]["model"])
        self.assertEqual("low", messages.calls[0]["output_config"]["effort"])
        self.assertTrue(result["trend"])
        self.assertIsNone(result["period"])
        self.assertIn("usage", result)

    def test_api_requires_key(self):
        res = TestClient(main.app).post("/interpret", json={"question": "q", "model": "m"})
        self.assertEqual(401, res.status_code)
