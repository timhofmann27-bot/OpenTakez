import json
import os
import re
import sys
import time
from pathlib import Path

import pytest

from app.cookies import CookieStore
from app.providers.base import MediaKind, ProviderError
from app.providers.gallerydl import CompletedRun, GalleryDlProvider, SizeWatch, run_subprocess

FIXTURES = Path(__file__).parent / "fixtures"
COOKIE_LINE = "\t".join([".instagram.com", "TRUE", "/", "TRUE", "4102444800", "sessionid", "v"])


class FakeRunner:
    def __init__(self, result: CompletedRun | None = None, files: dict[str, bytes] | None = None):
        self.result = result or CompletedRun(0, "[]", "")
        self.files = files or {}
        self.calls: list[dict] = []

    def __call__(self, argv, *, env, cwd, timeout, watch=None):
        config_path = Path(argv[argv.index("-c") + 1])
        config = json.loads(config_path.read_text())
        cookie_file = config["extractor"].get("cookies")
        self.calls.append(
            {
                "argv": argv,
                "env": env,
                "cwd": cwd,
                "timeout": timeout,
                "watch": watch,
                "config": config,
                "config_mode": config_path.stat().st_mode & 0o777,
                "cookie_text": Path(cookie_file).read_text() if cookie_file else None,
            }
        )
        out_dir = Path(config["extractor"]["base-directory"])
        for name, data in self.files.items():
            (out_dir / name).write_bytes(data)
        return self.result


def make(runner, cookies=None, **kw):
    return GalleryDlProvider(
        "instagram",
        ["instagram.com"],
        re.compile(r"^/(p|reel|tv)/[\w-]+"),
        cookies,
        runner=runner,
        **kw,
    )


@pytest.fixture
def store(tmp_path):
    return CookieStore(tmp_path / "data", 4096)


def test_supports_checks_host_path_and_cookies(store):
    p = make(FakeRunner(), store, requires_cookies=True)
    url = "https://www.instagram.com/p/CuE2WNQs6vH/"
    assert not p.supports(url)  # no cookies yet
    store.save("instagram", COOKIE_LINE)
    assert p.supports(url)
    assert p.supports("https://instagram.com/reel/abc_-1/?igsh=x")
    assert not p.supports("https://www.instagram.com/accounts/login/")
    assert not p.supports("https://evil.example/p/abc/")
    assert not p.supports("https://instagram.com.evil.example/p/abc/")
    assert not p.supports("ftp://instagram.com/p/abc/")
    assert not p.supports("https://instagram.com:8443/p/abc/")


def test_supports_without_requires_cookies():
    p = make(FakeRunner(), None)
    assert p.supports("https://www.instagram.com/p/abc/")


def test_argv_env_and_config(store, monkeypatch, tmp_path):
    monkeypatch.setenv("OPENTAKEZ_TOKEN", "supersecrettoken")
    monkeypatch.setenv("AWS_SECRET", "x")
    store.save("instagram", COOKIE_LINE)
    runner = FakeRunner(files={"001_1.jpg": b"x"})
    p = make(runner, store, max_items=7, max_filesize=1234, timeout=99)
    url = "https://www.instagram.com/p/abc/"
    out = tmp_path / "out"
    out.mkdir()
    p.download(url, "image-original", out, lambda _: None)
    call = runner.calls[0]
    argv = call["argv"]
    assert argv[0] == "gallery-dl" and argv[-1] == url
    assert "--config-ignore" in argv and "--no-input" in argv and "-j" not in argv
    assert argv[argv.index("--range") + 1] == "1-7"
    assert call["timeout"] == 99
    assert set(call["env"]) == {"PATH", "LANG", "HOME"}
    assert "supersecrettoken" not in json.dumps(call["env"])
    assert call["env"]["HOME"] == str(call["cwd"])
    assert call["config_mode"] == 0o600
    ex = call["config"]["extractor"]
    assert ex["base-directory"] == str(out) and ex["directory"] == []
    assert ex["retries"] == 2 and ex["timeout"] == 20 and ex["sleep-request"] == 1
    assert "{num" in ex["filename"] and ex["tiktok"] == {"audio": False}
    assert Path(ex["cookies"]).parent.parent == tmp_path / "data" / "run"
    assert call["cookie_text"] and "sessionid" in call["cookie_text"]
    assert call["config"]["downloader"]["filesize-max"] == 1234
    assert "postprocessors" not in json.dumps(call["config"]) and "exec" not in json.dumps(
        call["config"]
    )
    # lease and private run dir are gone afterwards
    assert not Path(ex["cookies"]).exists() and not call["cwd"].exists()


