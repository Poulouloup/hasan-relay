"""Tests unitaires pour DeviceChangeSignal (server/relay/device_watch.py)."""

from __future__ import annotations

import asyncio

from device_watch import DeviceChangeSignal


async def test_wait_returns_true_after_bump():
    signal = DeviceChangeSignal()

    async def bump_soon():
        await asyncio.sleep(0.05)
        signal.bump()

    asyncio.ensure_future(bump_soon())
    result = await signal.wait(timeout=2.0)
    assert result is True


async def test_wait_returns_false_on_timeout_without_bump():
    signal = DeviceChangeSignal()
    result = await signal.wait(timeout=0.1)
    assert result is False


async def test_bump_before_wait_is_still_observed():
    """bump() appelé avant wait() — l'Event reste set jusqu'à consommation."""
    signal = DeviceChangeSignal()
    signal.bump()
    result = await signal.wait(timeout=1.0)
    assert result is True


async def test_wait_consumes_signal_for_next_waiter():
    """Après un wait() réveillé, un wait() immédiatement suivant sans nouveau
    bump() doit timeout (signal consommé, pas rejoué)."""
    signal = DeviceChangeSignal()
    signal.bump()
    first = await signal.wait(timeout=1.0)
    assert first is True

    second = await signal.wait(timeout=0.1)
    assert second is False
