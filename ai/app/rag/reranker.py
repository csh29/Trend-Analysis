"""P-06 리랭커 — 질문과 청크를 함께 읽어 "이 청크가 질문에 답이 되는가"를 0~1 점수로 낸다.

임베딩 유사도는 질문·청크를 따로 벡터로 만들어 재서, 여러 아이템이 섞인 청크("블레이저 + 티셔츠 + 데님")는 점수가 희석되고
관련·무관 점수가 0.52~0.69에서 겹쳤다(2026-10-30 검색 평가). 근거 기준은 리랭커 점수에 건다(정확도 우선 — 사용자 결정).
모델은 처음 부를 때 한 번 올린다(기동만 할 때는 올리지 않는다 — 수 GB라서).
"""
import threading

from app.core import hf_cache
from app.core.config import settings

MAX_LENGTH = 1024  # 청크는 최대 500자(CHUNK_SIZE)라 잘리지 않는다
BATCH_SIZE = 8


def create_reranker(model_name: str):
    """cross-encoder 리랭커. Qwen3-Reranker-0.6B도 견줘 보려 했으나 이 PC(CPU)에서 평가가 1시간 넘게 끝나지 않아 bge로 정했다(2026-10-30)."""
    return CrossEncoderReranker(model_name)


class CrossEncoderReranker:
    """bge-reranker 같은 cross-encoder(sentence-transformers). 점수는 시그모이드(0~1)."""

    def __init__(self, model_name: str):
        self.model_name = model_name
        self._model = None
        self._lock = threading.Lock()

    def _get_model(self):
        with self._lock:
            if self._model is None:
                from sentence_transformers import CrossEncoder
                self._model = CrossEncoder(self.model_name, max_length=MAX_LENGTH, device="cpu",
                                           local_files_only=hf_cache.is_cached(self.model_name))
            return self._model

    def score(self, query: str, documents: list[str]) -> list[float]:
        if not documents:
            return []
        import torch
        scores = self._get_model().predict([(query, d) for d in documents], batch_size=BATCH_SIZE,
                                           activation_fn=torch.nn.Sigmoid(), show_progress_bar=False)
        return [float(s) for s in scores]


_lock = threading.Lock()
_reranker = None


def get():
    """설정(RERANK_MODEL)의 리랭커 하나. 모델은 처음 점수를 낼 때 올린다 — 리랭킹을 끈 채로 쓰면 메모리를 쓰지 않는다."""
    global _reranker
    with _lock:
        if _reranker is None:
            _reranker = create_reranker(settings.rerank_model)
        return _reranker