def test_resolve_uses_dump_flag_and_no_cookies_key_without_cookies(store):
    runner = FakeRunner(CompletedRun(0, json.dumps([[3, "https://x/a.jpg", {"id": 1}]]), ""))
    make(runner, store).resolve("https://www.instagram.com/p/abc/")
    call = runner.calls[0]
    assert "-j" in call["argv"] and call["argv"][-1] == "https://www.instagram.com/p/abc/"
    assert "cookies" not in call["config"]["extractor"]


def test_rejects_non_http_scheme():
    with pytest.raises(ProviderError):
        make(FakeRunner()).resolve("file:///etc/passwd")


def test_resolve_from_real_fixture():
    raw = (FIXTURES / "gallerydl_tiktok_photo.json").read_text()
    runner = FakeRunner(CompletedRun(0, raw, ""))
    url = json.loads((FIXTURES / "gallerydl_tiktok_photo.url").read_text())
    p = GalleryDlProvider(
        "tiktok", ["tiktok.com"], re.compile(r"^/@[^/]+/photo/\d+"), None, runner=runner
    )
    info = p.resolve(url)
    files = [r for r in json.loads(raw) if r[0] == 3]
    assert info.kind == MediaKind.IMAGE and info.platform == "tiktok"
    assert info.item_count == len(files) > 0
    assert info.options == ["image-original"]
    assert info.title and len(info.title) <= 120
    assert info.thumbnail is None or info.thumbnail.startswith("https://")


def records(*, caption="", n=3, thumb="https://cdn/x.jpg"):
    meta = {"username": "alice", "description": caption, "shortcode": "abc"}
    return json.dumps(
        [[2, meta]]
        + [[3, thumb if i == 0 else f"https://cdn/{i}.jpg", {"num": i + 1}] for i in range(n)]
    )


def test_resolve_title_and_uploader():
    p = make(FakeRunner(CompletedRun(0, records(caption="x" * 500), "")))
    info = p.resolve("https://www.instagram.com/p/abc/")
    assert info.uploader == "alice" and info.item_count == 3
    assert len(info.title) <= 120 and info.title.endswith("…")


def test_resolve_title_fallback_and_non_https_thumb():
    p = make(FakeRunner(CompletedRun(0, records(thumb="http://cdn/x.jpg"), "")))
    info = p.resolve("https://www.instagram.com/p/abc/")
    assert info.title == "Instagram post abc" and info.thumbnail is None


def test_login_required_from_dump_error_record():
    # Real shape of `gallery-dl -j` for an Instagram post without login (exit code 0).
    out = json.dumps(
        [
            [
                -1,
                {
                    "error": "AbortExtraction",
                    "message": "HTTP redirect to login page (https://www.instagram.com/accounts/login/)",
                },
            ]
        ]
    )
    with pytest.raises(ProviderError) as e:
        make(FakeRunner(CompletedRun(0, out, ""))).resolve("https://www.instagram.com/p/abc/")
    assert e.value.code == "login_required"
    assert "Instagram" in str(e.value) and "Settings" in str(e.value)


def test_login_required_from_stderr_on_download(tmp_path):
    # Real stderr of a download without login (exit code 4).
    err = "[instagram][error] HTTP redirect to login page (https://www.instagram.com/accounts/login/)\n"
    with pytest.raises(ProviderError) as e:
        make(FakeRunner(CompletedRun(4, "", err))).download(
            "https://www.instagram.com/p/abc/", "image-original", tmp_path, lambda _: None
        )
    assert e.value.code == "login_required"


