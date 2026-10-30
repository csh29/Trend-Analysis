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
from app.rag import agent
from app.rag import brief as briefing
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
    genders: list[CodeOptionModel] = []  # 옷의 대상 라인 — 예전 Spring은 보내지 않는다
    patterns: list[CodeOptionModel] = []  # 옷의 패턴·무늬 — 예전 Spring은 보내지 않는다
    details: list[CodeOptionModel] = []  # 옷의 디자인 디테일 — 예전 Spring은 보내지 않는다


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
    # 공통코드 속성이 비어 있으면 Spring(Jackson)이 null로 보낸다 — str만 받으면 요청 전체가 422가 되어 늘 규칙 해석으로 빠졌다(2026-10-22)
    words: str | None = None


class TurnModel(BaseModel):
    """챗봇의 앞 질문 한 건과 그 해석 — 이어 묻기("그럼 하의는?")를 알아듣는 데만 쓴다."""
    question: str
    trend: bool = False
    category: str | None = None
    item_type: str | None = None
    period_label: str | None = None  # 기간을 콕 집은 질문만
    period_from: str | None = None
    period_to: str | None = None


class BriefSource(BaseModel):
    n: int
    title: str | None = None
    media: str | None = None
    date: str | None = None
    summary: str | None = None


class BriefRequest(BaseModel):
    """기획 브리프 — data는 Spring이 센 집계 줄, sources는 예시 원문(요약). Claude는 서술만 쓴다."""
    question: str = Field(min_length=1, max_length=MAX_QUESTION_LENGTH)
    model: str
    today: date | None = None
    data: list[str] = Field(min_length=1)
    sources: list[BriefSource] = []


class InterpretRequest(BaseModel):
    question: str
    model: str
    today: date | None = None
    categories: list[CodeHintModel] = []
    item_types: list[CodeHintModel] = []
    history: list[TurnModel] = []  # 오래된 것 → 최근 순
    seasons: list[CodeHintModel] = []  # 시즌 코드 — 예전 Spring은 보내지 않는다
    materials: list[CodeHintModel] = []  # 대상을 좁히는 속성 값 후보 — 예전 Spring은 보내지 않는다
    colors: list[CodeHintModel] = []
    silhouettes: list[CodeHintModel] = []
    genders: list[CodeHintModel] = []  # 대상 라인(여성복…) — 예전 Spring은 보내지 않는다
    patterns: list[CodeHintModel] = []  # 패턴(체크…) — 예전 Spring은 보내지 않는다
    details: list[CodeHintModel] = []  # 디테일(하프넥…) — 예전 Spring은 보내지 않는다


class AgentStepRequest(BaseModel):
    """도구 경로 한 단계 — messages는 Anthropic 형식(user·assistant, tool_use·tool_result 블록) 그대로. 코드 목록은 해석과 같다.
    cache면 규칙·도구 정의를 캐시한다(같은 질문의 둘째 단계부터 — Spring이 정한다)."""
    cache: bool = False
    model: str
    today: date | None = None
    messages: list[dict] = Field(min_length=1)
    categories: list[CodeHintModel] = []
    item_types: list[CodeHintModel] = []
    seasons: list[CodeHintModel] = []
    materials: list[CodeHintModel] = []
    colors: list[CodeHintModel] = []
    silhouettes: list[CodeHintModel] = []
    genders: list[CodeHintModel] = []
    patterns: list[CodeHintModel] = []
    details: list[CodeHintModel] = []


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
        genders=[CodeOption(o.value, o.name) for o in req.allowed.genders],
        patterns=[CodeOption(o.value, o.name) for o in req.allowed.patterns],
        details=[CodeOption(o.value, o.name) for o in req.allowed.details],
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


@app.get("/index/doc-ids")
def index_doc_ids():
    """색인에 들어 있는 문서 ID 목록 — Spring이 기동 때 DB에 없는 문서의 색인을 지우는 데 쓴다."""
    return {"doc_ids": indexer.indexed_doc_ids(vector_store.get_collection())}


@app.delete("/index/{doc_id}")
def delete_index(doc_id: str):
    indexer.delete_document(vector_store.get_collection(), doc_id)
    return {"doc_id": doc_id}


@app.get("/search")
def search_chunks(q: str, top_k: int = Query(default=10, ge=1, le=50), category: str | None = None,
                  hybrid: bool | None = None):
    """질문으로 청크를 찾아 유사도와 함께 준다(읽기 전용, Claude 호출 없음) — 기준 유사도를 정할 때 점수 분포를 보고,
    도구 경로의 search_documents(Spring)가 이유·사례 근거를 찾는다. category를 주면 그 카테고리 아이템이 있는 원문만.
    결과는 관련 순서(하이브리드면 RRF)다. hybrid를 주면 설정(HYBRID_SEARCH) 대신 그 방식으로 — 검색 평가의 벡터/하이브리드 비교용."""
    try:
        query = rag.ensure_question(q, MAX_QUESTION_LENGTH)
    except AiServiceError as e:
        raise HTTPException(status_code=e.status_code, detail={"message": str(e), "retryable": False})
    return rag.search(vector_store.get_collection(), query, top_k, {f"cat_{category}": True} if category else None, hybrid)


