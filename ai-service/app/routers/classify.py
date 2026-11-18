from functools import lru_cache
from typing import Annotated

from fastapi import APIRouter, Depends

from app.classification_models import ClassifierMetadata, ClassifyRequest, ClassifyResponse
from app.classifier import PROMPT_VERSION, Classifier
from app.config import get_settings
from app.metrics import CLASSIFICATION_LATENCY, CLASSIFICATION_REQUESTS

router = APIRouter(prefix="/internal", tags=["internal"])


@lru_cache
def get_classifier() -> Classifier:
    return Classifier(get_settings())


@router.post("/classify", response_model=ClassifyResponse)
async def classify(
    request: ClassifyRequest,
    classifier: Annotated[Classifier, Depends(get_classifier)],
) -> ClassifyResponse:
    with CLASSIFICATION_LATENCY.time():
        response = await classifier.classify(request)
    CLASSIFICATION_REQUESTS.labels(outcome="degraded" if response.degraded else "model").inc()
    return response


@router.get("/classify/metadata", response_model=ClassifierMetadata)
async def metadata(
    classifier: Annotated[Classifier, Depends(get_classifier)],
) -> ClassifierMetadata:
    return ClassifierMetadata(
        classifier_version=classifier.classifier_version,
        model=classifier.model,
        prompt_version=PROMPT_VERSION,
    )
