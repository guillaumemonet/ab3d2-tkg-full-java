package ab3d2.full;

import ab3d2.full.level.Level;
import ab3d2.full.render.View;
import ab3d2.full.render.Wall;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifie la projection verticale des murs contre le portage, bande par bande.
 *
 * <p>On compare les SIX valeurs de chaque bande — abscisse, texture, distance, haut, bas,
 * luminosite — et non seulement le haut et le bas. Les accumulateurs sont compares entiers, en
 * virgule fixe : la partie fractionnaire se reporte d'une colonne a la suivante, et une erreur
 * d'un bit a gauche de l'ecran se voit a droite.
 *
 * <p>Le nombre de bandes compte autant que leur contenu : il dit si le mur a ete coupe le meme
 * nombre de fois, et c'est tout le reglage de finesse qui s'y joue.
 */
public final class WallCheck {

    private WallCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.walls", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=walls");
            System.exit(1);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        JsonArray cas = root.getAsJsonArray("cases");

        View view = new View(Data.sinCos());
        Wall mur = new Wall(Data.iterations());
        int ecarts = 0;
        int poses = 0;
        int murs = 0;
        int bandes = 0;
        int horsTable = 0;
        char courant = 0;
        int ecartsNiveau = 0;
        Map<Integer, Level.Wall> parOffset = new HashMap<>();

        for (int i = 0; i < cas.size(); i++) {
            JsonObject c = cas.get(i).getAsJsonObject();
            char lettre = c.get("level").getAsString().charAt(0);
            if (lettre != courant) {
                if (courant != 0) {
                    ligne(courant, ecartsNiveau);
                    ecarts += ecartsNiveau;
                }
                courant = lettre;
                ecartsNiveau = 0;
                parOffset = index(Data.level(lettre));
            }
            Level lvl = Data.level(lettre);
            int angle = c.get("angle").getAsInt();
            int centreY = c.get("centreY").getAsInt();
            boolean fin = c.get("goodRender").getAsBoolean();
            // La hauteur de l'oeil est rangee au 256e, comme le haut et le bas des pans.
            int playerY = c.get("y").getAsInt() << 8;

            view.screen(true);
            view.update(lvl, c.get("x").getAsInt(), c.get("z").getAsInt(), angle, 0);
            poses++;

            JsonArray oracleMurs = c.getAsJsonArray("walls");
            for (int k = 0; k < oracleMurs.size(); k++) {
                JsonObject o = oracleMurs.get(k).getAsJsonObject();
                int offset = o.get("offset").getAsInt();
                Level.Wall w = parOffset.get(offset);
                murs++;
                if (w == null) {
                    ecartsNiveau += note(ecarts + ecartsNiveau,
                            "%c secteur %d : aucun pan a l'offset %d",
                            lettre, c.get("zone").getAsInt(), offset);
                    continue;
                }

                mur.draw(view, w, playerY, centreY, 0, View.FULL_WIDTH, 1, 1, fin);
                List<Wall.Strip> mine = mur.strips();
                JsonArray oracle = o.getAsJsonArray("strips");
                bandes += oracle.size();

                if (mur.outOfTable() > 0) {
                    // Un morceau plus large que la table d'iterations : l'original y lit ce qui
                    // suit en memoire, et ce qui suit n'est pas la meme chose ici que sur
                    // l'Amiga. On ne compare pas ce pan, on le COMPTE. Voir Wall.beyond.
                    horsTable++;
                    System.out.printf("    %c secteur %d angle %4d %s, pan %d :"
                            + " %d morceau(x) plus large(s) que la table, %d bandes contre %d%n",
                            lettre, c.get("zone").getAsInt(), angle, fin ? "fin " : "brut",
                            offset, mur.outOfTable(), mine.size(), oracle.size());
                    continue;
                }
                if (mine.size() != oracle.size()) {
                    ecartsNiveau += note(ecarts + ecartsNiveau, "%c secteur %d angle %4d %s,"
                            + " pan %d : %d bandes, l'oracle en donne %d",
                            lettre, c.get("zone").getAsInt(), angle, fin ? "fin " : "brut",
                            offset, mine.size(), oracle.size());
                    continue;
                }
                for (int s = 0; s < mine.size(); s++) {
                    Wall.Strip a = mine.get(s);
                    JsonArray b = oracle.get(s).getAsJsonArray();
                    int[] moi = {a.x(), a.u(), a.distance(), a.top(), a.bottom(), a.bright()};
                    for (int f = 0; f < 6; f++) {
                        if (moi[f] == b.get(f).getAsInt()) {
                            continue;
                        }
                        ecartsNiveau += note(ecarts + ecartsNiveau, "%c secteur %d angle %4d %s,"
                                + " pan %d bande %d : %s = %d, l'oracle dit %d",
                                lettre, c.get("zone").getAsInt(), angle, fin ? "fin " : "brut",
                                offset, s, CHAMPS[f], moi[f], b.get(f).getAsInt());
                        f = 6;
                        s = mine.size();
                    }
                }
            }
        }
        if (courant != 0) {
            ligne(courant, ecartsNiveau);
            ecarts += ecartsNiveau;
        }

        System.out.printf("%n%d poses, %d pans, %d bandes, %d hors table, %d ecart(s) -> %s%n",
                poses, murs, bandes, horsTable, ecarts, ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    private static final String[] CHAMPS =
            {"abscisse", "texture", "distance", "haut", "bas", "luminosite"};

    /** Les pans d'un niveau, retrouvables par leur offset dans le fichier graphe. */
    private static Map<Integer, Level.Wall> index(Level lvl) {
        Map<Integer, Level.Wall> out = new HashMap<>();
        for (Level.Zone z : lvl.zones()) {
            for (Level.Graphics g : new Level.Graphics[] {z.lower(), z.upper()}) {
                if (g == null) {
                    continue;
                }
                for (Level.Paint p : g.program()) {
                    if (p instanceof Level.Wall w) {
                        out.put(w.offset(), w);
                    }
                }
            }
        }
        return out;
    }

    private static int note(int dejaVus, String format, Object... args) {
        if (dejaVus < 10) {
            System.out.printf("    " + format + "%n", args);
        }
        return 1;
    }

    private static void ligne(char lettre, int ecarts) {
        System.out.printf("  %c : %s%n", lettre, ecarts == 0 ? "conforme" : ecarts + " ECART(S)");
    }
}
