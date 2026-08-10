package com.hasan.v1.ui

/**
 * Implémenté par un Fragment d'onglet qui possède une profondeur de
 * navigation interne (détail ouvert, éditeur, sous-répertoire, overlay…).
 *
 * MainActivity consulte ce contrat AVANT de traiter le retour arrière comme
 * un changement d'onglet : sans lui, un retour depuis l'éditeur de tâche ou
 * la gestion des certificats sautait directement au Chat, perdant l'état
 * intermédiaire au lieu de le refermer (issue #3, second lot).
 *
 * L'app n'a pas de back stack fragment à dépiler (onglets en show/hide, voir
 * MainActivity.showFragment) — chaque écran est donc seul à savoir s'il a une
 * couche à refermer, et laquelle.
 */
interface BackHandledScreen {
    /**
     * Referme la couche interne la plus superficielle, s'il y en a une.
     *
     * @return true si quelque chose a été refermé — le retour est alors
     *   consommé et l'onglet reste affiché ; false si l'écran est déjà à sa
     *   racine, auquel cas MainActivity poursuit sa propre hiérarchie
     *   (retour au Chat, puis confirmation de sortie).
     */
    fun onBackPressed(): Boolean
}
