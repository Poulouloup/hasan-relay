"""Charge tools.py directement par chemin de fichier, sans passer par le
package `hasan_delivery` (dont __init__.py importe adapter.py, qui importe
`gateway.config` — le runtime Hermes, absent de ce dépôt). tools.py n'a
besoin que de httpx/asyncio pour être testé isolément.

Ce conftest.py vit dans tests/ (sans __init__.py), un dossier frère du
package plugin, jamais importé comme sous-module de hasan_delivery — c'est
ce qui évite de déclencher hasan_delivery/__init__.py."""

import importlib.util
import sys
from pathlib import Path

_TOOLS_PATH = Path(__file__).parent.parent / "tools.py"
_spec = importlib.util.spec_from_file_location("tools", _TOOLS_PATH)
_module = importlib.util.module_from_spec(_spec)
sys.modules["tools"] = _module
_spec.loader.exec_module(_module)
