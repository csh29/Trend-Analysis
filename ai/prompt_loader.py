"""Claude에게 보내는 글(프롬프트)을 prompts/*.md에서 읽는다 — 판단 기준을 코드와 떼어 두고 글만 고칠 수 있게.

md는 "## 키" 제목으로 구역을 나눈다. 주석(<!-- -->)은 보내지 않는다. 기동 때 한 번 읽고, 필요한 구역이 빠졌으면
바로 실패한다 — 프롬프트가 깨진 채로 분석이 돌면 결과가 조용히 나빠진다.
"""
import re
from pathlib import Path

PROMPT_DIR = Path(__file__).resolve().parent / "prompts"

_COMMENT = re.compile(r"<!--.*?-->", re.DOTALL)
_HEADING = re.compile(r"^## +(\S+)\s*$", re.MULTILINE)


def parse_sections(text: str) -> dict[str, str]:
    """"## 키" 아래 글(앞뒤 빈 줄 제거)을 키별로."""
    text = _COMMENT.sub("", text)
    matches = list(_HEADING.finditer(text))
    sections = {}
    for i, m in enumerate(matches):
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        sections[m.group(1)] = text[m.end():end].strip()
    return sections


def load(name: str, required: list[str]) -> dict[str, str]:
    path = PROMPT_DIR / f"{name}.md"
    sections = parse_sections(path.read_text(encoding="utf-8"))
    missing = [key for key in required if not sections.get(key)]
    if missing:
        raise RuntimeError(f"프롬프트 {path.name}에 구역이 없거나 비었습니다: {missing}")
    return sections
