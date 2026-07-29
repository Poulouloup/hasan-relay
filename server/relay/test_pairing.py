"""Tests unitaires pour PairingManager/Session — support multi-device.

Complète test_server.py (qui couvre le layer HTTP) en testant directement
PairingManager sans passer par aiohttp, pour ce qui ne nécessite pas de
requête réseau (round-trip de sérialisation, dédup par device_hash).
"""

from __future__ import annotations

import time

from pairing import PairingManager, Session


def make_session(device_hash: str, last_seen_at: float, **overrides) -> Session:
    defaults = dict(
        token=f"token-{device_hash}-{last_seen_at}",
        device_hash=device_hash,
        created_at=last_seen_at,
        last_seen_at=last_seen_at,
    )
    defaults.update(overrides)
    return Session(**defaults)


# ─────────────────────────── Session.device_label ───────────────────────────


def test_session_device_label_roundtrip():
    session = Session(
        token="t1", device_hash="h1", created_at=1.0, last_seen_at=1.0,
        device_label="Pixel 8 Pro",
    )
    restored = Session.from_dict(session.to_dict())
    assert restored is not None
    assert restored.device_label == "Pixel 8 Pro"


def test_session_from_dict_missing_device_label_defaults_none():
    """Rétrocompatibilité : une session persistée avant l'ajout du champ
    device_label doit se recharger sans erreur, avec device_label=None."""
    old_format = {
        "token": "t1",
        "device_hash": "h1",
        "created_at": 1.0,
        "last_seen_at": 1.0,
        "refresh_token_hash": None,
        "refresh_expires_at": None,
        "capabilities": None,
        # pas de clé "device_label" du tout
    }
    session = Session.from_dict(old_format)
    assert session is not None
    assert session.device_label is None


# ─────────────────────────── update_capabilities ───────────────────────────


def test_update_capabilities_returns_true_on_first_change():
    manager = PairingManager(sessions_path=None)
    result = manager.redeem(manager.create_pairing_code(), "a" * 64)
    assert result is not None
    device_hash = result.session.device_hash

    changed = manager.update_capabilities(device_hash, [{"name": "get_battery"}])
    assert changed is True


def test_update_capabilities_returns_false_when_unchanged():
    manager = PairingManager(sessions_path=None)
    result = manager.redeem(manager.create_pairing_code(), "b" * 64)
    device_hash = result.session.device_hash
    caps = [{"name": "get_battery"}]

    assert manager.update_capabilities(device_hash, caps) is True
    assert manager.update_capabilities(device_hash, caps) is False  # contenu identique


def test_update_capabilities_returns_true_on_label_only_change():
    manager = PairingManager(sessions_path=None)
    result = manager.redeem(manager.create_pairing_code(), "c" * 64)
    device_hash = result.session.device_hash
    caps = [{"name": "get_battery"}]

    assert manager.update_capabilities(device_hash, caps, "Pixel") is True
    assert manager.update_capabilities(device_hash, caps, "Pixel") is False
    assert manager.update_capabilities(device_hash, caps, "Desktop") is True  # label seul change


def test_update_capabilities_returns_false_for_unknown_device():
    manager = PairingManager(sessions_path=None)
    changed = manager.update_capabilities("nonexistent", [{"name": "x"}])
    assert changed is False


# ─────────────────────────── list_devices ───────────────────────────────────


def test_list_devices_empty_initially():
    manager = PairingManager(sessions_path=None)
    assert manager.list_devices() == []


def test_list_devices_dedupes_by_device_hash_max_last_seen():
    """Deux sessions pour le même device_hash (reconnexions successives, pas de
    réutilisation de session existante — limitation connue) : list_devices()
    ne doit retourner que la plus récente."""
    manager = PairingManager(sessions_path=None)
    now = time.time()

    older = make_session("shared-hash", now - 100)
    newer = make_session("shared-hash", now, device_label="Le plus récent")
    manager._sessions[older.token] = older
    manager._sessions[newer.token] = newer

    devices = manager.list_devices()
    assert len(devices) == 1
    assert devices[0].token == newer.token
    assert devices[0].device_label == "Le plus récent"


def test_list_devices_returns_one_per_distinct_device_hash():
    manager = PairingManager(sessions_path=None)
    now = time.time()

    session_a = make_session("device-a", now)
    session_b = make_session("device-b", now)
    manager._sessions[session_a.token] = session_a
    manager._sessions[session_b.token] = session_b

    devices = manager.list_devices()
    assert {d.device_hash for d in devices} == {"device-a", "device-b"}
