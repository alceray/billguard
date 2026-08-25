from datetime import date
from decimal import Decimal
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

Category = Literal[
    "streaming",
    "ai",
    "software",
    "gaming",
    "news_and_media",
    "fitness_and_health",
    "insurance",
    "storage_and_hosting",
    "telecom",
    "utilities",
    "food_and_delivery",
    "shopping_membership",
    "education",
    "finance",
    "housing",
    "loan",
    "other",
]


class ChargeStream(BaseModel):
    model_config = ConfigDict(extra="forbid")

    merchant_key: str = Field(min_length=1, max_length=200)
    merchant_name: str = Field(min_length=1, max_length=300)
    transaction_count: int = Field(ge=1, le=100)
    amounts: list[Decimal] = Field(min_length=1, max_length=100)
    dates: list[date] = Field(min_length=1, max_length=100)
    currency: str = Field(min_length=3, max_length=3)
    pfc_primary: str | None = Field(default=None, max_length=200)
    pfc_detailed: str | None = Field(default=None, max_length=250)

    @model_validator(mode="after")
    def matching_series_lengths(self) -> "ChargeStream":
        if self.transaction_count != len(self.amounts) or self.transaction_count != len(self.dates):
            raise ValueError("transaction_count must match amounts and dates")
        return self


class ClassifyRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    streams: list[ChargeStream] = Field(default_factory=list, max_length=200)
    singletons: list[ChargeStream] = Field(default_factory=list, max_length=200)

    @model_validator(mode="after")
    def has_candidates(self) -> "ClassifyRequest":
        if not self.streams and not self.singletons:
            raise ValueError("at least one stream or singleton is required")
        if any(stream.transaction_count < 3 for stream in self.streams):
            raise ValueError("streams must contain at least three transactions")
        if any(stream.transaction_count > 2 for stream in self.singletons):
            raise ValueError("singletons must contain one or two transactions")
        keys = [stream.merchant_key for stream in self.streams + self.singletons]
        if len(keys) != len(set(keys)):
            raise ValueError("merchant_key values must be unique")
        return self


class ClassificationResult(BaseModel):
    model_config = ConfigDict(extra="forbid")

    merchant_key: str
    display_name: str = Field(min_length=1, max_length=120)
    category: Category
    confidence: float = Field(ge=0, le=1)
    recurring_signal: bool
    reasoning: str = Field(min_length=1, max_length=300)


class ModelOutput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    results: list[ClassificationResult]


class ClassifyResponse(BaseModel):
    results: list[ClassificationResult]
    model: str
    prompt_version: str
    degraded: bool = False
