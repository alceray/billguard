import argparse
import asyncio
import json
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from app.classification_models import ClassifyRequest
from app.classifier import CONTRACT_VERSION, PROMPT_VERSION, Classifier
from app.config import Settings

ROOT = Path(__file__).parents[1]
CASES_PATH = ROOT / "evals" / "classification_cases.json"
RESULTS_PATH = ROOT / "evals" / "results.json"


def request_for(cases: list[dict[str, str]]) -> ClassifyRequest:
    return ClassifyRequest.model_validate(
        {
            "candidates": [
                {
                    "merchant_key": f"case-{index}",
                    "merchant_name": case["merchant"],
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
    model = await Classifier(Settings(local_llm_mode=True)).classify(request)
    results = {item.merchant_key: item.category for item in model.results}

    # An unanswered key is not a wrong answer; scoring it as one turns an unreachable
    # Ollama into a recorded accuracy regression.
    answered = [
        (case, results[f"case-{index}"])
        for index, case in enumerate(cases)
        if f"case-{index}" in results
    ]
    return {
        "prompt_version": PROMPT_VERSION,
        "contract_version": CONTRACT_VERSION,
        "evaluated_at": datetime.now(UTC).date().isoformat(),
        "labelled_cases": len(cases),
        "ollama_model": model.model,
        "ollama_accuracy": (
            accuracy([case for case, _ in answered], [category for _, category in answered])
            if answered
            else None
        ),
        "answered_keys": len(answered),
        "unanswered_keys": len(cases) - len(answered),
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
        # results.json is committed, so it must never record a run that did not happen.
        if result["answered_keys"] == 0:
            raise SystemExit(
                "Refusing to write results.json: the model answered nothing. "
                "Start Ollama and re-run."
            )
        RESULTS_PATH.write_text(rendered, encoding="utf-8")
    if args.require_model and result["model_degraded"]:
        raise SystemExit("Ollama did not return a complete structured response")


if __name__ == "__main__":
    asyncio.run(main())
