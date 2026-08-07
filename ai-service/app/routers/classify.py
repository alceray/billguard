from datetime import date
from decimal import Decimal
from typing import Literal

from fastapi import APIRouter
from pydantic import BaseModel, ConfigDict, Field

router = APIRouter(prefix="/internal", tags=["internal"])


class Transaction(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: str = Field(min_length=1)
    merchant_name: str | None = None
    name: str = Field(min_length=1)
    amount: Decimal
    currency: str = Field(min_length=3, max_length=3)
    date: date


class ClassifyRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    transactions: list[Transaction] = Field(min_length=1)


class ClassificationResult(BaseModel):
    transaction_id: str
    is_recurring: bool
    confidence: float = Field(ge=0, le=1)
    billing_cycle: Literal["weekly", "monthly", "quarterly", "annual"] | None
    reasoning: str


class ClassifyResponse(BaseModel):
    results: list[ClassificationResult]
    model: str


@router.post("/classify", response_model=ClassifyResponse)
async def classify(request: ClassifyRequest) -> ClassifyResponse:
    return ClassifyResponse(
        results=[
            ClassificationResult(
                transaction_id=transaction.id,
                is_recurring=False,
                confidence=0.0,
                billing_cycle=None,
                reasoning="Phase 1 stub; no model inference performed",
            )
            for transaction in request.transactions
        ],
        model="stub",
    )

