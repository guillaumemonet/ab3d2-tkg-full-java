package ab3d2.full.render;

import ab3d2.full.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Qui se voit depuis ou, et <b>par quelle ouverture</b>.
 *
 * <p>Le fichier de niveau porte deja, pour chaque secteur, la liste de ceux qui sont
 * potentiellement visibles depuis lui. C'est une liste grossiere : elle dit qu'un secteur
 * POURRAIT etre vu de quelque part dans la piece, pas qu'il l'est d'ou l'on se tient. Le
 * chargeur la raffine une fois pour toutes, en la decoupant par ouverture : pour chaque arete
 * qui mene ailleurs, il note ce qu'on atteint en passant par elle, et seulement par elle.
 *
 * <p>Le rendu s'en sert pour une economie qui est aussi une correction : il ne garde que les
 * ouvertures reellement dans le champ de vision, reunit ce qu'elles donnent, et ecarte tout le
 * reste. Deux pieces voisines de la meme salle peuvent ainsi etre l'une dessinee et l'autre non,
 * selon le cote ou l'on regarde.
 *
 * <p>La descente est recursive et sa regle d'arret est geometrique : depuis l'ouverture de
 * depart, on ne franchit une arete que si l'une de ses deux extremites lui fait face. Un couloir
 * qui tourne finit donc par se fermer sur lui-meme sans qu'on ait besoin de compter les pas.
 *
 * <p><b>Les portes</b> compliquent l'affaire, parce qu'elles s'ouvrent. Un secteur qu'on
 * n'atteint qu'en traversant une porte n'est visible que si cette porte est ouverte — et si la
 * chaine en compte plusieurs, il faut qu'elles le soient toutes. Quand le voisinage d'un secteur
 * comporte des portes, chaque case recoit donc, en plus, le jeu de portes qu'il faut avoir
 * ouvertes pour la voir. Le rendu ne gardera la case que si l'etat courant les contient toutes.
 *
 * <p>Tout cela est derive du niveau et ne change jamais : on le calcule une fois, a la lecture.
 */
public final class EdgePvs {

    /** Pas atteint par cette ouverture. */
    public static final int UNSEEN = 0;
    /** Atteint, mais seulement a travers une ou plusieurs portes. */
    public static final int BEHIND_DOORS = 32;
    /** C'est une porte. */
    public static final int IS_DOOR = 64;
    /** Atteint sans condition. */
    public static final int SEEN = 128;

    /** Au-dela, le chargeur d'origine renonce et laisse le secteur sans donnees. */
    private static final int MAX_OPENINGS = 10;

    /** Le jeu ne retient pas plus de portes qu'il n'a de bits pour les conditionner. */
    private static final int MAX_DOORS = 16;

    /** Un etage dont le sol est au-dela n'existe pas. */
    private static final int DISABLED_HEIGHT = 5000;

    /** Les facons dont deux secteurs peuvent communiquer. */
    private static final int NO_PATH = 0;
    private static final int LOWER_TO_LOWER = 1;
    private static final int LOWER_TO_UPPER = 2;
    private static final int UPPER_TO_LOWER = LOWER_TO_LOWER << 2;
    private static final int UPPER_TO_UPPER = LOWER_TO_UPPER << 2;

    /**
     * Une ouverture : une arete par laquelle on peut voir ailleurs.
     *
     * <p>Les deux sommets sont ceux de l'arete, resolus en indices dans la table des sommets du
     * niveau. Le rendu s'en sert pour savoir quelle tranche d'ecran l'ouverture occupe.
     *
     * @param edge       l'arete
     * @param startPoint son premier sommet
     * @param endPoint   son second
     */
    public record Opening(int edge, int startPoint, int endPoint) { }

    /**
     * Ce qu'un secteur voit, ouverture par ouverture.
     *
     * <p>{@code visibility} et {@code conditions} sont deux tableaux a plat, une ligne par
     * ouverture et une colonne par secteur de {@code pvs}. On les lit par {@link
     * EdgePvs#visibility} et {@link EdgePvs#condition} plutot qu'a la main.
     */
    private record ZoneView(List<Opening> openings, int[] pvs, byte[] visibility,
            int[] conditions) { }

    private final Level level;
    private final List<Integer> doorZones;
    private final List<ZoneView> views;

