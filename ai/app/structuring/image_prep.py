"""Claude에 보내기 전 이미지 크기 맞추기. Sonnet 5는 긴 변 2576px까지 읽고, API는 이미지 한 장 5MB(base64 전)까지 받는다.
업로드는 한 장 20MB까지 허용하므로 큰 사진은 여기서 줄여 보낸다 — 줄이지 않으면 요청이 거부된다.
"""
import base64
import io

from PIL import Image

MAX_LONG_EDGE = 2576
MAX_BYTES = 5 * 1024 * 1024 - 64 * 1024  # 여유를 둔다
# 원문 하나에 이미지가 여러 장이면 장마다 더 줄인다 — 요청 전체 상한(32MB)을 넘지 않게. 1568px는 여러 장을 보낼 때 권장 크기다.
MULTI_LONG_EDGE = 1568
MULTI_BYTES = 3 * 1024 * 1024
SUPPORTED = {"image/jpeg", "image/png", "image/gif", "image/webp"}


def prepare(image_bytes: bytes, media_type: str, multiple: bool = False) -> tuple[str, str]:
    """(base64 문자열, media_type). 그대로 보낼 수 있으면 원본을 쓰고, 아니면 줄여서 JPEG로 바꾼다."""
    max_edge, max_bytes = (MULTI_LONG_EDGE, MULTI_BYTES) if multiple else (MAX_LONG_EDGE, MAX_BYTES)
    image = Image.open(io.BytesIO(image_bytes))
    if (media_type in SUPPORTED and len(image_bytes) <= max_bytes
            and max(image.size) <= max_edge):
        return base64.standard_b64encode(image_bytes).decode("ascii"), media_type

    # BMP처럼 API가 받지 않는 형식이거나 너무 크면 RGB JPEG로 다시 만든다(GIF는 첫 프레임만)
    image.seek(0)
    image = image.convert("RGB")
    image.thumbnail((max_edge, max_edge))
    quality = 90
    while True:
        buffer = io.BytesIO()
        image.save(buffer, format="JPEG", quality=quality)
        data = buffer.getvalue()
        if len(data) <= max_bytes or quality <= 40:
            return base64.standard_b64encode(data).decode("ascii"), "image/jpeg"
        quality -= 15
