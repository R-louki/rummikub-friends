# La table de jeu (plateau + chevalet)

Depuis la version 1.5, le plateau et le chevalet forment **une seule surface** : `TableSurface`
(`android/app/src/main/java/com/rummikub/app/ui/table/`). Elle remplace l'ancien plateau en grille,
l'ancien chevalet à cases et le glisser-déposer du système Android.

## Ce que le joueur voit

- **Tuiles vectorielles** (`TileFace`) : nettes à toute densité d'écran, avec un repère de forme
  sous le chiffre (● ■ ▲ ◆) pour distinguer les couleurs sans les voir.
- **Plateau sans cadres** : les combinaisons sont simplement des tuiles séparées par un espace, sur un
  feutre texturé. Pas de rectangle, pas de total. Seul un trait rouge souligne une combinaison invalide,
  et un trait bleu celle où la tuile tenue va tomber.
- **Mise en page automatique** : la taille des tuiles du plateau s'adapte pour que toutes les
  combinaisons tiennent sans défiler (de 22 dp à 66 dp).
- **Chevalet réaliste** : un socle en bois clair à deux niveaux, avec l'épaisseur du bois, le veinage et
  un rebord qui passe devant le bas des tuiles. Le niveau avant masque le tiers du niveau arrière, ce qui
  le rend compact : le reste de l'écran va au plateau. La progression de la première pose est gravée sur
  la face avant du socle.
- **Joueurs lisibles** : noms en 14 sp et nombre de tuiles dans une pastille en 18 sp, orange quand un
  joueur n'a plus que 1 à 3 tuiles.
- **Animations** : chaque tuile est un élément animé par ressort. Elle glisse à sa nouvelle place
  quand quelque chose change, vole du chevalet à la table, et arrive en volant quand elle est piochée,
  distribuée ou posée par un adversaire.
- **Glisser avec aperçu en direct** : la tuile se soulève au-dessus du doigt, un trou s'ouvre là où
  elle va tomber, la combinaison visée s'illumine. Un lot de tuiles sélectionnées se glisse en bloc.
- **Première pose** : l'indicateur `points / seuil` est gravé sur la face avant du socle tant que la
  première pose n'est pas faite.

## Aides à la manipulation

| Geste ou bouton | Effet |
|---|---|
| Double toucher sur une tuile du chevalet | Placement automatique : prolonge une combinaison de la table, sinon forme une combinaison avec deux tuiles assorties du chevalet |
| Déposer une tuile qui prolonge une suite | Elle se range d'elle-même au bon bout |
| Déposer la tuile qu'un joker remplace | Échange automatique : le joker sort et doit être rejoué dans le tour |
| Appui long | Sélectionne la tuile et celles qui la suivent tant qu'elles prolongent une combinaison |
| 💡 | Suggestion : pose la meilleure combinaison trouvée avec le chevalet de début de tour |
| ↶ / ↷ | Annuler / rétablir un coup du tour |
| 789 / 999 | Trier le chevalet par séries / par groupes |

## Où est le code

- `core-engine/.../layout/TableLayout.kt` : calcul pur des positions (sans Compose, testé sur la JVM).
- `core-engine/.../MoveAssistant.kt` : suggestion, placement automatique, échange de joker.
- `app/.../ui/table/TableSurface.kt` : surface, gestes, animations.
- `app/.../game/TurnController.kt` : état du tour (annuler, rétablir, ordre du chevalet, assistance).

## Vérifier sur émulateur

Une page de démonstration sans serveur existe dans les versions debug :

```
adb shell am start -n com.rummikub.app/.PreviewActivity --es screen table
adb shell am start -n com.rummikub.app/.PreviewActivity --es screen table_full
```
