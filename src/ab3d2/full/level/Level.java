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
        List<Entity> entities) {

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
