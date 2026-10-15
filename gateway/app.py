from __future__ import annotations

import os
import time
import json
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
from typing import Literal

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

app = FastAPI(title="Jarvis Cloud Gateway", version="0.1.0")


class CompletionRequest(BaseModel):
    prompt: str = Field(min_length=1, max_length=100_000)
    provider: Literal["anthropic", "openai", "google"] = "openai"
    model: str = "gpt-4o-mini"
    privacy_mode: bool = True
    max_cost_cents: int = Field(default=100, ge=0)


class CompletionResponse(BaseModel):
    text: str
    provider: str
    latency_ms: int
    estimated_cost_cents: int


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/v1/complete", response_model=CompletionResponse)
def complete(request: CompletionRequest) -> CompletionResponse:
    if request.privacy_mode:
        raise HTTPException(
            status_code=403,
            detail="Cloud requests are disabled while privacy mode is enabled.",
        )
    if not os.getenv("JARVIS_GATEWAY_TOKEN"):
        raise HTTPException(status_code=503, detail="Gateway credentials are not configured.")

    started = time.perf_counter()
    try:
        text, cost = _complete_with_provider(request)
    except HTTPException:
        raise
    except (HTTPError, URLError, TimeoutError, ValueError) as error:
        raise HTTPException(status_code=502, detail=f"{request.provider} request failed: {error}") from error
    return CompletionResponse(
        text=text,
        provider=request.provider,
        latency_ms=round((time.perf_counter() - started) * 1000),
        estimated_cost_cents=cost,
    )


def _complete_with_provider(request: CompletionRequest) -> tuple[str, int]:
    prompt = request.prompt
    if request.provider == "openai":
        key = os.getenv("OPENAI_API_KEY")
        if not key:
            raise HTTPException(status_code=503, detail="OPENAI_API_KEY is not configured.")
        payload = {
            "model": request.model,
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": 2048,
        }
        response = _post_json("https://api.openai.com/v1/chat/completions", payload, {"Authorization": f"Bearer {key}"})
        return response["choices"][0]["message"]["content"], _estimate_cost(request.model)
    if request.provider == "anthropic":
        key = os.getenv("ANTHROPIC_API_KEY")
        if not key:
            raise HTTPException(status_code=503, detail="ANTHROPIC_API_KEY is not configured.")
        payload = {
            "model": request.model or "claude-3-5-haiku-latest",
            "max_tokens": 2048,
            "messages": [{"role": "user", "content": prompt}],
        }
        response = _post_json(
            "https://api.anthropic.com/v1/messages",
            payload,
            {"x-api-key": key, "anthropic-version": "2023-06-01"},
        )
        return response["content"][0]["text"], _estimate_cost(request.model)
    key = os.getenv("GOOGLE_API_KEY")
    if not key:
        raise HTTPException(status_code=503, detail="GOOGLE_API_KEY is not configured.")
    payload = {"contents": [{"parts": [{"text": prompt}]}]}
    response = _post_json(
        f"https://generativelanguage.googleapis.com/v1beta/models/{request.model}:generateContent?key={key}",
        payload,
        {},
    )
    return response["candidates"][0]["content"]["parts"][0]["text"], _estimate_cost(request.model)


def _post_json(url: str, payload: dict, headers: dict) -> dict:
    request = Request(
        url,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", **headers},
        method="POST",
    )
    with urlopen(request, timeout=30) as response:
        return json.load(response)


def _estimate_cost(model: str) -> int:
    return 1 if "mini" in model or "haiku" in model or "flash" in model else 5
