"""FastAPI 엔드포인트 테스트 — Claude 호출과 Chroma는 가짜로 바꿔 끼운다. 실행: .venv\\Scripts\\python -m unittest"""
import base64
import io
import unittest
from unittest import mock

from fastapi.testclient import TestClient
from PIL import Image

from app import main
from app.core.claude import AiServiceError
from tests.test_core import FakeCollection

ALLOWED = {
    "categories": [{"value": "OUTER", "name": "아우터"}],
    "item_types": [{"value": "OUTER_PADDING", "name": "패딩"}],
    "materials": [{"value": "LEATHER", "name": "레더"}],
    "colors": [{"value": "BROWN", "name": "브라운"}],
    "silhouettes": [{"value": "OVERSIZED", "name": "오버사이즈"}],
    "seasons": [{"value": "FALL", "name": "가을"}],
}


def png_base64() -> str:
    buffer = io.BytesIO()
    Image.new("RGB", (20, 20)).save(buffer, format="PNG")
    return base64.b64encode(buffer.getvalue()).decode()


class ApiTest(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(main.app)

    def test_health(self):
        self.assertEqual("ok", self.client.get("/health").json()["status"])

    def test_structure_without_api_key_is_401(self):
        res = self.client.post("/structure", json={"doc_id": "T", "doc_type": "TEXT", "model": "m", "text": "a",
                                                   "allowed": ALLOWED})
        self.assertEqual(401, res.status_code)
        self.assertFalse(res.json()["detail"]["retryable"])

    def test_structure_passes_key_and_returns_result(self):
        with mock.patch.object(main, "structure", return_value=({"category": "OUTER"}, {"input_tokens": 1, "output_tokens": 1})) as s:
            res = self.client.post("/structure", headers={"X-Claude-Api-Key": "secret"},
                                   json={"doc_id": "IMG-1", "doc_type": "IMAGE", "model": "claude-sonnet-5",
                                         "images": [{"data": png_base64(), "media_type": "image/png"}], "allowed": ALLOWED})
        self.assertEqual(200, res.status_code)
        self.assertEqual("OUTER", res.json()["result"]["category"])
        self.assertEqual("secret", s.call_args.args[1])
        self.assertNotIn("secret", res.text)

    def test_structuring_error_keeps_retryable_flag(self):
        with mock.patch.object(main, "structure", side_effect=AiServiceError("일시 오류", retryable=True)):
            res = self.client.post("/structure", headers={"X-Claude-Api-Key": "k"},
                                   json={"doc_id": "T", "doc_type": "TEXT", "model": "m", "text": "본문", "allowed": ALLOWED})
        self.assertEqual(502, res.status_code)
        self.assertTrue(res.json()["detail"]["retryable"])
        self.assertNotIn("usage", res.json()["detail"])  # Claude 응답 전 실패면 사용량이 없다

    def test_structuring_error_passes_usage_when_tokens_were_spent(self):
        error = AiServiceError("거절", retryable=False, status_code=422, usage={"input_tokens": 7, "output_tokens": 3})
        with mock.patch.object(main, "structure", side_effect=error):
            res = self.client.post("/structure", headers={"X-Claude-Api-Key": "k"},
                                   json={"doc_id": "T", "doc_type": "TEXT", "model": "m", "text": "본문", "allowed": ALLOWED})
        self.assertEqual(422, res.status_code)
        self.assertEqual(7, res.json()["detail"]["usage"]["input_tokens"])

    def test_empty_text_and_broken_image_are_400(self):
        headers = {"X-Claude-Api-Key": "k"}
        self.assertEqual(400, self.client.post("/structure", headers=headers, json={
            "doc_id": "T", "doc_type": "TEXT", "model": "m", "text": "  ", "allowed": ALLOWED}).status_code)
        self.assertEqual(400, self.client.post("/structure", headers=headers, json={
            "doc_id": "I", "doc_type": "IMAGE", "model": "m",
            "images": [{"data": base64.b64encode(b"not image").decode(), "media_type": "image/png"}],
            "allowed": ALLOWED}).status_code)
        self.assertEqual(400, self.client.post("/structure", headers=headers, json={  # 이미지 원문인데 이미지가 없다
            "doc_id": "I", "doc_type": "IMAGE", "model": "m", "allowed": ALLOWED}).status_code)

    def test_text_with_images_is_one_request(self):
        with mock.patch.object(main, "structure", return_value=({"items": []}, {"input_tokens": 1})) as s:
            res = self.client.post("/structure", headers={"X-Claude-Api-Key": "k"}, json={
                "doc_id": "TXT-1", "doc_type": "TEXT", "model": "m", "text": "버건디 레더 재킷",
                "images": [{"data": png_base64(), "media_type": "image/png"}] * 2, "allowed": ALLOWED})
        self.assertEqual(200, res.status_code)
        data = s.call_args.args[0]
        self.assertEqual(2, len(data.images))
        self.assertEqual("버건디 레더 재킷", data.text)

    def test_more_than_five_images_is_422(self):
        res = self.client.post("/structure", headers={"X-Claude-Api-Key": "k"}, json={
            "doc_id": "TXT-1", "doc_type": "TEXT", "model": "m", "text": "본문",
            "images": [{"data": png_base64(), "media_type": "image/png"}] * 6, "allowed": ALLOWED})
        self.assertEqual(422, res.status_code)

    def test_index_and_delete(self):
        collection = FakeCollection()
        with mock.patch("app.indexing.vector_store.get_collection", return_value=collection):
            res = self.client.post("/index", json={"doc_id": "IMG-1", "doc_type": "IMAGE",
                                                   "structured": {"summary": "요약", "keywords": ["k"]}})
            self.assertEqual(1, res.json()["chunks"])
            self.assertEqual(200, self.client.delete("/index/IMG-1").status_code)
        self.assertEqual({"doc_id": "IMG-1"}, collection.deleted[-1])

    def test_index_stats_and_chunks_are_read_only_views(self):
        collection = mock.Mock(metadata={"embedding_model": "Qwen/Qwen3-VL-Embedding-2B"})
        collection.count.return_value = 1
        collection.get.return_value = {
            "ids": ["IMG-1#0"], "documents": ["요약"], "metadatas": [{"doc_id": "IMG-1", "chunk_index": 0}],
            "embeddings": [[0.1] * 2048],
        }
        with mock.patch("app.indexing.vector_store.get_collection", return_value=collection):
            stats = self.client.get("/index/stats").json()
            items = self.client.get("/index/chunks", params={"doc_id": "IMG-1", "with_embedding": "true"}).json()
        self.assertEqual(1, stats["chunks"])
        self.assertEqual("Qwen/Qwen3-VL-Embedding-2B", stats["embedding_model"])
        self.assertEqual("요약", items[0]["document"])
        self.assertEqual(2048, items[0]["embedding_dim"])
        self.assertEqual(8, len(items[0]["embedding_preview"]))  # 벡터 전체는 싣지 않는다
        self.assertEqual({"doc_id": "IMG-1"}, collection.get.call_args.kwargs["where"])
        collection.add.assert_not_called()
        collection.delete.assert_not_called()


if __name__ == "__main__":
    unittest.main()
