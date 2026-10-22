"""Chroma 클라이언트를 만드는 유일한 곳. 다른 모듈은 chromadb 클라이언트를 직접 만들지 않고 get_collection()만 쓴다.

내장형(embedded, 로컬 폴더)과 서버형(http)은 CHROMA_MODE 설정 하나로 바뀐다 — 시스템 오픈 때 서버형으로 옮길 때
코드를 고치지 않기 위해서다. 두 클라이언트의 컬렉션 API(add·query·delete)는 같다.
"""
import logging

import chromadb

from app.indexing import indexer
from app.core.config import Settings, settings
from app.indexing.embedding import create_embedding_function

log = logging.getLogger("trend-ai")

_client = None
_collection = None


def create_client(conf: Settings):
    if conf.chroma_mode == "http":
        return chromadb.HttpClient(host=conf.chroma_host, port=conf.chroma_port)
    return chromadb.PersistentClient(path=conf.chroma_path)


def get_client():
    global _client
    if _client is None:
        _client = create_client(settings)
    return _client


def get_collection():
    """컬렉션 메타데이터에 임베딩 모델을 남기고, 다른 모델로 만든 컬렉션이면 막는다 — 섞이면 검색이 조용히 틀린다."""
    global _collection
    if _collection is None:
        collection = get_client().get_or_create_collection(
            name=settings.chroma_collection,
            embedding_function=create_embedding_function(settings.embedding_model),
            metadata={"embedding_model": settings.embedding_model},
            configuration={"hnsw": {"space": "cosine"}},
        )
        stored = (collection.metadata or {}).get("embedding_model")
        if stored and stored != settings.embedding_model:
            raise RuntimeError(
                f"컬렉션 {settings.chroma_collection}은 {stored}로 만들어졌습니다(현재 설정 {settings.embedding_model}). "
                "CHROMA_COLLECTION을 바꾸거나 재색인하세요.")
        filled = indexer.backfill_date_numbers(collection)
        if filled:
            log.info("예전 청크 %d개에 게시일 숫자(published_num)를 채웠습니다 — RAG 기간 필터용", filled)
        _collection = collection
    return _collection
