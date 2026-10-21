import io
import zipfile

import pytest

from app.zipstream import CHUNK_SIZE, stream_zip
from tests.conftest import wait_for_job
from tests.test_api import start_job


def test_stream_zip_roundtrips_and_streams_in_chunks(tmp_path):
    big = tmp_path / "big.bin"
    big.write_bytes(bytes(range(256)) * (3 * CHUNK_SIZE // 256 + 1))
    small = tmp_path / "Ünï 01.txt"
    small.write_text("hello")

    chunks = list(stream_zip([big, small]))
    archive = zipfile.ZipFile(io.BytesIO(b"".join(chunks)))

    assert len(chunks) > 3  # not one blob
    assert archive.namelist() == ["big.bin", "Ünï 01.txt"]
    assert archive.read("big.bin") == big.read_bytes()
    assert archive.read("Ünï 01.txt") == b"hello"
    assert all(i.compress_type == zipfile.ZIP_STORED for i in archive.infolist())
    assert archive.testzip() is None


def test_stream_zip_of_no_files_is_a_valid_empty_archive():
    archive = zipfile.ZipFile(io.BytesIO(b"".join(stream_zip([]))))

    assert archive.namelist() == []


def test_zip_endpoint(client, fake_provider):
    fake_provider.files = {"01 a.jpg": b"aaa", "02 b.mp4": b"bbbb"}
    job = wait_for_job(client, start_job(client)["id"], "done")

    response = client.get(f"/api/jobs/{job['id']}/zip")

    archive = zipfile.ZipFile(io.BytesIO(response.content))
    assert response.headers["content-type"] == "application/zip"
    assert (
        f'filename="OpenTakez-fake-{job["id"][:8]}.zip"' in response.headers["content-disposition"]
    )
    assert {n: archive.read(n) for n in archive.namelist()} == {
        "01 a.jpg": b"aaa",
        "02 b.mp4": b"bbbb",
    }


def zip_names(tmp_path, *names):
    paths = []
    for i, name in enumerate(names):
        path = tmp_path / f"src{i}"
        path.write_bytes(b"x")
        paths.append((path, name))
    return zipfile.ZipFile(
        io.BytesIO(b"".join(stream_zip([p for p, _ in paths], [n for _, n in paths])))
    ).namelist()


@pytest.mark.parametrize(
    ("name", "expected"),
    [
        ("..\\..\\evil.exe", "_.._evil.exe"),
        ("a\\b.jpg", "a_b.jpg"),
        ("C:evil.txt", "C_evil.txt"),
        ("a/b.jpg", "a_b.jpg"),
        (".hidden", "hidden"),
        ("..", "file"),
        ("", "file"),
        ("tab\tnew\nline.mp4", "tab_new_line.mp4"),
        ("bad\udcffname.jpg", "bad?name.jpg"),
        ("Ünï 01.txt", "Ünï 01.txt"),
    ],
)
def test_arcnames_cannot_escape_the_extraction_directory(tmp_path, name, expected):
    assert zip_names(tmp_path, name) == [expected]


def test_names_that_collide_after_sanitizing_stay_distinct(tmp_path):
    names = zip_names(tmp_path, "a\\b.jpg", "a/b.jpg", "a:b.jpg")

    assert names == ["a_b.jpg", "a_b (2).jpg", "a_b (3).jpg"]
