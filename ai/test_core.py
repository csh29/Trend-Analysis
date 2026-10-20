"""AI 서비스 순수 로직 테스트(네트워크·실제 Claude 호출 없음). 실행: .venv\\Scripts\\python -m unittest"""
import base64
import io
import os
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import anthropic
from PIL import Image

import chunker
import config
import embedding
import image_prep
import indexer
import vector_store
from structurer import CodeOption, StructureInput, StructuringError, build_content, build_schema, structure


def sample_input(doc_type="TEXT") -> StructureInput:
    return StructureInput(
        doc_id="TXT-1", doc_type=doc_type, model="claude-sonnet-5", title="제목", text="레더 재킷이 유행",
        image_base64="AAAA" if doc_type == "IMAGE" else None,
        image_media_type="image/png" if doc_type == "IMAGE" else None,
        categories=[CodeOption("OUTER", "아우터"), CodeOption("ETC", "기타")],
        materials=[CodeOption("LEATHER", "레더(가죽)")],
        colors=[CodeOption("BROWN", "브라운")],
        silhouettes=[CodeOption("OVERSIZED", "오버사이즈")],
    )


class FakeMessages:
    def __init__(self, response=None, error=None):
        self.response, self.error, self.calls = response, error, []

    def create(self, **kwargs):
        self.calls.append(kwargs)
        if self.error:
            raise self.error
        return self.response


def fake_response(text='{"category": "OUTER"}', stop_reason="end_turn", stop_details=None):
    return SimpleNamespace(
        content=[SimpleNamespace(type="text", text=text)], stop_reason=stop_reason, stop_details=stop_details,
        usage=SimpleNamespace(input_tokens=10, output_tokens=5))


