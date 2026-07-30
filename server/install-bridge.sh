#!/usr/bin/env bash
# Orchestrateur "une commande" pour le déploiement du bridge Hasan complet :
# relay + Caddy (en Docker, network_mode: host) + plugin hasan_delivery côté
# Hermes (en dehors de Docker — tourne dans le venv Hermes existant, pas un
# service autonome).
#
# Wizard interactif une seule fois au début, puis :
#   1. Vérifie/installe Docker (script officiel get.docker.com)
#   2. Génère RELAY_ADMIN_TOKEN + server/.env
#   3. Rend server/Caddyfile depuis les templates
#   4. docker compose up -d --build (relay + caddy)
#   5. Appelle plugin/hasan_delivery/install-plugin.sh (copie aussi le
#      skill de diagnostic Hermes)
#   6. Génère un premier code de pairing et affiche le résumé
#
# Usage :
#   sudo ./server/install-bridge.sh
#   sudo ./server/install-bridge.sh --force   # écrase un Caddyfile existant non géré par ce script
#
# Idempotent : peut être relancé pour mettre à jour un déploiement existant
# (re-render config + docker compose pull/up -d --build).
#
# Limites connues, non couvertes par ce script :
#   - Règle de firewall réseau côté provider (GCP/AWS/...) — n'ouvrir QUE
#     443 (et 8443 si le dashboard est exposé), jamais les ports internes
#     (8767, 8787, 9119) directement au public.
#   - Détection de collision avec un process Docker (docker-proxy) déjà lié
#     aux ports cibles — seule la collision avec un process natif est
#     nommée explicitement (docker-proxy et un process natif se
#     ressemblent peu dans `ss`, mais le script échoue dans les deux cas).
#   - Premier `apt`/install Docker sur une distro non testée par ce script
#     (repose sur get.docker.com, qui couvre Debian/Ubuntu/CentOS/Fedora —
#     voir sa propre documentation pour la liste à jour).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_PROJECT="hasan-bridge"
CADDY_MARKER="# managed-by: hasan-bridge docker compose"

FORCE=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --force)
            FORCE=1
            shift
            ;;
        *)
            echo "Argument inconnu : $1" >&2
            exit 1
            ;;
    esac
done

if [[ "$(id -u)" -ne 0 ]]; then
    echo "Ce script doit être exécuté en root (sudo)." >&2
    exit 1
fi

# Sous sudo, $HOME pointe vers /root — Hermes et hermes-webui appartiennent
# à l'utilisateur qui a invoqué sudo, pas à root. Résout le vrai home pour
# tous les usages qui suivent (détection ~/hermes-webui/.env, défaut
# HERMES_HOME, prompt du wizard).
if [[ -n "${SUDO_USER:-}" ]]; then
    USER_HOME="$(getent passwd "${SUDO_USER}" | cut -d: -f6)"
else
    USER_HOME="${HOME}"
fi

# ─────────────────────────── 1. Préflight ports ────────────────────────────

echo "==> Vérification des ports (443 relay/webui, 8767 relay direct)"

check_port_free() {
    local port="$1"
    local holder
    holder="$(ss -ltnp "sport = :${port}" 2>/dev/null | awk 'NR==2 {print $0}')"
    if [[ -n "${holder}" ]]; then
        echo "Le port ${port} est déjà occupé :" >&2
        echo "  ${holder}" >&2
        echo "Un Caddy natif (systemd) ou un autre process Docker occupe" >&2
        echo "peut-être déjà ce port — c'est exactement le piège double-" >&2
        echo "Caddyfile documenté dans le skill hasan-bridge-diagnosis." >&2
        echo "Libérer le port avant de relancer ce script." >&2
        exit 1
    fi
}

check_port_free 443
check_port_free 8767

# ─────────────────────────── 2. Docker ──────────────────────────────────────

echo "==> Docker"
if ! command -v docker >/dev/null 2>&1; then
    echo "Docker absent — installation via le script officiel get.docker.com."
    curl -fsSL https://get.docker.com | sh
fi
if ! docker compose version >/dev/null 2>&1; then
    echo "Le plugin 'docker compose' (v2) est requis mais introuvable." >&2
    echo "Voir https://docs.docker.com/compose/install/ pour l'installer." >&2
    exit 1
fi
systemctl enable --now docker >/dev/null 2>&1 || true

# ─────────────────────────── 3. Détection d'état existant ──────────────────

