from .adapter import register as _register_adapter
from .tools import register_tools


def register(ctx) -> None:
    """Point d'entrée du plugin, appelé une fois par Hermes au démarrage du
    gateway. Deux enregistrements distincts : le canal de messagerie
    (adapter.py, ctx.register_platform) et les tools function-calling
    hasan_phone (tools.py, registre natif — voir tools.py pour le pourquoi
    de cet appel explicite plutôt qu'un effet de bord à l'import)."""
    _register_adapter(ctx)
    register_tools(ctx)


__all__ = ["register"]
