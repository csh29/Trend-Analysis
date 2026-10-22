"""트렌드 분석 AI 서비스(FastAPI). Spring이 호출한다 — P-03 정형화(/structure, 본문+이미지), P-04 색인(/index), P-06 RAG 답변(/answer).

실행: ai/start-ai.cmd (직접 띄우면 ai 폴더에서 .venv\\Scripts\\python -m uvicorn app.main:app --host 127.0.0.1 --port 8001)
색인 데이터 확인: 브라우저에서 http://127.0.0.1:8001/docs → GET /index/stats, GET /index/chunks, GET /search (읽기 전용)
- 127.0.0.1에만 연다. 내장형 Chroma는 한 프로세스만 열어야 하므로 워커는 1개로 띄운다(--workers를 주지 않는다).
- Claude API 키는 Spring의 claude.api.key를 X-Claude-Api-Key 헤더로 받는다. 키를 로그·응답에 남기지 않는다.
"""
import base64
import binascii
import logging
import time
from datetime import date

from fastapi import FastAPI, Header, HTTPException, Query
from pydantic import BaseModel, Field

from app.structuring import image_prep
from app.indexing import indexer
from app.rag import answer as rag
from app.rag import interpret as interpreter
from app.indexing import vector_store
from app.core.config import settings
from app.core.claude import AiServiceError
from app.structuring.structurer import CodeOption, ImageInput, StructureInput, candidate_counts, structure

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s - %(message)s")
log = logging.getLogger("trend-ai")

app = FastAPI(title="trend-analysis-ai")


class CodeOptionModel(BaseModel):
    value: str
    name: str


class AllowedCodes(BaseModel):
    categories: list[CodeOptionModel] = Field(min_length=1)
    item_types: list[CodeOptionModel] = Field(min_length=1)  # 아이템 종류(카테고리_종류)
    materials: list[CodeOptionModel] = Field(min_length=1)
    colors: list[CodeOptionModel] = Field(min_length=1)
    silhouettes: list[CodeOptionModel] = Field(min_length=1)
    seasons: list[CodeOptionModel] = Field(min_length=1)


MAX_IMAGES = 5  # 원문 한 건에 붙는 이미지 상한(Spring 첨부 상한과 같다)


class ImageModel(BaseModel):
    data: str  # base64
    media_type: str = ""


class StructureRequest(BaseModel):
    """원문 1건 = 본문(TEXT) + 이미지 0~5장. IMAGE는 본문 없이 이미지만 있는 원문이다."""
    doc_id: str
    doc_type: str = Field(pattern="^(IMAGE|TEXT)$")
    model: str
    title: str | None = None
    text: str | None = None
    images: list[ImageModel] = Field(default=[], max_length=MAX_IMAGES)
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


class StatRowModel(BaseModel):
    type_label: str
    label: str
    count: int
    prev_count: int = 0
    prev_rate: float | None = None
    surge: bool = False
    is_new: bool = False


MAX_QUESTION_LENGTH = 500


class PeriodModel(BaseModel):
    start: date = Field(alias="from")
    end: date = Field(alias="to")
    label: str
    explicit: bool = False


class AnswerRequest(BaseModel):
    question: str
    model: str
    top_k: int = Field(default=8, ge=1, le=20)
    min_score: float = Field(ge=0.0, le=1.0)
    stats_week: str | None = None
    stats: list[StatRowModel] = []
    today: date | None = None  # 최신성·기간의 기준일 — Spring 서버 날짜. 없으면 이 서버 날짜
    # 질문 해석은 Spring이 한다(유행 질문인지·카테고리·기간 — QuestionInterpreter)
    mode: str = Field(default="SEARCH", pattern="^(SEARCH|TREND)$")
    category: str | None = None
    period: PeriodModel | None = None
    trend_facts: list[str] = []
    evidence_doc_ids: list[str] = Field(default=[], max_length=20)


class CodeHintModel(BaseModel):
    value: str
    name: str
    words: str = ""


