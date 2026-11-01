package ab3d2.full.render;

import ab3d2.full.SinCos;
import ab3d2.full.level.Level;

import static ab3d2.full.M68k.s16;

/**
 * Ce que le joueur voit vraiment d'ou il se tient.
 *
 * <p>{@link EdgePvs} a prepare, pour chaque ouverture, la liste de ce qu'on atteint en passant
 * par elle. Reste a savoir, a chaque image, quelles ouvertures sont devant les yeux — et c'est
 * la seule chose de tout ce mecanisme qui depende de l'angle de vue.
 *
 * <p>Le champ de vision est un coin : trois demi-plans qu'une ouverture doit tous satisfaire —
 * etre devant, etre a droite du bord gauche, etre a gauche du bord droit. Une ouverture compte
 * si l'une ou l'autre de ses extremites est dans ce coin, ou si elle le TRAVERSE de part en
 * part : une porte assez large pour qu'on en voie le milieu sans en voir les montants.
 *
 * <p>Les listes des ouvertures retenues sont reunies, et c'est cette reunion qui dit ce que le
 * rendu a le droit de dessiner. Les portes s'y invitent : une case qui demande des portes
 * ouvertes n'entre dans la reunion que si elles le sont toutes, ici et maintenant.
 *
 * <p>Enfin les memes ouvertures donnent la <b>tranche d'ecran</b> ou tout cela se passe. On
 * prend l'etendue de leurs projections, de la plus a gauche a la plus a droite : rien de ce
 * qu'on voit a travers elles ne peut sortir de la. Le secteur ou l'on se tient fait exception,
 * puisqu'on n'y regarde pas a travers quoi que ce soit.
 */
public final class VisibleEdges {

    /**
     * L'ouverture du champ de vision, en huit-mille-cent-quatre-vingt-douziemes de tour.
     *
     * <p>Environ soixante-dix-neuf degres : un peu moins que les quatre-vingt-dix que donnerait
     * un quart de tour. C'est volontairement etroit — ce coin ne sert pas a cadrer l'image mais
     * a ecarter du travail, et il vaut mieux qu'il se trompe par exces que par defaut.
     */
    public static final int FIELD_OF_VIEW = 1800;

    /** Le secteur courant se voit toujours entierement lui-meme. */
    private static final int FULLY_VISIBLE = 0xFF;

    private static final int IN_FRONT = 1;
    private static final int RIGHT_OF_LEFT_EDGE = 2;
    private static final int LEFT_OF_RIGHT_EDGE = 4;
    private static final int INSIDE = IN_FRONT | RIGHT_OF_LEFT_EDGE | LEFT_OF_RIGHT_EDGE;

    private final SinCos sinCos;

    private int zone = -1;
    private int openingMask;
    private int visibleCount;
    private int openingCount;

    /** Les deux sommets de chaque ouverture retenue, bout a bout. */
    private int[] points = new int[0];

    /** Ce que la reunion donne, par rang de la liste de visibilite. */
    private int[] slots = new int[0];

    /** La meme chose, rangee par secteur : zero pour ce qu'on ne dessine pas. */
    private int[] zones = new int[0];

    private int spanLeft;
    private int spanRight;

    public VisibleEdges(SinCos sinCos) {
        this.sinCos = sinCos;
    }

    /** Le secteur d'ou l'on regarde. */
    public int zone() {
        return zone;
    }

    /** Combien d'ouvertures sont dans le champ de vision. */
    public int visibleCount() {
        return visibleCount;
    }

    /** Combien le secteur en compte en tout. */
    public int openingCount() {
        return openingCount;
    }

    /** Lesquelles, un bit par ouverture. */
    public int openingMask() {
        return openingMask;
    }

    /** Les deux sommets de l'ouverture retenue de rang {@code n}. */
    public int startPoint(int n) {
        return points[n * 2];
    }

    public int endPoint(int n) {
        return points[n * 2 + 1];
    }

    /** Comment le secteur de rang {@code slot} est vu, ou zero. */
    public int slot(int slot) {
        return slots[slot];
    }

    /**
     * Vrai si ce secteur doit etre dessine.
     *
     * <p>Le jeu range cette reponse dans le secteur lui-meme et ne la remet pas a zero pour
     * ceux qu'il n'a pas examines ; ici tout est remis a plat a chaque image. Cela ne fait
     * aucune difference : le rendu ne parcourt que des secteurs de la liste de visibilite, et
     * ils y recoivent tous une reponse fraiche.
     */
    public boolean draws(int zoneId) {
        return zoneId >= 0 && zoneId < zones.length && zones[zoneId] != 0;
    }

    /** La borne gauche de depart d'un secteur, avant que ses decoupes ne la resserrent. */
    public int startLeft(int zoneId) {
        return zoneId == zone ? 0 : spanLeft;
    }

    /** La borne droite de depart. */
    public int startRight(int zoneId, View view) {
        return zoneId == zone ? view.rightX() : spanRight;
    }

