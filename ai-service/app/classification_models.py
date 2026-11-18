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


class MerchantCandidate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    merchant_key: str = Field(min_length=1, max_length=200)
    merchant_name: str = Field(min_length=1, max_length=300)
    pfc_primary: str | None = Field(default=None, max_length=200)
    pfc_detailed: str | None = Field(default=None, max_length=250)


class ClassifyRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    candidates: list[MerchantCandidate] = Field(min_length=1, max_length=400)

    @model_validator(mode="after")
    def has_candidates(self) -> "ClassifyRequest":
        keys = [candidate.merchant_key for candidate in self.candidates]
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
    classifier_version: str
    degraded: bool = False


class ClassifierMetadata(BaseModel):
    classifier_version: str
    model: str
    prompt_version: str
