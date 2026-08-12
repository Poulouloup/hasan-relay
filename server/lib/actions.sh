#!/usr/bin/env bash
# Actions d'installation, sourcées par install.sh. Séparées de detect.sh (qui,
# lui, ne fait que lire l'état) : ici on écrit, on installe, on redémarre.
#
# Chaque fonction est volontairement étroite pour rester lisible et testable.
# Elles supposent detect.sh déjà sourcé (elles s'appuient sur ses fonctions).

# ─────────────────────── webui absent en conteneur → abandon ───────────────

# Appelé quand Hermès est conteneurisé mais que webui n'est PAS dans l'image.
# webui ne peut pas être ajouté après coup (il importe le code de Hermès) :
# on refuse et on guide vers le rebuild, plutôt que de livrer un déploiement
# sans écran Chat.
webui_container_missing_abort() {
    cat >&2 <<EOF
Hermès est conteneurisé mais hermes-webui n'est pas dans l'image.

hermes-webui doit tourner DANS le conteneur Hermès — il importe le code de
Hermès et ne peut pas être ajouté à part. L'image utilisée est probablement
l'image officielle nue, sans webui.

À faire : reconstruire l'image dérivée qui embarque webui, puis pointer
votre compose Hermès dessus :

  docker build -t hasan-hermes ${REPO_ROOT}/server/hermes-image
  # dans votre docker-compose.yml Hermès :
  #   image: hasan-hermes    (au lieu de nousresearch/hermes-agent)
  docker compose up -d

Puis relancer ce script. Voir server/hermes-image/README.md.
EOF
    exit 1
}

# ─────────────────────── hermes-webui (mode natif) ─────────────────────────

# Clone hermes-webui depuis l'amont et installe ses deux dépendances dures
# dans le venv de Hermès. Réservé au cas natif : en conteneurisé, webui vit
# dans l'image dérivée (server/hermes-image/), pas ici.
#
# Suit master (choix assumé, #12). webui n'est PAS un service autonome — il
# importe le code de Hermès — donc il tourne dans le venv de Hermès, où ces
# modules vivent, et non dans un venv isolé.
install_webui_native() {
    local hermes_home="$1"
    local webui_dir="${hermes_home%/}/../hermes-webui"
    # ${hermes_home}/.. = le home de l'utilisateur ; hermes-webui s'installe
    # à côté de ~/.hermes, comme sur le déploiement existant.
    webui_dir="$(cd "$(dirname "${webui_dir}")" && pwd)/hermes-webui"
    local venv_python="${hermes_home}/hermes-agent/venv/bin/python"

    if [[ -d "${webui_dir}/.git" ]]; then
        echo "  hermes-webui déjà cloné dans ${webui_dir} — mise à jour (git pull)."
        as_user git -C "${webui_dir}" pull --ff-only 2>&1 | sed 's/^/    /' || \
            echo "    (git pull a échoué — dépôt local modifié ? on continue.)"
    else
        echo "  Clone de hermes-webui (branche master) dans ${webui_dir}."
        as_user git clone --depth 1 https://github.com/nesquena/hermes-webui.git "${webui_dir}"
    fi

    echo "  Installation des dépendances webui dans le venv Hermès."
    # python -m pip : le venv natif de Hermès a bien pip (contrairement à
    # l'image conteneur gérée par uv).
    as_user "${venv_python}" -m pip install --quiet \
        -r "${webui_dir}/requirements.txt"

    echo "  Démarrage de hermes-webui (ctl.sh start)."
    ( cd "${webui_dir}" && as_user env HERMES_WEBUI_HOST=127.0.0.1 ./ctl.sh start ) || \
        echo "    (démarrage échoué — lancer manuellement : cd ${webui_dir} && ./ctl.sh start)"
}

# ─────────────────────── .env du relay ─────────────────────────────────────

