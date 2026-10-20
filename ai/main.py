"""트렌드 분석 AI 서비스(FastAPI). Spring이 호출한다 — P-03 정형화(/structure), P-04 색인(/index).

실행: ai/start-ai.cmd (직접 띄우면 ai 폴더에서 .venv\\Scripts\\python -m uvicorn main:app --host 127.0.0.1 --port 8001)
색인 데이터 확인: 브라우저에서 http://127.0.0.1:8001/docs → GET /index/stats, GET /index/chunks (읽기 전용)
- 127.0.0.1에만 연다. 내장형 Chroma는 한 프로세스만 열어야 하므로 워커는 1개로 띄운다(--workers를 주지 않는다).
- Claude API 키는 Spring의 claude.api.key를 X-Claude-Api-Key 헤더로 받는다. 키를 로그·응답에 남기지 않는다.
"""
import base64
import binascii
import logging
import time

from fastapi import FastAPI, Header, HTTPException, Query
from pydantic import BaseModel, Field

import image_prep
import indexer
import vector_store
from config import settings
from structurer import CodeOption, StructureInput, StructuringError, candidate_counts, structure

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s - %(message)s")
log = logging.getLogger("trend-ai")

app = FastAPI(title="trend-analysis-ai")


class CodeOptionModel(BaseModel):
    value: str
    name: str


class AllowedCodes(BaseModel):
    categories: list[CodeOptionModel] = Field(min_length=1)
    materials: list[CodeOptionModel] = Field(min_length=1)
    colors: list[CodeOptionModel] = Field(min_length=1)
    silhouettes: list[CodeOptionModel] = Field(min_length=1)
    seasons: list[CodeOptionModel] = Field(min_length=1)


class StructureRequest(BaseModel):
    doc_id: str
    doc_type: str = Field(pattern="^(IMAGE|TEXT)$")
    model: str
    title: str | None = None
    text: str | None = None
    image_base64: str | None = None
    image_media_type: str | None = None
    allowed: AllowedCodes


class StructureResponse(BaseModel):
    doc_id: str
    result: dict
    usage: dict


class IndexRequest(BaseModel):
    doc_id: str
    doc_type: str = Field(pattern="^(IMAGE|TEXT)$")
    text: str | None = None
    structured: dict
    names: dict[str, str] = {}
    meta: dict = {}


@app.get("/health")
def health():
    return {
        "status": "ok",
        "chroma_mode": settings.chroma_mode,
        "collection": settings.chroma_collection,
        "embedding_model": settings.embedding_model,
    }


@app.post("/structure", response_model=StructureResponse)
def structure_document(req: StructureRequest, x_claude_api_key: str | None = Header(default=None)):
    if not x_claude_api_key:
        raise HTTPException(status_code=401, detail={"message": "Claude API 키가 없습니다(claude.api.key).", "retryable": False})

    image_base64, media_type = None, None
    if req.doc_type == "IMAGE":
        if not req.image_base64:
            raise HTTPException(status_code=400, detail={"message": "이미지가 없습니다.", "retryable": False})
        try:
            image_base64, media_type = image_prep.prepare(base64.b64decode(req.image_base64), req.image_media_type or "")
        except (binascii.Error, OSError) as e:
            raise HTTPException(status_code=400, detail={"message": f"이미지를 읽지 못했습니다: {e}", "retryable": False})
    elif not (req.text and req.text.strip()):
        raise HTTPException(status_code=400, detail={"message": "본문이 비어 있습니다.", "retryable": False})

    data = StructureInput(
        doc_id=req.doc_id, doc_type=req.doc_type, model=req.model, title=req.title, text=req.text,
        image_base64=image_base64, image_media_type=media_type,
        categories=[CodeOption(o.value, o.name) for o in req.allowed.categories],
        materials=[CodeOption(o.value, o.name) for o in req.allowed.materials],
        colors=[CodeOption(o.value, o.name) for o in req.allowed.colors],
        silhouettes=[CodeOption(o.value, o.name) for o in req.allowed.silhouettes],
        seasons=[CodeOption(o.value, o.name) for o in req.allowed.seasons],
    )
    log.info("정형화 시작 doc_id=%s type=%s model=%s 판단 후보: %s", req.doc_id, req.doc_type, req.model,
             candidate_counts(data))
    try:
        result, usage = structure(data, x_claude_api_key)
    except StructuringError as e:
        log.warning("정형화 실패 doc_id=%s: %s", req.doc_id, e)
        detail = {"message": str(e), "retryable": e.retryable}
        if e.usage:
            detail["usage"] = e.usage  # 실패해도 소모한 토큰은 Spring 누적에 넣는다
        raise HTTPException(status_code=e.status_code, detail=detail)
    log.info("정형화 완료 doc_id=%s category=%s keywords=%d개", req.doc_id, result.get("category"),
             len(result.get("keywords") or []))
    return StructureResponse(doc_id=req.doc_id, result=result, usage=usage)


@app.post("/index")
def index_document(req: IndexRequest):
    started = time.monotonic()
    count = indexer.index_document(vector_store.get_collection(), req.doc_id, req.doc_type, req.text,
                                   req.structured, req.names, req.meta)
    log.info("색인 완료 doc_id=%s 청크 %d개 임베딩 소요=%.1fs", req.doc_id, count, time.monotonic() - started)
    return {"doc_id": req.doc_id, "chunks": count}


@app.get("/index/stats")
def index_stats():
    """색인 현황(확인용). 브라우저에서 http://127.0.0.1:8001/docs 로 열어 본다."""
    collection = vector_store.get_collection()
    return {
        "collection": settings.chroma_collection,
        "chroma_mode": settings.chroma_mode,
        "embedding_model": (collection.metadata or {}).get("embedding_model"),
        "chunks": collection.count(),
    }


@app.get("/index/chunks")
def index_chunks(doc_id: str | None = None, limit: int = Query(default=20, ge=1, le=200), offset: int = Query(default=0, ge=0),
                 with_embedding: bool = False):
    """저장된 청크 조회(읽기 전용). doc_id로 거르고, with_embedding=true면 벡터 차원·앞부분을 같이 준다."""
    return indexer.list_chunks(vector_store.get_collection(), doc_id, limit, offset, with_embedding)


@app.delete("/index/{doc_id}")
def delete_index(doc_id: str):
    indexer.delete_document(vector_store.get_collection(), doc_id)
    return {"doc_id": doc_id}