    public EdgePvs(Level level) {
        this.level = level;
        this.doorZones = buildDoorList(level);

        // Deux passes, et l'ordre compte : la descente, partie d'un secteur, traverse ceux
        // d'a cote et a besoin de LEURS ouvertures. Il faut donc que le decor soit entierement
        // decoupe avant que la premiere descente ne commence.
        List<ZoneView> out = new ArrayList<>(level.zones().size());
        for (Level.Zone zone : level.zones()) {
            out.add(buildZone(zone));
        }
        this.views = List.copyOf(out);

        for (Level.Zone zone : level.zones()) {
            ZoneView v = views.get(zone.id());
            // Le chargeur d'origine renonce au-dela de dix ouvertures et laisse les masques
            // vides. Aucun secteur des seize niveaux n'en compte autant ; on garde le
            // renoncement.
            if (v.openings().size() <= MAX_OPENINGS) {
                new Walk(v).run(doorBit(zone.id()));
            }
        }
    }

    /** Les ouvertures d'un secteur, dans l'ordre ou le rendu les parcourt. */
    public List<Opening> openings(int zone) {
        return views.get(zone).openings();
    }

    /** Les secteurs potentiellement visibles depuis celui-la, dans l'ordre qui indexe les masques. */
    public int[] potentiallyVisible(int zone) {
        return views.get(zone).pvs().clone();
    }

    /** Combien de secteurs la liste compte : c'est la largeur des masques. */
    public int listSize(int zone) {
        return views.get(zone).pvs().length;
    }

    /**
     * Comment le secteur de rang {@code slot} est atteint en passant par l'ouverture donnee.
     *
     * @return {@link #UNSEEN}, {@link #BEHIND_DOORS}, {@link #IS_DOOR} ou {@link #SEEN}
     */
    public int visibility(int zone, int opening, int slot) {
        ZoneView v = views.get(zone);
        return v.visibility()[opening * v.pvs().length + slot] & 0xFF;
    }

    /**
     * Les portes qu'il faut avoir ouvertes pour que cette case compte, une par bit.
     *
     * <p>Zero quand rien ne conditionne la vue — y compris quand le secteur n'a aucune porte
     * dans son voisinage, auquel cas le jeu ne reserve meme pas la place pour les retenir.
     */
    public int condition(int zone, int opening, int slot) {
        ZoneView v = views.get(zone);
        if (v.conditions() == null) {
            return 0;
        }
        return v.conditions()[opening * v.pvs().length + slot];
    }

    /** Vrai si des portes conditionnent ce que ce secteur voit. */
    public boolean conditional(int zone) {
        return views.get(zone).conditions() != null;
    }

    /** Les secteurs qui sont des portes, dans l'ordre qui donne leur bit de condition. */
    public List<Integer> doorZones() {
        return doorZones;
    }

    // ===== construction =====

    /**
     * La liste compacte des portes du niveau.
     *
     * <p>Le rang d'un secteur dans cette liste est son bit de condition, et c'est tout ce qui
     * distingue une porte d'une autre pour le rendu. Au-dela de seize, le jeu n'a plus de bit a
     * leur donner et cesse d'en tenir compte.
     */
    private static List<Integer> buildDoorList(Level level) {
        List<Integer> out = new ArrayList<>();
        for (Level.Liftable door : level.doors()) {
            if (out.size() >= MAX_DOORS) {
                break;
            }
            if (validZone(level, door.zone())) {
                out.add(door.zone());
            }
        }
        return List.copyOf(out);
    }

    private static boolean validZone(Level level, int zone) {
        return zone >= 0 && zone < level.zones().size();
    }

    /** Le rang d'un secteur-porte, ou -1 s'il n'en est pas une. */
    private int doorIndex(int zone) {
        return doorZones.indexOf(zone);
    }

    /** Le bit de condition d'un secteur, ou zero. */
    private int doorBit(int zone) {
        int index = doorIndex(zone);
        return index < 0 ? 0 : 1 << index;
    }

