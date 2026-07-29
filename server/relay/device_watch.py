"""Signal de changement d'état des devices (connexion/déconnexion/capabilities),
pour GET /devices/watch — long-poll consommé par plugin/hasan_delivery pour
savoir quand relire GET /devices, sans dupliquer /phone/replies (stub non
fonctionnel, voir server.py handle_phone_replies) ni reconstruire un vrai
pub/sub. Un seul signal grossier ("quelque chose a changé"), pas de
granularité par device — le consommateur relit toujours l'état complet via
GET /devices après réveil, cohérent avec le choix de rester proportionné
plutôt que de bâtir un mécanisme de notification par device.
"""

from __future__ import annotations

import asyncio


class DeviceChangeSignal:
    """Un seul asyncio.Event partagé — simplification assumée : avec un seul
    process gateway Hermes réaliste comme consommateur, le clear-on-wake
    partagé (le premier waiter réveillé consomme le signal pour tous) n'est
    pas un problème en pratique. Pas conçu pour plusieurs waiters concurrents
    indépendants."""

    def __init__(self) -> None:
        self._event = asyncio.Event()

    def bump(self) -> None:
        """Appelé sur connexion WS, déconnexion WS, ou changement de contenu
        capabilities/device_label (update_capabilities) — réveille tous les
        waiters actuellement bloqués dans wait()."""
        self._event.set()

    async def wait(self, timeout: float) -> bool:
        """Bloque jusqu'à bump() ou timeout. Retourne True si un changement
        est survenu, False si timeout. Consomme le signal (clear) après
        réveil ou timeout — chaque appelant qui se rendort doit attendre un
        NOUVEAU bump, pas rejouer un événement déjà consommé."""
        try:
            await asyncio.wait_for(self._event.wait(), timeout=timeout)
        except asyncio.TimeoutError:
            return False
        finally:
            self._event.clear()
        return True
