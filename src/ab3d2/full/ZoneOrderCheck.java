package ab3d2.full;

import ab3d2.full.level.Level;
import ab3d2.full.render.ZoneOrder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifie l'ordre de dessin des secteurs contre le portage.
 *
 * <p>C'est une comparaison exigeante : l'ordre est une PERMUTATION, et il suffit qu'un secteur
 * soit une place trop loin pour que l'image soit fausse. On compare donc la suite entiere, et
 * non seulement sa longueur ou son contenu.
 */
public final class ZoneOrderCheck {

    private ZoneOrderCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.zoneorder", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=zoneorder");
            System.exit(1);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        JsonArray cas = root.getAsJsonArray("cases");

        ZoneOrder tri = new ZoneOrder();
        int ecarts = 0;
        int poses = 0;
        int secteurs = 0;
        char courant = 0;
        int ecartsNiveau = 0;

        for (int i = 0; i < cas.size(); i++) {
            JsonObject c = cas.get(i).getAsJsonObject();
            char lettre = c.get("level").getAsString().charAt(0);
            if (lettre != courant) {
                if (courant != 0) {
                    ligne(courant, ecartsNiveau);
                }
                courant = lettre;
                ecartsNiveau = 0;
            }
            Level lvl = Data.level(lettre);
            Level.Zone from = lvl.zones().get(c.get("zone").getAsInt());

            tri.reset();                               // chaque pose repart a neuf
            tri.update(lvl, from, c.get("x").getAsInt(), c.get("z").getAsInt());
            int[] mine = tri.order();
            JsonArray oracle = c.getAsJsonArray("order");
            poses++;
            secteurs += oracle.size();

            boolean bon = mine.length == oracle.size();
            if (bon) {
                for (int k = 0; k < mine.length; k++) {
                    if (mine[k] != oracle.get(k).getAsInt()) {
                        bon = false;
                        break;
                    }
                }
            }
            if (bon) {
                continue;
            }
            ecarts++;
            ecartsNiveau++;
            if (ecarts <= 6) {
                System.out.printf("  %c depuis le secteur %d :%n    attendu %s%n    obtenu  %s%n",
                        lettre, c.get("zone").getAsInt(), oracle, java.util.Arrays.toString(mine));
            }
        }
        if (courant != 0) {
            ligne(courant, ecartsNiveau);
        }

        System.out.printf("%n%d poses, %d secteurs ordonnes, %d ecart(s) -> %s%n",
                poses, secteurs, ecarts, ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    private static void ligne(char lettre, int ecarts) {
        System.out.printf("  %c : %s%n", lettre, ecarts == 0 ? "conforme" : ecarts + " ECART(S)");
    }
}