CADDYFILE="${SCRIPT_DIR}/Caddyfile"
ENV_FILE="${SCRIPT_DIR}/.env"
ALREADY_DEPLOYED=0
if [[ -f "${CADDYFILE}" ]] || [[ -f "${ENV_FILE}" ]]; then
    if [[ -f "${CADDYFILE}" ]] && ! head -n1 "${CADDYFILE}" | grep -qF "${CADDY_MARKER}"; then
        if [[ "${FORCE}" -ne 1 ]]; then
            echo "Un fichier ${CADDYFILE} existe déjà et n'a pas été créé par" >&2
            echo "ce script (pas de marqueur '${CADDY_MARKER}' en tête)." >&2
            echo "L'écraser silencieusement risquerait de casser un Caddy" >&2
            echo "déjà en place pour un autre usage." >&2
            echo "Sauvegardez-le, puis relancez avec --force pour confirmer" >&2
            echo "le remplacement." >&2
            exit 1
        fi
        echo "Caddyfile existant non géré par ce script — remplacé (--force)."
    else
        ALREADY_DEPLOYED=1
        echo "Déploiement existant détecté — mode mise à jour."
    fi
fi

# ─────────────────────────── 4. Wizard ──────────────────────────────────────

echo
echo "=== Configuration du bridge Hasan ==="
echo

read -rp "IP ou nom d'hôte public de ce serveur : " PUBLIC_HOST
if [[ -z "${PUBLIC_HOST}" ]]; then
    echo "L'IP/hostname public est requis." >&2
    exit 1
fi

WEBUI_URL=""
WEBUI_PASSWORD=""
WEBUI_DETECTED=0
if curl -sf -o /dev/null --max-time 3 http://127.0.0.1:8787/health 2>/dev/null; then
    WEBUI_DETECTED=1
fi

if [[ "${WEBUI_DETECTED}" -eq 1 ]]; then
    default_answer="Y"
else
    default_answer="N"
fi
read -rp "hermes-webui détecté sur ce serveur ? [${default_answer}/n] : " webui_answer
webui_answer="${webui_answer:-${default_answer}}"

if [[ "${webui_answer}" =~ ^[Yy] ]]; then
    WEBUI_URL="https://${PUBLIC_HOST}"
    # Trois niveaux, dans l'ordre : env déjà exportée > ~/hermes-webui/.env
    # (convention déjà documentée dans hermes-relay.service) > prompt manuel.
    if [[ -n "${HERMES_WEBUI_PASSWORD:-}" ]]; then
        WEBUI_PASSWORD="${HERMES_WEBUI_PASSWORD}"
    elif [[ -f "${USER_HOME}/hermes-webui/.env" ]]; then
        WEBUI_PASSWORD="$(grep '^HERMES_WEBUI_PASSWORD=' "${USER_HOME}/hermes-webui/.env" | head -n1 | cut -d= -f2-)"
    fi
    if [[ -z "${WEBUI_PASSWORD}" ]]; then
        read -rsp "Mot de passe hermes-webui (HERMES_WEBUI_PASSWORD) : " WEBUI_PASSWORD
        echo
    fi
fi

EXPOSE_DASHBOARD=0
read -rp "Exposer le dashboard Hermes interne (port 9119) publiquement sur :8443 ? [y/N] : " dash_answer
if [[ "${dash_answer}" =~ ^[Yy] ]]; then
    EXPOSE_DASHBOARD=1
    check_port_free 8443
fi

read -rp "HERMES_HOME [${USER_HOME}/.hermes] : " HERMES_HOME_INPUT
HERMES_HOME="${HERMES_HOME_INPUT:-${USER_HOME}/.hermes}"

# ─────────────────────────── 5. Secrets + .env ──────────────────────────────

echo
echo "==> Génération des secrets"

if [[ "${ALREADY_DEPLOYED}" -eq 1 ]] && [[ -f "${ENV_FILE}" ]]; then
    # Mise à jour : conserve le token admin déjà émis (les codes de pairing
    # émis avec l'ancien token ne doivent pas devenir invalides à chaque
    # re-run).
    RELAY_ADMIN_TOKEN="$(grep '^RELAY_ADMIN_TOKEN=' "${ENV_FILE}" | head -n1 | cut -d= -f2-)"
fi
if [[ -z "${RELAY_ADMIN_TOKEN:-}" ]]; then
    RELAY_ADMIN_TOKEN="$(openssl rand -hex 32)"
    echo "Nouveau RELAY_ADMIN_TOKEN généré."
else
    echo "RELAY_ADMIN_TOKEN existant conservé."
fi

RELAY_PUBLIC_URL="https://${PUBLIC_HOST}:8767"

cat > "${ENV_FILE}" <<EOF
RELAY_HOST=0.0.0.0
RELAY_PORT=8767
RELAY_ADMIN_TOKEN=${RELAY_ADMIN_TOKEN}
RELAY_PUBLIC_URL=${RELAY_PUBLIC_URL}
RELAY_SESSIONS_PATH=/data/hasan-relay-sessions.json
HERMES_API_BASE_URL=http://127.0.0.1:8443
WEBUI_URL=${WEBUI_URL}
WEBUI_PASSWORD=${WEBUI_PASSWORD}
EOF
chmod 600 "${ENV_FILE}"