class InterpretRequest(BaseModel):
    question: str
    model: str
    today: date | None = None
    categories: list[CodeHintModel] = []
    item_types: list[CodeHintModel] = []


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

    if req.doc_type == "IMAGE" and not req.images:
        raise HTTPException(status_code=400, detail={"message": "이미지가 없습니다.", "retryable": False})
    if req.doc_type == "TEXT" and not (req.text and req.text.strip()):
        raise HTTPException(status_code=400, detail={"message": "본문이 비어 있습니다.", "retryable": False})
    images = []
    for i, image in enumerate(req.images, start=1):
        try:
            data_b64, media_type = image_prep.prepare(base64.b64decode(image.data), image.media_type,
                                                      multiple=len(req.images) > 1)
        except (binascii.Error, OSError) as e:
            raise HTTPException(status_code=400, detail={"message": f"이미지 {i}을(를) 읽지 못했습니다: {e}", "retryable": False})
        images.append(ImageInput(data_b64, media_type))

    data = StructureInput(
        doc_id=req.doc_id, doc_type=req.doc_type, model=req.model, title=req.title, text=req.text, images=images,
        categories=[CodeOption(o.value, o.name) for o in req.allowed.categories],
        item_types=[CodeOption(o.value, o.name) for o in req.allowed.item_types],
        materials=[CodeOption(o.value, o.name) for o in req.allowed.materials],
        colors=[CodeOption(o.value, o.name) for o in req.allowed.colors],
        silhouettes=[CodeOption(o.value, o.name) for o in req.allowed.silhouettes],
        seasons=[CodeOption(o.value, o.name) for o in req.allowed.seasons],
    )
    log.info("정형화 시작 doc_id=%s type=%s model=%s 판단 후보: %s", req.doc_id, req.doc_type, req.model,
             candidate_counts(data))
    try:
        result, usage = structure(data, x_claude_api_key)
    except AiServiceError as e:
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


@app.get("/search")
def search_chunks(q: str, top_k: int = Query(default=10, ge=1, le=50)):
    """질문으로 청크를 찾아 유사도와 함께 준다(읽기 전용, Claude 호출 없음) — 기준 유사도를 정할 때 점수 분포를 본다."""
    try:
        query = rag.ensure_question(q, MAX_QUESTION_LENGTH)
    except AiServiceError as e:
        raise HTTPException(status_code=e.status_code, detail={"message": str(e), "retryable": False})
    return rag.search(vector_store.get_collection(), query, top_k)


@app.post("/answer")
def answer_question(req: AnswerRequest, x_claude_api_key: str | None = Header(default=None)):
    if not x_claude_api_key:
        raise HTTPException(status_code=401, detail={"message": "Claude API 키가 없습니다(claude.api.key).", "retryable": False})
    try:
        question = rag.ensure_question(req.question, MAX_QUESTION_LENGTH)
        data = rag.AnswerInput(question=question, model=req.model, top_k=req.top_k, min_score=req.min_score,
                               stats_week=req.stats_week,
                               stats=[rag.StatRow(**s.model_dump()) for s in req.stats],
                               today=req.today or date.today(), mode=req.mode, category=req.category,
                               period=rag.Period(req.period.start, req.period.end, req.period.label, req.period.explicit)
                               if req.period else None,
                               trend_facts=req.trend_facts, evidence_doc_ids=req.evidence_doc_ids)
        return rag.answer(vector_store.get_collection(), data, x_claude_api_key)
    except AiServiceError as e:
        log.warning("RAG 답변 실패: %s", e)
        detail = {"message": str(e), "retryable": e.retryable}
        if e.usage:
            detail["usage"] = e.usage
        raise HTTPException(status_code=e.status_code, detail=detail)


@app.post("/interpret")
def interpret_question(req: InterpretRequest, x_claude_api_key: str | None = Header(default=None)):
    """P-06 질문 해석(작은 모델). 실패하면 Spring이 규칙으로 대신한다."""
    if not x_claude_api_key:
        raise HTTPException(status_code=401, detail={"message": "Claude API 키가 없습니다(claude.api.key).", "retryable": False})
    try:
        question = rag.ensure_question(req.question, MAX_QUESTION_LENGTH)
        data = interpreter.InterpretInput(
            question=question, model=req.model, today=req.today or date.today(),
            categories=[interpreter.CodeHint(c.value, c.name, c.words) for c in req.categories],
            item_types=[interpreter.CodeHint(t.value, t.name) for t in req.item_types])
        return interpreter.interpret(data, x_claude_api_key)
    except AiServiceError as e:
        log.warning("질문 해석 실패: %s", e)
        detail = {"message": str(e), "retryable": e.retryable}
        if e.usage:
            detail["usage"] = e.usage
        raise HTTPException(status_code=e.status_code, detail=detail)
