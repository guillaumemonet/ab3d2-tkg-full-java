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
        int[] routes,
        List<Entity> entities,
        List<Edge> edges,
        List<Zone> zones,
        List<Liftable> doors,
        List<Liftable> lifts,
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
            int message) {

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
     */
    public record Edge(int x, int z, int dx, int dz, int joinZone, int divider) {

        /** Vrai si rien ne passe : l'arête est un mur. */
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
            boolean drawBackdrop,
            int echo,
            int teleportZone,
            int teleportX,
            int teleportZ,
            int floorSound,
            int upperFloorSound,
            int pointCount,
            List<Integer> edgeList,
            List<Visible> visible) {

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
     */
    public record Visible(int zone, Clip clip) {

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
     * Point de contrôle suivant sur le chemin de {@code from} vers {@code to}, ou -1 s'il n'y en
     * a pas.
     *
     * <p>Le pas de la table est de <b>100</b> quel que soit le nombre de points : la routine
     * d'origine multiplie par 100 en dur. La valeur {@code 0x7F} signale l'absence de chemin, et
     * le bit 7 marque les liaisons « à vue seulement ».
     */
    public int nextControlPoint(int from, int to) {
        int v = routes[from * 100 + to] & 0xFF;
        return (v & 0x7F) == 0x7F ? -1 : v & 0x7F;
    }

    /** Vrai si la liaison n'est empruntable qu'à vue. */
    public boolean routeOnlyWhenSeen(int from, int to) {
        return (routes[from * 100 + to] & 0x80) != 0;
    }
}
