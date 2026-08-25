import json
import logging
from typing import Any

import httpx
from pydantic import ValidationError

from app.classification_models import (
    Category,
    ChargeStream,
    ClassificationResult,
    ClassifyRequest,
    ClassifyResponse,
    ModelOutput,
)
from app.config import Settings
from app.metrics import OLLAMA_ERRORS

PROMPT_VERSION = "recurring-charge-v2"
logger = logging.getLogger(__name__)

SYSTEM_PROMPT = """You classify recurring consumer charges. Return only the requested JSON schema.
Do not invent merchants or keys. Categories must come from the supplied enum. Treat a singleton as
recurring only when its merchant or Plaid category is a strong subscription, bill, or fixed-expense
signal. Housing means rent, mortgage, landlord, or property management. Finance means paid financial
services, not rent. Shopping_membership requires explicit membership evidence; ordinary retail is
other. A strong Plaid bill category is a recurring signal even with one transaction. Keep reasoning
factual and under 25 words. Never generate advice or cancellation prose."""


class Classifier:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def classify(self, request: ClassifyRequest) -> ClassifyResponse:
        if not self._settings.local_llm_mode:
            return self._fallback(request, "rules-only")
        try:
            output = await self._call_ollama(request)
            return self._normalize(request, output)
        except (httpx.HTTPError, KeyError, TypeError, ValueError, ValidationError) as exc:
            OLLAMA_ERRORS.inc()
            logger.warning(
                "Ollama classification unavailable; using rules", extra={"error": str(exc)}
            )
            return self._fallback(request, self._settings.ollama_model)

    async def _call_ollama(self, request: ClassifyRequest) -> ModelOutput:
        candidates = request.streams + request.singletons
        schema = ModelOutput.model_json_schema()
        results_schema = schema["properties"]["results"]
        results_schema["minItems"] = len(candidates)
        results_schema["maxItems"] = len(candidates)
        merchant_keys = [candidate.merchant_key for candidate in candidates]
        user_prompt = (
            f"Return exactly {len(candidates)} results, in input order, one for each merchant_key "
            f"in {json.dumps(merchant_keys)}. Classify these aggregated charge streams. "
            "The JSON schema is "
            f"{json.dumps(schema, separators=(',', ':'))}. Input: {request.model_dump_json()}"
        )
        payload: dict[str, Any] = {
            "model": self._settings.ollama_model,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": user_prompt},
            ],
            "stream": False,
            "format": schema,
            "keep_alive": self._settings.ollama_keep_alive,
            "options": {"temperature": 0},
        }
        async with httpx.AsyncClient(timeout=self._settings.ollama_timeout_seconds) as client:
            endpoint = f"{str(self._settings.ollama_base_url).rstrip('/')}/api/chat"
            response = await client.post(endpoint, json=payload)
            response.raise_for_status()
        content = response.json()["message"]["content"]
        return ModelOutput.model_validate_json(content)

    def _normalize(self, request: ClassifyRequest, output: ModelOutput) -> ClassifyResponse:
        candidates = {item.merchant_key: item for item in request.streams + request.singletons}
        model_results = {result.merchant_key: result for result in output.results}
        results: list[ClassificationResult] = []
        for merchant_key, candidate in candidates.items():
            result = model_results.get(merchant_key)
            results.append(result if result is not None else self._rule_result(candidate))
        return ClassifyResponse(
            results=results,
            model=self._settings.ollama_model,
            prompt_version=PROMPT_VERSION,
            degraded=len(model_results) != len(candidates),
        )

    def _fallback(self, request: ClassifyRequest, model: str) -> ClassifyResponse:
        return ClassifyResponse(
            results=[self._rule_result(item) for item in request.streams + request.singletons],
            model=model,
            prompt_version=PROMPT_VERSION,
            degraded=True,
        )

    def _rule_result(self, stream: ChargeStream) -> ClassificationResult:
        text = (
            " ".join(
                value or ""
                for value in (stream.merchant_name, stream.pfc_primary, stream.pfc_detailed)
            )
            .lower()
            .replace("_", " ")
        )
        category = _category(text)
        recurring_signal = stream.transaction_count >= 2 or category != "other"
        return ClassificationResult(
            merchant_key=stream.merchant_key,
            display_name=_display_name(stream.merchant_name),
            category=category,
            confidence=0.75 if category != "other" else 0.35,
            recurring_signal=recurring_signal,
            reasoning="Deterministic merchant and Plaid category fallback",
        )


def _display_name(value: str) -> str:
    return " ".join(value.strip().split()).title()


def _category(text: str) -> Category:
    rules: tuple[tuple[Category, tuple[str, ...]], ...] = (
        ("streaming", ("netflix", "hulu", "spotify", "tv and movies", "music and audio")),
        ("ai", ("openai", "chatgpt", "anthropic", "claude", "midjourney")),
        ("software", ("github", "adobe", "software", "saas")),
        ("gaming", ("xbox", "playstation", "nintendo", "gaming")),
        ("news_and_media", ("news", "newspaper", "media subscription")),
        ("fitness_and_health", ("gym", "fitness", "peloton", "health club")),
        ("insurance", ("insurance", "premium")),
        ("storage_and_hosting", ("icloud", "dropbox", "cloud storage", "general services storage")),
        ("telecom", ("mobile phone", "wireless", "telephone", "internet and cable", "broadband")),
        ("housing", ("rent and utilities rent", "landlord", "mortgage", "property management")),
        ("loan", ("loan payments", "loan repayments", "student loan", "auto loan")),
        ("utilities", ("electric", "utility", "utilities", "water", "gas bill", "sewage")),
        ("food_and_delivery", ("doordash", "uber eats", "meal kit", "food delivery")),
        ("shopping_membership", ("membership", "costco", "amazon prime")),
        ("education", ("education", "tuition", "course")),
        ("finance", ("credit monitoring", "financial service", "investing subscription")),
    )
    for category, terms in rules:
        if any(term in text for term in terms):
            return category
    return "other"
