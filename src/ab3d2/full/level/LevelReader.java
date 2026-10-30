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
    private static final int OFFSET_POINTS = 22;
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
        Path f = mediaRoot.resolve("levels")
                .resolve("LEVEL_" + Character.toUpperCase(letter))
                .resolve("twolev.bin");
        return parse(new Blob(Files.readAllBytes(f), f.toString()), letter);
    }

    /** Variante pour un fichier déjà en mémoire. */
    public static Level parse(Blob f, char letter) {
        int h = HEADER;                                  // l'en-tete suit le bloc de messages

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

        return new Level(Character.toLowerCase(letter), List.copyOf(messages), s1, s2,
                List.copyOf(points), List.copyOf(control), routes, List.copyOf(entities));
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
