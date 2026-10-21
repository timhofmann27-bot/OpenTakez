import logging
import os
import stat
from datetime import UTC, datetime
from pathlib import Path

import pytest

from app.cookies import CookieError, CookieStore

HEADER = "# Netscape HTTP Cookie File\n"
FAR = 4102444800  # 2100-01-01


def line(domain=".instagram.com", name="sessionid", value="v", expiry=FAR, path="/", secure="TRUE"):
    return "\t".join([domain, "TRUE", path, secure, str(expiry), name, value])


@pytest.fixture
def store(tmp_path):
    return CookieStore(tmp_path, 4096)


def test_save_and_status(store):
    text = HEADER + line() + "\n" + line(name="csrftoken", expiry=FAR + 100) + "\n"
    up = store.save("instagram", text)
    assert up.present and up.cookie_count == 2 and up.dropped_count == 0
    assert up.domains == [".instagram.com"]
    assert up.earliest_expiry == datetime.fromtimestamp(FAR, UTC)
    assert not up.expired
    st = store.status("instagram")
    assert st.present and st.uploaded_at is not None and st.cookie_count == 2
    assert store.has("instagram")
    assert not store.has("tiktok")
    assert store.status("tiktok").present is False


def test_status_never_has_names_or_values(store):
    store.save("instagram", line(name="secretname", value="secretvalue"))
    dumped = store.status("instagram").model_dump_json()
    assert "secretname" not in dumped and "secretvalue" not in dumped


def test_httponly_prefix_and_comments_and_blank(store):
    text = HEADER + "\n#comment\n#HttpOnly_" + line() + "\n"
    assert store.save("instagram", text).cookie_count == 1
    stored = (store._dir / "instagram.txt").read_text()
    assert "#HttpOnly_.instagram.com\t" in stored


def test_session_cookie_expiry_zero_ignored_for_earliest(store):
    up = store.save("instagram", line(expiry=0) + "\n" + line(name="b", expiry=FAR))
    assert up.earliest_expiry == datetime.fromtimestamp(FAR, UTC)


def test_expired(store):
    up = store.save("instagram", line(expiry=1000))
    assert up.expired


def test_domain_filtering_counts_dropped(store):
    text = line() + "\n" + line(domain=".google.com") + "\n" + line(domain="evilinstagram.com")
    up = store.save("instagram", text)
    assert up.cookie_count == 1 and up.dropped_count == 2
    stored = (store._dir / "instagram.txt").read_text()
    assert "google" not in stored and "evilinstagram" not in stored


def test_empty_after_filter(store):
    with pytest.raises(CookieError, match="No cookies for instagram.com found"):
        store.save("instagram", line(domain=".google.com"))
    assert not store.has("instagram")


def test_rewrite_replaces_not_verbatim(store):
    store.save("instagram", "# junk comment SECRETCOMMENT\n" + line())
    assert "SECRETCOMMENT" not in (store._dir / "instagram.txt").read_text()


@pytest.mark.parametrize(
    "text",
    [
        "a\tb\tc\n",  # wrong field count
        line() + "\textra",  # 8 fields
        "x\x00y",  # NUL
        line(value="a\rb"),  # CR injection
        line(name=""),  # empty name
        line(path="x"),  # bad path
        line(expiry="abc"),  # bad expiry
        line(secure="MAYBE"),  # bad flag
        line(value="a\x07b"),  # control char
        "\n".join(line(name=f"n{i}") for i in range(1001)),  # too many lines
        "\xff".encode("latin-1").decode("latin-1") + "\n",  # not 7 fields anyway
    ],
)
def test_rejects_invalid(store, text):
    with pytest.raises(CookieError):
        store.save("instagram", text)


def test_rejects_bad_utf8_bytes(store):
    with pytest.raises(CookieError):
        store.save("instagram", b"\xff\xfe\x00".decode("latin-1") + "x")
    with pytest.raises(CookieError):
        store.save("instagram", b"\xff\xfe")


def test_rejects_oversize(store):
    with pytest.raises(CookieError):
        store.save("instagram", "#" * 5000)


def test_errors_contain_line_not_content(store):
    with pytest.raises(CookieError) as e:
        store.save("instagram", HEADER + "TOPSECRET\tonly\n")
    assert "TOPSECRET" not in str(e.value) and "2" in str(e.value)


def test_unknown_platform(store):
    for call in (
        lambda: store.save("myspace", line()),
        lambda: store.status("myspace"),
        lambda: store.has("myspace"),
        lambda: store.delete("myspace"),
        lambda: store.lease("../etc").__enter__(),
    ):
        with pytest.raises(KeyError):
            call()


def test_permissions(store):
    store.save("instagram", line())
    assert stat.S_IMODE(os.stat(store._dir).st_mode) == 0o700
    assert stat.S_IMODE(os.stat(store._dir / "instagram.txt").st_mode) == 0o600
    assert [p.name for p in store._dir.iterdir()] == ["instagram.txt"]  # no temp leftovers


def test_delete(store):
    store.save("instagram", line())
    store.delete("instagram")
    store.delete("instagram")
    assert not store.has("instagram")


def test_lease_isolated_and_cleaned(store, tmp_path):
    store.save("instagram", line(value="orig"))
    with store.lease("instagram") as path:
        assert path is not None and path.is_file()
        assert stat.S_IMODE(os.stat(path).st_mode) == 0o600
        assert stat.S_IMODE(os.stat(path.parent).st_mode) == 0o700
        assert (tmp_path / "run") in path.parents
        assert "jobs" not in path.parts
        path.write_text("modified by tool")
        store.delete("instagram")
        assert path.is_file()
        run_dir = path.parent
    assert not run_dir.exists()


def test_lease_does_not_follow_later_save(store):
    store.save("instagram", line(value="orig"))
    with store.lease("instagram") as path:
        store.save("instagram", line(value="new"))
        assert "orig" in path.read_text()


def test_lease_none_without_cookies(store):
    with store.lease("tiktok") as path:
        assert path is None


def test_lease_cleaned_on_exception(store):
    store.save("instagram", line())
    with pytest.raises(RuntimeError), store.lease("instagram") as path:
        run_dir = path.parent
        raise RuntimeError
    assert not run_dir.exists()


def test_run_dir_cleared_at_startup(tmp_path):
    stale = tmp_path / "run" / "old"
    stale.mkdir(parents=True)
    (stale / "cookies.txt").write_text("x")
    CookieStore(tmp_path, 4096)
    assert list((tmp_path / "run").iterdir()) == []


def test_no_log_leak(store, caplog):
    caplog.set_level(logging.DEBUG)
    store.save("instagram", line(value="LOGSECRET"))
    with store.lease("instagram"):
        pass
    with pytest.raises(CookieError):
        store.save("instagram", "LOGSECRET")
    assert "LOGSECRET" not in caplog.text


def test_status_of_a_file_deleted_mid_read_is_not_present(store, monkeypatch):
    store.save("instagram", line())

    def vanished(self, *a, **k):
        raise FileNotFoundError(str(self))

    monkeypatch.setattr(Path, "read_text", vanished)

    st = store.status("instagram")

    assert (st.present, st.cookie_count) == (False, 0)