    /**
     * Trie les ouvertures du secteur ou se tient le joueur, et reunit ce qu'elles donnent.
     *
     * @param angle     l'orientation du regard, en deplacement d'octets dans la table de sinus
     * @param doorState l'etat des portes : un bit par porte, arme quand elle n'est pas fermee
     */
    public void update(Level level, EdgePvs pvs, int from, int playerX, int playerZ, int angle,
            int doorState) {
        this.zone = from;

        // Le coin du regard : la perpendiculaire a la vue, puis ses deux bords.
        int perpX = s16(-sinCos.cos(angle));
        int perpZ = s16(sinCos.sin(angle));
        int left = s16(angle - FIELD_OF_VIEW / 2);
        int leftX = sinCos.sin(left);
        int leftZ = sinCos.cos(left);
        int right = s16(left + FIELD_OF_VIEW);
        int rightX = sinCos.sin(right);
        int rightZ = sinCos.cos(right);

        int viewX = s16(playerX);
        int viewZ = s16(playerZ);

        var openings = pvs.openings(from);
        int listSize = pvs.listSize(from);
        if (slots.length < listSize) {
            slots = new int[listSize];
        }
        if (points.length < openings.size() * 2) {
            points = new int[openings.size() * 2];
        }
        if (zones.length < level.zones().size()) {
            zones = new int[level.zones().size()];
        }

        // On part du secteur ou l'on est, et de rien d'autre.
        slots[0] = FULLY_VISIBLE;
        for (int i = 1; i < listSize; i++) {
            slots[i] = 0;
        }

        openingMask = 0;
        visibleCount = 0;
        openingCount = openings.size();

        for (int i = 0; i < openings.size(); i++) {
            Level.Edge edge = level.edges().get(openings.get(i).edge());

            int start = corner(viewX, viewZ, perpX, perpZ, leftX, leftZ, rightX, rightZ,
                    edge.x(), edge.z());
            int end = INSIDE;
            if (start != INSIDE) {
                // Le bout de l'arete est range dans un MOT par le code d'origine.
                end = corner(viewX, viewZ, perpX, perpZ, leftX, leftZ, rightX, rightZ,
                        s16(edge.x() + edge.dx()), s16(edge.z() + edge.dz()));
                if (end != INSIDE) {
                    // Ni l'un ni l'autre bout n'est dans le coin : reste le cas de l'ouverture
                    // qui le traverse, un bout debordant a gauche et l'autre a droite.
                    boolean across = ((start | end) & IN_FRONT) != 0
                            && (start & RIGHT_OF_LEFT_EDGE) == 0
                            && (end & LEFT_OF_RIGHT_EDGE) == 0;
                    if (!across) {
                        continue;
                    }
                }
            }

            openingMask |= 1 << i;
            points[visibleCount * 2] = openings.get(i).startPoint();
            points[visibleCount * 2 + 1] = openings.get(i).endPoint();
            visibleCount++;
            merge(pvs, from, i, listSize, doorState);
        }

        // La reponse, rangee par secteur. Le rang zero garde sa valeur pleine : le secteur ou
        // l'on se tient se dessine toujours.
        java.util.Arrays.fill(zones, 0);
        int[] list = pvs.potentiallyVisible(from);
        for (int i = 0; i < listSize; i++) {
            zones[list[i]] = slots[i];
        }
    }

    /** Dans quels demi-plans du coin un point se trouve. */
    private static int corner(int viewX, int viewZ, int perpX, int perpZ, int leftX, int leftZ,
            int rightX, int rightZ, int x, int z) {
        int flags = side(perpX, perpZ, viewX, viewZ, x, z) < 0 ? IN_FRONT : 0;
        flags |= side(leftX, leftZ, viewX, viewZ, x, z) <= 0 ? RIGHT_OF_LEFT_EDGE : 0;
        flags |= side(rightX, rightZ, viewX, viewZ, x, z) >= 0 ? LEFT_OF_RIGHT_EDGE : 0;
        return flags;
    }

    /** De quel cote d'une direction issue d'un point se trouve un autre point. */
    private static int side(int dirX, int dirZ, int orgX, int orgZ, int x, int z) {
        return dirX * (z - orgZ) - dirZ * (x - orgX);
    }

    /**
     * Ajoute a la reunion ce qu'une ouverture donne.
     *
     * <p>Le rang zero est laisse de cote : c'est le secteur ou l'on se tient, deja entierement
     * acquis, et aucune ouverture ne doit pouvoir le retrancher.
     */
    private void merge(EdgePvs pvs, int from, int opening, int listSize, int doorState) {
        boolean conditional = pvs.conditional(from);
        for (int i = 1; i < listSize; i++) {
            if (conditional) {
                int needed = pvs.condition(from, opening, i);
                if ((needed & doorState) != needed) {
                    continue;                          // il manque une porte ouverte
                }
            }
            slots[i] |= pvs.visibility(from, opening, i);
        }
    }

    /**
     * La tranche d'ecran que les ouvertures retenues occupent.
     *
     * <p>Une ouverture dont les deux sommets se croisent a l'ecran est a cheval sur le bord du
     * champ : on ne sait pas la borner, et le jeu lui rend alors toute la largeur plutot que de
     * risquer d'en cacher une part.
     */
    public void span(View view) {
        spanLeft = 0;
        spanRight = view.rightX();
        if (visibleCount <= 0) {
            return;
        }
        int min = view.rightX();
        int max = 0;
        for (int i = 0; i < visibleCount; i++) {
            int l = view.screenX(points[i * 2]);
            int r = view.screenX(points[i * 2 + 1]);
            if (l > r) {
                l = 0;
                r = view.rightX();
            }
            if (l < min) {
                min = l;
            }
            if (r > max) {
                max = r;
            }
        }
        spanLeft = Math.max(min, 0);
        spanRight = Math.min(max, view.rightX());
    }
}