    private ZoneView buildZone(Level.Zone zone) {
        // La liste de visibilite du fichier s'arrete a son premier secteur invalide.
        List<Integer> pvsList = new ArrayList<>();
        for (Level.Visible v : zone.visible()) {
            if (!validZone(level, v.zone())) {
                break;
            }
            pvsList.add(v.zone());
        }
        int[] pvs = new int[pvsList.size()];
        for (int i = 0; i < pvs.length; i++) {
            pvs[i] = pvsList.get(i);
        }

        // Des portes dans le voisinage ? Alors chaque case portera sa condition.
        boolean conditional = false;
        for (int z : pvs) {
            if (doorIndex(z) >= 0) {
                conditional = true;
                break;
            }
        }

        List<Opening> openings = buildOpenings(zone);
        byte[] visibility = new byte[openings.size() * pvs.length];
        int[] conditions = conditional ? new int[openings.size() * pvs.length] : null;
        return new ZoneView(openings, pvs, visibility, conditions);
    }

    /**
     * Les aretes du contour qui menent vraiment ailleurs.
     *
     * <p>Mener ailleurs ne suffit pas : encore faut-il que les deux secteurs se CHEVAUCHENT en
     * hauteur. Deux pieces superposees peuvent partager une arete sans qu'on puisse passer de
     * l'une a l'autre, et le decor ne s'ouvre pas la.
     */
    private List<Opening> buildOpenings(Level.Zone zone) {
        List<Opening> out = new ArrayList<>();
        for (int edgeId : zone.outline()) {
            Level.Edge edge = level.edges().get(edgeId);
            if (!validZone(level, edge.joinZone())) {
                continue;
            }
            if (crossing(zone, level.zones().get(edge.joinZone())) == NO_PATH) {
                continue;
            }
            out.add(new Opening(edgeId, pointIndex(edge.x(), edge.z()),
                    pointIndex(edge.x() + edge.dx(), edge.z() + edge.dz())));
        }
        return List.copyOf(out);
    }

    /**
     * Le sommet qui se trouve a cet endroit.
     *
     * <p>Le chargeur ne garde pas le lien entre une arete et les sommets qui la portent : il le
     * retrouve en balayant la table des sommets jusqu'a tomber sur les memes coordonnees. Deux
     * sommets confondus donneraient donc toujours le premier des deux.
     */
    private int pointIndex(int x, int z) {
        for (int i = 0; i < level.points().size(); i++) {
            Level.Point p = level.points().get(i);
            if (p.x() == x && p.z() == z) {
                return i;
            }
        }
        return -1;
    }

    // ===== la descente =====

    /**
     * La descente recursive, pour un secteur.
     *
     * <p>Elle marche sur une seule ligne de masques a la fois — celle de l'ouverture en cours —
     * et garde entre les appels le point de vue depuis lequel on juge les aretes : les deux
     * extremites de l'ouverture de depart.
     */
    private final class Walk {

        private final int[] pvs;
        private final byte[] visibility;
        private final int[] conditions;

        /** Le debut de la ligne courante dans les deux tableaux a plat. */
        private int row;

        /** Les deux extremites de l'ouverture de depart. */
        private int viewX1;
        private int viewZ1;
        private int viewX2;
        private int viewZ2;

        private final List<Opening> startOpenings;

        Walk(ZoneView view) {
            this.pvs = view.pvs();
            this.visibility = view.visibility();
            this.conditions = view.conditions();
            this.startOpenings = view.openings();
        }

        void run(int rootDoorBit) {
            for (Opening opening : startOpenings) {
                Level.Edge edge = level.edges().get(opening.edge());
                viewX1 = edge.x();
                viewZ1 = edge.z();
                viewX2 = edge.x() + edge.dx();
                viewZ2 = edge.z() + edge.dz();

                // Le secteur de depart se voit toujours lui-meme ; le reste de la ligne part
                // vierge.
                visibility[row] = (byte) SEEN;
                if (conditions != null) {
                    conditions[row] = rootDoorBit;
                }
                for (int i = 1; i < pvs.length; i++) {
                    visibility[row + i] = UNSEEN;
                }

                int slot = slotOf(level.edges().get(opening.edge()).joinZone());
                if (slot >= 0) {
                    descend(slot, rootDoorBit);
                }
                row += pvs.length;
            }
        }

        /** Le rang d'un secteur dans la liste de visibilite, ou -1 s'il n'y est pas. */
        private int slotOf(int zone) {
            if (!validZone(level, zone)) {
                return -1;
            }
            for (int i = 0; i < pvs.length; i++) {
                if (pvs[i] == zone) {
                    return i;
                }
            }
            return -1;
        }

