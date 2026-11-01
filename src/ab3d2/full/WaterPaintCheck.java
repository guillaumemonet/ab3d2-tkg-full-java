package ab3d2.full;

import ab3d2.full.level.Level;
import ab3d2.full.render.Flat;
import ab3d2.full.render.FlatPaint;
import ab3d2.full.render.Screen;
import ab3d2.full.render.View;
import ab3d2.full.render.Water;

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
 * Verifie le PLACAGE DE L'EAU : la surface qui ne se peint pas mais se reflete.
 *
 * <p>Dix valeurs par tranche — la ligne, l'abscisse de depart, la largeur, le miroitement, le
 * decalage du reflet, l'image d'ondulation en cours, la position dans cette image et son pas,
 * la distance et les lignes restantes — puis les OCTETS relus dans le tampon.
 *
 * <p>Cette verification a une contrainte que les autres n'ont pas : la boucle de l'eau LIT le
 * tampon. Elle n'a donc de sens que si le tampon est le meme des deux cotes. On y pose le meme
 * motif que l'oracle, au meme moment, et les tranches se peignent dans le meme ordre — chacune
 * pouvant relire ce qu'une precedente a ecrit.
 *
 * <p>Le corpus est petit, et pas par economie : l'eau est RARE. Quinze secteurs en portent sur
 * les deux mille cinq cents que comptent les seize niveaux, et on les prend tous.
 */
public final class WaterPaintCheck {

    /** La hauteur du tampon, celle que l'oracle remplit aussi. */
    private static final int HAUTEUR = 256;

    private WaterPaintCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.waterpaint", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=waterpaint");
            System.exit(1);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        JsonArray cas = root.getAsJsonArray("cases");

        View view = new View(Data.sinCos());
        Flat flat = new Flat();
        Screen screen = new Screen();
        FlatPaint paint = new FlatPaint(screen);
        Water anim = new Water();
        paint.water(Data.water(), anim, Data.sinCos());
        Base64.Decoder decodeur = Base64.getDecoder();

        int ecarts = 0;
        int poses = 0;
        int battements = 0;
        int surfaces = 0;
        int tranches = 0;
        long pixels = 0;
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

            // L'eau bat a chaque image, meme celles ou l'on n'en voit pas : on rattrape le
            // compte de l'oracle plutot que de battre une fois par pose relevee.
            while (battements < c.get("ticks").getAsInt()) {
                anim.tick();
                battements++;
            }
            motif(screen);
            if (anim.start() != c.get("frame").getAsInt()
                    || anim.angle() != c.get("angle0").getAsInt()
                    || anim.offset() != c.get("offset").getAsInt()) {
                ecartsNiveau += note(ecarts + ecartsNiveau, "%c secteur %d : l'animation dit"
                        + " image %d angle %d decalage %d, l'oracle dit %d %d %d",
                        lettre, zone, anim.start(), anim.angle(), anim.offset(),
                        c.get("frame").getAsInt(), c.get("angle0").getAsInt(),
                        c.get("offset").getAsInt());
            }

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

                paint.flat(f, null, Data.shades());
                flat.draw(f);
                if (flat.offTable() > 0) {
                    horsTable++;
                    continue;                          // voir Flat : contour hors des tables
                }
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
                    FlatPaint.Reflection fait = paint.paintWater(a, flat.above());
                    if (fait == null) {
                        horsTable++;
                        continue;                      // voir FlatPaint.offTable
                    }
                    JsonArray b = oracle.get(s).getAsJsonArray();
                    int[] moi = {a.y(), a.left(), a.width(), fait.glare(), fait.lift(),
                        anim.start(), a.texture() + anim.offset(), a.step(), a.distance(),
                        a.toBottom()};
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
                    byte[] attendu = decodeur.decode(b.get(10).getAsString());
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

    /**
     * Le motif de depart du tampon.
     *
     * <p>Tous les rangs de palette y paraissent, pour que la rampe de miroitement soit eprouvee
     * de bout en bout ; et deux lignes voisines different, pour que le decalage du reflet se
     * voie.
     */
    private static void motif(Screen screen) {
        byte[] pixels = screen.pixels();
        for (int y = 0; y < HAUTEUR; y++) {
            for (int x = 0; x < Screen.WIDTH; x++) {
                pixels[y * Screen.WIDTH + x] = (byte) ((x * 7 + y * 13) & 0xFF);
            }
        }
    }

    private static final String[] CHAMPS = {"ligne", "gauche", "largeur", "miroitement",
        "decalage du reflet", "image d'ondulation", "position", "pas", "distance",
        "lignes restantes"};

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
