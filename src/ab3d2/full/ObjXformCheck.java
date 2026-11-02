package ab3d2.full;

import ab3d2.full.level.Level;
import ab3d2.full.render.ObjectView;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Verifie la ROTATION DES CHOSES : ou se trouve, vu de l'oeil, chaque entite du niveau.
 *
 * <p>Trois valeurs par point — l'abscisse tournee, la profondeur, et l'abscisse remise a
 * l'echelle du decor —, sur toutes les entites de tous les niveaux.
 *
 * <p>La verification porte aussi sur deux invariants que le fichier affirme sans les dire : que
 * le nombre de points annonce vaut exactement le nombre d'entites non auxiliaires, et que le
 * rang de point d'une entite ordinaire est son rang parmi celles-la. S'ils tombaient, la
 * rotation serait juste et l'INDEXATION fausse — et rien dans les trois valeurs ne le dirait.
 */
public final class ObjXformCheck {

    private ObjXformCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.objxform", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=objxform");
            System.exit(1);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        JsonArray cas = root.getAsJsonArray("cases");

        ObjectView view = new ObjectView(Data.sinCos());
        int ecarts = 0;
        long valeurs = 0;
        char courant = 0;
        int ecartsNiveau = 0;
        List<ObjectView.Thing> choses = null;

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
                Level lvl = Data.level(lettre);
                choses = ObjectView.atRest(lvl.entities());
                ecartsNiveau += invariants(lvl);
            }
            Level lvl = Data.level(lettre);
            int points = c.get("points").getAsInt();
            if (lvl.objectPoints() != points) {
                ecartsNiveau += note(ecarts + ecartsNiveau, "%c : le niveau annonce %d points"
                        + " d'objets, l'oracle en donne %d", lettre, lvl.objectPoints(), points);
                continue;
            }

            view.update(choses, points, c.get("x").getAsInt(), c.get("z").getAsInt(),
                    c.get("angle").getAsInt(), c.get("wobble").getAsInt(),
                    c.get("full").getAsBoolean());

            JsonArray rx = c.getAsJsonArray("rotX");
            JsonArray rz = c.getAsJsonArray("rotZ");
            JsonArray wx = c.getAsJsonArray("worldX");
            valeurs += points * 3L;
            for (int p = 0; p < points; p++) {
                if (view.rotX(p) != rx.get(p).getAsInt()
                        || view.rotZ(p) != rz.get(p).getAsInt()
                        || view.worldX(p) != wx.get(p).getAsInt()) {
                    ecartsNiveau += note(ecarts + ecartsNiveau, "%c angle %4d balancement %d %s,"
                            + " point %d : (%d, %d, %d), l'oracle dit (%d, %d, %d)",
                            lettre, c.get("angle").getAsInt(), c.get("wobble").getAsInt(),
                            c.get("full").getAsBoolean() ? "plein ecran" : "vue encadree", p,
                            view.rotX(p), view.rotZ(p), view.worldX(p),
                            rx.get(p).getAsInt(), rz.get(p).getAsInt(), wx.get(p).getAsInt());
                    break;
                }
            }
        }
        if (courant != 0) {
            ligne(courant, ecartsNiveau);
            ecarts += ecartsNiveau;
        }

        System.out.printf("%n%d poses, %d valeurs, %d ecart(s) -> %s%n", cas.size(), valeurs,
                ecarts, ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    /**
     * Les deux invariants de l'indexation.
     *
     * <p>Le premier : le compte annonce vaut le nombre d'entites non auxiliaires. Le second :
     * le rang de point d'une entite est le nombre d'entites non auxiliaires qui la precedent —
     * ce qui, pour une auxiliaire, revient a dire qu'elle porte le rang de la suivante.
     */
    private static int invariants(Level lvl) {
        int rang = 0;
        int ecarts = 0;
        for (int i = 0; i < lvl.entities().size(); i++) {
            Level.Entity e = lvl.entities().get(i);
            if (e.point() != rang) {
                ecarts += note(ecarts, "%c : l'entite %d (%s) porte le rang de point %d,"
                        + " le compte en cours dit %d", lvl.letter(), i, e.kind(), e.point(),
                        rang);
            }
            if (e.kind() != Level.Kind.AUX) {
                rang++;
            }
        }
        if (rang != lvl.objectPoints()) {
            ecarts += note(ecarts, "%c : %d entites non auxiliaires, le fichier annonce %d"
                    + " points", lvl.letter(), rang, lvl.objectPoints());
        }
        return ecarts;
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
