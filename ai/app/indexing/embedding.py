"""Chroma에 넣을 임베딩 함수. EMBEDDING_MODEL 값으로 구현을 고른다(create_embedding_function).

- Qwen/Qwen3-VL-Embedding-*: 기본. sentence-transformers(torch)로 돌린다. 2026-10-20 한국어 비교에서 질의 3개를 모두
  맞혔다(mpnet 2/3, e5-large 1/3). 2B 모델이라 CPU에서 RAM 약 4.4GB, 짧은 문장 하나에 1~2초 걸린다.
- 그 밖의 이름: fastembed(torch 없음). Chroma 기본(영어 MiniLM)은 한국어 패션 용어를 거의 구분하지 못한다.
"""
import threading

from chromadb import Documents, EmbeddingFunction, Embeddings

QWEN_VL_PREFIX = "Qwen/Qwen3-VL-Embedding"
# 모델 카드 권장: 질의에만 영어 지시문을 붙이고 문서는 그대로 넣는다.
QWEN_QUERY_PROMPT = "Retrieve relevant documents for the query."


def create_embedding_function(model_name: str) -> EmbeddingFunction:
    if model_name.startswith(QWEN_VL_PREFIX):
        return QwenVLEmbeddingFunction(model_name)
    return FastEmbedFunction(model_name)


class QwenVLEmbeddingFunction(EmbeddingFunction[Documents]):
    """모델은 처음 임베딩할 때 한 번 내려받아 메모리에 둔다(기동만 할 때는 올리지 않는다 — 수 GB라서)."""

    def __init__(self, model_name: str):
        self.model_name = model_name
        self._model = None
        self._lock = threading.Lock()  # FastAPI 작업 스레드 둘이 동시에 처음 부르면 모델을 두 번 올린다

    def _get_model(self):
        with self._lock:
            if self._model is None:
                import torch
                from sentence_transformers import SentenceTransformer
                self._model = SentenceTransformer(
                    self.model_name, device="cuda" if torch.cuda.is_available() else "cpu",
                    model_kwargs={"torch_dtype": torch.bfloat16})
            return self._model

    def _encode(self, texts: list[str], prompt: str | None = None) -> Embeddings:
        vectors = self._get_model().encode(texts, prompt=prompt, normalize_embeddings=True)
        return [vector.tolist() for vector in vectors]

    def __call__(self, input: Documents) -> Embeddings:
        return self._encode(list(input))

    def embed_query(self, input: Documents) -> Embeddings:
        return self._encode(list(input), prompt=QWEN_QUERY_PROMPT)

    @staticmethod
    def name() -> str:
        return "trend_qwen3_vl"

    def get_config(self) -> dict:
        return {"model_name": self.model_name}

    @staticmethod
    def build_from_config(config: dict) -> "QwenVLEmbeddingFunction":
        return QwenVLEmbeddingFunction(config["model_name"])


class FastEmbedFunction(EmbeddingFunction[Documents]):
    """모델은 처음 임베딩할 때 한 번 내려받아 메모리에 둔다(기동만 할 때는 내려받지 않는다)."""

    def __init__(self, model_name: str):
        self.model_name = model_name
        self._model = None

    def __call__(self, input: Documents) -> Embeddings:
        if self._model is None:
            from fastembed import TextEmbedding
            self._model = TextEmbedding(self.model_name)
        return [vector.tolist() for vector in self._model.embed(list(input))]

    @staticmethod
    def name() -> str:
        return "trend_fastembed"

    def get_config(self) -> dict:
        return {"model_name": self.model_name}

    @staticmethod
    def build_from_config(config: dict) -> "FastEmbedFunction":
        return FastEmbedFunction(config["model_name"])