def test_other_failure_is_sanitized():
    err = "Traceback ... /home/secret/path/config.json token=abc"
    with pytest.raises(ProviderError) as e:
        make(FakeRunner(CompletedRun(1, "", err))).resolve("https://www.instagram.com/p/abc/")
    assert e.value.code is None
    assert "secret" not in str(e.value) and "Traceback" not in str(e.value)


def test_not_found_message():
    with pytest.raises(ProviderError, match="wasn't found"):
        make(FakeRunner(CompletedRun(1, "", "HttpError: '404 Not Found'"))).resolve(
            "https://www.instagram.com/p/abc/"
        )


def test_resolve_empty_and_garbage_output():
    for out in ("[]", "not json", ""):
        with pytest.raises(ProviderError):
            make(FakeRunner(CompletedRun(0, out, ""))).resolve("https://www.instagram.com/p/a/")


def test_missing_binary_is_provider_error():
    def runner(*a, **k):
        raise FileNotFoundError

    with pytest.raises(ProviderError):
        make(runner).resolve("https://www.instagram.com/p/abc/")


def test_timeout_is_provider_error():
    with pytest.raises(ProviderError, match="too long"):
        make(FakeRunner(CompletedRun(-9, "", "", timed_out=True))).resolve(
            "https://www.instagram.com/p/abc/"
        )


def test_download_lists_sorted_files_and_skips_junk(tmp_path):
    runner = FakeRunner(
        files={
            "002_b.jpg": b"1",
            "001_a.jpg": b"1",
            "003_c.mp4.part": b"1",
            "meta.json": b"{}",
            ".hidden": b"1",
        }
    )
    seen = []
    files = make(runner).download(
        "https://www.instagram.com/p/abc/", "image-original", tmp_path, seen.append
    )
    assert [f.name for f in files] == ["001_a.jpg", "002_b.jpg"]
    assert seen and seen[0].phase == "downloading" and seen[0].fraction is None


def test_download_no_files(tmp_path):
    with pytest.raises(ProviderError):
        make(FakeRunner()).download(
            "https://www.instagram.com/p/abc/", "image-original", tmp_path, lambda _: None
        )


# --- the real subprocess runner, with a Python script standing in for gallery-dl ---


def test_run_subprocess_captures_and_scrubs(tmp_path, monkeypatch):
    monkeypatch.setenv("OPENTAKEZ_TOKEN", "leak")
    code = "import os,sys;print(sorted(os.environ));print('E',file=sys.stderr);sys.exit(3)"
    r = run_subprocess(
        [sys.executable, "-c", code], env={"PATH": os.defpath}, cwd=tmp_path, timeout=10
    )
    assert r.returncode == 3 and r.stderr.strip() == "E" and not r.timed_out
    assert "OPENTAKEZ_TOKEN" not in r.stdout and "'PATH'" in r.stdout


def test_run_subprocess_timeout_kills_process_group(tmp_path):
    marker = tmp_path / "child-alive"
    child = tmp_path / "child.py"
    child.write_text(f"import time\ntime.sleep(1.5)\nopen({str(marker)!r}, 'w')\n")
    code = (
        "import subprocess, sys, time\n"
        f"subprocess.Popen([sys.executable, {str(child)!r}])\n"
        "time.sleep(60)\n"
    )
    start = time.monotonic()
    r = run_subprocess(
        [sys.executable, "-c", code], env={"PATH": os.defpath}, cwd=tmp_path, timeout=1
    )
    assert r.timed_out and time.monotonic() - start < 10
    time.sleep(2)
    assert not marker.exists()  # grandchild was killed too


def test_run_subprocess_caps_output(tmp_path):
    code = "import sys;sys.stdout.write('a'*(8*1024*1024))"
    r = run_subprocess(
        [sys.executable, "-c", code], env={"PATH": os.defpath}, cwd=tmp_path, timeout=30
    )
    assert r.returncode == 0 and len(r.stdout) == 5 * 1024 * 1024


