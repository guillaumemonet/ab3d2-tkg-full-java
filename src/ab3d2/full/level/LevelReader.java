package ab3d2.full.level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lit un fichier de niveau d'origine et en fait des objets.
 *
 * <p>Le fichier est auto-descriptif. Il commence par <b>dix messages de 160 octets</b>, puis
 * vient un en-tête qui donne les compteurs et des offsets <b>relatifs au début du fichier</b> —
 * ce que le chargeur d'origine se contente d'additionner à l'adresse de chargement pour
 * fabriquer ses pointeurs. Aucun octet n'est modifié au passage, ce qui autorise à lire le
 * fichier directement plutôt que de reconstituer une mémoire.
 */
public final class LevelReader {

    // --- le bloc de messages, en tête de fichier ---
    private static final int MESSAGE_LENGTH = 160;
    private static final int MESSAGE_COUNT = 10;
    private static final int HEADER = MESSAGE_LENGTH * MESSAGE_COUNT;

    // --- l'en-tete, relatif a la fin du bloc de messages ---
    private static final int PLR1_X = 0;
    private static final int PLR1_Z = 2;
    private static final int PLR1_ZONE = 4;
    private static final int PLR2_X = 6;
    private static final int PLR2_Z = 8;
    private static final int PLR2_ZONE = 10;
    private static final int NUM_CONTROL_POINTS = 12;
    private static final int NUM_POINTS = 14;
    private static final int NUM_ZONES = 16;
    private static final int OFFSET_POINTS = 22;
    /** Les aretes commencent la, et courent jusqu'aux entites. */
    private static final int OFFSET_EDGES = 26;
    private static final int OFFSET_OBJECTS = 30;
    private static final int OFFSET_OBJECT_POINTS = 42;
    /** Juste après l'en-tête commencent les coordonnées des points de contrôle. */
    private static final int HEADER_SIZE = 54;

    // --- l'enregistrement d'une entite : 64 octets ---
    private static final int ENTITY_SIZE = 64;
    private static final int ENT_POINT_INDEX = 0;
    private static final int ENT_BRIGHTNESS = 2;
    private static final int ENT_ZONE = 12;
    private static final int ENT_TYPE_ID = 16;
    private static final int ENT_MESSAGE = 24;
    private static final int ENT_UNLOCKS = 50;
    private static final int ENT_DEF = 54;
    private static final int ENT_UPPER_ZONE = 63;

    // --- une arete : 16 octets ---
    private static final int EDGE_SIZE = 16;
    private static final int EDGE_DX = 4;
    private static final int EDGE_JOIN = 8;
    private static final int EDGE_DIVIDER = 10;

    // --- un secteur : 50 octets, dans le fichier de NIVEAU ---
    private static final int ZONE_FLOOR = 2;
    private static final int ZONE_CEILING = 6;
    private static final int ZONE_UPPER_FLOOR = 10;
    private static final int ZONE_UPPER_CEILING = 14;
    private static final int ZONE_WATER = 18;
    private static final int ZONE_BRIGHTNESS = 22;
    private static final int ZONE_UPPER_BRIGHTNESS = 24;
    private static final int ZONE_CONTROL_POINT = 26;
    private static final int ZONE_AMBIENT_MASK = 28;
    private static final int ZONE_EDGE_LIST = 32;
    private static final int ZONE_POINT_COUNT = 34;
    private static final int ZONE_BACKDROP = 36;
    private static final int ZONE_ECHO = 37;
    private static final int ZONE_TEL_ZONE = 38;
    private static final int ZONE_TEL_X = 40;
    private static final int ZONE_TEL_Z = 42;
    private static final int ZONE_FLOOR_SOUND = 44;
    private static final int ZONE_UPPER_FLOOR_SOUND = 46;

    /** La liste des secteurs visibles, a la suite de l'enregistrement : tuples de 8 octets. */
    private static final int ZONE_VISIBLE = 48;
    private static final int VISIBLE_SIZE = 8;
    private static final int VISIBLE_CLIP = 2;

    // --- dans le fichier GRAPHE ---
    private static final int GRAPH_DOORS = 0;
    private static final int GRAPH_LIFTS = 4;
    /** La table qui donne, par secteur, son offset dans le fichier de niveau. */
    private static final int GRAPH_ZONE_TABLE = 16;

    // --- une porte ou un ascenseur : 36 octets, puis sa liste de murs ---
    private static final int LIFT_HEADER = 36;
    private static final int LIFT_WALL_SIZE = 10;
    /** Fin de la liste globale. */
    private static final int LIFT_END = 999;

    /** Le pas de la table de routage, en dur dans la routine d'origine. */
    private static final int ROUTE_STRIDE = 100;

