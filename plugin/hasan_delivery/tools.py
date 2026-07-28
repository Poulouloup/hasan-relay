"""Outils Hermes natifs pour les capabilities du téléphone Hasan.

Remplace l'ancien serveur MCP externe (`~/.hermes/phone-relay-mcp/server.js`,
Node.js, hors de ce repo) — même contrat HTTP (`POST /bridge/command` sur le
relay, voir `server/relay/server.py::handle_bridge_command` et
`server/relay/bridge_commands.py`), mais enregistré directement dans le
registre d'outils natif de Hermes plutôt que via un serveur MCP séparé,
via `ctx.register_tool()` (voir register_tools() plus bas).

La confirmation utilisateur pour les capabilities sensibles (send_sms,
make_call, get_location, get_contacts, get_calendar_events, get_clipboard)
vit entièrement côté app Android (voir BridgeCommandHandler.kt côté app,
settings.isCapabilityAuthRequired) — ce module ne fait que relayer la
requête HTTP et attendre le résultat, exactement comme le faisait le MCP
qu'il remplace. Aucun changement de ce mécanisme de sécurité.

Schémas récupérés DYNAMIQUEMENT depuis `GET /capabilities` sur le relay au
démarrage du gateway Hermes, plutôt que codés en dur ici — l'app annonce
ses propres capabilities (activées + permission accordée) à chaque
connexion WS (voir ConnectionManager.kt::capabilitiesAnnouncementJson,
CapabilitySchema.kt), le relay les persiste par device
(server/relay/pairing.py::Session.capabilities). Ajouter/modifier une
capability dans app/src/main/java/com/hasan/v1/Capability.kt suffit donc —
aucune édition de ce fichier nécessaire, seul un `hermes gateway restart`
est requis pour que Hermes voie le changement (les tools sont enregistrés
une seule fois, via register_tools() appelé depuis __init__.py::register(ctx)
— pas de rafraîchissement à chaud, cohérent avec le fonctionnement standard
des plugins Hermes qui nécessitent déjà un restart pour toute modification).

Si le relay est injoignable ou le device jamais appairé au démarrage du
gateway, aucun tool `hasan_phone` n'est enregistré cette session-là (pas
d'erreur bloquante pour le chargement du plugin) — cohérent avec
`_check_requirements()` qui gère déjà l'absence de config.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
from typing import Any

import httpx

logger = logging.getLogger(__name__)

_TIMEOUT_SECONDS = 35.0  # Marge au-dessus du timeout serveur (30s, bridge_commands.py)
_FETCH_TIMEOUT_SECONDS = 10.0


def _relay_config() -> tuple[str, str]:
    """Lit l'URL du relay et le token de session du device — mêmes env vars que adapter.py."""
    relay_url = os.getenv("HASAN_RELAY_URL", "").strip().rstrip("/")
    session_token = os.getenv("HASAN_RELAY_SESSION_TOKEN", "").strip()
    return relay_url, session_token


def _check_requirements() -> bool:
    relay_url, session_token = _relay_config()
    return bool(relay_url and session_token)


async def _call_capability(capability: str, params: dict[str, Any]) -> str:
    """POST /bridge/command sur le relay, attend le résultat, retourne du JSON.

    Contrat identique à celui utilisé côté MCP historique — mêmes codes
    d'erreur (401/400/503 device_not_connected/504 command_timeout, voir
    server/relay/server.py::handle_bridge_command).
    """
    relay_url, session_token = _relay_config()
    if not relay_url or not session_token:
        return json.dumps({"error": "HASAN_RELAY_URL/HASAN_RELAY_SESSION_TOKEN non configurés"})

    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT_SECONDS) as client:
            response = await client.post(
                f"{relay_url}/bridge/command",
                json={"capability": capability, "params": params},
                headers={"Authorization": f"Bearer {session_token}"},
            )
    except httpx.RequestError as exc:
        return json.dumps({"error": f"relay injoignable: {exc}"})

    try:
        data = response.json()
    except ValueError:
        return json.dumps({"error": f"réponse relay invalide (HTTP {response.status_code})"})
    return json.dumps(data)


def _tool(capability: str):
    """Fabrique un handler async qui relaie `capability` avec les args reçus du LLM."""

    async def handler(args: dict[str, Any], **_kw: Any) -> str:
        return await _call_capability(capability, args or {})

    return handler


async def _fetch_capabilities() -> dict[str, dict[str, Any]]:
    """GET /capabilities sur le relay — schémas annoncés par le device appairé.

    Retourne {} (silencieusement) si le relay n'est pas configuré/joignable,
    ou si la réponse est malformée : ce module ne doit jamais faire échouer
    le chargement du plugin hasan_delivery au démarrage du gateway.
    """
    relay_url, session_token = _relay_config()
    if not relay_url or not session_token:
        return {}

    try:
        async with httpx.AsyncClient(timeout=_FETCH_TIMEOUT_SECONDS) as client:
            response = await client.get(
                f"{relay_url}/capabilities",
                headers={"Authorization": f"Bearer {session_token}"},
            )
        response.raise_for_status()
        data = response.json()
    except (httpx.RequestError, httpx.HTTPStatusError, ValueError) as exc:
        logger.warning("hasan_delivery: échec récupération /capabilities: %s", exc)
        return {}

    capabilities = data.get("capabilities")
    if not isinstance(capabilities, list):
        return {}

    schemas: dict[str, dict[str, Any]] = {}
    for entry in capabilities:
        if not isinstance(entry, dict):
            continue
        name = entry.get("name")
        if not isinstance(name, str) or not name:
            continue
        schemas[name] = {
            "name": name,
            "description": entry.get("description") or f"Capability téléphone Hasan: {name}",
            "parameters": entry.get("parameters") or {"type": "object", "properties": {}, "required": []},
        }
    return schemas


# ─────────────────────────── Enregistrement ─────────────────────────────────
# Appelé explicitement depuis __init__.py::register(ctx) — jamais à l'import
# de ce module. Hermes n'importe que ce que `plugins/hasan_delivery/__init__.py`
# référence ; un simple `import tools.py` à côté d'`adapter.py` ne suffit pas
# à le faire charger. Appeler ceci depuis register(ctx) garantit aussi que les
# requires_env (HASAN_RELAY_URL, HASAN_RELAY_SESSION_TOKEN) sont déjà dans
# os.environ à ce stade, comme pour l'enregistrement de la plateforme adapter.py.
#
# IMPORTANT : passe par ctx.register_tool(), pas tools.registry.registry.register()
# directement. Les deux appellent le même registre sous le capot, mais seul
# ctx.register_tool() fait aussi self._manager._plugin_tool_names.add(name)
# côté PluginContext (hermes_cli/plugins.py) — c'est cette liste que
# get_plugin_toolsets()/`hermes tools enable` utilisent pour reconnaître un
# toolset comme valide. Un appel direct au registre enregistre bien le tool
# (invocable), mais le toolset reste invisible/« Unknown toolset » pour tout
# ce qui passe par cette liste d'attribution.
def register_tools(ctx) -> None:
    schemas = asyncio.run(_fetch_capabilities())
    for name, schema in schemas.items():
        ctx.register_tool(
            name=name,
            toolset="hasan_phone",
            schema=schema,
            handler=_tool(name),
            check_fn=_check_requirements,
            requires_env=["HASAN_RELAY_URL", "HASAN_RELAY_SESSION_TOKEN"],
            is_async=True,
        )
