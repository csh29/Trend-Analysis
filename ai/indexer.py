"""P-04 색인 — 검증을 통과한 문서 한 건을 청크로 나눠 Chroma에 출처 메타와 함께 넣는다.

같은 문서를 다시 분석하면 이전 청크를 지우고 새로 넣는다(문서 ID 단위로 갈아끼움). 이미지는 본문이 없으므로
정형화 결과(요약·키워드·소재·컬러)를 문장으로 만든 청크 하나를 넣는다. RAG(P-06)가 근거를 표시할 때 메타를 쓴다.
"""
from chunker import split_text
from config import settings


def summary_chunk(structured: dict, names: dict[str, str]) -> str:
    """정형 결과를 검색되는 문장으로 — 코드값(LEATHER) 대신 이름(레더)을 써야 한국어 질문과 맞는다."""
    def labels(values: list[str]) -> str:
        return ", ".join(names.get(v, v) for v in values)

    parts = [structured.get("summary", "")]
    if structured.get("keywords"):
        parts.append("키워드: " + ", ".join(structured["keywords"]))
    if structured.get("category"):
        parts.append("카테고리: " + names.get(structured["category"], structured["category"]))
    for key, label in (("materials", "소재"), ("colors", "컬러"), ("silhouettes", "실루엣")):
        if structured.get(key):
            parts.append(f"{label}: {labels(structured[key])}")
    return "\n".join(p for p in parts if p)


def build_chunks(doc_type: str, text: str | None, structured: dict, names: dict[str, str]) -> list[str]:
    chunks = [summary_chunk(structured, names)]
    if doc_type == "TEXT" and text:
        chunks.extend(split_text(text, settings.chunk_size, settings.chunk_overlap))
    return chunks


def index_document(collection, doc_id: str, doc_type: str, text: str | None, structured: dict,
                   names: dict[str, str], meta: dict) -> int:
    chunks = build_chunks(doc_type, text, structured, names)
    delete_document(collection, doc_id)
    base_meta = {
        "doc_id": doc_id,
        "doc_type": doc_type,
        "media": meta.get("media") or "",
        "published_at": meta.get("published_at") or "",
        "url": meta.get("url") or "",
        "category": structured.get("category") or "",
    }
    collection.add(
        ids=[f"{doc_id}#{i}" for i in range(len(chunks))],
        documents=chunks,
        metadatas=[{**base_meta, "chunk_index": i} for i in range(len(chunks))],
    )
    return len(chunks)


def delete_document(collection, doc_id: str) -> None:
    collection.delete(where={"doc_id": doc_id})