def test_run_subprocess_no_shell(tmp_path):
    r = run_subprocess(
        [sys.executable, "-c", "import sys;print(sys.argv[1])", "$(echo pwned);ls"],
        env={"PATH": os.defpath},
        cwd=tmp_path,
        timeout=10,
    )
    assert r.stdout.strip() == "$(echo pwned);ls"


def test_error_logged_with_exit_zero_is_failure():
    # Real: an unavailable TikTok post gives `[]` on stdout, exit code 0 and an [error] line.
    err = "[tiktok][error] https://www.tiktok.com/@nasa/video/1: Requested post not available\n"
    with pytest.raises(ProviderError, match="wasn't found|Couldn't load"):
        make(FakeRunner(CompletedRun(0, "[]", err))).resolve("https://www.instagram.com/p/a/")


# --- kind, thumbnail, options ---


def run_records(*files):
    return json.dumps([[2, {"username": "a", "shortcode": "abc"}]] + [[3, *f] for f in files])


def resolve_files(*files, **kw):
    runner = FakeRunner(CompletedRun(0, run_records(*files), ""))
    return make(runner, **kw).resolve("https://www.instagram.com/p/abc/")


def test_post_with_only_videos_is_a_video_without_thumbnail():
    info = resolve_files(
        ("https://cdninstagram.com/a.mp4?x=1", {"extension": "mp4"}),
        ("https://cdninstagram.com/b.mov", {"extension": "mov"}),
    )

    assert info.kind == MediaKind.VIDEO
    assert info.thumbnail is None
    assert info.options == ["image-original"]  # gallery-dl always saves the originals
    assert info.item_count == 2


def test_mixed_post_is_an_image_and_thumbnail_is_the_first_image():
    info = resolve_files(
        ("https://cdninstagram.com/a.mp4", {"extension": "mp4"}),
        ("https://cdninstagram.com/b.jpg?x=1", {"extension": "jpg"}),
    )

    assert info.kind == MediaKind.IMAGE
    assert info.thumbnail == "https://cdninstagram.com/b.jpg?x=1"


def test_extension_falls_back_to_the_url_path():
    info = resolve_files(("https://cdninstagram.com/a.mp4?x=1.jpg", {}))

    assert info.kind == MediaKind.VIDEO


def test_files_without_a_known_extension_count_as_images():
    info = resolve_files(("https://cdninstagram.com/a", {}))

    assert info.kind == MediaKind.IMAGE and info.thumbnail == "https://cdninstagram.com/a"


def test_thumbnail_must_be_on_a_platform_cdn():
    files = (("https://evil.example/a.jpg", {"extension": "jpg"}),)

    assert resolve_files(*files, thumbnail_hosts=["cdninstagram.com"]).thumbnail is None
    assert resolve_files(*files).thumbnail == "https://evil.example/a.jpg"


def test_provider_offers_only_image_options():
    assert make(FakeRunner()).options == ["image-original"]


# --- login detection ---


@pytest.mark.parametrize(
    "stderr",
    [
        "[instagram][error] HttpError: '404 Not Found' for 'https://www.instagram.com/accounts/login/?next=/p/x'",
        "[instagram][error] The post does not exist (https://www.instagram.com/login)",
    ],
)
def test_not_found_wins_over_a_login_path_in_the_output(stderr):
    with pytest.raises(ProviderError, match="wasn't found") as e:
        make(FakeRunner(CompletedRun(1, "", stderr))).resolve("https://www.instagram.com/p/a/")
    assert e.value.code is None


def test_a_url_with_401_or_login_in_it_is_not_a_login_error():
    err = "[instagram][error] Unable to extract https://x.example/login/401/y"
    with pytest.raises(ProviderError) as e:
        make(FakeRunner(CompletedRun(1, "", err))).resolve("https://www.instagram.com/p/a/")
    assert e.value.code is None


def test_rate_limit_is_its_own_message():
    err = "[instagram][error] HttpError: '429 Too Many Requests' for 'https://www.instagram.com/x'"
    with pytest.raises(ProviderError, match="rate-limiting|Try again later") as e:
        make(FakeRunner(CompletedRun(1, "", err))).resolve("https://www.instagram.com/p/a/")
    assert e.value.code is None