        private void descend(int slot, int doorMask) {
            int zone = pvs[slot];

            // De quelle facon ce secteur-ci est atteint : directement, parce qu'il EST une
            // porte, ou parce qu'on a traverse des portes pour y venir.
            int reached = SEEN;
            if (conditions != null) {
                int bit = doorBit(zone);
                if (bit != 0) {
                    doorMask |= bit;
                    reached = IS_DOOR;
                } else if (doorMask != 0) {
                    reached = BEHIND_DOORS;
                }
                conditions[row + slot] = doorMask;
            }
            visibility[row + slot] = (byte) reached;

            for (Opening opening : openings(zone)) {
                Level.Edge edge = level.edges().get(opening.edge());
                int next = slotOf(edge.joinZone());
                if (next < 0) {
                    continue;                          // hors de la liste de visibilite
                }

                // On ne repasse pas par ou l'on est deja venu, sauf a y venir mieux : une case
                // atteinte a travers des portes peut l'etre a nouveau, si cette fois-ci le
                // chemin est franc.
                int already = visibility[row + next] & 0xFF;
                if (already >= IS_DOOR) {
                    continue;
                }
                if (already > UNSEEN && reached < SEEN) {
                    continue;
                }

                // L'ouverture de depart fait-elle face a cette arete ? Il suffit de l'une de ses
                // deux extremites.
                if (sideOf(edge, viewX1, viewZ1) < 0 || sideOf(edge, viewX2, viewZ2) < 0) {
                    descend(next, doorMask);
                }
            }
        }
    }

    /** De quel cote d'une arete se trouve un point : un produit en croix, comme partout ici. */
    private static int sideOf(Level.Edge edge, int x, int z) {
        return edge.dx() * (z - edge.z()) - edge.dz() * (x - edge.x());
    }

    // ===== hauteurs =====

    /** La hauteur utile d'une cote : le jeu les porte au 256e et n'en garde qu'un mot. */
    private static int heightOf(int height) {
        return (short) (height >> 8);
    }

    /** Vrai si le secteur a un second etage habitable. */
    private static boolean hasUpper(Level.Zone zone) {
        int floor = heightOf(zone.upperFloor());
        return floor < DISABLED_HEIGHT && floor > heightOf(zone.upperCeiling());
    }

    /**
     * Vrai si deux tranches de hauteur se chevauchent assez pour qu'on passe de l'une a l'autre.
     *
     * <p>Les hauteurs vont vers le BAS : le sol a une valeur plus grande que le plafond. Il faut
     * donc que chaque plafond soit strictement au-dessus du sol d'en face.
     */
    private static boolean overlap(int floor1, int ceiling1, int floor2, int ceiling2) {
        if (heightOf(ceiling1) >= heightOf(floor2)) {
            return false;
        }
        if (heightOf(ceiling2) >= heightOf(floor1)) {
            return false;
        }
        return true;
    }

    /**
     * Par quels etages deux secteurs communiquent.
     *
     * <p>Un secteur a un etage bas, parfois un etage haut, et les quatre appariements n'ont pas
     * tous un sens : on ne teste le haut que la ou il existe.
     */
    private static int crossing(Level.Zone from, Level.Zone to) {
        int result = overlap(from.floor(), from.ceiling(), to.floor(), to.ceiling())
                ? LOWER_TO_LOWER : NO_PATH;

        int which = (hasUpper(from) ? 1 : 0) | (hasUpper(to) ? 2 : 0);
        if (which == 1 || which == 3) {
            result |= overlap(from.upperFloor(), from.upperCeiling(), to.floor(), to.ceiling())
                    ? UPPER_TO_LOWER : NO_PATH;
        }
        if (which == 2 || which == 3) {
            result |= overlap(from.floor(), from.ceiling(), to.upperFloor(), to.upperCeiling())
                    ? LOWER_TO_UPPER : NO_PATH;
        }
        if (which == 3) {
            result |= overlap(from.upperFloor(), from.upperCeiling(),
                    to.upperFloor(), to.upperCeiling()) ? UPPER_TO_UPPER : NO_PATH;
        }
        return result;
    }
}
