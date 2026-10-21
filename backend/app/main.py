from importlib.metadata import version

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse

from app import __version__
from app.config import settings

app = FastAPI(title="OpenTakez", version=__version__)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/api/health")
def health() -> dict[str, str]:
    return {
        "status": "ok",
        "version": app.version,
        "yt_dlp": version("yt-dlp"),
    }


# Serve the built PWA when it's bundled (Docker image). Unknown paths fall back to
# index.html so client-side routes like /share work.
@app.get("/{path:path}", include_in_schema=False)
def spa(path: str) -> FileResponse:
    web_root = settings.web_dir.resolve()
    if path.startswith("api/") or not web_root.is_dir():
        raise HTTPException(status_code=404)
    file = (web_root / path).resolve()
    if file.is_file() and file.is_relative_to(web_root):
        return FileResponse(file)
    return FileResponse(web_root / "index.html")
