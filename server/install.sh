#!/usr/bin/env bash
# Installateur unique du bridge Hasan, adaptatif selon l'infrastructure Hermès
# trouvée (natif ou conteneurisé). Voir issue #12.
#
# Une commande installe tout ce dont Hasan a besoin pour dialoguer avec
# Hermès : le plugin hasan_delivery (dans l'installation Hermès existante),
# hermes-webui (si absent), le relay et Caddy (conteneurs).
#
# Ce qu'il NE fait jamais :
#   - installer ou modifier Hermès (son installateur s'en charge) ;
#   - s'exécuter depuis l'intérieur d'un conteneur (garde-fou) ;
#   - modifier le docker-compose.yml de l'utilisateur (il affiche le bloc
#     à coller) ;
#   - écraser un Caddy ou un webui tiers déjà en place.
#
# Usage :
#   sudo ./server/install.sh
#   sudo ./server/install.sh --force   # remplace un Caddyfile non géré par nous
#
# Idempotent : relançable pour mettre à jour un déploiement existant.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_PROJECT="hasan-bridge"
CADDY_MARKER="# managed-by: hasan-bridge docker compose"

# shellcheck source=./lib/detect.sh
source "${SCRIPT_DIR}/lib/detect.sh"
# shellcheck source=./lib/actions.sh
source "${SCRIPT_DIR}/lib/actions.sh"

FORCE=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --force) FORCE=1; shift ;;
        *) echo "Argument inconnu : $1" >&2; exit 1 ;;
    esac
done

# ─────────────────────────── 0. Garde-fous ─────────────────────────────────

# Le tout premier test, avant même de vérifier root : refuser de tourner
# dans un conteneur (Docker-dans-Docker, écritures éphémères). Voir detect.sh.
abort_if_inside_container

# Deux façons valides de lancer ce script :
#   - en root (sudo) : nécessaire si Docker doit être installé ;
#   - en utilisateur normal membre du groupe docker : suffisant quand Docker
#     est déjà là, ce qui est le cas courant sur une machine homelab. Ça évite
#     d'exiger root pour un déploiement qui n'écrit que dans le home de
#     l'utilisateur et pilote un Docker déjà accessible.
IS_ROOT=0
[[ "$(id -u)" -eq 0 ]] && IS_ROOT=1

if [[ "${IS_ROOT}" -eq 0 ]] && ! docker ps >/dev/null 2>&1; then
    echo "À lancer soit en root (sudo), soit en utilisateur du groupe docker." >&2
    echo "Ici : ni root, ni accès Docker. Ajouter l'utilisateur au groupe" >&2
    echo "docker (newgrp docker), ou relancer avec sudo." >&2
    exit 1
fi

# Résout le home et l'utilisateur cible selon le mode de lancement.
if [[ "${IS_ROOT}" -eq 1 ]] && [[ -n "${SUDO_USER:-}" ]]; then
    # Root via sudo : Hermès/webui appartiennent à l'invocateur, pas à root.
    USER_HOME="$(getent passwd "${SUDO_USER}" | cut -d: -f6)"
    RUN_AS_USER="${SUDO_USER}"
else
    # Root direct, ou utilisateur normal : on écrit dans son propre home.
    USER_HOME="${HOME}"
    RUN_AS_USER=""
fi

# Exécute une commande en tant qu'utilisateur invocateur (pas root) — pour
# tout ce qui écrit dans son home ou pilote Docker. No-op quand on n'est pas
# root (on est déjà le bon utilisateur).
as_user() {
    if [[ -n "${RUN_AS_USER}" ]]; then
        sudo -u "${RUN_AS_USER}" "$@"
    else
        "$@"
    fi
}

# ─────────────────────────── 1. Docker ─────────────────────────────────────

echo "==> Docker"
if ! command -v docker >/dev/null 2>&1; then
    if [[ "${IS_ROOT}" -eq 1 ]]; then
        echo "Docker absent — installation via le script officiel get.docker.com."
        curl -fsSL https://get.docker.com | sh
        systemctl enable --now docker >/dev/null 2>&1 || true
    else
        echo "Docker absent et script lancé sans root : impossible de l'installer." >&2
        echo "Relancer avec sudo pour l'installation de Docker." >&2
        exit 1
    fi
fi
if ! docker compose version >/dev/null 2>&1; then
    echo "Le plugin 'docker compose' (v2) est requis mais introuvable." >&2
    echo "Voir https://docs.docker.com/compose/install/" >&2
    exit 1
fi

# ─────────────────────────── 2. Détection Hermès ───────────────────────────

