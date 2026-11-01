package ab3d2.full;

import ab3d2.full.level.Level;
import ab3d2.full.render.EdgePvs;
import ab3d2.full.render.View;
import ab3d2.full.render.VisibleEdges;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifie le tri des ouvertures visibles et la tranche d'ecran qu'elles donnent.
 *
 * <p>C'est le seul maillon du mecanisme de visibilite qui depende de l'angle. On compare quatre
 * choses, et il faut les quatre : quelles ouvertures sont retenues, les sommets qu'elles
 * portent, la reunion de leurs listes, et la tranche d'ecran qui en sort. Une reunion juste
 * tiree des mauvaises ouvertures serait un hasard, pas une preuve.
 */
public final class VisibleEdgesCheck {

    private VisibleEdgesCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.visedges", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=visedges");
            System.exit(1);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        JsonArray cas = root.getAsJsonArray("cases");

        View view = new View(Data.sinCos());
        VisibleEdges vis = new VisibleEdges(Data.sinCos());
        int ecarts = 0;
        int poses = 0;
        char courant = 0;
        int ecartsNiveau = 0;
        EdgePvs pvs = null;

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
                pvs = Data.edgePvs(lettre);
            }
            Level lvl = Data.level(lettre);
            int zone = c.get("zone").getAsInt();
            int x = c.get("x").getAsInt();
            int z = c.get("z").getAsInt();
            int angle = c.get("angle").getAsInt();
            int portes = c.get("doors").getAsInt();

            view.screen(true);
            view.update(lvl, x, z, angle, 0);
            vis.update(lvl, pvs, zone, x, z, angle, portes);
            vis.span(view);
            poses++;

            ecartsNiveau += compare(ecarts + ecartsNiveau, lettre, zone, angle, portes,
                    "ouvertures retenues", vis.openingMask(), c.get("visJoinMask").getAsInt());
            ecartsNiveau += compare(ecarts + ecartsNiveau, lettre, zone, angle, portes,
                    "combien", vis.visibleCount(), c.get("visJoins").getAsInt());
            ecartsNiveau += compare(ecarts + ecartsNiveau, lettre, zone, angle, portes,
                    "ouvertures en tout", vis.openingCount(), c.get("totJoins").getAsInt());

            JsonArray sommets = c.getAsJsonArray("points");
            if (sommets.size() == vis.visibleCount() * 2) {
                for (int k = 0; k < vis.visibleCount(); k++) {
                    ecartsNiveau += compare(ecarts + ecartsNiveau, lettre, zone, angle, portes,
                            "sommet " + (k * 2), vis.startPoint(k), sommets.get(k * 2).getAsInt());
                    ecartsNiveau += compare(ecarts + ecartsNiveau, lettre, zone, angle, portes,
                            "sommet " + (k * 2 + 1), vis.endPoint(k),
                            sommets.get(k * 2 + 1).getAsInt());
                }
            }

            String masque = c.get("mask").getAsString();
            for (int slot = 0; slot < masque.length() / 2; slot++) {
                int attendu = Integer.parseInt(masque.substring(slot * 2, slot * 2 + 2), 16);
                ecartsNiveau += compare(ecarts + ecartsNiveau, lettre, zone, angle, portes,
                        "rang " + slot, vis.slot(slot), attendu);
            }

            ecartsNiveau += compare(ecarts + ecartsNiveau, lettre, zone, angle, portes,
                    "tranche gauche", vis.startLeft(-1), c.get("spanLeft").getAsInt());
            ecartsNiveau += compare(ecarts + ecartsNiveau, lettre, zone, angle, portes,
                    "tranche droite", vis.startRight(-1, view), c.get("spanRight").getAsInt());
            ecartsNiveau += compare(ecarts + ecartsNiveau, lettre, zone, angle, portes,
                    "son propre secteur, gauche", vis.startLeft(zone),
                    c.get("ownLeft").getAsInt());
            ecartsNiveau += compare(ecarts + ecartsNiveau, lettre, zone, angle, portes,
                    "son propre secteur, droite", vis.startRight(zone, view),
                    c.get("ownRight").getAsInt());
        }
        if (courant != 0) {
            ligne(courant, ecartsNiveau);
        }
        ecarts += ecartsNiveau;

        System.out.printf("%n%d poses, %d ecart(s) -> %s%n",
                poses, ecarts, ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    private static int compare(int dejaVus, char lettre, int zone, int angle, int portes,
            String quoi, int mien, int oracle) {
        if (mien == oracle) {
            return 0;
        }
        if (dejaVus < 10) {
            System.out.printf("    %c secteur %d angle %4d portes %04x : %s = %d, l'oracle dit %d%n",
                    lettre, zone, angle, portes, quoi, mien, oracle);
        }
        return 1;
    }

    private static void ligne(char lettre, int ecarts) {
        System.out.printf("  %c : %s%n", lettre, ecarts == 0 ? "conforme" : ecarts + " ECART(S)");
    }
}