    private LevelReader() {
    }

    /**
     * Lit le niveau {@code letter} depuis l'arborescence des données d'origine.
     *
     * @param mediaRoot racine des données dépackées, celle qui contient {@code levels/}
     */
    public static Level read(Path mediaRoot, char letter) throws IOException {
        Path dir = mediaRoot.resolve("levels").resolve("LEVEL_" + Character.toUpperCase(letter));
        Path bin = dir.resolve("twolev.bin");
        Path graph = dir.resolve("twolev.graph.bin");
        Path clips = dir.resolve("twolev.clips");
        return parse(new Blob(Files.readAllBytes(bin), bin.toString()),
                new Blob(Files.readAllBytes(graph), graph.toString()),
                new Blob(Files.readAllBytes(clips), clips.toString()), letter);
    }

    /**
     * Variante pour des fichiers déjà en mémoire.
     *
     * <p>Il en faut <b>deux</b>. Les secteurs vivent dans le fichier de niveau, mais c'est le
     * fichier graphe qui dit où : il porte une table donnant, pour chaque secteur, son offset
     * dans le niveau. Le jeu fait exactement cela — {@code pZone = niveau[table[zoneId]]}.
     */
    public static Level parse(Blob f, Blob graph, Blob clipData, char letter) {
        int h = HEADER;                                  // l'en-tete suit le bloc de messages
        ClipAssigner clips = new ClipAssigner(clipData);

        List<String> messages = new ArrayList<>(MESSAGE_COUNT);
        for (int i = 0; i < MESSAGE_COUNT; i++) {
            messages.add(f.text(i * MESSAGE_LENGTH, MESSAGE_LENGTH));
        }

        Level.Start s1 = new Level.Start(f.s16(h + PLR1_X), f.s16(h + PLR1_Z), f.s16(h + PLR1_ZONE));
        Level.Start s2 = new Level.Start(f.s16(h + PLR2_X), f.s16(h + PLR2_Z), f.s16(h + PLR2_ZONE));

        // --- les sommets du decor ---
        int nPoints = f.u16(h + NUM_POINTS);
        int pointsAt = f.s32(h + OFFSET_POINTS);
        List<Level.Point> points = new ArrayList<>(nPoints);
        for (int i = 0; i < nPoints; i++) {
            points.add(new Level.Point(f.s16(pointsAt + i * 4), f.s16(pointsAt + i * 4 + 2)));
        }

        // --- le maillage de navigation, juste apres l'en-tete ---
        int nControl = f.u16(h + NUM_CONTROL_POINTS);
        int controlAt = h + HEADER_SIZE;
        List<Level.ControlPoint> control = new ArrayList<>(nControl);
        for (int i = 0; i < nControl; i++) {
            int o = controlAt + i * 8;
            control.add(new Level.ControlPoint(f.s16(o), f.s16(o + 2), f.s16(o + 4), f.s16(o + 6)));
        }

        // La table de routage suit les coordonnees : un octet par couple, au pas de 100.
        int routesAt = controlAt + nControl * 8;
        int[] routes = new int[nControl * ROUTE_STRIDE];
        for (int i = 0; i < routes.length; i++) {
            routes[i] = f.u8(routesAt + i);
        }

        List<Level.Entity> entities = readEntities(f, h);

        List<Level.Zone> zones = readZones(f, graph, f.u16(h + NUM_ZONES) + 1, clips);

        // --- les aretes ---
        // Leur nombre n'est ecrit nulle part. On serait tente de le deduire des deux offsets
        // d'en-tete, mais la soustraction est NEGATIVE : les entites precedent les aretes dans
        // le fichier. Le corpus de reference prend la valeur absolue de cette difference, ce qui
        // lui fait lire deux fois trop loin -- au-dela de la table, dans les enregistrements de
        // secteur -- et lui donne des aretes qui n'existent pas.
        //
        // La vraie borne est structurelle : la table court jusqu'au PREMIER enregistrement de
        // secteur. Verifie sur les seize niveaux, elle contient toujours l'indice maximum que
        // les secteurs referencent, et rien de plus.
        int edgesAt = f.s32(h + OFFSET_EDGES);
        int firstZone = Integer.MAX_VALUE;
        for (Level.Zone z : zones) {
            firstZone = Math.min(firstZone, graph.s32(GRAPH_ZONE_TABLE + z.id() * 4));
        }
        int edgeCount = (firstZone - edgesAt) / EDGE_SIZE;
        List<Level.Edge> edges = new ArrayList<>(edgeCount);
        for (int i = 0; i < edgeCount; i++) {
            int e = edgesAt + i * EDGE_SIZE;
            edges.add(new Level.Edge(f.s16(e), f.s16(e + 2), f.s16(e + EDGE_DX),
                    f.s16(e + EDGE_DX + 2), f.s16(e + EDGE_JOIN), f.s16(e + EDGE_DIVIDER)));
        }
        // Le mot qui precede les aretes porte le secteur de sortie du niveau.
        int exitZone = f.s16(edgesAt - 2);

        List<Level.Liftable> doors = readLiftables(graph, graph.s32(GRAPH_DOORS));
        List<Level.Liftable> lifts = readLiftables(graph, graph.s32(GRAPH_LIFTS));

        return new Level(Character.toLowerCase(letter), List.copyOf(messages), s1, s2,
                List.copyOf(points), List.copyOf(control), routes, List.copyOf(entities),
                List.copyOf(edges), List.copyOf(zones), List.copyOf(doors), List.copyOf(lifts),
                clips.groups(), exitZone);
    }

