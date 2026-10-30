# AB3D2 Full Java — la conversion, pas la traduction

Troisième lecture d'*Alien Breed 3D II : The Killing Grounds* (Team17, Amiga, 1996), après le
portage fidèle et le remake 3D. Celle-ci convertit l'assembleur d'origine en **vrai Java** :
des classes, des champs nommés, des collections. Pas de mémoire plate, pas de registres.

> ⚠️ **Chantier qui commence.** Ce dépôt n'exécute pas encore le jeu.

---

## Pourquoi un troisième dépôt

Le [portage](../ab3d2-tkg-java) traduit l'assembleur **instruction par instruction** : une RAM
plate de 68000 (`Mem.RAM`), des accès par offset, des registres simulés (`d0`, `a3`), la
sémantique exacte du 68k. C'était le bon choix — il a permis de prouver la fidélité, et il
tourne. Mais il se lit comme de l'assembleur écrit en Java, et chaque champ y est un nombre.

Ici on prend le problème par l'autre bout : **ce que le code FAIT**, exprimé comme on
l'écrirait aujourd'hui. Une zone est un objet avec un sol, un plafond et des arêtes, pas un
offset dans un bloc d'octets.

Le portage reste l'**oracle** : quand une règle est douteuse, c'est contre lui qu'on tranche —
et au besoin contre l'assembleur (`../ab3d2-tkg/ab3d2_source`).

---

## Les règles

1. **Aucune mémoire plate.** Pas de `Mem`, pas d'adresses globales. Chaque fichier d'origine est
   lu par un lecteur d'octets local et devient des objets.
2. **Aucune émulation de registres.** Pas de `d0`/`a3`, pas de `setw`, pas de drapeaux.
3. **Une seule concession, assumée et isolée** : {@code Arith}. Les algorithmes d'origine
   *dépendent* de la troncature à 16 bits et de la division signée tronquée vers zéro — changer
   cela change les trajectoires, pas seulement les arrondis. Ces trois opérations sont donc
   gardées, documentées comme un **contrat arithmétique** et non comme de l'émulation.
4. **Des noms, pas des nombres.** `zone.floorHeight()`, pas `Mem.l(z + 2)`.
5. **Immutable par défaut** pour les données de niveau, mutable pour l'état de jeu.

---

## Ce qui existe déjà ailleurs, et qu'on récupère

Le remake a déjà converti **toute la simulation** en objets — joueur, monstres, tirs,
animations, collision, ligne de vue, navigation, inventaire : environ 6 600 lignes dans
`rebirth/src/ab3d2/rebirth/sim/`, sans la moindre trace de `Mem`. Ce travail-là est fait ; il
sera repris ici une fois le modèle de données en place.

Reste à convertir ce qui vit encore dans la RAM plate :

| morceau | état |
| --- | --- |
| chargement des niveaux | **en cours** — ce dépôt commence par là |
| simulation (joueur, IA, tirs, anims) | déjà objet dans le remake, à rapatrier |
| rasteriseur logiciel | à faire (~10 000 lignes dans le portage) |
| menu et flux de jeu | à faire |
| audio (Paula) | à faire |

---

## Pourquoi commencer par le niveau

Parce que c'est ce qui tient la RAM plate en vie. Le fichier de niveau est **auto-descriptif** :
un bloc de dix messages de 160 octets, puis un en-tête donnant les compteurs et des offsets
relatifs au début du fichier. Le portage s'en sert pour fabriquer des pointeurs ; on s'en sert
pour fabriquer des objets. Aucune transformation en place n'est faite sur ces octets — vérifié
dans le chargeur d'origine — donc la lecture directe est légitime.

```bash
gradle -p ab3d2-tkg-fulljava levelCheck      # lit les 16 niveaux, compare au corpus de reference
```

La validation se fait contre les données extraites par le remake, qui sont elles-mêmes
produites par le portage : si les deux chemins donnent les mêmes compteurs et les mêmes
valeurs, c'est que la lecture directe est juste.

---

*Alien Breed 3D II : The Killing Grounds* et ses données sont la propriété de **Team17**. Projet
non commercial, à but d'étude et de préservation.
