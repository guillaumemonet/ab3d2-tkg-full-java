package ab3d2.full;

import ab3d2.full.defs.Database;
import ab3d2.full.defs.DatabaseReader;
import ab3d2.full.level.Level;
import ab3d2.full.level.LevelReader;
import ab3d2.full.render.EdgePvs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Ou trouver les donnees du jeu, et comment les ouvrir.
 *
 * <p>Deux racines, pour deux provenances. Les niveaux et la base de liaison viennent des
 * disquettes, depackees ailleurs, et leur chemin se regle par {@code ab3d2.media}. Les tables
 * que l'assembleur incorporait dans l'executable — la table de sinus notamment — vivent avec le
 * depot, sous {@code data/}.
 *
 * <p>La base et la table de sinus sont lues une fois : elles ne dependent d'aucun niveau et rien
 * ne les modifie.
 */
public final class Data {

    private static final Map<String, Level> LEVELS = new HashMap<>();
    private static final Map<String, EdgePvs> EDGE_PVS = new HashMap<>();
    private static Database database;
    private static SinCos sinCos;

    private Data() {
    }

    /** La racine des donnees depackees des disquettes. */
    public static Path media() {
        return Path.of(System.getProperty("ab3d2.media", "medias/original"));
    }

    /** La racine des tables livrees avec le depot. */
    public static Path local() {
        return Path.of(System.getProperty("ab3d2.data", "data"));
    }

    /** Un niveau, designe par sa lettre. */
    public static Level level(char letter) {
        return LEVELS.computeIfAbsent(String.valueOf(Character.toLowerCase(letter)), k -> {
            try {
                return LevelReader.read(media(), k.charAt(0));
            } catch (IOException e) {
                throw new UncheckedIOException("niveau " + k + " illisible", e);
            }
        });
    }

    /**
     * Le decoupage de la visibilite par ouverture, pour un niveau.
     *
     * <p>Il se deduit du niveau seul et ne change jamais, mais il coute une poignee de
     * millisecondes a etablir : on le garde comme le niveau lui-meme.
     */
    public static EdgePvs edgePvs(char letter) {
        return EDGE_PVS.computeIfAbsent(String.valueOf(Character.toLowerCase(letter)),
                k -> new EdgePvs(level(k.charAt(0))));
    }

    /** La base de liaison. */
    public static synchronized Database database() {
        if (database == null) {
            try {
                database = DatabaseReader.read(media());
            } catch (IOException e) {
                throw new UncheckedIOException("base de liaison illisible", e);
            }
        }
        return database;
    }

    /** La table de sinus. */
    public static synchronized SinCos sinCos() {
        if (sinCos == null) {
            sinCos = SinCos.read(local());
        }
        return sinCos;
    }
}
