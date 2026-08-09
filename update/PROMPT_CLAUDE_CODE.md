# Rework UI Hasan — implémentation Jetpack Compose

## Contexte

On a fait un rework complet de l'UI de l'app Hasan (Kotlin / Jetpack Compose,
repo `hasan-mobile-relay`, branche `webui migration`) sous forme de maquette
HTML interactive. Le fichier `hasan-rework-mockup.html` (joint) est la
**référence visuelle et comportementale à suivre** : ouvre-le dans un
navigateur, clique dans l'écran de téléphone pour naviguer d'un écran à
l'autre exactement comme dans l'app réelle (menu burger → drawer, boutons
internes, etc.). Deux fichiers logo sont aussi joints (voir section Assets).

**Important : ce mockup est une maquette HTML/CSS/JS statique avec des
données bidon (tâches, sessions, skills, contenu des fichiers mémoire,
statistiques…). Il ne représente QUE l'UI et les interactions. Le vrai
contenu doit venir des vraies sources de données de l'app (ViewModels,
repositories, hermes-webui, relay, etc.) — ne recopie jamais les données du
mockup en dur.**

Avant de toucher au code : explore l'architecture existante (composables,
ViewModels, navigation, thème actuel, gestion des insets) et adapte ce
rework à ces patterns plutôt que d'imposer une structure différente. Si un
écran du mockup n'a pas d'équivalent clair côté backend/state actuel,
demande ou fais l'hypothèse la plus raisonnable et signale-la dans ton
résumé de fin de tâche.

---

## Ce qui a changé pendant la session de design — checklist

### 1. Design tokens
- Fond noir réchauffé (`#0A0A0D` / `#0D0D10`) plutôt que noir pur — moins
  d'éblouissement en usage nocturne.
- Gris de texte remontés pour un contraste ≥ 4.5:1 sur fond sombre (WCAG AA).
  Trois paliers : texte primaire quasi blanc, secondaire, tertiaire (discret
  mais jamais illisible).
