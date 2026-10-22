import pytest

from app.main import app


@pytest.fixture
def only_dl_example(client):
    """The middleware is built at import from settings; swap its config and rebuild the stack."""
    original = app.user_middleware
    app.user_middleware = [
        m.__class__(m.cls, *m.args, **({**m.kwargs, "allowed_hosts": ["dl.example.com"]}))
        if m.cls.__name__ == "HostGuard"
        else m
        for m in original
    ]
    app.middleware_stack = None
    yield client
    app.user_middleware = original
    app.middleware_stack = None


def test_default_allows_any_host(client):
    assert client.get("/api/health", headers={"Host": "anything.test"}).status_code == 200


def test_unknown_host_is_400(only_dl_example):
    response = only_dl_example.get("/", headers={"Host": "evil.com"})

    assert response.status_code == 400


def test_allowed_host_passes(only_dl_example):
    response = only_dl_example.get("/", headers={"Host": "dl.example.com"})

    assert response.status_code != 400


def test_health_answers_any_host(only_dl_example):
    """Otherwise the container healthcheck (127.0.0.1) would mark the app unhealthy."""
    response = only_dl_example.get("/api/health", headers={"Host": "127.0.0.1:8000"})

    assert response.status_code == 200