echo "==> Détection de Hermès"
read -rp "HERMES_HOME [${USER_HOME}/.hermes] : " HERMES_HOME_INPUT
HERMES_HOME="${HERMES_HOME_INPUT:-${USER_HOME}/.hermes}"

HERMES_MODE="$(detect_hermes_mode "${HERMES_HOME}")"
case "${HERMES_MODE}" in
    native)
        echo "  Hermès natif détecté (venv ${HERMES_HOME}/hermes-agent/venv)."
        PLUGIN_DEST_DIR="${HERMES_HOME}"
        ;;
    container)
        HERMES_CONTAINER="$(detect_hermes_container)"
        echo "  Hermès conteneurisé détecté (conteneur « ${HERMES_CONTAINER} »)."
        PLUGIN_DEST_DIR="$(detect_hermes_volume_host_path "${HERMES_CONTAINER}")"
        if [[ -z "${PLUGIN_DEST_DIR}" ]]; then
            echo "Le conteneur Hermès n'a aucun volume monté sur /opt/data." >&2
            echo "Sans volume, le plugin serait perdu au redémarrage. Ajoutez" >&2
            echo "au compose de Hermès :  - ${HERMES_HOME}:/opt/data" >&2
            exit 1
        fi
        echo "  Volume /opt/data monté depuis : ${PLUGIN_DEST_DIR}"
        ;;
    none)
        echo "Aucune installation Hermès trouvée (ni conteneur, ni venv dans" >&2
        echo "${HERMES_HOME}). Ce script n'installe PAS Hermès lui-même." >&2
        echo "Installer Hermès d'abord — natif ou conteneurisé — puis relancer." >&2
        echo "Pour le mode conteneurisé, voir server/hermes-image/ (image" >&2
        echo "dérivée qui embarque aussi hermes-webui)." >&2
        exit 1
        ;;
esac

# ─────────────────────────── 3. Wizard réseau ──────────────────────────────

echo
echo "=== Configuration du bridge ==="
read -rp "IP ou nom d'hôte public/tailscale de ce serveur : " PUBLIC_HOST
if [[ -z "${PUBLIC_HOST}" ]]; then
    echo "L'hôte public est requis." >&2
    exit 1
fi

# Exposition optionnelle du dashboard interne de Hermès (:9119) sur :8443.
# Off par défaut : le dashboard est de l'admin, jamais utilisé par l'app, et
# l'exposer publiquement demande une auth (voir la garde du dashboard).
EXPOSE_DASHBOARD=0
read -rp "Exposer le dashboard Hermès interne (:9119) publiquement sur :8443 ? [y/N] : " dash_answer
if [[ "${dash_answer}" =~ ^[Yy] ]]; then
    EXPOSE_DASHBOARD=1
fi

# ─────────────────────────── 4. hermes-webui ───────────────────────────────

echo "==> hermes-webui (écran Chat de l'app)"
WEBUI_URL=""
if [[ "${HERMES_MODE}" == "container" ]]; then
    # En conteneurisé, la question n'est pas "webui répond-il ?" (il peut être
    # lent à démarrer) mais "est-il dans l'image ?". S'il n'y est pas, l'image
    # nue a été utilisée et webui ne peut pas être ajouté après coup.
    if webui_embedded_in_container "${HERMES_CONTAINER}"; then
        echo "  hermes-webui embarqué dans l'image — rien à installer."
        WEBUI_URL="https://${PUBLIC_HOST}"
    else
        webui_container_missing_abort
    fi
elif webui_is_running; then
    echo "  hermes-webui répond déjà sur :8787 — rien à installer."
    WEBUI_URL="https://${PUBLIC_HOST}"
else
    # Hermès natif : webui tourne à côté, dans le même venv. On l'installe
    # s'il est absent (clone amont master, deps dans le venv Hermès).
    echo "  hermes-webui absent — installation à côté de Hermès natif."
    install_webui_native "${HERMES_HOME}"
    WEBUI_URL="https://${PUBLIC_HOST}"
fi

# Mot de passe hermes-webui — porté dans le QR de pairing pour que l'app se
# connecte au Chat automatiquement. Résolu sans prompt quand c'est possible :
# env déjà exportée > .env de webui > prompt en dernier recours. Vide = pas de
# login auto (l'utilisateur le saisira dans l'app).
WEBUI_PASSWORD=""
if [[ -n "${WEBUI_URL}" ]]; then
    WEBUI_PASSWORD="$(resolve_webui_password "${USER_HOME}" "${HERMES_MODE}" "${HERMES_CONTAINER:-}")"
