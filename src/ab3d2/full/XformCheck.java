package ab3d2.full;

import ab3d2.full.level.Level;
import ab3d2.full.render.View;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifie la transformation de vue contre le portage, point par point.
 *
 * <p>Les autres verifications confrontent des DONNEES ; celle-ci confronte un CALCUL. L'oracle
 * est produit en faisant tourner la routine du portage elle-meme, pour des poses choisies : dix
 * angles qui couvrent le tour et ne tombent pas tous rond, dans les deux tailles de vue, sur les
 * seize niveaux.
 *
 * <p>On compare les trois sorties — l'abscisse laterale, la profondeur et l'abscisse a l'ecran —
 * et non la seule derniere : une erreur de rotation peut se compenser a la division et se
 * rattraper sur certains points, pas sur tous.
 */
public final class XformCheck {

    private XformCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.xform", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=xform");
            System.exit(1);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        JsonArray cas = root.getAsJsonArray("cases");

        View view = new View(Data.sinCos());
        int ecarts = 0;
        int points = 0;
        int poses = 0;
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

            view.screen(c.get("fullScreen").getAsBoolean());
            view.update(lvl, c.get("x").getAsInt(), c.get("z").getAsInt(),
                    c.get("angle").getAsInt(), 0);
            poses++;

            JsonArray rx = c.getAsJsonArray("rotX");
            JsonArray rz = c.getAsJsonArray("rotZ");
            JsonArray sx = c.getAsJsonArray("screenX");
            // L'oracle porte un point de plus que le niveau n'en compte : la boucle d'origine
            // en transforme un au-dela de sa table. On compare ce que le niveau contient.
            int n = Math.min(lvl.points().size(), rx.size());
            for (int p = 0; p < n; p++) {
                points++;
                int mx = view.rotX(p);
                int mz = view.rotZ(p);
                int ms = view.screenX(p);
                int ox = rx.get(p).getAsInt();
                int oz = rz.get(p).getAsInt();
                int os = sx.get(p).getAsInt();
                if (mx == ox && mz == oz && ms == os) {
                    continue;
                }
                ecarts++;
                ecartsNiveau++;
                if (ecarts <= 10) {
                    System.out.printf("  %c angle %4d %s point %4d :"
                            + " lateral %d/%d, profondeur %d/%d, ecran %d/%d%n",
                            lettre, c.get("angle").getAsInt(),
                            c.get("fullScreen").getAsBoolean() ? "plein " : "cadre ",
                            p, mx, ox, mz, oz, ms, os);
                }
            }
        }
        if (courant != 0) {
            ligne(courant, ecartsNiveau);
        }

        System.out.printf("%n%d poses, %d points projetes, %d ecart(s) -> %s%n",
                poses, points, ecarts, ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    private static void ligne(char lettre, int ecarts) {
        System.out.printf("  %c : %s%n", lettre, ecarts == 0 ? "conforme" : ecarts + " ECART(S)");
    }
}