- Rouge de marque à deux niveaux : un rouge profond (`#CC2936`, proche de
  l'historique) pour les aplats/boutons pleins, un rouge plus clair
  (`#FF4D5E`) réservé au texte/icônes actifs sur fond sombre (le rouge
  profond seul ne passe pas le contraste AA en texte fin).
- **Forme signature : coin coupé ("cut-corner")**, pas des coins arrondis.
  Cartes, boutons, chips, tabs, badges avec bordure utilisent une forme à
  deux coins opposés coupés en diagonale (voir Composants). Les éléments
  qui restent ronds : avatars, points de statut, radios (cercles = un autre
  langage, volontairement distinct).
- Typographies : **Chakra Petch** pour les titres/labels/boutons (UI
  "système", capitales espacées sur les boutons), **IBM Plex Mono** pour
  tout ce qui est technique (noms de tools, cron, hash, tokens, timestamps),
  **Inter** pour le contenu conversationnel (bulles de chat, contenu
  markdown) — c'est ce dernier qui doit rester le plus lisible/neutre.
- Cibles tactiles ≥ 44dp partout (toggles, icônes, lignes de liste), même
  quand le visuel est plus compact.

### 2. Composants
- **Switch** : redevenu un interrupteur "mécanique" — un rail rectangulaire
  (pas une pilule), un bloc qui glisse, état actif en rouge avec un léger
  glow. Toujours accompagné d'un label texte, jamais couleur seule.
- **Boutons** : forme à coin coupé, texte en Chakra Petch capitales
  espacées. Variantes : plein (fond rouge profond), contour ("ghost", fond
  transparent/fond de l'écran + bordure), contour destructif (bordure rouge
  clair). *Note technique* : en CSS il a fallu une astuce (double calque
  fond+remplissage) parce qu'un `border` CSS ne suit pas un `clip-path` sur
  l'arête coupée. **En Compose ce problème n'existe pas** :
  `Modifier.clip(cutCornerShape).border(1.dp, color, cutCornerShape)`
  fonctionne nativement et suit la forme correctement. Implémente le
  cut-corner comme une `Shape` Compose réutilisable (via `GenericShape` ou
  un `Path` custom), pas via l'astuce CSS.
- **Cartes** : même forme à coin coupé, fond légèrement plus clair que
  l'écran, bordure fine.
- **Badges de statut** : texte + couleur (jamais couleur seule) —
  ex. "Connecté" en vert avec un point, "Inactif" en gris neutre.
- **Chips de jour** (nouveau, éditeur de tâche) : 7 puces coin-coupé (L M M
  J V S D), sélection simple (un seul jour actif à la fois pour cette
  version — voir section Éditeur de tâche).
- **Tabs de fréquence** (nouveau, éditeur de tâche) : 4 onglets coin-coupé
  (Une fois / Toutes les X / Quotidien / Hebdo — "Hebdomadaire" est trop
  long pour tenir sur un seul mot dans l'espace disponible, d'où
  l'abréviation).

### 3. Écran Chat
- Le logo de l'app apparaît maintenant dans le header, à côté du titre
  "Hasan".
- **Bug corrigé dans le mockup, à bien reproduire côté Compose** : le fil de
  messages doit défiler indépendamment du header et du composer (qui
  restent fixes à l'écran). Dans le mockup HTML j'ai dû utiliser
  `flex-direction: column-reverse` parce que `justify-content: flex-end` +
  `overflow-y: auto` est buggé en CSS. **En Compose, utilise simplement une
  `LazyColumn` normale** (header et composer en dehors du `LazyColumn`,
  dans un `Column`/`Scaffold`), avec :
  - soit `reverseLayout = true` sur la `LazyColumn` (liste de messages
    passée du plus récent au plus ancien),
  - soit une `LazyColumn` classique (ordre chronologique) + un
    `LazyListState` avec scroll automatique vers le dernier item à la
    composition initiale et à chaque nouveau message
    (`scope.launch { listState.animateScrollToItem(messages.lastIndex) }`).
  Choisis l'option la plus cohérente avec l'existant. Le comportement
  attendu : à l'ouverture on voit les derniers messages, on peut remonter
  pour voir l'historique, header et composer ne bougent jamais.
- Le composer (sélecteur de modèle, bouton pièce jointe, champ de saisie,
  bouton micro) n'a **pas** de fond plein — transparent, seuls les
  éléments à l'intérieur (chip modèle, champ, boutons) ont leur propre fond.
- **Sélecteur de modèle** : retire toute icône à gauche du nom du modèle.
  Le nom du modèle est suivi (à droite) d'une petite icône "chevron haut
  au-dessus de chevron bas" (indicateur de sélecteur/dropdown), pas
  l'ancienne icône en forme de roue/soleil.
- Bulles de chat : coins nets/légèrement arrondis mais **sans** coin coupé
  agressif (le coin coupé est réservé au chrome système, pas au texte
  conversationnel, pour rester lisible sur du texte long).

### 4. Barre système / punch-hole caméra
- Ne jamais faire dessiner le contenu de l'app sous l'encoche caméra
  (punch-hole) : utiliser les vraies APIs — `WindowInsets.displayCutout` /
  `safeDrawing` en Compose (`Modifier.windowInsetsPadding`,
  `Scaffold` avec `contentWindowInsets`) — plutôt qu'une hauteur en dur.
- **La zone au-dessus du header (celle qui dégage l'encoche) doit avoir
  exactement la même couleur de fond que le header**, sur TOUS les écrans
  (pas seulement le Chat) — un seul panneau visuel continu du haut de
  l'écran jusqu'au bas du header, jamais de dégradé ou de couleur
  différente qui crée une coupure visible.

### 5. Écran Tâches / Éditeur de tâche
- Le champ de planification en cron brut n'est plus le mode par défaut.
  Nouveau sélecteur léger, par défaut visible :
  - 4 onglets de fréquence : **Une fois**, **Toutes les X**, **Quotidien**,
    **Hebdo**.
  - Selon l'onglet : un sélecteur date+heure natif (Une fois), un champ
    nombre + unité minutes/heures (Toutes les X), un sélecteur d'heure
    (Quotidien), un sélecteur de jour de semaine (7 puces) + heure (Hebdo).
  - Sous ces contrôles, un disclosure replié **"Expression cron
    (avancé)"** qui révèle le champ texte cron brut pour les utilisateurs
    avancés — génère automatiquement l'expression cron réelle depuis les
    contrôles ci-dessus (implémente la conversion contrôles → cron et,
    idéalement, cron existant → contrôles pré-remplis pour l'édition d'une
    tâche déjà planifiée en mode avancé).
  - Actuellement le mockup ne permet qu'un seul jour sélectionné pour
    l'hebdomadaire (pas de multi-jour). Si le besoin réel est du
    multi-jour (cron supporte les listes de jours), adapte le composant en
    sélection multiple — c'est un choix produit à trancher, pas une
    contrainte technique.

### 6. Drawer (menu de navigation)
- Restructuré en 3 zones : le bloc de navigation principale (Chat, Tâches,
  Kanban, Mémoire, Tools, Paramètres) est **fixe**, ne défile jamais ; seule
  la liste des sessions actives défile, indépendamment, dans son propre
  conteneur scrollable.
- Chaque session affiche son nom et son **âge relatif** (`-2 min`, `-1 h`,
  `-3 j`…) en fin de ligne, triée par activité récente. La session active
  est repérable par un point ET une couleur de texte différente (jamais la
  couleur seule).
- **Appui long** (ou clic droit sur desktop) sur une session ouvre un menu
  contextuel Renommer / Supprimer — relie-le aux vraies actions
  existantes si elles existent déjà (sinon, à créer côté ViewModel).

### 7. Paramètres — section Connexions
- Ligne "Chat" : titre **"Chat"** seul, avec **"hermes-webui"** en
  sous-titre gris juste en dessous (pas de parenthèses, pas de suffixe à
  côté du titre). Badge de statut à droite : point + **"Connecté"**.
- Ligne "Relay" : titre **"Relay"** seul, sous-titre gris **"Actions
  téléphone"** en dessous. À droite : même badge point + **"Connecté"**
  (texte simplifié, avant c'était "Appairé — connecté") + le toggle
  d'activation du relay à côté du badge.

### 8. Paramètres — Profil Hermes
- Le mockup affichait un lien "Pourquoi pas 833 comme dans Mémoire ?"
  expliquant un écart de comptage entre deux compteurs de skills — **à
  retirer**, ce n'est plus voulu dans la version finale.
- La liste de profils ne doit plus se limiter à un seul profil `default` —
  prévoir l'affichage d'une vraie liste de profils Hermes disponibles
  (radio buttons, un seul actif à la fois), à brancher sur la vraie source
  de profils si elle existe déjà côté backend/MCP ; sinon signaler que
  cette fonctionnalité nécessite un endpoint/état qui n'existe pas encore.

### 9. Mémoire — sous-onglet "Memory"
- `MEMORY.md`, `USER.md`, `SOUL.md` ne sont plus de simples lignes
  d'affichage : chaque ligne est cliquable et ouvre un vrai écran de
  lecture (titre = nom du fichier, bouton retour, bouton copier, contenu
  rendu). **Le contenu doit venir de la vraie lecture de ces fichiers**
  (stockage local de l'app ou API hermes-webui selon l'architecture
  existante), pas du faux contenu markdown que j'ai mis dans le mockup pour
  la démo. Un rendu markdown léger suffit (titres, listes, `code`) — pas
  besoin d'un moteur markdown complet si l'app n'en a pas déjà un.

### 10. Kanban
- L'état vide n'est plus un simple message : icône + titre + phrase
  explicative + bouton d'action clair pour créer la première carte.

### 11. Mode mains-libres
- Les boutons "Couper le son" et "Quitter" sont dans cet ordre de gauche à
  droite (Couper le son à gauche, Quitter à droite) — c'était inversé
  avant.

### 12. Icônes
- **Roue crantée** : l'icône Paramètres doit être un vrai engrenage (dents
  visibles), pas un cercle avec des traits qui rayonnent (ancien style qui
  ressemblait à un soleil). Utilise l'icône Material "Settings"/cog
  standard d'Android (`Icons.Rounded.Settings` ou équivalent dans votre
  jeu d'icônes actuel) plutôt que de redessiner un path custom.
- **Sélecteur de modèle** (voir section Chat) : icône "chevron haut
  au-dessus de chevron bas" à droite du nom, pas d'icône à gauche.

### 13. Accessibilité (à vérifier sur l'ensemble des écrans)
- Contraste texte ≥ 4.5:1 sur toutes les couleurs de texte utilisées.
- Cibles tactiles ≥ 44dp (48dp si vous suivez Material par défaut, très
  bien aussi).
- Aucune information portée par la couleur seule (statuts, badges,
  sélections) — toujours doublée d'un texte ou d'une icône.
- Labels de contenu (`contentDescription`) sur toutes les icônes
  interactives, `Modifier.semantics` corrects sur les composants custom
  (switch, radio, day-chip, freq-tab) pour que TalkBack les annonce comme
  de vrais contrôles (rôle, état sélectionné/coché).
- Respect de l'option système "réduire les animations"
  (`LocalAccessibilityManager` / `Settings.Global.ANIMATOR_DURATION_SCALE`
  ou l'équivalent Compose déjà utilisé dans le projet).
- Focus clavier visible si l'app doit supporter clavier physique/D-pad.

---

## Assets fournis

- `hasan-rework-mockup.html` — maquette interactive de référence (ouvrir
  dans un navigateur).
- `hasan_logo_transparent.png` — logo détouré (fond transparent), à
  intégrer tel quel dans le header du Chat, le drawer, la carte "À propos"
  des Paramètres (`Image` composable, pas besoin de traitement
  supplémentaire).
- `hasan_logo_square_1024.png` — même logo recentré sur un canevas carré
  1024×1024 avec marge de sécurité (~20 %), pensé pour générer l'icône de
  lanceur (Image Asset Studio / adaptive icon foreground). Utilise-le comme
  foreground layer, choisis un fond uni (noir ou le rouge de marque) comme
  background layer de l'adaptive icon.

---

## Ce qu'il ne faut PAS reproduire tel quel du mockup

- Les données (tâches, sessions, skills, contenu des fichiers mémoire,
  stats d'usage, certificats, logs, fichiers du workspace) sont **toutes
  fictives** — uniquement pour donner corps à la démo visuelle. Branche
  chaque écran sur ses vraies sources de données existantes.
- Le mockup n'a plus de panneau de navigation latéral "prototype" (on l'a
  retiré exprès) — la seule navigation à implémenter est celle qui existe
  réellement dans l'app (drawer via burger, boutons internes à chaque
  écran). Ne construis aucun outil de navigation additionnel.
- Les astuces CSS pures (cadre+remplissage pour les bordures sur coin
  coupé, `column-reverse`, reset `appearance` des boutons HTML) sont des
  contournements de limitations du navigateur — **aucune ne doit être
  transposée littéralement en Compose**, où ces limitations n'existent pas.
  Implémente le résultat visuel/comportemental voulu avec les outils
  Compose natifs (`Shape`, `border`, `LazyColumn`, etc.).

---

## Plan de travail suggéré

1. Explorer l'arborescence actuelle (composables, thème, navigation,
   ViewModels) et lister où chaque écran concerné vit aujourd'hui.
2. Mettre à jour le thème (couleurs, typographies, `Shape` cut-corner
   réutilisable) dans le fichier de thème central du projet.
3. Construire/adapter les composants réutilisables (bouton, carte, switch,
   badge, day-chip, freq-tab) comme des composables partagés.
4. Intégrer les assets logo (drawable + adaptive icon).
5. Reprendre écran par écran dans cet ordre : Chat → Drawer → Paramètres
   (Connexions + Profil Hermes) → Tâches/Éditeur de tâche → Mémoire →
   Kanban → Mode mains-libres, en vérifiant à chaque étape le lien avec le
   vrai state/ViewModel.
6. Gérer les insets/cutout proprement sur tous les écrans.
7. Repasser sur la checklist accessibilité en fin de tâche.

## Vérifications avant de considérer la tâche terminée

- [ ] Le fil de chat défile correctement (header/composer fixes, historique
      accessible en remontant, ouverture sur les derniers messages).
- [ ] Aucun écran ne dessine de contenu sous l'encoche caméra ; la zone du
      haut a la couleur du header sur tous les écrans.
- [ ] Le drawer : nav fixe, sessions seules scrollables, appui
      long/clic droit fonctionnel.
- [ ] L'éditeur de tâche produit une expression cron valide à partir du
      sélecteur simplifié, et le mode avancé reste accessible.
- [ ] Les fichiers Memory s'ouvrent avec leur vrai contenu.
- [ ] Aucune donnée de démo du mockup n'est restée en dur dans le code.
- [ ] Contrastes, tailles de cibles tactiles et labels d'accessibilité
      vérifiés sur les écrans modifiés.