    /**
     * Les secteurs. Leur offset vient du fichier graphe, leur contenu du fichier de niveau.
     *
     * <p>La liste d'arêtes est rangée <b>à la suite du secteur</b>, à un déplacement que le
     * secteur porte lui-même. Elle se lit jusqu'au marqueur -2, avec un -1 qui referme le
     * premier groupe : celui-là est le contour, le reste sert à la collision.
     */
    private static List<Level.Zone> readZones(Blob f, Blob graph, int count, ClipAssigner clips) {
        List<Level.Zone> out = new ArrayList<>(count);
        for (int id = 0; id < count; id++) {
            int at = graph.s32(GRAPH_ZONE_TABLE + id * 4);
            if (at == 0) {
                continue;
            }
            List<Integer> edgeList = new ArrayList<>();
            int list = at + f.s16(at + ZONE_EDGE_LIST);
            for (int i = 0; i < 512; i++) {
                int e = f.s16(list + i * 2);
                edgeList.add(e);
                if (e == -2) {
                    break;
                }
            }
            out.add(new Level.Zone(
                    id,
                    f.s32(at + ZONE_FLOOR),
                    f.s32(at + ZONE_CEILING),
                    f.s32(at + ZONE_UPPER_FLOOR),
                    f.s32(at + ZONE_UPPER_CEILING),
                    f.s32(at + ZONE_WATER),
                    f.s16(at + ZONE_BRIGHTNESS),
                    f.s16(at + ZONE_UPPER_BRIGHTNESS),
                    f.u8(at + ZONE_CONTROL_POINT),
                    f.u8(at + ZONE_CONTROL_POINT + 1),
                    f.s16(at + ZONE_AMBIENT_MASK),
                    f.u8(at + ZONE_BACKDROP) != 0,
                    f.u8(at + ZONE_ECHO),
                    f.s16(at + ZONE_TEL_ZONE),
                    f.s16(at + ZONE_TEL_X),
                    f.s16(at + ZONE_TEL_Z),
                    f.s16(at + ZONE_FLOOR_SOUND),
                    f.s16(at + ZONE_UPPER_FLOOR_SOUND),
                    f.u16(at + ZONE_POINT_COUNT),
                    List.copyOf(edgeList),
                    readVisible(f, at, clips)));
        }
        return out;
    }

    /**
     * Les secteurs potentiellement visibles depuis celui-ci : des couples {@code (secteur,
     * decoupe)} de 8 octets, a la suite de l'enregistrement, termines par un secteur negatif.
     *
     * <p>Le second mot n'est PAS un index : c'est un drapeau. Negatif, la vue par cette
     * ouverture est degagee ; positif, elle a un groupe de decoupe, que l'assigneur distribue.
     */
    private static List<Level.Visible> readVisible(Blob f, int zoneAt, ClipAssigner clips) {
        List<Level.Visible> out = new ArrayList<>();
        for (int at = zoneAt + ZONE_VISIBLE; ; at += VISIBLE_SIZE) {
            int zone = f.s16(at);
            if (zone < 0) {
                return List.copyOf(out);
            }
            out.add(new Level.Visible(zone,
                    f.s16(at + VISIBLE_CLIP) < 0 ? null : clips.next()));
        }
    }