# --- robustness ---


def test_malformed_records_do_not_crash_resolve():
    out = json.dumps([5, "x", None, [], [2], [3, "https://cdninstagram.com/a.jpg", {}]])

    info = make(FakeRunner(CompletedRun(0, out, ""))).resolve("https://www.instagram.com/p/a/")

    assert info.item_count == 1


def test_missing_working_directory_is_not_reported_as_a_missing_binary():
    def runner(*a, **k):
        raise FileNotFoundError(2, "No such file or directory", "/gone/run-dir")

    with pytest.raises(ProviderError, match="Couldn't load"):
        make(runner).resolve("https://www.instagram.com/p/abc/")


def test_missing_binary_message():
    def runner(argv, **k):
        raise FileNotFoundError(2, "No such file or directory", argv[0])

    with pytest.raises(ProviderError, match="aren't available"):
        make(runner).resolve("https://www.instagram.com/p/abc/")


# --- partial results and the job size cap ---


def test_partial_download_returns_files_and_counts_skipped(tmp_path):
    err = "[instagram][error] a failed\n[instagram][error] b failed\n"
    runner = FakeRunner(CompletedRun(4, "", err), files={"001_a.jpg": b"1"})

    files = make(runner).download(
        "https://www.instagram.com/p/a/", "image-original", tmp_path, print
    )

    assert [f.name for f in files] == ["001_a.jpg"] and files.skipped == 2


def test_clean_download_skips_nothing(tmp_path):
    files = make(FakeRunner(files={"001_a.jpg": b"1"})).download(
        "https://www.instagram.com/p/a/", "image-original", tmp_path, print
    )

    assert files.skipped == 0


def test_job_cap_is_watched_and_limits_each_file(tmp_path):
    runner = FakeRunner(files={"001_a.jpg": b"1"})
    p = make(runner, max_filesize=5000, max_job_bytes=3000)

    p.download("https://www.instagram.com/p/a/", "image-original", tmp_path, print)

    call = runner.calls[0]
    assert call["watch"] == SizeWatch(tmp_path, 3000)
    assert call["config"]["downloader"]["filesize-max"] == 3000


def test_resolve_has_no_size_watch():
    runner = FakeRunner(CompletedRun(0, run_records(("https://x/a.jpg", {})), ""))
    make(runner, max_job_bytes=3000).resolve("https://www.instagram.com/p/a/")

    assert runner.calls[0]["watch"] is None


def test_exceeding_the_job_cap_is_an_error(tmp_path):
    runner = FakeRunner(CompletedRun(-9, "", "", too_large=True), files={"001_a.jpg": b"1"})

    with pytest.raises(ProviderError, match="size limit"):
        make(runner, max_job_bytes=10).download(
            "https://www.instagram.com/p/a/", "image-original", tmp_path, print
        )


def test_run_subprocess_kills_the_process_group_over_the_size_cap(tmp_path):
    out = tmp_path / "out"
    out.mkdir()
    code = (
        "import time\n"
        f"open({str(out / 'big.bin')!r}, 'wb').write(b'x' * 3_000_000)\n"
        "time.sleep(60)\n"
    )
    start = time.monotonic()
    r = run_subprocess(
        [sys.executable, "-c", code],
        env={"PATH": os.defpath},
        cwd=tmp_path,
        timeout=60,
        watch=SizeWatch(out, 1_000_000),
    )

    assert r.too_large and not r.timed_out
    assert time.monotonic() - start < 15


def test_run_subprocess_under_the_cap_is_unaffected(tmp_path):
    r = run_subprocess(
        [sys.executable, "-c", "print('ok')"],
        env={"PATH": os.defpath},
        cwd=tmp_path,
        timeout=10,
        watch=SizeWatch(tmp_path, 1_000_000),
    )

    assert r.returncode == 0 and not r.too_large and r.stdout.strip() == "ok"
