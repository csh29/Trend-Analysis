"""AI 서비스 설정. ai/.env(커밋 제외)를 읽고, 같은 이름의 환경변수가 있으면 그 값이 이긴다.

별도 패키지(python-dotenv) 없이 KEY=VALUE 줄만 읽는다. Claude API 키는 여기서 읽지 않는다 —
Spring의 claude.api.key 하나를 출처로 두고 요청 헤더로 받는다(키를 두 군데 두면 바꿀 때 어긋난다).
"""
import os
from dataclasses import dataclass
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent


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
    )


settings = load_settings()
