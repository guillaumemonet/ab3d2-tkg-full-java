package ab3d2.full;

import ab3d2.full.level.Level;
import ab3d2.full.level.WallTexture;
import ab3d2.full.render.GouraudWall;
import ab3d2.full.render.GouraudWallPaint;
import ab3d2.full.render.Screen;
import ab3d2.full.render.View;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifie le PLACAGE des murs DEGRADES : les pixels, et ce qui les a decides.
 *
 * <p>Le pendant de {@link WallPaintCheck} pour l'autre rastériseur. Neuf valeurs par colonne
 * cette fois — ou elle commence, sur quelle hauteur, ou commence le degrade et de combien il
 * avance, le champ de texel, le bloc de texture, le point d'entree et le pas — puis les OCTETS
 * relus dans le tampon.
 *
 * <p>Les octets sont ici plus probants encore que pour le rendu simple : ils sont les seuls a
 * eprouver que la rampe change bien en descendant la colonne, et au bon rythme. Les neuf
 * valeurs ne disent que le depart et la pente.
 *
 * <p>Les QUATRE luminosites de coin viennent de l'oracle, comme les deux de
 * {@link WallPaintCheck} : l'eclairage est verifie ailleurs.
 */
public final class GouraudWallPaintCheck {

    private GouraudWallPaintCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.gourwallpaint", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=gourwallpaint");
            System.exit(1);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        JsonArray cas = root.getAsJsonArray("cases");

        View view = new View(Data.sinCos());
        GouraudWall mur = new GouraudWall(Data.iterations());
        Screen screen = new Screen();
        GouraudWallPaint paint = new GouraudWallPaint(screen);
        Base64.Decoder decodeur = Base64.getDecoder();

        int ecarts = 0;
        int poses = 0;
        int murs = 0;
        int colonnes = 0;
        long pixels = 0;
        int horsTable = 0;
        char courant = 0;
        int ecartsNiveau = 0;
        Map<Integer, Level.Wall> parOffset = new HashMap<>();
        List<WallTexture> textures = List.of();

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
                textures = Data.walls(lettre);
            }
            Level lvl = Data.level(lettre);
            int zone = c.get("zone").getAsInt();
            int angle = c.get("angle").getAsInt();
            int centreY = c.get("centreY").getAsInt();
            int playerY = c.get("y").getAsInt() << 8;

            view.screen(true);
            view.update(lvl, c.get("x").getAsInt(), c.get("z").getAsInt(), angle, 0);
            paint.eye(0, Screen.FULL_HEIGHT, 0, true);
            poses++;

            JsonArray oracleMurs = c.getAsJsonArray("walls");
            for (int k = 0; k < oracleMurs.size(); k++) {
                JsonObject o = oracleMurs.get(k).getAsJsonObject();
                int offset = o.get("offset").getAsInt();
                Level.Wall w = parOffset.get(offset);
                murs++;
                if (w == null) {
                    ecartsNiveau += note(ecarts + ecartsNiveau,
                            "%c secteur %d : aucun pan a l'offset %d", lettre, zone, offset);
                    continue;
                }
                WallTexture peau = w.texture() < textures.size() ? textures.get(w.texture())
                        : null;
                if (peau == null) {
                    ecartsNiveau += note(ecarts + ecartsNiveau,
                            "%c secteur %d, pan %d : aucune texture au rang %d",
                            lettre, zone, offset, w.texture());
                    continue;
                }

                mur.draw(view, w, playerY, centreY, 0, View.FULL_WIDTH,
                        o.get("leftBright").getAsInt(), o.get("rightBright").getAsInt(),
                        o.get("leftUpperBright").getAsInt(),
                        o.get("rightUpperBright").getAsInt(), true);
                if (mur.outOfTable() > 0) {
                    horsTable++;
                    continue;                          // voir Wall.beyond : pan non comparable
                }
                // draw_WallYOffset_w : le calage vertical sur la hauteur d oeil. Ce releve
                // le forcait a ZERO, et la conversion aussi — d ou un trou qui ne se voyait
                // qu en jouant. Il varie desormais des deux cotes.
                paint.wall(w, peau, ((playerY >> 8) + 224) & 255);
                paint.clearOffTable();

                List<GouraudWallPaint.Column> mine = new ArrayList<>();
                for (GouraudWall.Strip s : mur.strips()) {
                    GouraudWallPaint.Column col = paint.paint(s);
                    if (col != null) {
                        mine.add(col);
                    }
                }
                JsonArray oracle = o.getAsJsonArray("strips");
                colonnes += oracle.size();

                if (paint.offTable() > 0) {
                    horsTable++;
                    System.out.printf("    %c secteur %d angle %4d, pan %d :"
                            + " %d lecture(s) hors table, %d colonnes contre %d%n",
                            lettre, zone, angle, offset, paint.offTable(),
                            mine.size(), oracle.size());
                    continue;
                }
                if (mine.size() != oracle.size()) {
                    ecartsNiveau += note(ecarts + ecartsNiveau, "%c secteur %d angle %4d,"
                            + " pan %d : %d colonnes peintes, l'oracle en donne %d",
                            lettre, zone, angle, offset, mine.size(), oracle.size());
                    continue;
                }

                boolean casse = false;
                for (int s = 0; s < mine.size() && !casse; s++) {
                    GouraudWallPaint.Column a = mine.get(s);
                    JsonArray b = oracle.get(s).getAsJsonArray();
                    int[] moi = {a.y(), a.x(), a.height(), a.start(), a.step(), a.field(),
                            a.chunk() - WallTexture.CHUNK, a.position(), a.vstep(),
                            w.heightMask()};
                    for (int f = 0; f < moi.length && !casse; f++) {
                        if (moi[f] != b.get(f).getAsInt()) {
                            ecartsNiveau += note(ecarts + ecartsNiveau, "%c secteur %d angle"
                                    + " %4d, pan %d colonne %d : %s = %d, l'oracle dit %d",
                                    lettre, zone, angle, offset, s, CHAMPS[f], moi[f],
                                    b.get(f).getAsInt());
                            casse = true;
                        }
                    }
                    if (casse) {
                        break;
                    }
                    byte[] attendu = decodeur.decode(b.get(10).getAsString());
                    pixels += attendu.length;
                    for (int p = 0; p < attendu.length; p++) {
                        byte eu = screen.pixels()[screen.line(a.y() + p) + a.x()];
                        if (eu != attendu[p]) {
                            ecartsNiveau += note(ecarts + ecartsNiveau, "%c secteur %d angle"
                                    + " %4d, pan %d colonne %d pixel %d : %d, l'oracle dit %d",
                                    lettre, zone, angle, offset, s, p, eu & 0xFF,
                                    attendu[p] & 0xFF);
                            casse = true;
                            break;
                        }
                    }
                }
            }
        }
        if (courant != 0) {
            ligne(courant, ecartsNiveau);
            ecarts += ecartsNiveau;
        }

        System.out.printf("%n%d poses, %d pans, %d colonnes, %d pixels, %d hors table,"
                + " %d ecart(s) -> %s%n", poses, murs, colonnes, pixels, horsTable, ecarts,
                ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    private static final String[] CHAMPS = {"ligne", "abscisse", "hauteur", "depart du degrade",
        "pas du degrade", "champ", "bloc", "position", "pas de texture", "masque de hauteur"};

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
