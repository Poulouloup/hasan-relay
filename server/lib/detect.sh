#!/usr/bin/env bash
# Détection de l'infrastructure existante, sourcé par install-bridge.sh.
#
# Isolé dans son propre fichier pour être testable seul (voir
# server/lib/test-detect.sh) : ces fonctions n'écrivent rien, ne demandent
# rien à l'utilisateur et n'ont aucun effet de bord — elles répondent à des
# questions sur la machine.
#
# Toutes les affirmations codées ici ont été vérifiées en conditions réelles
# sur nousresearch/hermes-agent:latest et sur le VPS de dev (voir issue #12).

# ─────────────────────── Suis-je dans un conteneur ? ────────────────────────

# /.dockerenv est le seul test fiable : présent dans un conteneur Docker,
# absent sur l'hôte (vérifié dans les deux sens). La méthode alternative
# souvent citée — chercher "docker" dans /proc/1/cgroup — a été testée et
# NE FONCTIONNE PAS sur Docker moderne (cgroup v2) : elle renvoie "absent"
# depuis l'intérieur d'un conteneur, donc un faux négatif silencieux.
running_inside_container() {
    [[ -f /.dockerenv ]]
}

# Garde-fou : ce script installe Docker et lance des conteneurs. Exécuté
# DEPUIS un conteneur (typiquement si on demande à Hermes conteneurisé
# d'installer Hasan lui-même), il tenterait un Docker-dans-Docker et
# n'écrirait que dans des couches éphémères, perdues au prochain
# `docker compose up`.
abort_if_inside_container() {
    if running_inside_container; then
        cat >&2 <<'EOF'
Ce script s'exécute à l'intérieur d'un conteneur.

Il doit tourner sur la MACHINE HÔTE : il installe Docker au besoin et lance
des conteneurs. Depuis l'intérieur, il installerait Docker dans Docker, et
tout ce qu'il écrirait hors des volumes disparaîtrait au prochain
redémarrage du conteneur.

À faire : ouvrir un shell sur l'hôte et relancer ce script là-bas.
EOF
        exit 1
    fi
}

# ─────────────────────── Hermes : natif ou conteneurisé ? ───────────────────

# Nom d'image officielle publiée par Nous Research (Docker Hub + GHCR).
HERMES_IMAGE_MATCH="nousresearch/hermes-agent"

# Renvoie le nom du conteneur Hermes en cours d'exécution, ou une chaîne vide.
# Cherche par image plutôt que par nom de conteneur : le nom est libre
# (chacun nomme son service comme il veut dans son compose), l'image ne l'est
# pas.
detect_hermes_container() {
    command -v docker >/dev/null 2>&1 || return 0
    docker ps --filter "ancestor=${HERMES_IMAGE_MATCH}" --format '{{.Names}}' 2>/dev/null | head -n1
}

# Mode d'installation de Hermes : "container", "native" ou "none".
#
# L'ordre compte : on teste le conteneur AVANT le venv, car une machine peut
# porter les deux (une ancienne installation native laissée en place à côté
# d'un conteneur devenu la vraie instance). Le conteneur en cours d'exécution
# est la source de vérité — c'est lui qui tourne.
detect_hermes_mode() {
    local hermes_home="$1"

    if [[ -n "$(detect_hermes_container)" ]]; then
        echo "container"
        return 0
    fi
    if [[ -x "${hermes_home}/hermes-agent/venv/bin/python" ]]; then
        echo "native"
        return 0
    fi
    echo "none"
}

# ─────────────────────── httpx : la dépendance du plugin ────────────────────

# Vérifié sur l'image officielle : httpx 0.28.1 est déjà présent dans le venv
# /opt/hermes/.venv (Python 3.13.5). Le cas conteneurisé n'a donc RIEN à
# installer — d'où l'absence d'image dérivée dans ce déploiement.
#
# On vérifie quand même plutôt que de le supposer : une future image amont
# pourrait cesser de l'embarquer, et il vaut mieux un message clair qu'un
# plugin qui refuse de charger sans explication.
hermes_container_has_httpx() {
    local container="$1"
    docker exec "${container}" python -c 'import httpx' >/dev/null 2>&1
}

hermes_native_has_httpx() {
    local hermes_home="$1"
    "${hermes_home}/hermes-agent/venv/bin/python" -c 'import httpx' >/dev/null 2>&1
}

# ─────────────────────── Dépendances déjà en place ──────────────────────────

# hermes-webui répond-il ? C'est lui qui alimente l'écran Chat de l'app.
# Projet tiers (github.com/nesquena/hermes-webui), installé séparément de
# Hermes — vérifié : il ne fait pas partie de l'image officielle.
webui_is_running() {
    curl -sf -o /dev/null --max-time 3 http://127.0.0.1:8787/health 2>/dev/null
}

# Un dépôt hermes-webui est-il déjà cloné, même s'il ne tourne pas ?
webui_is_installed() {
    local user_home="$1"
    [[ -d "${user_home}/hermes-webui/.git" ]]
}

# Un Caddy tourne-t-il déjà, et est-ce le nôtre ?
#
# Distinguer les deux est essentiel : écraser le Caddyfile d'un tiers casse
# son service, et faire tourner deux Caddy sur le même port produit
# exactement le piège du 2026-07-28 (deux Caddyfiles divergents, 401
# trompeurs, ban fail2ban accidentel).
caddy_container_running() {
    command -v docker >/dev/null 2>&1 || return 1
    [[ -n "$(docker ps --filter "ancestor=caddy" --format '{{.Names}}' 2>/dev/null | head -n1)" ]]
}

# Un Caddy NATIF (systemd) tourne-t-il ?
#
# Ne pas chercher que le conteneur : sur le VPS de dev, c'est un Caddy natif
# qui tient le port 443 (vérifié — `caddy` en systemd, pas docker-proxy).
# Ne détecter que la variante conteneur laisserait croire le port libre pour
# nous, et reproduirait le piège du 2026-07-28.
caddy_native_running() {
    systemctl is-active --quiet caddy 2>/dev/null
}

# Description de ce qui occupe un port, ou chaîne vide s'il est libre.
# Sert à nommer le coupable dans les messages plutôt que de dire
# "port occupé" sans dire par qui.
port_holder() {
    local port="$1"
    command -v ss >/dev/null 2>&1 || return 0
    ss -ltnp "sport = :${port}" 2>/dev/null | awk 'NR==2 {print $NF}'
}

caddyfile_is_ours() {
    local caddyfile="$1" marker="$2"
    [[ -f "${caddyfile}" ]] && head -n1 "${caddyfile}" | grep -qF "${marker}"
}

# ─────────────────────── Chemin du volume Hermes ────────────────────────────

# Où déposer le plugin, selon le mode.
#
# Cas conteneurisé : l'image officielle définit HERMES_HOME=/opt/data, monté
# depuis l'hôte (vérifié). Le dossier plugins/ que Hermes scanne au démarrage
# vit donc dans le volume — y écrire depuis l'hôte revient EXACTEMENT au cas
# natif. C'est ce qui permet un seul chemin de code pour les deux modes.
#
# On résout le point de montage réel plutôt que de supposer ~/.hermes :
# l'utilisateur est libre de monter n'importe quel dossier hôte sur
# /opt/data.
detect_hermes_volume_host_path() {
    local container="$1"
    docker inspect "${container}" \
        --format '{{range .Mounts}}{{if eq .Destination "/opt/data"}}{{.Source}}{{end}}{{end}}' \
        2>/dev/null
}
