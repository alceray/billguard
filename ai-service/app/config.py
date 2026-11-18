from functools import lru_cache

from pydantic import AnyHttpUrl, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    app_env: str = "dev"
    log_level: str = "INFO"
    local_llm_mode: bool = True
    openai_api_key: SecretStr | None = None
    ollama_base_url: AnyHttpUrl = AnyHttpUrl("http://localhost:11434")
    ollama_model: str = "gemma3:1b"
    ollama_keep_alive: str = "0"
    ollama_timeout_seconds: float = 25.0
    ollama_num_ctx: int = 4096


@lru_cache
def get_settings() -> Settings:
    return Settings()
