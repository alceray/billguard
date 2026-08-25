import argparse
import asyncio
import json
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from app.classification_models import ClassifyRequest
from app.classifier import PROMPT_VERSION, Classifier
from app.config import Settings

ROOT = Path(__file__).parents[1]
CASES_PATH = ROOT / "evals" / "classification_cases.json"
RESULTS_PATH = ROOT / "evals" / "results.json"


def request_for(cases: list[dict[str, str]]) -> ClassifyRequest:
    return ClassifyRequest.model_validate(
        {
            "streams": [],
            "singletons": [
                {
                    "merchant_key": f"case-{index}",
                    "merchant_name": case["merchant"],
                    "transaction_count": 1,
                    "amounts": ["9.99"],
                    "dates": ["2026-08-01"],
                    "currency": "USD",
                    "pfc_primary": None,
                    "pfc_detailed": case["pfc"],
                }
                for index, case in enumerate(cases)
            ],
        }
    )


def accuracy(cases: list[dict[str, str]], categories: list[str]) -> float:
    correct = sum(
        category == case["category"] for case, category in zip(cases, categories, strict=True)
    )
    return correct / len(cases)


async def evaluate() -> dict[str, Any]:
    cases: list[dict[str, str]] = json.loads(CASES_PATH.read_text(encoding="utf-8"))
    request = request_for(cases)
    rules = await Classifier(Settings(local_llm_mode=False)).classify(request)
    model = await Classifier(Settings(local_llm_mode=True)).classify(request)

    return {
        "prompt_version": PROMPT_VERSION,
        "evaluated_at": datetime.now(UTC).date().isoformat(),
        "labelled_cases": len(cases),
        "rules_fallback_accuracy": accuracy(cases, [item.category for item in rules.results]),
        "ollama_model": model.model,
        "ollama_accuracy": None
        if model.degraded
        else accuracy(cases, [item.category for item in model.results]),
        "model_degraded": model.degraded,
    }


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--require-model", action="store_true")
    parser.add_argument("--write-results", action="store_true")
    args = parser.parse_args()

    result = await evaluate()
    rendered = json.dumps(result, indent=2) + "\n"
    print(rendered, end="")
    if args.write_results:
        RESULTS_PATH.write_text(rendered, encoding="utf-8")
    if result["rules_fallback_accuracy"] != 1:
        raise SystemExit("Rules fixture accuracy regressed")
    if args.require_model and result["model_degraded"]:
        raise SystemExit("Ollama did not return a complete structured response")


if __name__ == "__main__":
    asyncio.run(main())
