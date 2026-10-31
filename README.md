# AB3D2 Full Java — la conversion, pas la traduction

Troisième lecture d'*Alien Breed 3D II : The Killing Grounds* (Team17, Amiga, 1996), après le
portage fidèle et le remake 3D. Celle-ci convertit l'assembleur d'origine en **vrai Java** :
des classes, des champs nommés, des collections. Pas de mémoire plate, pas de registres.

**La simulation tourne.** On peut y jouer, vu de dessus. Le rendu du jeu, lui, reste à porter.

---

## Pourquoi un troisième dépôt

Le [portage](../java) traduit l'assembleur **instruction par instruction** : une RAM plate de
68000 (`Mem.RAM`), des accès par offset, des registres simulés (`d0`, `a3`), la sémantique
exacte du 68k. C'était le bon choix — il a permis de prouver la fidélité, et il tourne. Mais il
se lit comme de l'assembleur écrit en Java, et chaque champ y est un nombre.

Le [remake](../rebirth) prend l'autre extrême : une relecture moderne sous jMonkeyEngine, en
vraie 3D, qui s'autorise à s'écarter.

Ici, la voie du milieu : **les mécaniques d'origine, exactement**, mais exprimées comme on les
écrirait aujourd'hui. Un secteur est un objet avec un sol, un plafond et des arêtes, pas un
offset dans un bloc d'octets.

Le portage reste l'**oracle** : quand une règle est douteuse, c'est contre lui qu'on tranche —
et au besoin contre l'assembleur (`../../ab3d2-tkg/ab3d2_source`).

---

## Les règles

1. **Aucune mémoire plate.** Pas de `Mem`, pas d'adresses globales. Chaque fichier d'origine est
   lu par un lecteur d'octets local et devient des objets.
2. **Aucune émulation de registres.** Pas de boucle d'instructions, pas de drapeaux.
3. **Une seule concession, assumée et isolée** : `M68k`. Les algorithmes d'origine *dépendent*
   de la troncature à 16 bits, de l'écriture d'un mot dans un registre de 32, et du fait qu'une
   division impossible laisse le registre **inchangé** au lieu de lever une exception. Changer
   cela change les trajectoires, pas seulement les arrondis.
4. **Des noms, pas des nombres.** `zone.floor()`, pas `Mem.l(z + 2)`.
5. **Immuable pour le niveau, mutable pour l'état de jeu.** Le fichier dit ce qui est posé et
   où, une fois pour toutes ; ce qui change vit ailleurs.
6. **Aucune dépendance.** Gson ne sert qu'aux outils de validation ; le jeu lui-même n'importe
   rien.

---

## Où en est-on

| morceau | état |
| --- | --- |
| lecture des niveaux | **fait** — 16 niveaux, 0 écart |
| base de liaison (`test.lnk`) | **fait** — 0 écart |
| simulation : joueur, IA, tirs, objets, portes | **fait** — rapatriée du remake |
| boucle de jeu | **fait** — `sim.World` |
| vue de dessus jouable | **fait** — `view.Play` |
| rastériseur logiciel | à faire (~10 000 lignes dans le portage) |
| menu et enchaînement des niveaux | à faire |
| audio (Paula) | à faire |

---

## Jouer, et vérifier

```bash
gradle play -Plevel=c     # une fenêtre, vue de dessus, 50 images/s
gradle checkAll           # les six vérifications
```

Dans la fenêtre : flèches ou **ZQSD** pour aller, **A**/**E** pour tourner, **ESPACE** tirer,
**F** actionner (la touche qui ouvre les portes à serrure), **1-9** l'arme, **TAB** la caméra,
**+/-** le zoom. Les hauteurs ne s'y voient pas : un secteur à deux étages s'y montre à plat.

Les six vérifications, chacune lançable seule :

| tâche | ce qu'elle éprouve |
| --- | --- |
| `levelCheck` | les 16 niveaux, champ par champ |
| `defsCheck` | la base de liaison, champ par champ |
| `moveTest` | le déplacement, les portes, le ramassage |
| `alienTest` | l'IA des monstres |
| `shotTest` | le tir et les projectiles |
| `worldTest` | la boucle **entière**, 1 000 images sur chaque niveau |

Les harnais acceptent `-Plevel=a` (ou `all`).

### Contre quoi on valide

Le corpus de référence est celui qu'extrait le remake, lui-même produit en lisant la mémoire du
portage après chargement. Si la lecture **sans mémoire plate** retrouve les mêmes valeurs, c'est
que la conversion est juste.

`alienTest` et `shotTest` donnent des résultats **identiques** à ceux du remake sur les seize
niveaux — mêmes trajectoires, mêmes images, mêmes tirs, mêmes dégâts.

Trois écarts relevés tiennent à la référence et non à la lecture : son compteur d'arêtes lit
au-delà de la table, ses messages ont subi un repli à 40 colonnes, et elle perd le dernier
sommet de chaque sol et de chaque plafond. Les outils les signalent en « remarques ».

---

## Les données

Deux provenances, deux racines.

- `medias/original/` (cherché en remontant) — ce qui vient des disquettes : les niveaux et
  `Includes/test.lnk`.
- `data/incbin/` — les tables que l'assembleur incorporait à l'exécutable. La table de sinus en
  fait partie, et on la **lit** plutôt que de la recalculer : les trajectoires dépendent de ses
  arrondis.

---

*Alien Breed 3D II : The Killing Grounds* et ses données sont la propriété de **Team17**. Projet
non commercial, à but d'étude et de préservation.
