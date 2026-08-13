#!/usr/bin/env bash
# Détection de l'infrastructure existante, sourcé par install.sh.
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

# Motifs d'image reconnus comme "un Hermès" :
#   - nousresearch/hermes-agent : l'image officielle (Docker Hub + GHCR) ;
#   - hasan-hermes : notre image dérivée (server/hermes-image/), qui embarque
#     hermes-webui. Sans ce second motif, un déploiement homelab utilisant
#     l'image dérivée serait vu comme "pas de Hermès" (bug rencontré au test).
HERMES_IMAGE_PATTERNS=("nousresearch/hermes-agent" "hasan-hermes")

# Renvoie le nom du conteneur Hermes en cours d'exécution, ou une chaîne vide.
# Cherche par image plutôt que par nom de conteneur : le nom est libre
# (chacun nomme son service comme il veut dans son compose), l'image ne l'est
# pas. --filter ancestor exige le nom exact du tag, donc on liste les
# conteneurs et on filtre l'image nous-mêmes pour matcher un préfixe
# (l'utilisateur peut tagger hasan-hermes:latest, :local, :v2…).
detect_hermes_container() {
    command -v docker >/dev/null 2>&1 || return 0
    local line name image
    while IFS='|' read -r name image; do
        [[ -z "${name}" ]] && continue
        for pat in "${HERMES_IMAGE_PATTERNS[@]}"; do
            if [[ "${image}" == "${pat}"* || "${image}" == *"/${pat}"* ]]; then
                echo "${name}"
                return 0
            fi
        done
    done < <(docker ps --format '{{.Names}}|{{.Image}}' 2>/dev/null)
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

# webui est-il EMBARQUÉ dans l'image du conteneur Hermes ?
#
# Distinct de "répond-il ?" : au démarrage, webui met ~15-20 s à répondre
# (s6 le lance après main-hermes). Sans cette distinction, un webui simplement
# lent serait pris pour une image nue et l'installation refusée à tort. On
# regarde donc si le CODE est présent (/opt/hermes/hermes-webui), ce qui
# tranche entre l'image dérivée (présent) et l'image officielle nue (absent),
# indépendamment de l'état de démarrage.
webui_embedded_in_container() {
    local container="$1"
    docker exec "${container}" sh -c 'test -d /opt/hermes/hermes-webui' >/dev/null 2>&1
}

# Un dépôt hermes-webui est-il déjà cloné, même s'il ne tourne pas ?
webui_is_installed() {
    local user_home="$1"
    [[ -d "${user_home}/hermes-webui/.git" ]]
}

# Le port 443 est-il tenu par quelque chose QUI N'EST PAS À NOUS ?
#
# C'est la vraie question, plus large que "un Caddy tourne-t-il ?" : n'importe
# quel service tiers (un autre reverse-proxy, un conteneur du homelab) peut
# déjà occuper 443. S'il est occupé par un tiers, on ne doit PAS lancer notre
# Caddy — il entrerait en conflit et crash-looperait (bug rencontré au test :
# hasan-bridge-caddy en Restarting derrière un caddy-perso). On réutilise
# l'existant à la place.
#
# "À nous" = un conteneur du projet compose hasan-bridge. Tout le reste est
# tiers. On renvoie le nom du détenteur tiers, ou vide si 443 est libre ou
# tenu par nous.
port_443_foreign_holder() {
    command -v docker >/dev/null 2>&1 || { _port_holder_native 443; return; }
    # Conteneurs publiant 443, hors projet hasan-bridge.
    local holder
    holder="$(docker ps --format '{{.Names}}\t{{.Ports}}' 2>/dev/null \
        | awk -F'\t' '$2 ~ /:443->/ {print $1}' \
        | grep -v '^hasan-bridge-' | head -n1)"
    if [[ -n "${holder}" ]]; then
        echo "conteneur ${holder}"
        return
    fi
    # Sinon, un process natif (Caddy systemd, autre).
    _port_holder_native 443
}

# Détenteur natif d'un port (hors Docker), ou vide.
_port_holder_native() {
    local port="$1" h
    command -v ss >/dev/null 2>&1 || return 0
    h="$(ss -ltnp "sport = :${port}" 2>/dev/null | awk 'NR==2 {print $NF}')"
    # `[[ -n ... ]] && echo` en fin de fonction renverrait le code du test (1
    # si vide), fatal sous `set -e` chez l'appelant. On sépare pour toujours
    # sortir 0.
    if [[ -n "${h}" ]]; then
        echo "process ${h}"
    fi
    return 0
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
