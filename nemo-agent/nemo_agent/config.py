from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AI_", case_sensitive=False)

    environment: str = "production"
    provider: str = ""
    model_id: str = ""
    api_key: SecretStr = SecretStr("")
    base_url: str = ""
    internal_secret: SecretStr = SecretStr("")
    question_service_url: str = "http://question-service:8103"
    rabbitmq_host: str = "rabbitmq"
    rabbitmq_port: int = 5672
    rabbitmq_user: str = "guest"
    rabbitmq_password: SecretStr = SecretStr("guest")
    postgres_url: SecretStr = SecretStr("")
    queue: str = "nemo.ai.tasks"
    max_output_tokens: int = 12000
    model_timeout_seconds: float = 45
    completion_timeout_seconds: float = 720
    max_attempts: int = 2
    model_concurrency: int = 2
    auto_repair_enabled: bool = True
    canonical_generation_enabled: bool = True
    trusted_templates_enabled: bool = True
    staged_generation_enabled: bool = True
    python_preflight_enabled: bool = True
    event_driven_validation_enabled: bool = False
    mock_enabled: bool = False
    retention_days: int = 30
    input_usd_per_million_tokens: float = 0
    output_usd_per_million_tokens: float = 0

    @property
    def model_enabled(self) -> bool:
        configured = bool(self.provider and self.model_id and self.api_key.get_secret_value())
        mock = self.mock_enabled and self.environment.lower() in {"development", "test"}
        return configured or mock


settings = Settings()
