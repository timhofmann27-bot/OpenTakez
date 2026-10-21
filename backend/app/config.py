from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="OPENTAKEZ_", env_file=".env", extra="ignore")

    # Access token. If empty, one is generated on first start and stored in data_dir/token,
    # unless allow_no_token is set (only for instances that aren't reachable by others).
    token: str = ""
    allow_no_token: bool = False
    data_dir: Path = Path("./data")
    # Built PWA (web/dist). Served by the backend when present.
    web_dir: Path = Path("../web/dist")
    file_ttl_hours: int = 6
    max_concurrent_jobs: int = 2
    # Downloads that may be queued or running at once; more are refused with 503.
    max_queued_jobs: int = 20
    # Link checks (each runs a full yt-dlp extraction) at once.
    max_concurrent_resolves: int = 4
    max_file_mb: int = 2048
    cors_origins: list[str] = ["http://localhost:5173"]


settings = Settings()
