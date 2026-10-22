"""Claude 호출 공통부 — 정형화(structuring)와 RAG 답변(rag)이 같이 쓴다.

SDK 예외를 AiServiceError(HTTP 상태·재시도 여부)로 바꾸고, Structured Output 응답의 JSON을 읽고, 토큰 사용량을 정리한다.
Claude API 키는 요청마다 받아 쓰고 저장하지 않는다.
"""
import json

import anthropic


class AiServiceError(Exception):
    """AI 서비스 처리 실패 — FastAPI가 {"message", "retryable"}(+usage)로 돌려준다. retryable이면 Spring이 다시 시도해 볼 만하다."""

    def __init__(self, message: str, retryable: bool, status_code: int = 502, usage: dict | None = None):
        super().__init__(message)
        self.retryable = retryable
        self.status_code = status_code
        self.usage = usage  # Claude가 응답은 했지만 쓸 수 없던 경우(거절·잘림·JSON 오류)에도 토큰은 과금된다


def usage_of(response, elapsed: float) -> dict:
    usage = response.usage
    return {
        "input_tokens": usage.input_tokens,
        "output_tokens": usage.output_tokens,
        "cache_creation_input_tokens": getattr(usage, "cache_creation_input_tokens", None) or 0,
        "cache_read_input_tokens": getattr(usage, "cache_read_input_tokens", None) or 0,
        "elapsed_ms": int(elapsed * 1000),
    }


def create_message(client: anthropic.Anthropic | None, api_key: str, **kwargs):
    """Claude 호출 한 번. SDK 예외를 AiServiceError(재시도 여부·HTTP 상태)로 바꾼다 — 정형화·RAG 답변이 같이 쓴다."""
    if client is None:
        client = anthropic.Anthropic(api_key=api_key, max_retries=2, timeout=120.0)
    try:
        return client.messages.create(**kwargs)
    except anthropic.AuthenticationError as e:
        raise AiServiceError("Claude API 키가 올바르지 않습니다(claude.api.key).", retryable=False, status_code=401) from e
    except anthropic.BadRequestError as e:
        raise AiServiceError(f"Claude가 요청을 거부했습니다: {e.message}", retryable=False, status_code=400) from e
    except (anthropic.RateLimitError, anthropic.APIConnectionError, anthropic.InternalServerError) as e:
        raise AiServiceError(f"Claude 호출 일시 오류: {e}", retryable=True) from e
    except anthropic.APIStatusError as e:
        raise AiServiceError(f"Claude 호출 실패({e.status_code}): {e.message}", retryable=e.status_code >= 500) from e


def parse_json(response, usage: dict) -> dict:
    """Structured Output 응답의 JSON 본문. 거절·잘림·형식 오류면 사용량을 실은 AiServiceError."""
    if response.stop_reason == "refusal":
        category = response.stop_details.category if response.stop_details else None
        raise AiServiceError(f"Claude가 응답을 거절했습니다(분류: {category}).", retryable=False, status_code=422,
                               usage=usage)
    if response.stop_reason == "max_tokens":
        raise AiServiceError("응답이 길이 상한에서 잘렸습니다.", retryable=True, status_code=422, usage=usage)

    text = next((block.text for block in response.content if block.type == "text"), None)
    if text is None:
        raise AiServiceError("응답에 JSON 본문이 없습니다.", retryable=True, status_code=422, usage=usage)
    try:
        result = json.loads(text)
    except json.JSONDecodeError as e:
        raise AiServiceError(f"응답 JSON을 읽지 못했습니다: {e}", retryable=True, status_code=422,
                               usage=usage) from e
    return result
