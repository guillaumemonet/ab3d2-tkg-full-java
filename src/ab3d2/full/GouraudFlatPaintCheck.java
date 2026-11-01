package ab3d2.full;

import ab3d2.full.level.FloorTexture;
import ab3d2.full.level.Level;
import ab3d2.full.level.ShadeTable;
import ab3d2.full.render.Flat;
import ab3d2.full.render.FlatPaint;
import ab3d2.full.render.Screen;
import ab3d2.full.render.View;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifie le PLACAGE des sols et des plafonds a l'ombrage INTERPOLE.
 *
 * <p>Le pendant de {@link FlatPaintCheck} pour l'autre eclairage. Neuf valeurs par tranche — la
 * ligne, l'abscisse de depart, la largeur, le DEPART de la luminosite et son PAS, le bloc
 * d'ombrage, la tuile, la position dans la texture et son pas — puis les OCTETS relus dans le
 * tampon.
 *
 * <p>Le depart et le pas sont ce que tout le maillon produit : ils resument le trace du
 * contour, l'interpolation le long des aretes, le recadrage de la ligne et la part que la
 * distance y prend. Les octets, eux, eprouvent que la rangee d'ombrage change bien d'un pixel
 * au suivant.
 *
 * <p>Les luminosites de sommets viennent de l'oracle, qui les a semees : {@link
 * ab3d2.full.render.Lights} est verifie ailleurs, et ce qui se verifie ici est ce qu'on en
 * fait.
 */
public final class GouraudFlatPaintCheck {

    private GouraudFlatPaintCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.gourflatpaint", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=gourflatpaint");
            System.exit(1);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        JsonArray graine = root.getAsJsonArray("pointBrights");
        int[] pointBrights = new int[graine.size()];
        for (int i = 0; i < pointBrights.length; i++) {
            pointBrights[i] = graine.get(i).getAsInt();
        }
        JsonArray cas = root.getAsJsonArray("cases");

        View view = new View(Data.sinCos());
        Flat flat = new Flat();
        Screen screen = new Screen();
        FlatPaint paint = new FlatPaint(screen);
        ShadeTable shades = Data.shades();
        Base64.Decoder decodeur = Base64.getDecoder();
        flat.gouraud(true);

        int ecarts = 0;
        int poses = 0;
        int surfaces = 0;
        int tranches = 0;
        long pixels = 0;
        int horsTable = 0;
        char courant = 0;
        int ecartsNiveau = 0;
        Map<Integer, Level.Flat> parOffset = new HashMap<>();
        FloorTexture tuiles = null;

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
                tuiles = Data.floors(lettre);
            }
            Level lvl = Data.level(lettre);
            int zone = c.get("zone").getAsInt();
            int x = c.get("x").getAsInt();
            int z = c.get("z").getAsInt();
            int y = c.get("y").getAsInt();
            int angle = c.get("angle").getAsInt();

            view.screen(true);
            view.update(lvl, x, z, angle, 0);
            flat.eye(view, x, z, y, 0, 0, Data.sinCos().sin(angle), Data.sinCos().cos(angle),
                    c.get("centreY").getAsInt(), 0);
            flat.room(c.get("roof").getAsInt(), c.get("floor").getAsInt(), 0,
                    0, View.FULL_WIDTH, true);
            flat.water(true);
            flat.clearWaterTint();
            poses++;

            JsonArray oracleFlats = c.getAsJsonArray("flats");
            for (int k = 0; k < oracleFlats.size(); k++) {
                JsonObject o = oracleFlats.get(k).getAsJsonObject();
                int offset = o.get("offset").getAsInt();
                Level.Flat f = parOffset.get(offset);
                surfaces++;
                if (f == null) {
                    ecartsNiveau += note(ecarts + ecartsNiveau,
                            "%c secteur %d : aucune surface a l'offset %d", lettre, zone, offset);
                    continue;
                }

                flat.brights(pointBrights, o.get("brights").getAsInt());
                flat.draw(f);
                if (flat.offTable() > 0) {
                    horsTable++;
                    continue;                          // voir Flat : contour hors des tables
                }
                paint.flat(f, tuiles, shades);
                paint.clearOffTable();

                List<Flat.Span> mine = flat.spans();
                JsonArray oracle = o.getAsJsonArray("spans");
                tranches += oracle.size();
                if (mine.size() != oracle.size()) {
                    ecartsNiveau += note(ecarts + ecartsNiveau, "%c secteur %d angle %4d y %6d,"
                            + " surface %d : %d tranches, l'oracle en donne %d",
                            lettre, zone, angle, y, offset, mine.size(), oracle.size());
                    continue;
                }

                boolean casse = false;
                for (int s = 0; s < mine.size() && !casse; s++) {
                    Flat.Span a = mine.get(s);
                    int ecrits = paint.paintGouraud(a);
                    if (ecrits < 0) {
                        horsTable++;
                        continue;                      // voir FlatPaint.offTable
                    }
                    JsonArray b = oracle.get(s).getAsJsonArray();
                    int[] moi = {a.y(), a.left(), a.width(), a.leftBright(), a.brightSpeed(),
                        ShadeTable.FLOOR, f.tile(), a.texture(), a.step()};
                    for (int ch = 0; ch < moi.length && !casse; ch++) {
                        if (moi[ch] != b.get(ch).getAsInt()) {
                            ecartsNiveau += note(ecarts + ecartsNiveau, "%c secteur %d angle"
                                    + " %4d y %6d, surface %d tranche %d : %s = %d,"
                                    + " l'oracle dit %d", lettre, zone, angle, y, offset, s,
                                    CHAMPS[ch], moi[ch], b.get(ch).getAsInt());
                            casse = true;
                        }
                    }
                    if (casse) {
                        break;
                    }
                    byte[] attendu = decodeur.decode(b.get(9).getAsString());
                    pixels += attendu.length;
                    for (int p = 0; p < attendu.length; p++) {
                        byte eu = screen.pixels()[screen.line(a.y()) + a.left() + p];
                        if (eu != attendu[p]) {
                            ecartsNiveau += note(ecarts + ecartsNiveau, "%c secteur %d angle"
                                    + " %4d y %6d, surface %d tranche %d pixel %d : %d,"
                                    + " l'oracle dit %d", lettre, zone, angle, y, offset, s, p,
                                    eu & 0xFF, attendu[p] & 0xFF);
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

        System.out.printf("%n%d poses, %d surfaces, %d tranches, %d pixels, %d hors table,"
                + " %d ecart(s) -> %s%n", poses, surfaces, tranches, pixels, horsTable, ecarts,
                ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    private static final String[] CHAMPS = {"ligne", "gauche", "largeur", "depart du degrade",
        "pas du degrade", "bloc d'ombrage", "tuile", "position", "pas"};

    /** Les surfaces d'un niveau, retrouvables par leur offset dans le fichier graphe. */
    private static Map<Integer, Level.Flat> index(Level lvl) {
        Map<Integer, Level.Flat> out = new HashMap<>();
        for (Level.Zone z : lvl.zones()) {
            for (Level.Graphics g : new Level.Graphics[] {z.lower(), z.upper()}) {
                if (g == null) {
                    continue;
                }
                for (Level.Paint p : g.program()) {
                    if (p instanceof Level.Flat f) {
                        out.put(f.offset(), f);
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