class ConfigTest(unittest.TestCase):
    def test_env_file_and_environment_override(self):
        with tempfile.TemporaryDirectory() as d:
            env = Path(d) / ".env"
            env.write_text("CHROMA_MODE=http  # 주석\nCHROMA_PORT=9000\nCHROMA_PATH=./x\n", encoding="utf-8")
            with mock.patch.dict(os.environ, {"CHROMA_PORT": "9100"}, clear=False):
                s = config.load_settings(env)
            self.assertEqual("http", s.chroma_mode)
            self.assertEqual(9100, s.chroma_port)  # 환경변수가 파일을 이긴다
            self.assertTrue(Path(s.chroma_path).is_absolute())  # 상대경로는 ai/ 기준으로 바꾼다

    def test_invalid_mode_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            env = Path(d) / ".env"
            env.write_text("CHROMA_MODE=cloud\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                config.load_settings(env)


class VectorStoreTest(unittest.TestCase):
    """내장형/서버형 전환이 설정값만으로 되는지 — 클라이언트를 만드는 곳은 create_client 하나다."""

    def test_embedded_mode_uses_persistent_client(self):
        conf = mock.Mock(chroma_mode="embedded", chroma_path="/tmp/x")
        with mock.patch("chromadb.PersistentClient") as persistent, mock.patch("chromadb.HttpClient") as http:
            vector_store.create_client(conf)
        persistent.assert_called_once_with(path="/tmp/x")
        http.assert_not_called()

    def test_http_mode_uses_http_client(self):
        conf = mock.Mock(chroma_mode="http", chroma_host="chroma.internal", chroma_port=8000)
        with mock.patch("chromadb.PersistentClient") as persistent, mock.patch("chromadb.HttpClient") as http:
            vector_store.create_client(conf)
        http.assert_called_once_with(host="chroma.internal", port=8000)
        persistent.assert_not_called()


class ChunkerTest(unittest.TestCase):
    def test_short_text_is_one_chunk(self):
        self.assertEqual(["짧은 글"], chunker.split_text("  짧은 글 ", 500, 80))

    def test_long_text_is_split_with_overlap_and_covers_everything(self):
        text = " ".join(f"문장{i}입니다." for i in range(300))
        chunks = chunker.split_text(text, 200, 40)
        self.assertGreater(len(chunks), 1)
        self.assertTrue(all(len(c) <= 200 for c in chunks))
        self.assertIn("문장299입니다.", chunks[-1])

    def test_empty_text(self):
        self.assertEqual([], chunker.split_text("   ", 500, 80))


class ImagePrepTest(unittest.TestCase):
    @staticmethod
    def image_bytes(size, fmt):
        buffer = io.BytesIO()
        Image.new("RGB", size, (200, 30, 60)).save(buffer, format=fmt)
        return buffer.getvalue()

    def test_small_supported_image_is_sent_as_is(self):
        raw = self.image_bytes((100, 80), "PNG")
        data, media = image_prep.prepare(raw, "image/png")
        self.assertEqual("image/png", media)
        self.assertEqual(raw, base64.b64decode(data))

    def test_large_image_is_downscaled_to_long_edge(self):
        data, media = image_prep.prepare(self.image_bytes((4000, 3000), "PNG"), "image/png")
        self.assertEqual("image/jpeg", media)
        self.assertEqual(image_prep.MAX_LONG_EDGE, max(Image.open(io.BytesIO(base64.b64decode(data))).size))

    def test_bmp_is_converted(self):
        _, media = image_prep.prepare(self.image_bytes((50, 50), "BMP"), "image/bmp")
        self.assertEqual("image/jpeg", media)


class StructurerTest(unittest.TestCase):
    def test_schema_limits_values_to_codes(self):
        schema = build_schema(sample_input())
        self.assertEqual(["OUTER", "ETC"], schema["properties"]["category"]["enum"])
        self.assertEqual(["LEATHER"], schema["properties"]["materials"]["items"]["enum"])
        self.assertFalse(schema["additionalProperties"])
        self.assertNotIn("source", schema["properties"])  # 출처는 LLM이 만들지 않는다

    def test_image_content_puts_image_first(self):
        content = build_content(sample_input("IMAGE"))
        self.assertEqual("image", content[0]["type"])
        self.assertIn("LEATHER=레더(가죽)", content[-1]["text"])

    def test_successful_call_uses_structured_output(self):
        messages = FakeMessages(fake_response())
        result, usage = structure(sample_input(), "k", client=SimpleNamespace(messages=messages))
        self.assertEqual({"category": "OUTER"}, result)
        self.assertEqual(10, usage["input_tokens"])
        self.assertEqual(0, usage["cache_read_input_tokens"])  # 캐시 필드가 없는 응답도 0으로 채운다
        self.assertIn("elapsed_ms", usage)
        call = messages.calls[0]
        self.assertEqual("claude-sonnet-5", call["model"])
        self.assertEqual("json_schema", call["output_config"]["format"]["type"])

    def test_refusal_is_not_retryable(self):
        response = fake_response(stop_reason="refusal", stop_details=SimpleNamespace(category="cyber"))
        with self.assertRaises(StructuringError) as ctx:
            structure(sample_input(), "k", client=SimpleNamespace(messages=FakeMessages(response)))
        self.assertFalse(ctx.exception.retryable)
        self.assertEqual(10, ctx.exception.usage["input_tokens"])  # 거절돼도 과금된 토큰은 넘긴다

    def test_truncated_or_broken_json_is_retryable(self):
        for response in (fake_response(stop_reason="max_tokens"), fake_response(text="{broken")):
            with self.assertRaises(StructuringError) as ctx:
                structure(sample_input(), "k", client=SimpleNamespace(messages=FakeMessages(response)))
            self.assertTrue(ctx.exception.retryable)

    def test_connection_error_is_retryable(self):
        error = anthropic.APIConnectionError(request=mock.Mock())
        with self.assertRaises(StructuringError) as ctx:
            structure(sample_input(), "k", client=SimpleNamespace(messages=FakeMessages(error=error)))
        self.assertTrue(ctx.exception.retryable)


class FakeCollection:
    def __init__(self):
        self.added, self.deleted = [], []

    def add(self, ids, documents, metadatas):
        self.added.append((ids, documents, metadatas))

    def delete(self, where):
        self.deleted.append(where)


class IndexerTest(unittest.TestCase):
    structured = {"category": "OUTER", "keywords": ["레더 재킷"], "materials": ["LEATHER"],
                  "colors": [], "silhouettes": [], "summary": "레더 아우터가 주목받는다."}
    names = {"OUTER": "아우터", "LEATHER": "레더(가죽)"}

    def test_image_gets_one_summary_chunk_with_code_names(self):
        collection = FakeCollection()
        count = indexer.index_document(collection, "IMG-1", "IMAGE", None, self.structured, self.names,
                                       {"media": "업로드", "published_at": "2026-10-20", "url": "/api/images/1/file"})
        self.assertEqual(1, count)
        ids, docs, metas = collection.added[0]
        self.assertEqual(["IMG-1#0"], ids)
        self.assertIn("소재: 레더(가죽)", docs[0])  # 코드값이 아니라 이름으로 — 한국어 질문과 맞아야 한다
        self.assertEqual("업로드", metas[0]["media"])
        self.assertEqual([{"doc_id": "IMG-1"}], collection.deleted)  # 재분석이면 이전 청크를 지운다

    def test_text_adds_body_chunks(self):
        collection = FakeCollection()
        count = indexer.index_document(collection, "TXT-1", "TEXT", "본문 " * 400, self.structured, self.names, {})
        self.assertGreater(count, 2)


class EmbeddingFunctionTest(unittest.TestCase):
    """모델 이름으로 구현을 고르고, Qwen은 질의에만 지시문을 붙인다(모델은 가짜 — 내려받지 않는다)."""

    def test_picks_implementation_by_model_name(self):
        self.assertIsInstance(embedding.create_embedding_function("Qwen/Qwen3-VL-Embedding-2B"),
                              embedding.QwenVLEmbeddingFunction)
        self.assertIsInstance(embedding.create_embedding_function("sentence-transformers/paraphrase-multilingual-mpnet-base-v2"),
                              embedding.FastEmbedFunction)

    def test_qwen_adds_prompt_only_to_queries(self):
        import numpy as np
        func = embedding.QwenVLEmbeddingFunction("Qwen/Qwen3-VL-Embedding-2B")
        func._model = mock.Mock()
        func._model.encode.return_value = np.array([[0.6, 0.8]], dtype=np.float32)

        self.assertEqual(1, len(func(["가죽 재킷"])))
        self.assertIsNone(func._model.encode.call_args.kwargs["prompt"])
        func.embed_query(["가죽 아우터"])
        self.assertEqual(embedding.QWEN_QUERY_PROMPT, func._model.encode.call_args.kwargs["prompt"])
        self.assertTrue(func._model.encode.call_args.kwargs["normalize_embeddings"])

    def test_config_round_trip(self):
        func = embedding.QwenVLEmbeddingFunction("Qwen/Qwen3-VL-Embedding-2B")
        self.assertEqual("Qwen/Qwen3-VL-Embedding-2B",
                         embedding.QwenVLEmbeddingFunction.build_from_config(func.get_config()).model_name)


if __name__ == "__main__":
    unittest.main()
