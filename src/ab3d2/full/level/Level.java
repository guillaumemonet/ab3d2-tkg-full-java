package ab3d2.full.level;

import java.util.List;

/**
 * Un niveau, tel que le jeu le lit — mais en objets.
 *
 * <p>Toutes les coordonnées sont celles du jeu d'origine : des mots signés, X et Z dans le plan,
 * Y vers le BAS (une valeur plus grande est plus basse). On ne les convertit pas : convertir ici
 * obligerait à reconvertir partout ailleurs, et c'est la simulation qui décide de son repère.
 *
 * @param letter       la lettre du niveau, {@code a} à {@code p}
 * @param messages     les dix textes du niveau, affichés au ramassage ou à la mort
 * @param start1       départ du joueur 1
 * @param start2       départ du joueur 2
 * @param points       sommets du décor, partagés par les arêtes et les secteurs
 * @param controlPoints maillage de navigation des monstres
 * @param routes       table de routage entre points de contrôle (voir {@link #nextControlPoint})
 * @param entities     monstres et objets posés dans le niveau
 */
public record Level(
        char letter,
        List<String> messages,
        Start start1,
        Start start2,
        List<Point> points,
        List<ControlPoint> controlPoints,
        int[] walkRoutes,
        int[] flyRoutes,
        List<Entity> entities,
        List<Edge> edges,
        List<Zone> zones,
        List<Liftable> doors,
        List<Liftable> lifts,
        List<Switch> switches,
        List<Clip> clips,
        int exitZone) {

    /** Position de départ d'un joueur. */
    public record Start(int x, int z, int zone) { }

    /** Un sommet du décor. */
    public record Point(int x, int z) { }

    /** Un point du maillage de navigation. */
    public record ControlPoint(int x, int z, int y, int extra) { }

    /** Ce qu'une entité est : un monstre, ou un objet posé. */
    public enum Kind {
        /** Monstre. */
        ALIEN,
        /** Objet : ramassable, décor animé, interrupteur. */
        OBJECT,
        /** Projectile, auxiliaire, joueur — pas de définition dans les tables. */
        OTHER;

        /** Le type d'entite correspondant au code brut du fichier. */
        public static Kind of(int typeId) {
            return switch (typeId) {
                case 0 -> ALIEN;
                case 1 -> OBJECT;
                default -> OTHER;
            };
        }
    }

    /**
     * Une entité posée dans le niveau.
     *
     * @param kind       monstre, objet, ou autre
     * @param def        index dans la table de définitions correspondant au {@code kind}
     * @param x          position, reprise de la table des points d'objets
     * @param z          position
     * @param zone       secteur de départ ; négatif = retirée du niveau
     * @param upperZone  vrai si l'entité est à l'étage supérieur d'un secteur à deux niveaux
     * @param brightness luminosité propre, ajoutée à celle de la distance
     * @param unlocks    bits des portes et ascenseurs que cette entité VERROUILLE tant qu'elle
     *                   est présente — autrement dit les clés qu'elle porte
     * @param message    index du texte du niveau à afficher, ou -1
     * @param angle      orientation de depart
     * @param hitPoints  points de vie de depart — n'a de sens que pour un monstre
     * @param team       equipe du monstre, negatif s'il n'en a pas : deux monstres d'equipes
     *                   differentes se tirent dessus
     * @param mode       conduite de depart
     * @param controlPoint       point du maillage ou le monstre se trouve
     * @param targetControlPoint celui vers lequel il se dirige
     * @param height     hauteur de dessin et de collision
     */
    public record Entity(
            Kind kind,
            int def,
            int x,
            int z,
            int zone,
            boolean upperZone,
            int brightness,
            int unlocks,
            int message,
            int angle,
            int hitPoints,
            int team,
            int mode,
            int controlPoint,
            int targetControlPoint,
            int height) {

        /** Vrai si l'entité tient la clé d'au moins une porte ou un ascenseur. */
        public boolean holdsKey() {
            return unlocks != 0;
        }
    }

    /**
     * Une arête : un segment du plan, qui sépare deux secteurs ou ferme le décor.
     *
     * @param x        origine
     * @param z        origine
     * @param dx       vecteur, de l'origine vers l'autre bout
     * @param dz       vecteur
     * @param joinZone secteur de l'autre côté, ou -1 si l'arête est un mur plein
     * @param divider  diviseur du test de côté, précalculé par l'éditeur
     * @param normalX  normale de l'arete, precalculee par l'editeur : la collision s'en sert
     *                 pour ECARTER le point teste du mur, de la largeur de ce qui bouge. Elle
     *                 decale la normale a gauche d'autant de bits que l'objet est large, ce qui
     *                 fait de ces deux octets une unite de largeur plutot qu'une distance
     * @param normalZ  l'autre composante de la normale
     */
    public record Edge(int x, int z, int dx, int dz, int joinZone, int divider,
            int normalX, int normalZ) {

        /** Vrai si rien ne passe : l'arete est un mur. */
        public boolean solid() {
            return joinZone < 0;
        }
    }

    /**
     * Un secteur.
     *
     * <p>Les hauteurs sont en Y vers le BAS : le sol a une valeur plus grande que le plafond.
     * Un secteur peut avoir un second étage ({@code upperFloor} / {@code upperCeiling}), ce
     * qui permet au jeu de superposer deux pièces au même endroit du plan.
     *
     * <p>{@code brightness} est signé : une valeur négative est une luminosité fixe, une valeur
     * positive dont l'octet fort n'est pas nul désigne une lumière ANIMÉE dans la table du jeu.
     *
     * @param edgeList liste brute des arêtes, avec ses marqueurs : -1 ferme le premier groupe
     *                 (le contour, qui sert au rendu du sol et du plafond), -2 termine la liste
     */
    public record Zone(
            int id,
            int floor,
            int ceiling,
            int upperFloor,
            int upperCeiling,
            int water,
            int brightness,
            int upperBrightness,
            int controlPoint,
            int upperControlPoint,
            int ambientSoundMask,
            int upperAmbientSoundMask,
            boolean drawBackdrop,
            int echo,
            int teleportZone,
            int teleportX,
            int teleportZ,
            int floorSound,
            int upperFloorSound,
            int pointCount,
            List<Integer> edgeList,
            List<Visible> visible,
            List<Integer> borderPoints,
            List<Integer> pointBrights,
            Graphics lower,
            Graphics upper) {

        /**
         * Les luminosites de points telles que le jeu les utilise, et non telles que le fichier
         * les range.
         *
         * <p>Le fichier n'y met qu'un octet, que le chargement etire sur l'echelle du rendu :
         * il le multiplie par 397, le ramene de huit bits, ecarte les valeurs negatives de six
         * cents, puis decale le tout de trois cents. C'est ce resultat que lisent aussi bien le
         * rendu que les monstres, qui jugent a sa moyenne si une piece est assez sombre pour
         * s'y cacher.
         *
         * <p>L'octet FORT du mot brut, lui, designe une lumiere ANIMEE et n'est pas resolu ici :
         * cette valeur est la base sur laquelle l'animation vient jouer.
         */
        public List<Integer> litPointBrights() {
            List<Integer> out = new java.util.ArrayList<>(pointBrights.size());
            for (int raw : pointBrights) {
                int v = (byte) raw;
                v = (v * 397) >> 8;
                if (v < 0) {
                    v -= 600;
                }
                out.add(v + 300);
            }
            return List.copyOf(out);
        }

        /**
         * Les points de bord effectifs : la liste s'arrete a sa premiere valeur negative.
         *
         * <p>ATTENTION, elle peut DEBORDER de son emplacement. Le jeu lui reserve dix mots par
         * secteur mais ne verifie rien : une liste plus longue continue dans le secteur suivant,
         * et l'indice de luminosite suit. C'est un debordement d'origine, pas un defaut de
         * lecture, et il faut le reproduire.
         */
        public List<Integer> border() {
            List<Integer> out = new java.util.ArrayList<>();
            for (int p : borderPoints) {
                if (p < 0) {
                    break;
                }
                out.add(p);
            }
            return List.copyOf(out);
        }

        /** Vrai si l'etage superieur a son propre habillage. */
        public boolean hasUpperGraphics() {
            return upper != null;
        }

        /** Vrai si le secteur a un second étage. */
        public boolean hasUpperFloor() {
            return upperFloor != floor || upperCeiling != ceiling;
        }

        /** Vrai si le secteur téléporte ailleurs. */
        public boolean teleports() {
            return teleportZone >= 0;
        }

        /** Les arêtes du CONTOUR : le premier groupe, celui qui ferme le secteur. */
        public List<Integer> outline() {
            List<Integer> out = new java.util.ArrayList<>();
            for (int e : edgeList) {
                if (e < 0) {
                    break;
                }
                out.add(e);
            }
            return List.copyOf(out);
        }
    }

    /**
     * Un secteur potentiellement visible depuis un autre, et par quelle ouverture.
     *
     * <p>C'est la base de la ligne de vue, donc de la detection du joueur par les monstres : un
     * monstre ne regarde que les secteurs de cette liste.
     *
     * @param clip le groupe de points qui retrecit le champ en passant par cette ouverture, ou
     *             {@code null} si la vue est degagee
     * @param orderMask l'etat de depart du tri des secteurs : trois bits par arete du secteur,
     *                  disant ce que l'editeur a deja tranche sur l'ordre de dessin. Le fichier
     *                  le range en deux mots que le jeu lit d'un seul tenant
     */
    public record Visible(int zone, Clip clip, int orderMask) {

        /** Vrai si traverser cette ouverture ne retrecit pas le champ de vision. */
        public boolean open() {
            return clip == null;
        }
    }

    /**
     * Un groupe de points qui resserre le champ de vision au passage d'une ouverture.
     *
     * <p>Le fichier de niveau ne porte pas ces groupes : il marque seulement, d'un mot positif,
     * les ouvertures qui en ont un. C'est le CHARGEUR qui les distribue, en parcourant la table
     * des decoupes du debut a la fin et en donnant a chaque ouverture marquee le groupe suivant,
     * dans l'ordre des secteurs. Un groupe tient donc son identite de son rang, et {@code
     * wordIndex} est la position en mots a laquelle le jeu le retrouvait.
     *
     * <p>Les deux cotes sont independants : l'un comme l'autre peut etre vide. Chaque valeur est
     * l'indice d'un sommet du decor ; le rendu en tire une abscisse a l'ecran et ne garde que la
     * plus contraignante.
     *
     * @param left  les points qui bornent la vue a gauche
     * @param right les points qui la bornent a droite
     */
    public record Clip(int wordIndex, List<Integer> left, List<Integer> right) { }

    /**
     * L'habillage d'un etage de secteur : la suite de commandes que le rendu execute.
     *
     * <p>Le fichier graphe ne decrit pas des surfaces, il ecrit un <b>programme</b>. Chaque
     * commande commence par un mot dont l'octet bas est le code ; le reste de l'enregistrement
     * depend de ce code, et un octet bas dont le bit 7 est mis arrete le programme. Le rendu ne
     * fait que derouler cette suite, ce qui veut dire que l'ORDRE est significatif : le sol
     * avant les objets, l'eau entre les deux.
     *
     * <p>Un secteur en a jusqu'a deux, un par etage. L'etage superieur est absent plus souvent
     * qu'autrement, et le fichier le dit par un offset nul.
     *
     * @param offset ou l'habillage commence dans le fichier graphe — le jeu n'y accede que par
     *               la table des secteurs, mais c'est l'identite que la commande porte
     */
    public record Graphics(int zone, int offset, List<Paint> program) { }

    /** Une commande du programme d'habillage. */
    public sealed interface Paint
            permits Wall, Flat, Objects, Sky, Unknown {

        /** Le mot de tete, dont l'octet bas porte le code et l'octet haut des indicateurs. */
        int tag();
    }

    /**
     * Un pan de mur.
     *
     * <p>Les deux points sont ceux du decor ; {@code leftSlot} et {@code rightSlot} disent
     * lequel des points lumineux du secteur eclaire chaque bout. La texture se plaque avec
     * {@code fromTile} pour abscisse de depart et {@code textureY} pour ordonnee, les masques
     * donnant ses dimensions — ce sont des masques et non des tailles parce que le rendu
     * boucle par ET binaire, ce qui impose des puissances de deux.
     *
     * @param top    haut du pan, en virgule fixe, Y vers le BAS
     * @param bottom bas du pan ; le rendu abandonne si {@code top >= bottom}, le mur tournant
     *               alors le dos a la camera
     */
    public record Wall(
            int tag,
            int offset,
            int leftPoint,
            int rightPoint,
            int leftSlot,
            int rightSlot,
            int length,
            int fromTile,
            int textureY,
            int texture,
            int heightMask,
            int heightShift,
            int widthMask,
            int brightRow,
            int top,
            int bottom,
            int brightOffset,
            int otherZone) implements Paint {

        /** Taille de l'enregistrement, mot de tete non compris. */
        public static final int SIZE = 28;

        /**
         * Vrai si ce mur figure sur la carte automatique.
         *
         * <p>Le moteur lit l'octet haut du mot de tete comme un octet SIGNE et abandonne s'il
         * est negatif : c'est ainsi que l'editeur marque les murs a ne pas tracer.
         */
        public boolean onMap() {
            return (tag & 0x8000) == 0;
        }

        /**
         * L'emplacement de ce mur sur la carte automatique, dans les quatre bits bas de
         * l'octet haut — sans valeur si {@link #onMap()} est faux.
         *
         * <p>Ce n'est pas par la que les portes et les interrupteurs designent un mur : ceux-la
         * en gardent l'offset dans le fichier graphe, celui que rend {@link #offset()}.
         */
        public int mapSlot() {
            return (tag >>> 8) & 0x0F;
        }

        /** Vrai si la carte automatique doit tracer ce mur comme une porte. */
        public boolean mapDoor() {
            return (tag & 0x1000) != 0;
        }

        /**
         * L'abscisse de depart de la texture, en pixels.
         *
         * <p>Le fichier compte en <b>seizaines</b> et le rendu decale de quatre bits ; le
         * decalage se fait sur un mot, donc il reboucle, et c'est voulu.
         */
        public int textureX() {
            return (short) (fromTile << 4);
        }

        /** Le haut du pan, en unites du monde. */
        public int topWorld() {
            return top >> 8;
        }

        /** Le bas du pan, en unites du monde. */
        public int bottomWorld() {
            return bottom >> 8;
        }
    }

    /**
     * Une surface horizontale : sol, plafond ou plan d'eau.
     *
     * <p>Son contour est une ligne brisee de points du decor, et le fichier la REFERME : le
     * dernier point repete le premier, parce que le rendu avance par couples successifs.
     *
     * @param y hauteur, Y vers le BAS
     */
    public record Flat(
            int tag,
            Surface kind,
            int y,
            List<FlatPoint> outline,
            int scale,
            int tile,
            int light) implements Paint {

        /**
         * Les sommets distincts du contour, sans le point de fermeture.
         *
         * <p>Ce sont exactement les points de bord du secteur, dans le meme ordre.
         */
        public List<FlatPoint> corners() {
            return outline.subList(0, outline.size() - 1);
        }

        /**
         * Le numero de la tuile, de 0 a 15.
         *
         * <p>Le fichier ne le range pas tel quel : le mot vaut {@code banque * 256 + rang},
         * parce que le rendu s'en sert comme d'un DEPLACEMENT dans le bloc des textures de sol,
         * ou les tuiles sont entrelacees quatre par quatre.
         */
        public int tileIndex() {
            return (tile >>> 8) * 4 + (tile & 0xFF);
        }
    }

    /** Ce qu'une surface horizontale represente. */
    public enum Surface {
        /** Le sol. */
        FLOOR,
        /** Le plafond. */
        CEILING,
        /** La surface de l'eau. */
        WATER;

        /** La surface correspondant au code de commande. */
        public static Surface of(int opcode) {
            return switch (opcode) {
                case 1 -> FLOOR;
                case 2 -> CEILING;
                default -> WATER;
            };
        }
    }

    /**
     * Un sommet du contour d'une surface horizontale.
     *
     * <p>Le fichier les range un par mot : les douze bits de poids faible designent le point du
     * decor, les quatre autres le point lumineux du secteur qui l'eclaire.
     */
    public record FlatPoint(int point, int slot) { }

    /**
     * Le moment ou le rendu insere les objets du secteur.
     *
     * <p>C'est une commande sans geometrie : elle ne dit que QUAND dessiner, par rapport au plan
     * d'eau, ce qui suffit a ranger correctement ce qui flotte et ce qui est immerge.
     */
    public record Objects(int tag, Moment moment) implements Paint {

        /** A quel moment de la traversee du secteur les objets viennent. */
        public enum Moment {
            /** Avant le plan d'eau. */
            BEFORE_WATER,
            /** Apres le plan d'eau. */
            AFTER_WATER,
            /** Sur toute la hauteur du secteur. */
            WHOLE_ROOM;

            /** Le moment correspondant au mot de l'enregistrement. */
            public static Moment of(int v) {
                if (v < 1) {
                    return BEFORE_WATER;
                }
                return v == 1 ? AFTER_WATER : WHOLE_ROOM;
            }
        }
    }

    /** Le ciel. La commande tient dans son seul mot de tete. */
    public record Sky(int tag) implements Paint { }

    /**
     * Une commande dont le code n'est pas reconnu.
     *
     * <p>Le rendu d'origine ne s'en plaint pas : il passe au mot suivant. On garde donc la
     * meme tolerance, et la trace de ce qui a ete rencontre.
     */
    public record Unknown(int tag) implements Paint { }

    /**
     * Une porte ou un ascenseur — le jeu n'en fait qu'un seul format, le « liftable ».
     *
     * <p>Ce qui les distingue n'est pas leur structure mais ce qu'ils deplacent : une porte fait
     * descendre le plafond de son secteur, un ascenseur en fait monter le sol. Les deux
     * deforment au passage les murs que {@code walls} designe.
     *
     * @param bottom    position basse de la course
     * @param top       position haute
     * @param openSpeed vitesse d'ouverture
     * @param closeSpeed vitesse de fermeture
     * @param openDuration temps passe ouvert avant de se refermer
     * @param position  position de depart
     * @param zone      secteur dont la porte ou l'ascenseur bouge le plafond ou le sol
     * @param openBits  condition d'ouverture : 0 et 1 demandent la touche d'action du joueur,
     *                  4 s'ouvre tout seul a l'approche
     * @param closeBits condition de fermeture
     * @param walls     les murs deformes par le mouvement
     */
    public record Liftable(
            int index,
            int bottom,
            int top,
            int openSpeed,
            int closeSpeed,
            int openDuration,
            int openingSound,
            int closingSound,
            int openedSound,
            int closedSound,
            int x,
            int z,
            int position,
            int speed,
            int zone,
            int openBits,
            int closeBits,
            List<Wall> walls) {

        /** Vrai si la porte s'ouvre d'elle-meme, sans que le joueur ait a agir. */
        public boolean automatic() {
            return openBits == 4;
        }

        /**
         * Un mur deforme par le mouvement.
         *
         * @param edge       l'arete concernee
         * @param graphics   ou se trouve son enregistrement dans le flux graphique
         * @param textureTop base du decalage vertical de la texture, dont le jeu retranche la
         *                   position courante a chaque image
         */
        public record Wall(int edge, int graphics, int textureTop) { }
    }

    /**
     * Un interrupteur mural, que le joueur actionne en s'en approchant.
     *
     * <p>Le moteur en parcourt huit a chaque image, nombre ecrit en dur. Le <i>n</i>-ieme
     * commande le bit {@code 4 + n} des conditions du niveau, et change la texture du mur qu'il
     * designe.
     *
     * <p>Aucun des seize niveaux n'en active un seul : ils sont tous marques inutilises. Le
     * mecanisme est du contenu mort, mais il est parcouru, donc il est ici.
     *
     * @param enabled faux si l'emplacement est vide, ce qui est le cas partout
     * @param point   premier des deux points consecutifs dont l'interrupteur occupe le milieu
     * @param wall    ou se trouve, dans le fichier graphe, le mur dont il change la texture
     */
    public record Switch(int index, boolean enabled, int point, int wall) {

        /** Le bit des conditions du niveau que cet interrupteur bascule. */
        public int conditionBit() {
            return 4 + index;
        }
    }

    /**
     * Point de controle suivant sur le chemin de {@code from} vers {@code to}, ou -1 s'il n'y en
     * a pas.
     *
     * <p>Il y a DEUX tables, selon que le monstre marche ou vole, et elles vivent dans leurs
     * propres fichiers a cote du niveau. Le pas est de <b>100</b> quel que soit le nombre de
     * points : la routine d'origine multiplie par 100 en dur, et les fichiers font donc
     * toujours dix mille octets.
     *
     * <p>La valeur {@code 0x7F} signale l'absence de chemin, et le bit 7 marque les liaisons
     * qui ne valent qu'a vue.
     */
    public int nextControlPoint(boolean flying, int from, int to) {
        int v = (flying ? flyRoutes : walkRoutes)[from * ROUTE_STRIDE + to] & 0xFF;
        return (v & 0x7F) == 0x7F ? -1 : v & 0x7F;
    }

    /** Vrai si la liaison n'est empruntable qu'a vue. */
    public boolean routeOnlyWhenSeen(boolean flying, int from, int to) {
        return ((flying ? flyRoutes : walkRoutes)[from * ROUTE_STRIDE + to] & 0x80) != 0;
    }

    /** Le pas des tables de routage, en dur dans la routine d'origine. */
    public static final int ROUTE_STRIDE = 100;
}
