"""RAG 질문의 기간 해석 — 질문에 기간이 분명할 때만 근거를 그 게시일 범위로 자른다.

질문에 기간이 분명하면(지난달·이번 주·최근 3개월·올해·작년…) 그 기간을 쓴다. 시즌 표현(올가을·26FW 등)은 그 시즌을 다룬
기사가 몇 달 앞서 나오므로 최근 6개월을 본다.
"요즘"·"최근"처럼 막연하거나 기간이 없으면 **자르지 않는다**(explicit=False, 범위 없음) — 대신 answer.py가 유사도에
최신성 가중치(반감기 4주)를 곱해 최근 자료를 앞세운다(2026-10-22 사용자 결정: 고정 4주로 자르면 데이터가 띄엄띄엄일 때
관련 깊은 5주 전 자료를 버리고 덜 관련된 1건으로 답하게 된다).
"""
import re
from dataclasses import dataclass
from datetime import date, timedelta

SEASON_DAYS = 183
RECENT_LABEL = "최근 자료 우선"


@dataclass(frozen=True)
class Period:
    start: date | None  # 기간이 없는 질문(최근 자료 우선)은 None
    end: date | None
    label: str          # 화면·프롬프트에 그대로 쓰는 설명(예: "지난달(9월)")
    explicit: bool      # 질문에 기간이 있었는지 — 있으면 그 범위로 자른다


def _month_start(d: date) -> date:
    return d.replace(day=1)


def _prev_month(d: date) -> tuple[date, date]:
    end = _month_start(d) - timedelta(days=1)
    return _month_start(end), end


_RECENT_N = re.compile(r"(?:최근|지난)\s*(\d{1,3})\s*(일|주|개월|달)")
_SEASON = re.compile(r"올\s*(봄|여름|가을|겨울)|이번\s*(봄|여름|가을|겨울)|\d{2}\s*(SS|FW|S/S|F/W)|(SS|FW)\s*시즌", re.IGNORECASE)


def resolve(question: str, today: date) -> Period:
    q = question.replace(" ", "")
    m = _RECENT_N.search(question)
    if m:
        n, unit = int(m.group(1)), m.group(2)
        days = {"일": n, "주": n * 7}.get(unit, n * 30)
        days = max(1, min(days, 3 * 365))
        return Period(today - timedelta(days=days - 1), today, f"최근 {n}{unit}", True)
    if "지난주" in q or "저번주" in q:
        monday = today - timedelta(days=today.weekday())
        return Period(monday - timedelta(days=7), monday - timedelta(days=1), "지난주", True)
    if "이번주" in q or "금주" in q:
        return Period(today - timedelta(days=today.weekday()), today, "이번 주", True)
    if "지난달" in q or "저번달" in q:  # "전월 대비"는 비교 표현이라 기간으로 보지 않는다
        start, end = _prev_month(today)
        return Period(start, end, f"지난달({start.month}월)", True)
    if "이번달" in q or "이달" in q or "금월" in q:
        return Period(_month_start(today), today, f"이번 달({today.month}월)", True)
    if "작년" in q or "지난해" in q:
        return Period(date(today.year - 1, 1, 1), date(today.year - 1, 12, 31), f"작년({today.year - 1}년)", True)
    if "올해" in q or "금년" in q:
        return Period(date(today.year, 1, 1), today, f"올해({today.year}년)", True)
    if _SEASON.search(question):
        return Period(today - timedelta(days=SEASON_DAYS - 1), today, "최근 6개월(시즌 표현)", True)
    return Period(None, None, RECENT_LABEL, False)


def where(period: Period) -> dict | None:
    """Chroma 메타 필터 — published_num(20261022)이 기간 안인 청크만. 기간이 없는 질문은 자르지 않는다(None)."""
    if not period.explicit:
        return None
    start, end = int(period.start.strftime("%Y%m%d")), int(period.end.strftime("%Y%m%d"))
    return {"$and": [{"published_num": {"$gte": start}}, {"published_num": {"$lte": end}}]}
