"""Streams files as a ZIP without buffering whole files or building the archive on disk."""

import re
import zipfile
from collections.abc import Iterator
from datetime import datetime
from pathlib import Path

CHUNK_SIZE = 1024 * 1024


class _Sink:
    """Unseekable file object: collects what zipfile writes until the caller drains it."""

    def __init__(self) -> None:
        self._chunks: list[bytes] = []
        self._pos = 0

    def write(self, data: bytes) -> int:
        self._chunks.append(bytes(data))
        self._pos += len(data)
        return len(data)

    def flush(self) -> None:
        pass

    def tell(self) -> int:
        return self._pos

    def drain(self) -> bytes:
        data = b"".join(self._chunks)
        self._chunks.clear()
        return data


def _arcname(name: str, used: set[str]) -> str:
    """A safe, unique entry name. Windows unzip treats backslashes and drive colons as path
    separators, so those (and control characters, leading dots) must not survive."""
    name = name.encode("utf-8", "replace").decode("utf-8")  # lone surrogates from odd filenames
    name = re.sub(r"[\\/:\x00-\x1f]", "_", name).lstrip(".") or "file"
    candidate, n = name, 1
    while candidate in used:
        n += 1
        stem, dot, ext = name.rpartition(".")
        candidate = f"{stem} ({n}).{ext}" if dot and stem else f"{name} ({n})"
    used.add(candidate)
    return candidate


def stream_zip(files: list[Path], names: list[str] | None = None) -> Iterator[bytes]:
    """Yield a stored (uncompressed) ZIP64 of `files`, named by sanitized basename (or `names`).
    Media is already compressed, so deflating would only cost CPU. Sync on purpose: runs in a
    threadpool."""
    sink = _Sink()
    used: set[str] = set()
    with zipfile.ZipFile(sink, "w", zipfile.ZIP_STORED, allowZip64=True) as archive:  # type: ignore[arg-type]
        for i, path in enumerate(files):
            arcname = _arcname(names[i] if names else path.name, used)
            info = zipfile.ZipInfo(arcname, _zip_time(path))
            info.compress_type = zipfile.ZIP_STORED
            with path.open("rb") as source, archive.open(info, "w", force_zip64=True) as target:
                while chunk := source.read(CHUNK_SIZE):
                    target.write(chunk)
                    if data := sink.drain():
                        yield data
        # Leaving the block writes the central directory.
    if data := sink.drain():
        yield data


def _zip_time(path: Path) -> tuple[int, int, int, int, int, int]:
    # ZIP cannot represent dates before 1980.
    stamp = datetime.fromtimestamp(path.stat().st_mtime)
    return (max(stamp.year, 1980), stamp.month, stamp.day, stamp.hour, stamp.minute, stamp.second)
