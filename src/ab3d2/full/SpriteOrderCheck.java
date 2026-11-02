package ab3d2.full;

import ab3d2.full.level.Level;
import ab3d2.full.render.ObjectView;
import ab3d2.full.render.Sprites;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifie le TRI DES CHOSES : lesquelles dessiner dans ce secteur, et dans quel ordre.
 *
 * <p>On confronte la TABLE ENTIERE, cases vides comprises — quatre-vingts profondeurs et
 * quatre-vingts rangs. Se contenter des cases servies laisserait passer tout ce qui se joue au
 * bout : le glissement d'un cran, la chose la plus proche qui tombe, la coupure a la premiere
 * profondeur negative.
 *
 * <p>Les secteurs de DESSIN et les octets d'etage viennent de l'oracle, qui les a releves tels
 * que le jeu les avait. Ce ne sont pas ceux du fichier : le jeu en tient une copie qu'il ne
 * rafraichit qu'une image sur cinq, et c'est elle qui decide.
 *
 * <p>Un cas par niveau entasse toutes les choses dans un seul secteur, pour atteindre le
 * debordement de la table — que les seize niveaux publies n'atteignent jamais. Un autre reduit
 * la fenetre a un pixel : la routine sort alors AVANT d'avoir vide la table, et on verifie les
 * deux choses a la fois, qu'elle ne dessine rien et qu'elle ne touche a rien.
 */
public final class SpriteOrderCheck {

    private SpriteOrderCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.spriteorder", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=spriteorder");
            System.exit(1);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        JsonArray cas = root.getAsJsonArray("cases");

        ObjectView view = new ObjectView(Data.sinCos());
        Sprites sprites = new Sprites();
        int ecarts = 0;
        long cases = 0;
        int debordements = 0;
        int chutes = 0;
        int coupures = 0;
        char courant = 0;
        int ecartsNiveau = 0;

        // La table telle que le cas precedent l'a laissee : c'est elle qu'une fenetre vide doit
        // laisser intacte.
        int[] avantDepth = new int[Sprites.SLOTS];
        int[] avantWhich = new int[Sprites.SLOTS];

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
            }
            Level lvl = Data.level(lettre);
            JsonArray zones = c.getAsJsonArray("drawZones");
            JsonArray uppers = c.getAsJsonArray("uppers");

            List<ObjectView.Thing> choses = new ArrayList<>(zones.size());
            for (int e = 0; e < zones.size(); e++) {
                Level.Entity ent = lvl.entities().get(e);
                choses.add(new ObjectView.Thing(ent.x(), ent.z(), ent.zone(),
                        zones.get(e).getAsInt(), uppers.get(e).getAsInt(),
                        ent.kind() == Level.Kind.AUX, ent.point()));
            }

            view.update(choses, lvl.objectPoints(), c.get("x").getAsInt(),
                    c.get("z").getAsInt(), c.get("angle").getAsInt(), 0,
                    c.get("full").getAsBoolean());

            for (int s = 0; s < Sprites.SLOTS; s++) {
                avantDepth[s] = sprites.slotDepth(s);
                avantWhich[s] = sprites.slotWhich(s);
            }
            sprites.clearCounts();
            sprites.sort(choses, view, c.get("zone").getAsInt(), c.get("upper").getAsInt(),
                    c.get("leftClip").getAsInt(), c.get("rightClip").getAsInt());
            debordements += sprites.overrun();
            chutes += sprites.dropped();

            JsonArray depths = c.getAsJsonArray("depths");
            if (depths.isEmpty()) {
                // La fenetre d'un seul pixel : rien a dessiner, et la table intacte.
                boolean intacte = sprites.count() == 0;
                for (int s = 0; s < Sprites.SLOTS && intacte; s++) {
                    intacte = sprites.slotDepth(s) == avantDepth[s]
                            && sprites.slotWhich(s) == avantWhich[s];
                }
                if (!intacte) {
                    ecartsNiveau += note(ecarts + ecartsNiveau, "%c secteur %d, fenetre %d..%d :"
                            + " %d choses a dessiner et la table a bouge", lettre,
                            c.get("zone").getAsInt(), c.get("leftClip").getAsInt(),
                            c.get("rightClip").getAsInt(), sprites.count());
                }
                continue;
            }

            JsonArray ranks = c.getAsJsonArray("ranks");
            cases += Sprites.SLOTS;
            int attendus = 0;
            while (attendus < Sprites.SLOTS && depths.get(attendus).getAsInt() >= 0) {
                attendus++;
            }
            if (attendus < Sprites.SLOTS) {
                coupures++;
            }
            if (sprites.count() != attendus) {
                ecartsNiveau += note(ecarts + ecartsNiveau, "%c secteur %d etage %d angle %d :"
                        + " %d choses a dessiner, l'oracle en donne %d", lettre,
                        c.get("zone").getAsInt(), c.get("upper").getAsInt(),
                        c.get("angle").getAsInt(), sprites.count(), attendus);
                continue;
            }
            for (int s = 0; s < Sprites.SLOTS; s++) {
                if (sprites.slotDepth(s) != depths.get(s).getAsInt()
                        || sprites.slotWhich(s) != ranks.get(s).getAsInt()) {
                    ecartsNiveau += note(ecarts + ecartsNiveau, "%c secteur %d etage %d angle"
                            + " %d, case %d : (%d, %d), l'oracle dit (%d, %d)", lettre,
                            c.get("zone").getAsInt(), c.get("upper").getAsInt(),
                            c.get("angle").getAsInt(), s, sprites.slotDepth(s),
                            sprites.slotWhich(s), depths.get(s).getAsInt(),
                            ranks.get(s).getAsInt());
                    break;
                }
            }
        }
        if (courant != 0) {
            ligne(courant, ecartsNiveau);
            ecarts += ecartsNiveau;
        }

        System.out.printf("%n%d poses, %d cases, %d coupures, %d chutes du bout, %d hors table,"
                + " %d ecart(s) -> %s%n", cas.size(), cases, coupures, chutes, debordements,
                ecarts, ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
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
