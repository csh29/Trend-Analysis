"""P-04 색인용 청크 분할. 문단 → 문장 경계를 우선해 자르고, 앞 청크 끝을 조금 겹쳐 문맥이 끊기지 않게 한다."""
import re


def split_text(text: str, size: int, overlap: int) -> list[str]:
    text = re.sub(r"[ \t]+", " ", (text or "").strip())
    if not text:
        return []
    if len(text) <= size:
        return [text]

    chunks: list[str] = []
    start = 0
    while start < len(text):
        end = min(start + size, len(text))
        if end < len(text):
            # 청크 끝을 가장 가까운 문단·문장 경계로 당긴다(청크 절반 아래로는 당기지 않는다)
            window = text[start:end]
            cut = max(window.rfind("\n"), window.rfind(". "), window.rfind("다. "), window.rfind("? "), window.rfind("! "))
            if cut >= size // 2:
                end = start + cut + 1
        chunk = text[start:end].strip()
        if chunk:
            chunks.append(chunk)
        if end >= len(text):
            break
        start = max(end - overlap, start + 1)
    return chunks
