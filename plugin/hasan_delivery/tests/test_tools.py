"""Tests unitaires pour tools.py — découverte dynamique multi-device.

Ne dépend pas du runtime Hermes réel (tools.registry n'existe pas dans ce
repo) : les fonctions testées ici (_merge_capabilities, _build_schema,
_resolve_device_id, sync_tools) n'importent tools.registry qu'à l'intérieur
de register_tools()/_register(), jamais au niveau module — donc importables
et testables isolément. Les appels réseau (_fetch_devices) sont mockés via
httpx.MockTransport (pas de dépendance de test supplémentaire).

Lancer : pytest -v plugin/hasan_delivery/test_tools.py
"""

from __future__ import annotations

import httpx
import pytest

import tools


ADMIN_TOKEN = "test-admin-token"
RELAY_URL = "http://relay.test"


@pytest.fixture(autouse=True)
def relay_env(monkeypatch):
    monkeypatch.setenv("HASAN_RELAY_URL", RELAY_URL)
    monkeypatch.setenv("HASAN_RELAY_ADMIN_TOKEN", ADMIN_TOKEN)
    # Chaque test repart d'un état d'enregistrement propre.
    tools._known_tool_names = set()
    yield


def make_devices_transport(devices: list[dict], expected_path: str = "/devices"):
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == expected_path
        assert request.headers["authorization"] == f"Bearer {ADMIN_TOKEN}"
        return httpx.Response(200, json={"devices": devices})

    return httpx.MockTransport(handler)


@pytest.fixture
def patch_devices_client(monkeypatch):
    """Patch httpx.AsyncClient pour que _fetch_devices() utilise le transport
    fourni, sans requête réseau réelle."""

    def _apply(devices: list[dict]):
        transport = make_devices_transport(devices)

        class _PatchedClient(httpx.AsyncClient):
            def __init__(self, *args, **kwargs):
                kwargs["transport"] = transport
                kwargs.setdefault("base_url", RELAY_URL)
                super().__init__(*args, **kwargs)

        monkeypatch.setattr(tools.httpx, "AsyncClient", _PatchedClient)

    return _apply


# ─────────────────────────── _merge_capabilities ────────────────────────────


def test_merge_capabilities_union_across_devices():
    devices = [
        {
            "device_id": "phone", "connected": True,
            "capabilities": [{"name": "get_battery", "description": "batt", "parameters": {}}],
        },
        {
            "device_id": "desktop", "connected": True,
            "capabilities": [
                {"name": "get_battery", "description": "batt desktop", "parameters": {}},
                {"name": "get_clipboard", "description": "clip", "parameters": {}},
            ],
        },
    ]
    merged = tools._merge_capabilities(devices)
    assert set(merged.keys()) == {"get_battery", "get_clipboard"}
    # Premier device rencontré qui expose le nom gagne — pas de doublon.
    assert merged["get_battery"]["description"] == "batt"


def test_merge_capabilities_empty_devices_list():
    assert tools._merge_capabilities([]) == {}


def test_merge_capabilities_ignores_malformed_entries():
    devices = [{"device_id": "x", "connected": True, "capabilities": [{"no_name": True}, "not_a_dict", None]}]
    assert tools._merge_capabilities(devices) == {}


# ─────────────────────────── _build_schema ───────────────────────────────────


def test_build_schema_always_includes_optional_device_id():
    schema = tools._build_schema({"type": "object", "properties": {"to": {"type": "string"}}, "required": ["to"]})
    assert "device_id" in schema["properties"]
    assert "device_id" not in schema["required"]
    assert schema["required"] == ["to"]  # les required existants sont préservés


def test_build_schema_handles_none_base_parameters():
    schema = tools._build_schema(None)
    assert schema["properties"] == {"device_id": schema["properties"]["device_id"]}
    assert schema["required"] == []


# ─────────────────────────── _resolve_device_id ──────────────────────────────


async def test_resolve_device_id_auto_targets_single_connected_match(patch_devices_client):
    patch_devices_client([
        {"device_id": "phone", "label": "Téléphone", "connected": True,
         "capabilities": [{"name": "get_battery"}]},
        {"device_id": "desktop", "label": "Desktop", "connected": False,
         "capabilities": [{"name": "get_battery"}]},  # pas connecté, ignoré
    ])
    device_id, error = await tools._resolve_device_id("get_battery", None)
    assert device_id == "phone"
    assert error is None


async def test_resolve_device_id_errors_on_ambiguity(patch_devices_client):
    patch_devices_client([
        {"device_id": "phone", "label": "Téléphone", "connected": True,
         "capabilities": [{"name": "get_battery"}]},
        {"device_id": "desktop", "label": "Desktop", "connected": True,
         "capabilities": [{"name": "get_battery"}]},
    ])
    device_id, error = await tools._resolve_device_id("get_battery", None)
    assert device_id is None
    assert "Téléphone" in error and "Desktop" in error


