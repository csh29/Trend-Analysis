"""P-04 색인 — 검증을 통과한 문서 한 건을 청크로 나눠 Chroma에 출처 메타와 함께 넣는다.

같은 문서를 다시 분석하면 이전 청크를 지우고 새로 넣는다(문서 ID 단위로 갈아끼움). 이미지는 본문이 없으므로
정형화 결과(요약·키워드·의류 아이템별 소재·컬러·실루엣)를 문장으로 만든 청크 하나를 넣는다. RAG(P-06)가 근거를 표시할 때 메타를 쓴다.
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
    if structured.get("seasons"):
        parts.append("시즌: " + labels(structured["seasons"]))
    # 아이템 한 줄씩 — 속성을 그 아이템에 붙여 써야 "블랙 스커트"와 "그레이 카디건"이 섞이지 않는다
    for item in structured.get("items") or []:
        attrs = [f"{label} {labels(item[key])}"
                 for key, label in (("materials", "소재"), ("colors", "컬러"), ("silhouettes", "실루엣")) if item.get(key)]
        category = names.get(item.get("category"), item.get("category") or "")
        parts.append(f"아이템: {item.get('item', '')}({category})" + (" — " + ", ".join(attrs) if attrs else ""))
    return "\n".join(p for p in parts if p)


def item_categories(structured: dict) -> list[str]:
    """아이템 카테고리(중복 없이, 나온 순서). 첫 번째가 대표 카테고리다."""
    result = []
    for item in structured.get("items") or []:
        if item.get("category") and item["category"] not in result:
            result.append(item["category"])
    return result


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
        # Chroma 메타 값은 스칼라만 된다 — 대표(첫 아이템) 카테고리와 전체 목록(쉼표)을 따로 둔다
        "category": (item_categories(structured) or [""])[0],
        "categories": ",".join(item_categories(structured)),
        "seasons": ",".join(structured.get("seasons") or []),
    }
    collection.add(
        ids=[f"{doc_id}#{i}" for i in range(len(chunks))],
        documents=chunks,
        metadatas=[{**base_meta, "chunk_index": i} for i in range(len(chunks))],
    )
    return len(chunks)


def delete_document(collection, doc_id: str) -> None:
    collection.delete(where={"doc_id": doc_id})


EMBEDDING_PREVIEW = 8  # 벡터는 2048개라 응답에는 앞부분과 차원 수만 싣는다


def list_chunks(collection, doc_id: str | None, limit: int, offset: int, with_embedding: bool) -> list[dict]:
    """저장된 청크 조회(확인용, 읽기 전용). doc_id를 주면 그 문서의 청크만."""
    include = ["documents", "metadatas"] + (["embeddings"] if with_embedding else [])
    got = collection.get(where={"doc_id": doc_id} if doc_id else None, limit=limit, offset=offset, include=include)
    embeddings = got.get("embeddings") if with_embedding else None
    items = []
    for i, chunk_id in enumerate(got["ids"]):
        item = {"id": chunk_id, "document": got["documents"][i], "metadata": got["metadatas"][i]}
        if embeddings is not None:
            vector = [float(v) for v in embeddings[i]]
            item["embedding_dim"] = len(vector)
            item["embedding_preview"] = [round(v, 5) for v in vector[:EMBEDDING_PREVIEW]]
        items.append(item)
    return items
