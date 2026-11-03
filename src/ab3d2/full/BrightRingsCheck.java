package ab3d2.full;

import ab3d2.full.level.Level;
import ab3d2.full.render.BrightRings;
import ab3d2.full.render.Lights;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifie les ANNEAUX DE LUMIERE : les soixante-quatre octets de la boussole, pour plusieurs
 * positions dans plusieurs secteurs de chaque niveau.
 *
 * <p>L'eclairage est seme d'abord — les clignotements avancent, la table est refaite, les
 * lumieres mobiles sont posees — exactement comme l'oracle l'a fait. C'est la table qui nourrit
 * la boussole : sans elle les seize rangs ne diraient rien.
 *
 * <h2>Ce qui est ecarte, et par qui</h2>
 *
 * <p>Le jeu ne verifie aucun de ses indices : une liste de points de bord sans fin continue
 * dans le secteur suivant, et au-dela du dernier secteur elle lit ce qui trainait la. Ces
 * cas-la ne sont pas comparables, et la decision de les ecarter est prise ICI, en refaisant le
 * parcours — jamais d'apres ce que la routine eprouvee a compte. C'est la lecon du melange
 * additif et du miroitement : une faute ne doit pas pouvoir prononcer son propre acquittement.
 */
public final class BrightRingsCheck {

    /** Les soixante-quatre octets de la boussole. */
    private static final int ANNEAUX = 64;

    private BrightRingsCheck() {
    }

    private static int ecarts;

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.brightrings", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=brightrings");
            System.exit(1);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        JsonArray cas = root.getAsJsonArray("cases");

        int poses = 0;
        long octets = 0;
        int horsTable = 0;
        int vides = 0;
        int repris = 0;
        char courant = 0;
        int avant = 0;

        for (int i = 0; i < cas.size(); i++) {
            JsonObject c = cas.get(i).getAsJsonObject();
            char lettre = c.get("level").getAsString().charAt(0);
            if (lettre != courant) {
                if (courant != 0) {
                    ligne(courant, ecarts - avant);
                }
                courant = lettre;
                avant = ecarts;
            }
            Level lvl = Data.level(lettre);
            int zone = c.get("zone").getAsInt();

            Lights lights = new Lights(lvl, Data.sinCos());
            for (int n = 0; n < c.get("frames").getAsInt(); n++) {
                lights.tick();
            }
            lights.frame(lvl.zones().get(zone));
            for (var e : c.getAsJsonArray("lights")) {
                JsonObject o = e.getAsJsonObject();
                int angle = o.get("angle").getAsInt();
                if (angle < 0) {
                    lights.brighten(o.get("bright").getAsInt(), o.get("x").getAsInt(),
                            o.get("z").getAsInt(), o.get("zone").getAsInt(),
                            o.get("y").getAsInt());
                } else {
                    lights.brightenAngle(o.get("bright").getAsInt(), o.get("x").getAsInt(),
                            o.get("z").getAsInt(), o.get("zone").getAsInt(), angle,
                            o.get("y").getAsInt());
                }
            }
            if (lights.offTable() > 0) {
                horsTable++;
                continue;                              // voir Lights.offTable
            }

            if (horsDesTables(lvl, zone)) {
                horsTable++;
                continue;
            }

            for (var e : c.getAsJsonArray("placed")) {
                JsonObject o = e.getAsJsonObject();
                int x = o.get("x").getAsInt();
                int z = o.get("z").getAsInt();
                boolean haut = o.get("upper").getAsBoolean();
                // Une boussole neuve par pose : l'oracle remet le dernier angle a zero.
                BrightRings rings = new BrightRings(lvl, lights, Data.sinCos());
                rings.calc(zone, haut, x, z);
                vides += rings.emptyRings();
                repris += rings.stale();
                poses++;

                JsonArray attendu = o.getAsJsonArray("rings");
                octets += ANNEAUX;
                for (int k = 0; k < ANNEAUX; k++) {
                    int moi = rings.bright(k / BrightRings.RING, k % BrightRings.RING);
                    if (moi != attendu.get(k).getAsInt()) {
                        ecarts += note("%c secteur %d, (%d, %d)%s : anneau %d rang %d = %d,"
                                + " l'oracle dit %d", lettre, zone, x, z, haut ? " haut" : "",
                                k / BrightRings.RING, k % BrightRings.RING, moi,
                                attendu.get(k).getAsInt());
                        break;
                    }
                }
            }
        }
        if (courant != 0) {
            ligne(courant, ecarts - avant);
        }

        System.out.printf("%n%d cas, %d poses, %d octets, %d hors table, %d anneau(x) vide(s),"
                + " %d angle(s) repris, %d ecart(s) -> %s%n", cas.size(), poses, octets,
                horsTable, vides, repris, ecarts, ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    /**
     * Vrai si la boussole de ce secteur lirait hors des tables du jeu.
     *
     * <p>Refait le parcours : les aretes du contour, puis, pour le secteur lui-meme et pour
     * chacun de ses voisins, la liste des points de bord et le rang de luminosite qui l'y
     * accompagne. C'est une copie deliberee de {@code BrightRings}, et c'est tout l'interet :
     * la decision d'ecarter ne doit rien devoir au code qu'on eprouve.
     */
    private static boolean horsDesTables(Level lvl, int zone) {
        if (horsDuSecteur(lvl, zone)) {
            return true;
        }
        for (int e : lvl.zones().get(zone).outline()) {
            int off = (short) (e << 4);
            if ((off >> 4) != e || e >= lvl.edges().size()) {
                return true;
            }
            int join = lvl.edges().get(e).joinZone();
            if (join >= 0 && (join >= lvl.zones().size() || horsDuSecteur(lvl, join))) {
                return true;
            }
        }
        return false;
    }

    /** Vrai si la liste de points de bord d'un secteur sort des tables. */
    private static boolean horsDuSecteur(Level lvl, int zone) {
        int bo = zone * Lights.POINTS;
        int bp = zone * Lights.POINTS * Lights.SLOTS;
        int fin = lvl.zones().size() * Lights.POINTS;
        while (true) {
            if (bo >= fin) {
                return true;
            }
            int p = pointDeBord(lvl, bo++);
            if (p < 0) {
                return false;
            }
            if (p >= lvl.points().size()
                    || bp + 1 >= lvl.zones().size() * Lights.POINTS * Lights.SLOTS) {
                return true;
            }
            bp += Lights.SLOTS;
        }
    }

    /** Le numero de point a un rang de la table MISE A PLAT. */
    private static int pointDeBord(Level lvl, int at) {
        var pts = lvl.zones().get(at / Lights.POINTS).borderPoints();
        int k = at % Lights.POINTS;
        return k < pts.size() ? pts.get(k) : 0;
    }

    private static int note(String format, Object... args) {
        if (ecarts < 10) {
            System.out.printf("    " + format + "%n", args);
        }
        return 1;
    }

    private static void ligne(char lettre, int combien) {
        System.out.printf("  %c : %s%n", lettre, combien == 0 ? "conforme" : combien + " ECART(S)");
    }
}
