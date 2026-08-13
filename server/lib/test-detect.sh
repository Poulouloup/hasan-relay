#!/usr/bin/env bash
# Vérifie la détection d'infrastructure SANS RIEN INSTALLER ni modifier.
#
# À lancer en premier sur une machine cible : il affiche ce que le vrai
# installateur va décider, et pourquoi. Si une ligne est fausse, l'installation
# le serait aussi — autant s'en apercevoir avant.
#
# Usage :
#   ./server/lib/test-detect.sh
#
# Ne demande pas root : aucune écriture, aucun conteneur lancé. Docker est
# seulement interrogé en lecture (docker ps/inspect), ce qui peut nécessiter
# d'être dans le groupe docker — auquel cas le script le signale au lieu
# d'échouer.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./detect.sh
source "${SCRIPT_DIR}/detect.sh"

if [[ -n "${SUDO_USER:-}" ]]; then
    USER_HOME="$(getent passwd "${SUDO_USER}" | cut -d: -f6)"
else
    USER_HOME="${HOME}"
fi
HERMES_HOME="${HERMES_HOME:-${USER_HOME}/.hermes}"
CADDY_MARKER="# managed-by: hasan-bridge docker compose"

ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
no()   { printf '  \033[31m✗\033[0m %s\n' "$1"; }
info() { printf '    %s\n' "$1"; }

echo
echo "=== Détection de l'infrastructure — aucune modification ==="
echo

# ── 1. Garde-fou ────────────────────────────────────────────────────────────
echo "1. Emplacement d'exécution"
if running_inside_container; then
    no "DANS un conteneur — le vrai installateur refuserait de continuer"
    info "Relancer depuis la machine hôte."
else
    ok "Sur la machine hôte (/.dockerenv absent)"
fi
echo

# ── 2. Docker ───────────────────────────────────────────────────────────────
echo "2. Docker"
if command -v docker >/dev/null 2>&1; then
    if docker ps >/dev/null 2>&1; then
        ok "présent et accessible ($(docker --version 2>/dev/null | head -n1))"
    else
        no "présent mais inaccessible sans privilèges"
        info "Relancer avec sudo, ou ajouter l'utilisateur au groupe docker."
        info "La détection Hermes/Caddy ci-dessous sera incomplète."
    fi
else
    no "absent — le vrai installateur l'installerait via get.docker.com"
fi
echo

# ── 3. Hermes ───────────────────────────────────────────────────────────────
echo "3. Hermes"
HERMES_MODE="$(detect_hermes_mode "${HERMES_HOME}")"
case "${HERMES_MODE}" in
    container)
        CONTAINER="$(detect_hermes_container)"
        ok "conteneurisé — conteneur « ${CONTAINER} »"
        VOL="$(detect_hermes_volume_host_path "${CONTAINER}")"
        if [[ -n "${VOL}" ]]; then
            info "volume /opt/data monté depuis : ${VOL}"
            info "→ le plugin sera déposé dans ${VOL}/plugins/"
        else
            no "aucun volume monté sur /opt/data"
            info "Sans volume, le plugin serait perdu au prochain redémarrage."
            info "Ajouter au compose : - ~/.hermes:/opt/data"
        fi
        if hermes_container_has_httpx "${CONTAINER}"; then
            ok "httpx déjà présent dans l'image — rien à installer"
        else
            no "httpx absent de l'image"
            info "Inattendu : l'image officielle l'embarque (vérifié en 0.28.1)."
            info "Une image dérivée serait nécessaire pour ce cas."
        fi
        ;;
    native)
        ok "natif — venv ${HERMES_HOME}/hermes-agent/venv"
        if hermes_native_has_httpx "${HERMES_HOME}"; then
            ok "httpx déjà présent dans le venv"
        else
            info "httpx absent — sera installé par l'installateur"
        fi
        info "→ le plugin sera déposé dans ${HERMES_HOME}/plugins/"
        ;;
    none)
        no "introuvable (ni conteneur, ni venv dans ${HERMES_HOME})"
        info "Le vrai installateur s'arrêterait ici : il n'installe pas Hermes."
        info "Installer Hermes d'abord, ou définir HERMES_HOME."
        ;;
esac
echo

# ── 4. hermes-webui ─────────────────────────────────────────────────────────
echo "4. hermes-webui (écran Chat de l'app)"
if webui_is_running; then
    ok "répond sur 127.0.0.1:8787 — rien à installer"
elif webui_is_installed "${USER_HOME}"; then
    no "cloné dans ${USER_HOME}/hermes-webui mais ne répond pas"
    info "L'installateur tenterait de le démarrer plutôt que de le recloner."
else
    no "absent — sera cloné depuis github.com/nesquena/hermes-webui"
    info "Projet tiers, distinct de Hermes : non fourni avec l'image officielle."
fi
echo

# ── 5. Caddy / port 443 ─────────────────────────────────────────────────────
echo "5. Caddy (TLS, point d'entrée unique)"
CADDY_HOLDER="$(port_443_foreign_holder)"
if [[ -n "${CADDY_HOLDER}" ]]; then
    no "le port 443 est déjà tenu par un tiers : ${CADDY_HOLDER}"
    info "L'installateur réutilisera cet existant plutôt que d'en lancer un"
    info "second (qui crash-looperait — piège du 2026-07-28)."
    info "→ il affichera les lignes de routage à ajouter à votre Caddyfile."
else
    info "port 443 libre — l'installateur lancera son propre Caddy"
fi
if caddyfile_is_ours "${SCRIPT_DIR}/../Caddyfile" "${CADDY_MARKER}"; then
    ok "server/Caddyfile porte notre marqueur — déploiement existant"
elif [[ -f "${SCRIPT_DIR}/../Caddyfile" ]]; then
    no "server/Caddyfile existe SANS notre marqueur"
    info "L'installateur refuserait de l'écraser sans --force."
fi
echo

# ── 6. Ports ────────────────────────────────────────────────────────────────
echo "6. Ports requis"
for port in 443 8767; do
    holder="$(port_holder "${port}")"
    if [[ -n "${holder}" ]]; then
        no "port ${port} occupé par : ${holder}"
    else
        ok "port ${port} libre"
    fi
done
info "Ports occupés par un déploiement Hasan existant = normal (mise à jour)."
echo

# ── Verdict ─────────────────────────────────────────────────────────────────
echo "=== Résumé ==="
echo "  Mode Hermes détecté : ${HERMES_MODE}"
case "${HERMES_MODE}" in
    container) echo "  → plugin copié dans le volume, httpx déjà là, redémarrage via docker restart" ;;
    native)    echo "  → plugin copié dans ~/.hermes, httpx via pip, redémarrage via systemctl" ;;
    none)      echo "  → installation impossible en l'état : Hermes est requis" ;;
esac
echo
