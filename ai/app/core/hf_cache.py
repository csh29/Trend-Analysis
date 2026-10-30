"""Hugging Face 모델을 한 번 받은 뒤에는 인터넷에 묻지 않고 로컬 캐시로 올린다.

캐시가 있어도 모델을 올릴 때마다 HF에 파일이 바뀌었는지 묻는 요청이 수십 번 나가 몇 초씩 걸렸다(2026-10-30). 캐시가 없으면
(처음) 평소처럼 내려받는다. 모델을 새 버전으로 받고 싶으면 캐시(~/.cache/huggingface)에서 그 모델을 지운다.
"""
from huggingface_hub import try_to_load_from_cache


def is_cached(model_name: str) -> bool:
    try:
        return isinstance(try_to_load_from_cache(model_name, "config.json"), str)
    except Exception:  # 이름 형식이 다르거나 캐시를 못 읽으면 평소처럼 받는다
        return False
