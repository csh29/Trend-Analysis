"""AI 서비스 설정. ai/.env(커밋 제외)를 읽고, 같은 이름의 환경변수가 있으면 그 값이 이긴다.

별도 패키지(python-dotenv) 없이 KEY=VALUE 줄만 읽는다. Claude API 키는 여기서 읽지 않는다 —
Spring의 claude.api.key 하나를 출처로 두고 요청 헤더로 받는다(키를 두 군데 두면 바꿀 때 어긋난다).
"""
import os
from dataclasses import dataclass
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parents[2]  # ai/ — .env·chroma_data 기준(코드는 ai/app/core/)


def _read_env_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.is_file():
        return values
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.split(" #", 1)[0].strip()
    return values


@dataclass(frozen=True)
class Settings:
    chroma_mode: str
    chroma_path: str
    chroma_host: str
    chroma_port: int
    chroma_collection: str
    embedding_model: str
    chunk_size: int
    chunk_overlap: int
    hybrid_search: bool = True  # P-06 검색에 키워드(BM25)를 RRF로 합친다. false면 벡터만
    rerank: bool = True  # P-06 근거 검색에 리랭킹. 켜면 근거 기준도 리랭커 점수(rerank_min_score), 끄면 Spring rag.min-score(유사도)
    rerank_model: str = "BAAI/bge-reranker-v2-m3"
    rerank_min_score: float = 0.10  # 리랭커 근거 기준(0~1) — 2026-10-30 rerank_eval: 자료 없는 주제 1위 최고 0.042, 정답은 모두 0.10 이상
    preload_models: bool = True  # 기동 때 임베딩·리랭커 모델을 백그라운드로 미리 올린다


def _score(value: str) -> float:
    """0~1 사이(양 끝 제외) 기준값. 잘못 적어 기준이 사라지면(0) 무관한 원문까지 근거가 되므로 기동을 막는다."""
    score = float(value)
    if not 0 < score < 1:
        raise ValueError(f"RERANK_MIN_SCORE는 0과 1 사이여야 합니다: {value}")
    return score


def load_settings(env_file: Path = BASE_DIR / ".env") -> Settings:
    file_values = _read_env_file(env_file)

    def get(key: str, default: str) -> str:
        return os.environ.get(key) or file_values.get(key) or default

    mode = get("CHROMA_MODE", "embedded").lower()
    if mode not in ("embedded", "http"):
        raise ValueError(f"CHROMA_MODE는 embedded 또는 http여야 합니다: {mode}")
    chroma_path = get("CHROMA_PATH", "./chroma_data")
    if not Path(chroma_path).is_absolute():
        chroma_path = str(BASE_DIR / chroma_path)  # 실행 위치가 아니라 ai/ 기준 — 어디서 띄워도 같은 폴더를 쓴다
    return Settings(
        chroma_mode=mode,
        chroma_path=chroma_path,
        chroma_host=get("CHROMA_HOST", "localhost"),
        chroma_port=int(get("CHROMA_PORT", "8000")),
        chroma_collection=get("CHROMA_COLLECTION", "trend_chunks_qwen3vl"),
        embedding_model=get("EMBEDDING_MODEL", "Qwen/Qwen3-VL-Embedding-2B"),
        chunk_size=int(get("CHUNK_SIZE", "500")),
        chunk_overlap=int(get("CHUNK_OVERLAP", "80")),
        hybrid_search=get("HYBRID_SEARCH", "true").lower() != "false",
        rerank=get("RERANK", "true").lower() != "false",
        rerank_model=get("RERANK_MODEL", "BAAI/bge-reranker-v2-m3"),
        rerank_min_score=_score(get("RERANK_MIN_SCORE", "0.10")),
        preload_models=get("PRELOAD_MODELS", "true").lower() != "false",
    )


settings = load_settings()
