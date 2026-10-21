from pathlib import Path

from pydantic import Field
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
    # Playlists and carousels: items per job (more are truncated), and caps for the whole job.
    max_playlist_items: int = Field(50, ge=1, le=200)
    max_job_mb: int = 4096
    job_timeout_seconds: int = 3600
    # Uploaded cookies.txt (login sessions) per platform.
    cookie_max_kb: int = 256
    # gallery-dl runs as a separate program (GPL-2.0-only, not imported).
    gallery_dl_bin: str = "gallery-dl"
    gallery_dl_timeout: int = 300
    cors_origins: list[str] = ["http://localhost:5173"]


settings = Settings()