async def test_resolve_device_id_errors_on_zero_connected(patch_devices_client):
    patch_devices_client([
        {"device_id": "phone", "label": "Téléphone", "connected": False,
         "capabilities": [{"name": "get_battery"}]},
    ])
    device_id, error = await tools._resolve_device_id("get_battery", None)
    assert device_id is None
    assert "aucun device connecté" in error


async def test_resolve_device_id_honors_explicit_request_even_if_ambiguous(patch_devices_client):
    patch_devices_client([
        {"device_id": "phone", "label": "Téléphone", "connected": True,
         "capabilities": [{"name": "get_battery"}]},
        {"device_id": "desktop", "label": "Desktop", "connected": True,
         "capabilities": [{"name": "get_battery"}]},
    ])
    device_id, error = await tools._resolve_device_id("get_battery", "desktop")
    assert device_id == "desktop"
    assert error is None


async def test_resolve_device_id_rejects_unknown_explicit_request(patch_devices_client):
    patch_devices_client([
        {"device_id": "phone", "label": "Téléphone", "connected": True,
         "capabilities": [{"name": "get_battery"}]},
    ])
    device_id, error = await tools._resolve_device_id("get_battery", "nonexistent")
    assert device_id is None
    assert "nonexistent" in error


# ─────────────────────────── sync_tools ──────────────────────────────────────


class _FakeCtx:
    def __init__(self):
        self.registered: list[str] = []

    def register_tool(self, *, name, **_kwargs):
        self.registered.append(name)


async def test_sync_tools_noop_when_name_set_unchanged(patch_devices_client):
    devices = [
        {"device_id": "phone", "connected": True,
         "capabilities": [{"name": "get_battery", "description": "d", "parameters": {}}]},
    ]
    patch_devices_client(devices)
    ctx = _FakeCtx()

    tools._known_tool_names = {"get_battery"}  # déjà enregistré (simule register_tools initial)
    await tools.sync_tools(ctx)
    assert ctx.registered == []  # rien de nouveau, pas de ré-enregistrement


async def test_sync_tools_registers_newly_appeared_capability(patch_devices_client):
    devices = [
        {"device_id": "phone", "connected": True,
         "capabilities": [{"name": "get_battery", "description": "d", "parameters": {}}]},
        {"device_id": "desktop", "connected": True,
         "capabilities": [{"name": "get_clipboard", "description": "d2", "parameters": {}}]},
    ]
    patch_devices_client(devices)
    ctx = _FakeCtx()

    tools._known_tool_names = {"get_battery"}  # get_clipboard est nouveau
    await tools.sync_tools(ctx)
    assert ctx.registered == ["get_clipboard"]
    assert tools._known_tool_names == {"get_battery", "get_clipboard"}


async def test_sync_tools_never_deregisters(patch_devices_client):
    """Un device qui disparaît (capability plus exposée par personne) ne doit
    jamais retirer le tool déjà enregistré — voir docstring du module."""
    patch_devices_client([])  # plus aucun device/capability
    ctx = _FakeCtx()

    tools._known_tool_names = {"get_battery"}
    await tools.sync_tools(ctx)
    assert ctx.registered == []  # rien désenregistré, rien de nouveau à ajouter
    assert tools._known_tool_names == {"get_battery"}  # toujours présent


# ─────────────────────────── _check_requirements ─────────────────────────────


def test_check_requirements_true_when_configured(relay_env):
    assert tools._check_requirements() is True


def test_check_requirements_false_when_admin_token_missing(monkeypatch):
    monkeypatch.setenv("HASAN_RELAY_URL", RELAY_URL)
    monkeypatch.delenv("HASAN_RELAY_ADMIN_TOKEN", raising=False)
    assert tools._check_requirements() is False


# ─────────────────────────── _fetch_devices resilience ───────────────────────


async def test_fetch_devices_returns_empty_when_env_missing(monkeypatch):
    monkeypatch.delenv("HASAN_RELAY_URL", raising=False)
    monkeypatch.delenv("HASAN_RELAY_ADMIN_TOKEN", raising=False)
    assert await tools._fetch_devices() == []


async def test_fetch_devices_returns_empty_on_unreachable_relay(monkeypatch):
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    transport = httpx.MockTransport(handler)

    class _PatchedClient(httpx.AsyncClient):
        def __init__(self, *args, **kwargs):
            kwargs["transport"] = transport
            kwargs.setdefault("base_url", RELAY_URL)
            super().__init__(*args, **kwargs)

    monkeypatch.setattr(tools.httpx, "AsyncClient", _PatchedClient)
    assert await tools._fetch_devices() == []
