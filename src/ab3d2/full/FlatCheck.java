package ab3d2.full;

import ab3d2.full.level.Level;
import ab3d2.full.render.Flat;
import ab3d2.full.render.View;

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
 * Verifie la projection des sols, des plafonds et des plans d'eau, tranche par tranche.
 *
 * <p>On compare les SEPT valeurs de chaque tranche, et non seulement ses deux bords : la
 * distance, le rang d'ombrage et les deux accumulateurs de texture en font partie. Les
 * accumulateurs se comparent entiers, en virgule fixe, parce que leur partie fractionnaire se
 * reporte d'un pixel au suivant.
 *
 * <p>Le nombre de tranches compte autant que leur contenu : il dit si le contour a ete trace sur
 * les memes lignes d'ecran, et c'est tout le decoupage vertical qui s'y joue.
 *
 * <p>La teinte d'eau plein ecran se verifie aussi : elle ne s'arme qu'en RENONCANT a dessiner,
 * c'est-a-dire exactement dans les cas qui ne produisent aucune tranche.
 */
public final class FlatCheck {

    private FlatCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.flats", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=flats");
            System.exit(1);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        JsonArray cas = root.getAsJsonArray("cases");

        View view = new View(Data.sinCos());
        Flat flat = new Flat();
        int ecarts = 0;
        int poses = 0;
        int surfaces = 0;
        int tranches = 0;
        int horsTable = 0;
        char courant = 0;
        int ecartsNiveau = 0;
        Map<Integer, Level.Flat> parOffset = new HashMap<>();

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
            int zone = c.get("zone").getAsInt();
            int x = c.get("x").getAsInt();
            int z = c.get("z").getAsInt();
            int y = c.get("y").getAsInt();
            int angle = c.get("angle").getAsInt();
            int centreY = c.get("centreY").getAsInt();

            view.screen(true);
            view.update(lvl, x, z, angle, 0);
            flat.eye(view, x, z, y, 0, 0,
                    Data.sinCos().sin(angle), Data.sinCos().cos(angle), centreY, 0);
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

                flat.draw(f);
                List<Flat.Span> mine = flat.spans();
                JsonArray oracle = o.getAsJsonArray("spans");
                tranches += oracle.size();

                if (flat.offTable() > 0) {
                    horsTable++;
                    System.out.printf("    %c secteur %d angle %4d y %6d, surface %d :"
                            + " %d ligne(s) hors des tables, %d tranches contre %d%n",
                            lettre, zone, angle, y, offset, flat.offTable(),
                            mine.size(), oracle.size());
                    continue;
                }
                if (mine.size() != oracle.size()) {
                    ecartsNiveau += note(ecarts + ecartsNiveau, "%c secteur %d angle %4d y %6d,"
                            + " surface %d : %d tranches, l'oracle en donne %d",
                            lettre, zone, angle, y, offset, mine.size(), oracle.size());
                    continue;
                }
                for (int s = 0; s < mine.size(); s++) {
                    Flat.Span a = mine.get(s);
                    JsonArray b = oracle.get(s).getAsJsonArray();
                    int[] moi = {a.y(), a.left(), a.right(), a.distance(), a.bright(),
                            a.texture(), a.step()};
                    for (int ch = 0; ch < moi.length; ch++) {
                        if (moi[ch] == b.get(ch).getAsInt()) {
                            continue;
                        }
                        ecartsNiveau += note(ecarts + ecartsNiveau, "%c secteur %d angle %4d"
                                + " y %6d, surface %d tranche %d : %s = %d, l'oracle dit %d",
                                lettre, zone, angle, y, offset, s, CHAMPS[ch], moi[ch],
                                b.get(ch).getAsInt());
                        ch = moi.length;
                        s = mine.size();
                    }
                }
            }

            int tint = c.get("tint").getAsInt();
            if (flat.waterTint() != tint) {
                ecartsNiveau += note(ecarts + ecartsNiveau, "%c secteur %d angle %4d y %6d :"
                        + " teinte d'eau = %d, l'oracle dit %d",
                        lettre, zone, angle, y, flat.waterTint(), tint);
            }
        }
        if (courant != 0) {
            ligne(courant, ecartsNiveau);
            ecarts += ecartsNiveau;
        }

        System.out.printf("%n%d poses, %d surfaces, %d tranches, %d hors table, %d ecart(s)"
                + " -> %s%n", poses, surfaces, tranches, horsTable, ecarts,
                ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    private static final String[] CHAMPS =
            {"ligne", "gauche", "droite", "distance", "luminosite", "texture", "pas"};

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
