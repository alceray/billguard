import logging
from typing import Any

import httpx
from pydantic import ValidationError

from app.classification_models import (
    ClassifyRequest,
    ClassifyResponse,
    ModelOutput,
)
from app.config import Settings
from app.metrics import OLLAMA_ERRORS

PROMPT_VERSION = "recurring-charge-v4"
CONTRACT_VERSION = "merchant-candidate-v2"
logger = logging.getLogger(__name__)

SYSTEM_PROMPT = """You classify consumer bank charges. Return only the requested JSON schema.
Do not invent merchants or keys. Categories must come from the supplied enum.

recurring_signal means this merchant bills the same customer again on a schedule. Judge the
merchant, not the amount.

confidence means how certain you are that recurring_signal is correct. It is not your certainty
about the category or the display name. Be certain only when the merchant name or the Plaid
category names a subscription or a bill. Say you are unsure when it does not.

A grocer, market, cafe, restaurant, bookstore, or fuel stop is other with recurring_signal false,
however many times it appears.

Housing means rent, mortgage, landlord, or property management. Finance means paid financial
services, not rent. Shopping_membership requires explicit membership evidence; ordinary retail is
other. A strong Plaid bill category is a recurring signal even with one transaction.

Keep reasoning factual and under 25 words. Never generate advice or cancellation prose."""


class Classifier:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    @property
    def classifier_version(self) -> str:
        return f"{PROMPT_VERSION}/{CONTRACT_VERSION}/{self._settings.ollama_model}"

    @property
    def model(self) -> str:
        return self._settings.ollama_model

    async def classify(self, request: ClassifyRequest) -> ClassifyResponse:
        if not self._settings.local_llm_mode:
            return self._fallback("rules-only")
        try:
            output = await self._call_ollama(request)
            return self._normalize(request, output)
        except (httpx.HTTPError, KeyError, TypeError, ValueError, ValidationError) as exc:
            OLLAMA_ERRORS.inc()
            logger.warning(
                "Ollama classification unavailable", extra={"error": str(exc)}
            )
            return self._fallback(self._settings.ollama_model)

    async def _call_ollama(self, request: ClassifyRequest) -> ModelOutput:
        candidates = request.candidates
        schema = ModelOutput.model_json_schema()
        results_schema = schema["properties"]["results"]
        results_schema["minItems"] = len(candidates)
        results_schema["maxItems"] = len(candidates)
        schema["$defs"]["ClassificationResult"]["properties"]["merchant_key"]["enum"] = [
            candidate.merchant_key for candidate in candidates
        ]
        user_prompt = (
            f"Return exactly {len(candidates)} results, in input order. "
            f"Classify these merchants. Input: {request.model_dump_json()}"
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
            "options": {"temperature": 0, "num_ctx": self._settings.ollama_num_ctx},
        }
        async with httpx.AsyncClient(timeout=self._settings.ollama_timeout_seconds) as client:
            endpoint = f"{str(self._settings.ollama_base_url).rstrip('/')}/api/chat"
            response = await client.post(endpoint, json=payload)
            response.raise_for_status()
        body = response.json()
        content = body["message"]["content"]
        output = ModelOutput.model_validate_json(content)
        logger.info(
            "Ollama classification metrics prompt_eval_count=%s eval_count=%s "
            "total_duration=%s raw_result_count=%s",
            body.get("prompt_eval_count"),
            body.get("eval_count"),
            body.get("total_duration"),
            len(output.results),
        )
        return output

    def _normalize(self, request: ClassifyRequest, output: ModelOutput) -> ClassifyResponse:
        candidate_keys = {item.merchant_key for item in request.candidates}
        model_results = {result.merchant_key: result for result in output.results}
        results = [
            model_results[candidate.merchant_key]
            for candidate in request.candidates
            if candidate.merchant_key in model_results
        ]
        return ClassifyResponse(
            results=results,
            model=self._settings.ollama_model,
            prompt_version=PROMPT_VERSION,
            classifier_version=self.classifier_version,
            degraded=set(model_results) != candidate_keys,
        )

    def _fallback(self, model: str) -> ClassifyResponse:
        return ClassifyResponse(
            results=[],
            model=model,
            prompt_version=PROMPT_VERSION,
            classifier_version=self.classifier_version,
            degraded=True,
        )