write_env_file() {
    local env_file="$1" admin_token="$2" public_host="$3"
    cat > "${env_file}" <<EOF
# Généré par server/install.sh — ne pas éditer à la main.
RELAY_ADMIN_TOKEN=${admin_token}
RELAY_PUBLIC_URL=https://${public_host}:8767
HERMES_API_BASE_URL=http://127.0.0.1:8443
# Sessions de pairing persistées dans le volume du conteneur relay.
RELAY_SESSIONS_PATH=/data/sessions.json
EOF
    chmod 600 "${env_file}"
    echo "  ${env_file} écrit (permissions 600)."
}

# ─────────────────────── Caddyfile ─────────────────────────────────────────

render_caddyfile() {
    local template="$1" caddyfile="$2" public_host="$3" force="$4" marker="$5"

    if [[ -f "${caddyfile}" ]] && ! head -n1 "${caddyfile}" | grep -qF "${marker}"; then
        if [[ "${force}" -ne 1 ]]; then
            echo "Un ${caddyfile} existe sans notre marqueur — refus d'écraser." >&2
            echo "Le sauvegarder puis relancer avec --force." >&2
            exit 1
        fi
        echo "  Caddyfile tiers remplacé (--force)."
    fi

    sed "s/{{PUBLIC_HOST}}/${public_host}/g" "${template}" > "${caddyfile}"
    echo "  ${caddyfile} rendu pour ${public_host}."
}

# ─────────────────────── Dépôt du plugin ───────────────────────────────────

# Dépose le plugin et ses skills là où Hermès les scanne. Le CHEMIN dépend du
# mode, pour une raison découverte au test :
#
#   - Natif : ~/.hermes appartient à l'utilisateur, on copie directement.
#   - Conteneurisé : le volume /opt/data appartient à l'uid 10000 du conteneur
#     (mode 0700), l'utilisateur hôte n'y a AUCUN accès. On ne peut donc pas
#     écrire depuis l'hôte — on passe par `docker cp`, qui écrit en tant que
#     propriétaire du conteneur, avec les bons droits d'emblée.
install_plugin() {
    local plugin_src="$1" dest_dir="$2" mode="$3" hermes_home="$4"

    if [[ "${mode}" == "container" ]]; then
        install_plugin_container "${plugin_src}" "${HERMES_CONTAINER}"
        return
    fi
    install_plugin_native "${plugin_src}" "${dest_dir}" "${hermes_home}"
}

install_plugin_native() {
    local plugin_src="$1" dest_dir="$2" hermes_home="$3"
    local plugins_dir="${dest_dir%/}/plugins/hasan_delivery"
    local skills_dir="${dest_dir%/}/skills/general"

    as_user mkdir -p "${plugins_dir}" "${skills_dir}"
    echo "  Copie du plugin vers ${plugins_dir}"
    as_user rm -rf "${plugins_dir}"
    as_user mkdir -p "${plugins_dir}"
    as_user cp "${plugin_src}"/*.py "${plugin_src}"/*.yaml \
        "${plugin_src}/requirements.txt" "${plugins_dir}/"
    copy_plugin_skills_native "${plugin_src}" "${skills_dir}"

    echo "  Installation de httpx dans le venv Hermès"
    as_user "${hermes_home}/hermes-agent/venv/bin/python" -m pip install --quiet httpx || \
        echo "    (échec pip httpx — vérifier le venv)"
}

copy_plugin_skills_native() {
    local plugin_src="$1" skills_dir="$2"
    [[ -d "${plugin_src}/skills" ]] || return 0
    for skill_dir in "${plugin_src}/skills"/*/; do
        [[ -d "${skill_dir}" ]] || continue
        local name; name="$(basename "${skill_dir}")"
        echo "  Copie du skill ${name}"
        as_user rm -rf "${skills_dir:?}/${name}"
        as_user cp -r "${skill_dir}" "${skills_dir}/${name}"
    done
}

