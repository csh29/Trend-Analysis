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

from app.indexing import chunker
from app.core import config
from app.indexing import embedding
from app.structuring import image_prep
from app.indexing import indexer
from app.core import prompt_loader
from app.indexing import vector_store
from app.core.claude import AiServiceError
from app.structuring.structurer import CodeOption, ImageInput, StructureInput, build_content, build_schema, structure, system_prompt


def sample_input(doc_type="TEXT", images=None) -> StructureInput:
    """TEXT는 본문만, IMAGE는 이미지만. images를 주면 TEXT에 이미지를 붙인 원문(기사·게시물)이 된다."""
    if images is None:
        images = 1 if doc_type == "IMAGE" else 0
    return StructureInput(
        doc_id="TXT-1", doc_type=doc_type, model="claude-sonnet-5", title="제목",
        text="레더 재킷이 유행" if doc_type == "TEXT" else None,
        images=[ImageInput("AAAA", "image/png") for _ in range(images)],
        categories=[CodeOption("OUTER", "아우터"), CodeOption("ETC", "기타")],
        item_types=[CodeOption("OUTER_PADDING", "패딩(숏패딩·푸퍼)"), CodeOption("OUTER_ETC", "기타 아우터"), CodeOption("ETC", "기타")],
        materials=[CodeOption("LEATHER", "레더(가죽)")],
        colors=[CodeOption("BROWN", "브라운")],
        silhouettes=[CodeOption("OVERSIZED", "오버사이즈")],
        seasons=[CodeOption("FALL", "가을"), CodeOption("WINTER", "겨울")],
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

    def test_multiple_images_are_shrunk_to_smaller_edge(self):
        # 원문 하나에 여러 장이면 요청 전체 크기를 넘지 않게 장마다 더 줄인다
        raw = self.image_bytes((2000, 1000), "PNG")
        single, _ = image_prep.prepare(raw, "image/png")
        multi, media = image_prep.prepare(raw, "image/png", multiple=True)
        self.assertEqual(2000, Image.open(io.BytesIO(base64.b64decode(single))).size[0])
        self.assertEqual(image_prep.MULTI_LONG_EDGE, Image.open(io.BytesIO(base64.b64decode(multi))).size[0])
        self.assertEqual("image/jpeg", media)

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
        item = schema["properties"]["items"]["items"]  # 의류 아이템마다 카테고리·속성을 따로 받는다
        self.assertEqual(["OUTER", "ETC"], item["properties"]["category"]["enum"])
        self.assertEqual(["LEATHER"], item["properties"]["materials"]["items"]["enum"])
        self.assertFalse(item["additionalProperties"])
        self.assertEqual({"category", "item", "type", "materials", "colors", "silhouettes"}, set(item["required"]))
        self.assertEqual(["OUTER_PADDING", "OUTER_ETC", "ETC"], item["properties"]["type"]["enum"])  # 아이템 종류(집계용)
        self.assertFalse(schema["additionalProperties"])
        self.assertNotIn("category", schema["properties"])  # 문서 단위 카테고리는 없다
        self.assertEqual(["FALL", "WINTER"], schema["properties"]["seasons"]["items"]["enum"])  # 시즌은 문서 단위
        self.assertIn("seasons", schema["required"])
        self.assertNotIn("genders", item["properties"])  # 대상 라인 코드를 안 보내면(예전 Spring) 그 칸을 받지 않는다
        self.assertNotIn("source", schema["properties"])  # 출처는 LLM이 만들지 않는다

    def test_schema_asks_gender_when_codes_given(self):
        data = sample_input()
        data.genders = [CodeOption("WOMEN", "여성복"), CodeOption("MEN", "남성복"), CodeOption("UNISEX", "공용")]
        item = build_schema(data)["properties"]["items"]["items"]
        self.assertEqual(["WOMEN", "MEN", "UNISEX"], item["properties"]["genders"]["items"]["enum"])  # 아이템마다
        self.assertIn("genders", item["required"])
        self.assertIn("- genders: WOMEN=여성복", system_prompt(data)[1]["text"])

    def test_image_content_puts_image_first(self):
        content = build_content(sample_input("IMAGE"))
        self.assertEqual("image", content[0]["type"])
        self.assertNotIn("LEATHER", content[-1]["text"])  # 허용 코드 목록은 system(캐시 구간)에 있다

    def test_fixed_part_is_cached_in_system(self):
        # 규칙 + 허용 코드 목록은 매 요청 같다 — system 마지막 블록에 캐시 표시를 달아 둘 다 캐시에 든다
        system = system_prompt(sample_input("IMAGE"))
        self.assertNotIn("cache_control", system[0])
        self.assertEqual({"type": "ephemeral"}, system[-1]["cache_control"])
        self.assertIn("LEATHER=레더(가죽)", system[-1]["text"])
        self.assertEqual(system, system_prompt(sample_input("IMAGE")))  # 같은 입력이면 바이트까지 같아야 캐시가 맞는다

    def _system_for(self, doc_type):
        messages = FakeMessages(fake_response())
        structure(sample_input(doc_type), "k", client=SimpleNamespace(messages=messages))
        return "\n\n".join(b["text"] for b in messages.calls[0]["system"])

    def test_each_type_gets_only_its_own_rules(self):
        # 이미지로 소재를 짐작하면 오판이 많다(그레이 데님 → 면) — 질감이 분명할 때만, 텍스트는 본문에 나온 것만.
        # 유형별 기준은 그 유형에만 보낸다(이미지 분석에 텍스트 기준이 섞이지 않게)
        image, text = self._system_for("IMAGE"), self._system_for("TEXT")
        for system in (image, text):
            self.assertIn("materials는 특히 엄격하게", system)  # 공통 규칙
            self.assertIn("축마다 최대 1개", system)            # 실루엣 기준은 양쪽 모두
        self.assertIn("질감이 사진에서 분명할 때만", image)
        self.assertNotIn("본문에 그 아이템의 소재가 직접 나온 것만", image)
        self.assertIn("본문에 그 아이템의 소재가 직접 나온 것만", text)
        self.assertNotIn("질감이 사진에서 분명할 때만", text)
        self.assertNotIn("본문을 따른다", image + text)  # 본문+이미지 우선순위는 둘 다 있을 때만

    def test_article_gets_both_rules_and_precedence(self):
        messages = FakeMessages(fake_response())
        structure(sample_input("TEXT", images=2), "k", client=SimpleNamespace(messages=messages))
        system = messages.calls[0]["system"][0]["text"]
        self.assertIn("본문에 그 아이템의 소재가 직접 나온 것만", system)
        self.assertIn("질감이 사진에서 분명할 때만", system)
        self.assertLess(system.index("질감이 사진에서 분명할 때만"), system.index("본문을 따른다"))  # 우선순위가 마지막
        content = messages.calls[0]["messages"][0]["content"]
        self.assertEqual(["image", "image", "text", "text"], [c["type"] for c in content])  # 이미지 → 본문 → 지시문
        self.assertIn("<document>", content[2]["text"])
        self.assertIn("본문이 주된 근거", content[3]["text"])  # 지시도 본문 우선(사진 위주로 뽑지 않게)

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
        with self.assertRaises(AiServiceError) as ctx:
            structure(sample_input(), "k", client=SimpleNamespace(messages=FakeMessages(response)))
        self.assertFalse(ctx.exception.retryable)
        self.assertEqual(10, ctx.exception.usage["input_tokens"])  # 거절돼도 과금된 토큰은 넘긴다

    def test_truncated_or_broken_json_is_retryable(self):
        for response in (fake_response(stop_reason="max_tokens"), fake_response(text="{broken")):
            with self.assertRaises(AiServiceError) as ctx:
                structure(sample_input(), "k", client=SimpleNamespace(messages=FakeMessages(response)))
            self.assertTrue(ctx.exception.retryable)

    def test_connection_error_is_retryable(self):
        error = anthropic.APIConnectionError(request=mock.Mock())
        with self.assertRaises(AiServiceError) as ctx:
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
    structured = {"items": [
        {"category": "OUTER", "item": "레더 재킷", "type": "OUTER_LEATHER_JACKET", "materials": ["LEATHER"], "colors": ["BROWN"],
         "silhouettes": []},
        {"category": "BOTTOM", "item": "미니스커트", "materials": [], "colors": ["BLACK"], "silhouettes": []},
    ], "keywords": ["레더 재킷"], "seasons": ["FALL"], "summary": "레더 아우터가 주목받는다."}
    names = {"OUTER_LEATHER_JACKET": "레더 재킷(라이더)", "OUTER": "아우터", "BOTTOM": "하의", "LEATHER": "레더(가죽)", "BROWN": "브라운", "BLACK": "블랙", "FALL": "가을"}

    def test_image_gets_one_summary_chunk_with_code_names(self):
        collection = FakeCollection()
        count = indexer.index_document(collection, "IMG-1", "IMAGE", None, self.structured, self.names,
                                       {"media": "업로드", "published_at": "2026-10-20", "url": "/api/images/1/file"})
        self.assertEqual(1, count)
        ids, docs, metas = collection.added[0]
        self.assertEqual(["IMG-1#0"], ids)
        # 코드값이 아니라 이름으로(한국어 질문과 맞게), 속성은 그 아이템 줄에만
        self.assertIn("아이템: 레더 재킷(아우터·레더 재킷(라이더)) — 소재 레더(가죽), 컬러 브라운", docs[0])  # 종류 이름도
        self.assertIn("아이템: 미니스커트(하의) — 컬러 블랙", docs[0])
        self.assertEqual("업로드", metas[0]["media"])
        self.assertEqual("OUTER", metas[0]["category"])
        self.assertEqual("OUTER,BOTTOM", metas[0]["categories"])
        self.assertIn("시즌: 가을", docs[0])
        self.assertEqual("FALL", metas[0]["seasons"])
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


class PromptLoaderTest(unittest.TestCase):
    """Claude가 읽는 글은 prompts/*.md — 구역(## 키)으로 나누고 주석은 보내지 않는다."""

    def test_sections_without_comments(self):
        sections = prompt_loader.parse_sections("<!-- 개발자용 -->\n## a\n\n첫 줄\n둘째 줄\n\n## b\n끝\n")
        self.assertEqual({"a": "첫 줄\n둘째 줄", "b": "끝"}, sections)

    def test_missing_section_fails_at_load(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(prompt_loader, "PROMPT_DIR", Path(tmp)):
            Path(tmp, "x.md").write_text("## system\n규칙\n", encoding="utf-8")
            with self.assertRaises(RuntimeError):
                prompt_loader.load("x", ["system", "task_text"])

    def test_prompt_files_are_what_claude_gets(self):
        common = prompt_loader.load("structure/common", ["system"])
        silhouette = prompt_loader.load("structure/silhouette", ["system"])
        text_rules = prompt_loader.load("structure/text", ["system"])
        messages = FakeMessages(fake_response())
        structure(sample_input("TEXT"), "k", client=SimpleNamespace(messages=messages))
        call = messages.calls[0]
        # 공통 → 실루엣 기준 → 자료 유형 기준 순서로 붙는다
        rules, allowed = call["system"]
        self.assertEqual("\n\n".join([common["system"], silhouette["system"], text_rules["system"]]), rules["text"])
        self.assertTrue(allowed["text"].startswith(common["allowed_values_header"]))  # 규칙 뒤에 허용 코드 목록
        self.assertNotIn("<!--", rules["text"] + allowed["text"])  # md 주석은 보내지 않는다
        texts = [b["text"] for b in call["messages"][0]["content"] if b["type"] == "text"]
        self.assertTrue(any(text_rules["task"] in t for t in texts))
        self.assertTrue(any(t.startswith("<document>\n" + text_rules["title_label"]) for t in texts))


if __name__ == "__main__":
    unittest.main()