fi

# ─────────────────────────── 5. Caddy existant ? ───────────────────────────

echo "==> Caddy"
CADDYFILE="${SCRIPT_DIR}/Caddyfile"
REUSE_EXISTING_CADDY=0
CADDY_443_HOLDER="$(port_443_foreign_holder)"
if [[ -n "${CADDY_443_HOLDER}" ]]; then
    echo "  Le port 443 est déjà tenu par un tiers : ${CADDY_443_HOLDER}."
    echo "  On ne lance PAS un second Caddy (il crash-looperait sur le port"
    echo "  occupé — piège du 2026-07-28). L'existant sera réutilisé ; les"
    echo "  lignes de routage à ajouter seront affichées à la fin."
    REUSE_EXISTING_CADDY=1
fi

# ─────────────────────────── 6. Secrets + .env ─────────────────────────────

echo "==> Secrets"
ENV_FILE="${SCRIPT_DIR}/.env"
RELAY_ADMIN_TOKEN=""
if [[ -f "${ENV_FILE}" ]]; then
    RELAY_ADMIN_TOKEN="$(grep '^RELAY_ADMIN_TOKEN=' "${ENV_FILE}" 2>/dev/null | head -n1 | cut -d= -f2-)"
fi
if [[ -z "${RELAY_ADMIN_TOKEN}" ]]; then
    RELAY_ADMIN_TOKEN="$(openssl rand -hex 32)"
    echo "  Nouveau RELAY_ADMIN_TOKEN généré."
else
    echo "  RELAY_ADMIN_TOKEN existant conservé."
fi

# Le relay joint Hermès par HERMES_API_BASE_URL. En conteneurisé, Hermès
# n'écoute pas forcément sur 127.0.0.1 vu du relay ; mais le relay tourne en
# network_mode: host, donc 127.0.0.1 pointe bien vers l'hôte, où le port de
# Hermès est publié. On garde 127.0.0.1 — cohérent avec le déploiement natif.
write_env_file "${ENV_FILE}" "${RELAY_ADMIN_TOKEN}" "${PUBLIC_HOST}" "${WEBUI_URL}" "${WEBUI_PASSWORD}"

# ─────────────────────────── 7. Caddyfile ──────────────────────────────────

if [[ "${REUSE_EXISTING_CADDY}" -eq 0 ]]; then
    render_caddyfile "${SCRIPT_DIR}/Caddyfile.template" "${CADDYFILE}" "${PUBLIC_HOST}" "${FORCE}" "${CADDY_MARKER}" "${EXPOSE_DASHBOARD}" "${SCRIPT_DIR}/Caddyfile.dashboard-block.template"
fi

# ─────────────────────────── 8. Conteneurs ─────────────────────────────────

echo "==> Lancement des conteneurs (relay$( [[ "${REUSE_EXISTING_CADDY}" -eq 0 ]] && echo " + Caddy" ))"
COMPOSE_SERVICES=(relay)
[[ "${REUSE_EXISTING_CADDY}" -eq 0 ]] && COMPOSE_SERVICES+=(caddy)
( cd "${SCRIPT_DIR}" && docker compose -p "${COMPOSE_PROJECT}" up -d --build "${COMPOSE_SERVICES[@]}" )

echo "==> Vérification"
sleep 2
if ! curl -sf --max-time 5 http://127.0.0.1:8767/health >/dev/null; then
    echo "  AVERTISSEMENT : le relay ne répond pas encore sur :8767." >&2
    echo "  Voir : docker compose -p ${COMPOSE_PROJECT} logs relay" >&2
fi

# ─────────────────────────── 9. Plugin ─────────────────────────────────────

echo "==> Dépôt du plugin hasan_delivery dans ${PLUGIN_DEST_DIR}"
install_plugin "${REPO_ROOT}/plugin/hasan_delivery" "${PLUGIN_DEST_DIR}" "${HERMES_MODE}" "${HERMES_HOME}"

# ─────────────────────────── 10. Redémarrage Hermès ────────────────────────

echo "==> Redémarrage de Hermès pour charger le plugin"
restart_hermes "${HERMES_MODE}" "${HERMES_CONTAINER:-}"

# ─────────────────────────── 11. Pairing + résumé ──────────────────────────

echo "==> Code de pairing"
sleep 1
PAIRING_JSON="$(curl -sf --max-time 5 -X POST "http://127.0.0.1:8767/pairing/create" \
    -H "Authorization: Bearer ${RELAY_ADMIN_TOKEN}" || true)"

print_summary
