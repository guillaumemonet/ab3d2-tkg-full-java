package ab3d2.full;

import ab3d2.full.level.Level;
import ab3d2.full.render.View;
import ab3d2.full.render.ZoneClip;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifie la fenetre de dessin des secteurs contre le portage.
 *
 * <p>Deux bornes par secteur visible, et rien d'autre a comparer — mais elles sont le produit de
 * toute la chaine amont : un sommet mal projete, un voisin mal lu, et la fenetre se ferme ou
 * s'ouvre au mauvais endroit. On compare donc aussi le GROUPE de decoupe attribue a chaque
 * ouverture, qui dit si le chargeur les a distribues dans le meme ordre que le jeu.
 */
public final class ClipCheck {

    private ClipCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.clips", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=clips");
            System.exit(1);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        JsonArray cas = root.getAsJsonArray("cases");

        View view = new View(Data.sinCos());
        ZoneClip clip = new ZoneClip();
        int ecarts = 0;
        int poses = 0;
        int fenetres = 0;
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
            int angle = c.get("angle").getAsInt();

            boolean plein = c.get("fullScreen").getAsBoolean();
            view.screen(plein);
            view.update(lvl, c.get("x").getAsInt(), c.get("z").getAsInt(), angle, 0);
            poses++;

            Level.Zone from = lvl.zones().get(c.get("zone").getAsInt());
            JsonArray oracle = c.getAsJsonArray("entries");
            int n = Math.min(from.visible().size(), oracle.size());
            if (from.visible().size() != oracle.size()) {
                ecarts++;
                ecartsNiveau++;
                if (ecarts <= 6) {
                    System.out.printf("  %c secteur %d : %d ouvertures, l'oracle en donne %d%n",
                            lettre, from.id(), from.visible().size(), oracle.size());
                }
            }

            for (int k = 0; k < n; k++) {
                Level.Visible v = from.visible().get(k);
                JsonObject o = oracle.get(k).getAsJsonObject();
                fenetres++;

                clip.narrow(lvl, view, v.clip(), 0, view.rightX());
                int mine = v.clip() == null ? -1 : v.clip().wordIndex();
                int oClip = o.get("clipID").getAsInt();
                int oLeft = o.get("left").getAsInt();
                int oRight = o.get("right").getAsInt();

                if (v.zone() == o.get("zone").getAsInt() && mine == oClip
                        && clip.left() == oLeft && clip.right() == oRight) {
                    continue;
                }
                ecarts++;
                ecartsNiveau++;
                if (ecarts <= 10) {
                    System.out.printf("  %c secteur %d angle %4d %s, ouverture %d vers %d/%d :"
                            + " decoupe %d/%d, gauche %d/%d, droite %d/%d%n",
                            lettre, from.id(), angle, plein ? "plein " : "cadre ",
                            k, v.zone(), o.get("zone").getAsInt(),
                            mine, oClip, clip.left(), oLeft, clip.right(), oRight);
                }
            }
        }
        if (courant != 0) {
            ligne(courant, ecartsNiveau);
        }

        System.out.printf("%n%d poses, %d fenetres de dessin, %d ecart(s) -> %s%n",
                poses, fenetres, ecarts, ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    private static void ligne(char lettre, int ecarts) {
        System.out.printf("  %c : %s%n", lettre, ecarts == 0 ? "conforme" : ecarts + " ECART(S)");
    }
}