@app.post("/agent/step")
def agent_step(req: AgentStepRequest, x_claude_api_key: str | None = Header(default=None)):
    """도구 경로 한 단계(Claude 1회). 도구 실행·반복·상한은 Spring(AgentService)이 한다."""
    if not x_claude_api_key:
        raise HTTPException(status_code=401, detail={"message": "Claude API 키가 없습니다(claude.api.key).", "retryable": False})
    hint = lambda items: [interpreter.CodeHint(c.value, c.name, c.words or "") for c in items]  # noqa: E731
    today = req.today or date.today()
    hints = interpreter.InterpretInput(question="", model=req.model, today=today, categories=hint(req.categories),
                                       item_types=hint(req.item_types), seasons=hint(req.seasons), materials=hint(req.materials),
                                       colors=hint(req.colors), silhouettes=hint(req.silhouettes), genders=hint(req.genders),
                                       patterns=hint(req.patterns), details=hint(req.details))
    try:
        return agent.step(agent.AgentStepInput(model=req.model, today=today, messages=req.messages, hints=hints, cache=req.cache), x_claude_api_key)
    except AiServiceError as e:
        log.warning("도구 경로 한 단계 실패: %s", e)
        detail = {"message": str(e), "retryable": e.retryable}
        if e.usage:
            detail["usage"] = e.usage
        raise HTTPException(status_code=e.status_code, detail=detail)


@app.get("/agent/task")
def agent_task(today: date | None = None):
    """첫 user 메시지 앞에 붙일 지시(오늘 날짜 포함) — 프롬프트 문구를 Spring에 두지 않으려고."""
    return {"text": agent.task_text(today or date.today())}


@app.post("/answer")
def answer_question(req: AnswerRequest, x_claude_api_key: str | None = Header(default=None)):
    if not x_claude_api_key:
        raise HTTPException(status_code=401, detail={"message": "Claude API 키가 없습니다(claude.api.key).", "retryable": False})
    try:
        question = rag.ensure_question(req.question, MAX_QUESTION_LENGTH)
        data = rag.AnswerInput(question=question, model=req.model, top_k=req.top_k, min_score=req.min_score,
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


@app.post("/brief")
def write_brief(req: BriefRequest, x_claude_api_key: str | None = Header(default=None)):
    """기획 브리프 8개 구성의 서술(JSON). 차트·건수는 Spring 집계를 화면이 그린다."""
    if not x_claude_api_key:
        raise HTTPException(status_code=401, detail={"message": "Claude API 키가 없습니다(claude.api.key).", "retryable": False})
    try:
        return briefing.write(briefing.BriefInput(question=req.question, model=req.model, today=req.today or date.today(),
                                                  data=req.data, sources=[s.model_dump() for s in req.sources]), x_claude_api_key)
    except AiServiceError as e:
        log.warning("기획 브리프 실패: %s", e)
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
            categories=[interpreter.CodeHint(c.value, c.name, c.words or "") for c in req.categories],
            item_types=[interpreter.CodeHint(t.value, t.name) for t in req.item_types],
            seasons=[interpreter.CodeHint(s.value, s.name) for s in req.seasons],
            materials=[interpreter.CodeHint(c.value, c.name) for c in req.materials],
            colors=[interpreter.CodeHint(c.value, c.name) for c in req.colors],
            silhouettes=[interpreter.CodeHint(c.value, c.name) for c in req.silhouettes],
            genders=[interpreter.CodeHint(c.value, c.name) for c in req.genders],
            patterns=[interpreter.CodeHint(c.value, c.name) for c in req.patterns],
            details=[interpreter.CodeHint(c.value, c.name) for c in req.details],
            # 앞 대화는 최근 몇 건만, 질문은 상한까지만 — 화면이 보낸 값이라 길이를 믿지 않는다
            history=[interpreter.Turn(h.question[:MAX_QUESTION_LENGTH], h.trend, h.category or "", h.item_type or "",
                                      h.period_label or "", h.period_from or "", h.period_to or "")
                     for h in req.history[-interpreter.MAX_HISTORY:] if h.question.strip()])
        return interpreter.interpret(data, x_claude_api_key)
    except AiServiceError as e:
        log.warning("질문 해석 실패: %s", e)
        detail = {"message": str(e), "retryable": e.retryable}
        if e.usage:
            detail["usage"] = e.usage
        raise HTTPException(status_code=e.status_code, detail=detail)
