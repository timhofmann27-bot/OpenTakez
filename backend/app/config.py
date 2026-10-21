from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="OPENTAKEZ_", env_file=".env", extra="ignore")

    # Optional access token. Empty means no auth (only for local use).
    token: str = ""
    data_dir: Path = Path("./data")
    # Built PWA (web/dist). Served by the backend when present.
    web_dir: Path = Path("../web/dist")
    file_ttl_hours: int = 6
    max_concurrent_jobs: int = 2
    cors_origins: list[str] = ["http://localhost:5173"]


settings = Settings()
