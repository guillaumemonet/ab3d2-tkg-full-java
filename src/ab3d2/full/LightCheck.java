package ab3d2.full;

import ab3d2.full.level.Level;
import ab3d2.full.render.Lights;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Verifie l'ECLAIRAGE : les sept sequences de clignotement, le recalcul par image, et ce que
 * les lumieres mobiles y ajoutent.
 *
 * <p>La table est comparee DEUX fois par pose — une fois le recalcul fait, une fois les
 * lumieres posees. C'est ce qui permet de dire lequel des deux mecanismes s'ecarte, et sans
 * quoi on ne saurait que la table differe.
 *
 * <p>Troisieme comparaison, et la plus facile a oublier : que les secteurs ABSENTS du releve
 * soient restes vierges chez nous aussi. Le recalcul ne porte que sur ce que l'on regarde, et
 * ce qu'il laisse de cote compte autant que ce qu'il touche.
 */
public final class LightCheck {

    private LightCheck() {
    }

    private static int ecarts;
    private static long valeurs;

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.lights", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=lights");
            System.exit(1);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        JsonArray cas = root.getAsJsonArray("cases");

        int poses = 0;
        int secteurs = 0;
        int horsTable = 0;
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
            int frames = c.get("frames").getAsInt();
            String quoi = String.format("%c secteur %d, %d image(s)", lettre, zone, frames);
            poses++;

            Lights lights = new Lights(lvl, Data.sinCos());
            for (int n = 0; n < frames; n++) {
                lights.tick();
            }
            JsonArray oracleFlicker = c.getAsJsonArray("flicker");
            for (int n = 0; n < oracleFlicker.size(); n++) {
                if (lights.flicker(n) != oracleFlicker.get(n).getAsInt()) {
                    ecarts += note("%s : sequence %d = %d, l'oracle dit %d",
                            quoi, n, lights.flicker(n), oracleFlicker.get(n).getAsInt());
                }
            }

            lights.frame(lvl.zones().get(zone));
            secteurs += compare(lights, lvl, c.getAsJsonArray("before"),
                    quoi + ", avant les lumieres");

            for (Lumiere l : lumieres(c)) {
                if (l.angle() < 0) {
                    lights.brighten(l.bright(), l.x(), l.z(), l.zone(), l.y());
                } else {
                    lights.brightenAngle(l.bright(), l.x(), l.z(), l.zone(), l.angle(), l.y());
                }
            }
            if (lights.offTable() > 0) {
                horsTable++;
                continue;                              // voir Lights.offTable
            }
            compare(lights, lvl, c.getAsJsonArray("zones"), quoi + ", lumieres posees");
        }
        if (courant != 0) {
            ligne(courant, ecarts - avant);
        }

        System.out.printf("%n%d poses, %d secteurs eclaires, %d valeurs, %d hors table,"
                + " %d ecart(s) -> %s%n", poses, secteurs, valeurs, horsTable, ecarts,
                ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    /**
     * Confronte la table a un releve, et rend le nombre de secteurs qu'il porte.
     *
     * <p>Les secteurs que le releve ne mentionne pas doivent etre restes vierges : on le
     * verifie aussi, et c'est la moitie de ce que cette comparaison dit.
     */
    private static int compare(Lights lights, Level lvl, JsonArray releve, String quoi) {
        Set<Integer> vus = new HashSet<>();
        for (int k = 0; k < releve.size(); k++) {
            JsonObject o = releve.get(k).getAsJsonObject();
            int z = o.get("zone").getAsInt();
            vus.add(z);
            if (lights.zoneBright(z, false) != o.get("lower").getAsInt()) {
                ecarts += note("%s, secteur %d : luminosite basse = %d, l'oracle dit %d",
                        quoi, z, lights.zoneBright(z, false), o.get("lower").getAsInt());
                return releve.size();
            }
            if (lights.zoneBright(z, true) != o.get("upper").getAsInt()) {
                ecarts += note("%s, secteur %d : luminosite haute = %d, l'oracle dit %d",
                        quoi, z, lights.zoneBright(z, true), o.get("upper").getAsInt());
                return releve.size();
            }
            JsonArray points = o.getAsJsonArray("points");
            valeurs += points.size();
            for (int p = 0; p < points.size(); p++) {
                int moi = lights.pointBright(z, p / Lights.SLOTS, p % Lights.SLOTS);
                if (moi != points.get(p).getAsInt()) {
                    ecarts += note("%s, secteur %d point %d emplacement %d : %d,"
                            + " l'oracle dit %d", quoi, z, p / Lights.SLOTS, p % Lights.SLOTS,
                            moi, points.get(p).getAsInt());
                    return releve.size();
                }
            }
        }
        for (Level.Zone z : lvl.zones()) {
            if (vus.contains(z.id())) {
                continue;
            }
            boolean vierge = lights.zoneBright(z.id(), false) == 0
                    && lights.zoneBright(z.id(), true) == 0;
            for (int p = 0; vierge && p < Lights.POINTS * Lights.SLOTS; p++) {
                vierge = lights.pointBright(z.id(), p / Lights.SLOTS, p % Lights.SLOTS) == 0;
            }
            if (!vierge) {
                ecarts += note("%s : le secteur %d a ete touche, l'oracle le dit vierge",
                        quoi, z.id());
                break;
            }
        }
        return releve.size();
    }

    /** Une lumiere mobile du releve. */
    private record Lumiere(int bright, int x, int z, int zone, int y, int angle) { }

    private static List<Lumiere> lumieres(JsonObject c) {
        List<Lumiere> out = new ArrayList<>();
        for (var e : c.getAsJsonArray("lights")) {
            JsonObject o = e.getAsJsonObject();
            out.add(new Lumiere(o.get("bright").getAsInt(), o.get("x").getAsInt(),
                    o.get("z").getAsInt(), o.get("zone").getAsInt(), o.get("y").getAsInt(),
                    o.get("angle").getAsInt()));
        }
        return out;
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
