"""키워드 색인(BM25) — 하이브리드 검색의 키워드 쪽. 벡터 검색이 놓치는 드문 고유한 말("무스탕"·"발마칸")을 글자 그대로 찾는다.

Chroma 청크 글로 메모리에 만든다(따로 저장하지 않는다 — Chroma가 원본이라 어긋날 일이 없다). 색인이 바뀌면 invalidate()로
버리고 다음 검색 때 다시 만든다. 토큰은 공백·기호를 지운 글자 2-gram이다 — 형태소 분석기 없이 "와이드팬츠"와 "와이드 팬츠"가
같게 잡힌다(2026-10-30 사용자 결정).
"""
import math
import re
import threading
from collections import Counter

K1, B = 1.2, 0.75  # BM25 표준값
_NOT_WORD = re.compile(r"[^0-9a-z가-힣]+")


def tokens(text: str) -> list[str]:
    s = _NOT_WORD.sub("", (text or "").lower())
    if len(s) < 2:
        return [s] if s else []
    return [s[i:i + 2] for i in range(len(s) - 1)]


class KeywordIndex:
    def __init__(self, ids: list[str], documents: list[str]):
        self.ids = ids
        self.tf = [Counter(tokens(d)) for d in documents]
        self.lengths = [sum(tf.values()) for tf in self.tf]
        self.avg_length = (sum(self.lengths) / len(self.lengths)) if self.lengths else 0.0
        self.df: Counter = Counter()
        for tf in self.tf:
            self.df.update(tf.keys())

    def search(self, query: str, limit: int, allowed: set[str] | None = None) -> list[tuple[str, float]]:
        """(청크 ID, BM25 점수) 높은 순. allowed가 있으면 그 청크만(메타 필터를 통과한 것). 점수 0은 뺀다."""
        n = len(self.ids)
        terms = set(tokens(query))
        if not n or not terms:
            return []
        idf = {t: math.log(1 + (n - self.df[t] + 0.5) / (self.df[t] + 0.5)) for t in terms if self.df[t]}
        scored = []
        for i, tf in enumerate(self.tf):
            if allowed is not None and self.ids[i] not in allowed:
                continue
            norm = K1 * (1 - B + B * self.lengths[i] / self.avg_length) if self.avg_length else K1
            score = sum(w * tf[t] * (K1 + 1) / (tf[t] + norm) for t, w in idf.items() if tf[t])
            if score > 0:
                scored.append((self.ids[i], score))
        return sorted(scored, key=lambda e: -e[1])[:limit]


_lock = threading.Lock()
_index: KeywordIndex | None = None
_source = None  # 색인을 만든 컬렉션 — 다른 컬렉션이면 다시 만든다


def invalidate() -> None:
    """청크를 넣거나 지우면 부른다 — 다음 검색 때 다시 만든다."""
    global _index
    with _lock:
        _index = None


def get(collection) -> KeywordIndex:
    global _index, _source
    with _lock:
        if _index is None or _source is not collection:
            got = collection.get(include=["documents"])
            _index, _source = KeywordIndex(list(got["ids"]), [d or "" for d in got["documents"]]), collection
        return _index