# Dépose le plugin DANS le conteneur via docker cp — la seule façon d'écrire
# dans /opt/data quand il appartient à l'uid 10000. On copie d'abord dans un
# staging propre (sans .git/__pycache__/scripts), puis on cp ce staging.
install_plugin_container() {
    local plugin_src="$1" container="$2"
    local staging; staging="$(mktemp -d)"

    cp "${plugin_src}"/*.py "${plugin_src}"/*.yaml \
        "${plugin_src}/requirements.txt" "${staging}/"

    echo "  docker cp du plugin vers ${container}:/opt/data/plugins/hasan_delivery"
    docker exec "${container}" rm -rf /opt/data/plugins/hasan_delivery 2>/dev/null || true
    docker exec "${container}" mkdir -p /opt/data/plugins /opt/data/skills/general
    docker cp "${staging}/." "${container}:/opt/data/plugins/hasan_delivery"

    if [[ -d "${plugin_src}/skills" ]]; then
        for skill_dir in "${plugin_src}/skills"/*/; do
            [[ -d "${skill_dir}" ]] || continue
            local name; name="$(basename "${skill_dir}")"
            echo "  docker cp du skill ${name}"
            docker exec "${container}" rm -rf "/opt/data/skills/general/${name}" 2>/dev/null || true
            docker cp "${skill_dir%/}" "${container}:/opt/data/skills/general/${name}"
        done
    fi

    # Les fichiers copiés par docker cp appartiennent à root dans le conteneur.
    # Hermès tourne en uid 10000 : on rend le tout lisible pour lui, et on
    # aligne le propriétaire sur hermes pour éviter toute surprise d'écriture.
    docker exec "${container}" sh -c \
        'chown -R hermes:hermes /opt/data/plugins/hasan_delivery /opt/data/skills/general 2>/dev/null; \
         chmod -R a+rX /opt/data/plugins/hasan_delivery /opt/data/skills/general' || true

    echo "  httpx déjà présent dans l'image conteneur — rien à installer."
    rm -rf "${staging}"
}

# ─────────────────────── Redémarrage de Hermès ─────────────────────────────

# Hermès ne scanne plugins/ qu'au démarrage : il faut le redémarrer pour qu'il
# découvre le plugin fraîchement déposé.
restart_hermes() {
    local mode="$1" container="$2"
    case "${mode}" in
        container)
            echo "  docker restart ${container}"
            docker restart "${container}" >/dev/null 2>&1 || \
                echo "    (échec — redémarrer manuellement : docker restart ${container})"
            ;;
        native)
            # Le gateway Hermès tourne en service utilisateur (systemd --user).
            echo "  Redémarrage du gateway Hermès (systemctl --user)"
            as_user systemctl --user restart hermes-gateway.service 2>/dev/null || \
                echo "    (service hermes-gateway introuvable — redémarrer Hermès à la main)"
            ;;
    esac
}

# ─────────────────────── Résumé final ──────────────────────────────────────

print_summary() {
    cat <<EOF

═══════════════════════════════════════════════════════════════════
Déploiement terminé — mode Hermès : ${HERMES_MODE}

  Relay (Android)     : https://${PUBLIC_HOST}:8767
$( [[ -n "${WEBUI_URL}" ]] && echo "  Chat (hermes-webui) : ${WEBUI_URL}" )

  RELAY_ADMIN_TOKEN (à conserver, affiché une seule fois) :
    ${RELAY_ADMIN_TOKEN}
EOF

    if [[ "${REUSE_EXISTING_CADDY}" -eq 1 ]]; then
        cat <<EOF

  Un Caddy existant a été réutilisé. Ajoutez ces lignes à votre
  Caddyfile pour router vers le bridge, puis rechargez Caddy :

    https://${PUBLIC_HOST} {
        tls internal
        reverse_proxy 127.0.0.1:8787 {
            flush_interval -1
        }
    }
EOF
    fi

    cat <<EOF

  Code de pairing (à scanner en QR depuis l'app) :
    ${PAIRING_JSON:-échec de génération — relancer POST /pairing/create}

  Rappel firewall : n'ouvrir que 443 au public, jamais 8767/8787/9119.

  Diagnostic :
    docker compose -p ${COMPOSE_PROJECT} ps
    docker compose -p ${COMPOSE_PROJECT} logs -f relay
═══════════════════════════════════════════════════════════════════
EOF
}