    /**
     * Distribue les groupes de decoupe aux ouvertures qui en reclament un.
     *
     * <p>Le jeu ne range nulle part quelle ouverture utilise quel groupe : il s'en remet a
     * l'ORDRE. Le chargeur descend les secteurs par numero croissant, et leurs ouvertures dans
     * l'ordre de la liste ; chaque fois qu'il en trouve une marquee, il lui donne le groupe sous
     * son curseur, puis avance le curseur jusqu'apres le prochain {@code -2}. Lire les groupes
     * demande donc de refaire ce parcours dans le meme ordre, et c'est ce que fait cette classe,
     * un appel a {@link #next()} par ouverture marquee.
     *
     * <p>A l'interieur d'un groupe, un premier terminateur separe les points qui bornent la vue
     * a gauche de ceux qui la bornent a droite ; le {@code -2} ferme le tout. Un cote vide se
     * reconnait a son terminateur immediat.
     *
     * <p>Ce que le curseur laisse derriere lui, une fois tous les secteurs traites, marque la
     * fin des decoupes : la table de connexion commence la, dans le meme fichier.
     */
    private static final class ClipAssigner {

        /** Ferme un groupe entier. */
        private static final int GROUP_END = -2;

        private final Blob f;
        private final List<Level.Clip> groups = new ArrayList<>();
        /** Position du prochain groupe, en octets — le {@code d0} de la routine d'origine. */
        private int cursor;

        ClipAssigner(Blob f) {
            this.f = f;
        }

        /** Le groupe suivant, attribue a l'ouverture courante. */
        Level.Clip next() {
            int start = cursor;
            int end = start;
            while (f.s16(end) != GROUP_END) {
                end += 2;
            }
            cursor = end + 2;

            List<Integer> left = new ArrayList<>();
            int at = start;
            while (at < end && f.s16(at) >= 0) {
                left.add(f.s16(at));
                at += 2;
            }
            List<Integer> right = new ArrayList<>();
            at += 2;                                     // on passe le terminateur de gauche
            while (at < end && f.s16(at) >= 0) {
                right.add(f.s16(at));
                at += 2;
            }

            Level.Clip g = new Level.Clip(start / 2, List.copyOf(left), List.copyOf(right));
            groups.add(g);
            return g;
        }

        List<Level.Clip> groups() {
            return List.copyOf(groups);
        }
    }

    /**
     * Les portes ou les ascenseurs : une suite d'enregistrements, chacun suivi de sa liste de
     * murs. La liste de murs finit sur une arete negative, la liste globale sur une position
     * basse valant 999.
     */
    private static List<Level.Liftable> readLiftables(Blob graph, int at) {
        List<Level.Liftable> out = new ArrayList<>();
        if (at <= 0) {
            return out;
        }
        while (true) {
            int bottom = graph.s16(at);
            if (bottom == LIFT_END) {
                return List.copyOf(out);
            }
            List<Level.Liftable.Wall> walls = new ArrayList<>();
            int w = at + LIFT_HEADER;
            while (true) {
                int edge = graph.s16(w);
                if (edge < 0) {
                    break;
                }
                walls.add(new Level.Liftable.Wall(edge, graph.s32(w + 2), graph.s32(w + 6)));
                w += LIFT_WALL_SIZE;
            }
            out.add(new Level.Liftable(
                    out.size(), bottom, graph.s16(at + 2), graph.s16(at + 4), graph.s16(at + 6),
                    graph.s16(at + 8), graph.s16(at + 10), graph.s16(at + 12),
                    graph.s16(at + 14), graph.s16(at + 16), graph.s16(at + 18),
                    graph.s16(at + 20), graph.s16(at + 22), graph.s16(at + 24),
                    graph.s16(at + 30), graph.u8(at + 34), graph.u8(at + 35),
                    List.copyOf(walls)));
            at = w + 2;                              // saute le terminateur de la liste de murs
        }
    }

    /**
     * Les entités : une suite d'enregistrements de 64 octets, terminée par un index de point
     * négatif. Leur position ne vit pas dans l'enregistrement mais dans une table à part, que
     * l'index désigne — c'est elle que le moteur fait tourner pour l'affichage.
     */
    private static List<Level.Entity> readEntities(Blob f, int h) {
        int at = f.s32(h + OFFSET_OBJECTS);
        int positionsAt = f.s32(h + OFFSET_OBJECT_POINTS);
        List<Level.Entity> out = new ArrayList<>();
        for (int o = at; ; o += ENTITY_SIZE) {
            int pointIndex = f.s16(o + ENT_POINT_INDEX);
            if (pointIndex < 0) {
                break;
            }
            int p = positionsAt + (pointIndex & 0xFFFF) * 8;
            out.add(new Level.Entity(
                    Level.Kind.of(f.u8(o + ENT_TYPE_ID)),
                    f.u8(o + ENT_DEF),
                    f.s16(p),
                    f.s16(p + 4),
                    f.s16(o + ENT_ZONE),
                    f.u8(o + ENT_UPPER_ZONE) != 0,
                    f.s16(o + ENT_BRIGHTNESS),
                    f.s32(o + ENT_UNLOCKS),
                    f.s16(o + ENT_MESSAGE)));
        }
        return out;
    }
}