# ─────────────────────────── 6. Caddyfile ───────────────────────────────────

echo "==> Rendu de server/Caddyfile"
sed "s/{{PUBLIC_HOST}}/${PUBLIC_HOST}/g" "${SCRIPT_DIR}/Caddyfile.template" > "${CADDYFILE}"
if [[ "${EXPOSE_DASHBOARD}" -eq 1 ]]; then
    echo >> "${CADDYFILE}"
    sed "s/{{PUBLIC_HOST}}/${PUBLIC_HOST}/g" "${SCRIPT_DIR}/Caddyfile.dashboard-block.template" >> "${CADDYFILE}"
fi

# ─────────────────────────── 7. docker compose up ───────────────────────────

echo "==> docker compose up -d --build"
( cd "${SCRIPT_DIR}" && docker compose -p "${COMPOSE_PROJECT}" pull caddy )
( cd "${SCRIPT_DIR}" && docker compose -p "${COMPOSE_PROJECT}" up -d --build )

echo "==> Vérification post-démarrage"
sleep 2
if ! curl -sf --max-time 5 http://127.0.0.1:8767/health >/dev/null; then
    echo "AVERTISSEMENT : le relay ne répond pas encore sur 127.0.0.1:8767/health." >&2
    echo "Voir : docker compose -p ${COMPOSE_PROJECT} logs relay" >&2
fi
if ! curl -sfk --max-time 5 "https://${PUBLIC_HOST}/" >/dev/null; then
    echo "AVERTISSEMENT : https://${PUBLIC_HOST}/ (Caddy) ne répond pas encore." >&2
    echo "Voir : docker compose -p ${COMPOSE_PROJECT} logs caddy" >&2
fi

# ─────────────────────────── 8. Plugin hasan_delivery ───────────────────────

echo "==> Installation du plugin hasan_delivery (hors Docker, venv Hermes)"
# Exécuté en tant qu'utilisateur invocateur (pas root) : ce script écrit
# directement dans ${HERMES_HOME}, le home de cet utilisateur — l'exécuter
# en root créerait des fichiers root-owned dans un home utilisateur normal.
if [[ -n "${SUDO_USER:-}" ]]; then
    sudo -u "${SUDO_USER}" env HERMES_HOME="${HERMES_HOME}" \
        "${REPO_ROOT}/plugin/hasan_delivery/install-plugin.sh"
else
    HERMES_HOME="${HERMES_HOME}" "${REPO_ROOT}/plugin/hasan_delivery/install-plugin.sh"
fi

# ─────────────────────────── 9. Code de pairing ─────────────────────────────

echo "==> Génération d'un code de pairing"
sleep 1
PAIRING_JSON="$(curl -sf --max-time 5 -X POST "http://127.0.0.1:8767/pairing/create" \
    -H "Authorization: Bearer ${RELAY_ADMIN_TOKEN}" || true)"

# ─────────────────────────── 10. Résumé ─────────────────────────────────────

cat <<EOF

Déploiement terminé.

  Relay (Android)     : https://${PUBLIC_HOST}:8767
$( [[ -n "${WEBUI_URL}" ]] && echo "  Chat (hermes-webui) : ${WEBUI_URL}" )
$( [[ "${EXPOSE_DASHBOARD}" -eq 1 ]] && echo "  Dashboard Hermes    : https://${PUBLIC_HOST}:8443" )

  RELAY_ADMIN_TOKEN (à conserver, affiché une seule fois) :
    ${RELAY_ADMIN_TOKEN}

Rappel — à faire manuellement si pas déjà en place :
  - Ouvrir dans le firewall du provider (GCP/AWS/...) ET sur la machine
    UNIQUEMENT les ports 443$( [[ "${EXPOSE_DASHBOARD}" -eq 1 ]] && echo " et 8443" ) — jamais 8767/8787/9119
    directement au public.

Code de pairing (scanner ce JSON en QR depuis l'app, ou le régénérer via
POST /pairing/create) :
  ${PAIRING_JSON:-échec de génération — relancer manuellement avec le token ci-dessus}

Diagnostic :
  docker compose -p ${COMPOSE_PROJECT} -f "${SCRIPT_DIR}/docker-compose.yml" ps
  docker compose -p ${COMPOSE_PROJECT} -f "${SCRIPT_DIR}/docker-compose.yml" logs -f relay
  journalctl --user -u hermes-gateway.service -f | grep -i hasan_delivery
EOF
