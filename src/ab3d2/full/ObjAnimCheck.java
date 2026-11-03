package ab3d2.full;

import ab3d2.full.defs.Database;
import ab3d2.full.render.Appearance;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Verifie l'APPARENCE des choses : ce qu'un pas d'animation donne a voir.
 *
 * <p>C'est le chainon qui manquait entre la simulation et le dessin. Les verifications des
 * sprites SEMAIENT la feuille, l'image et les demi-tailles, faute de savoir d'ou elles
 * viennent ; celle-ci eprouve la routine qui les pose, et le semis n'a plus lieu d'etre.
 *
 * <p>Trente definitions, deux scripts, vingt pas : douze cents appels, et sept valeurs
 * comparees a chaque fois.
 */
public final class ObjAnimCheck {

    private ObjAnimCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.objanim", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=objanim");
            System.exit(1);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        JsonArray cas = root.getAsJsonArray("cases");
        Database glf = Data.database();

        int ecarts = 0;
        int sprites = 0;
        int modeles = 0;
        int halos = 0;
        for (int i = 0; i < cas.size(); i++) {
            JsonObject c = cas.get(i).getAsJsonObject();
            int type = c.get("type").getAsInt();
            int script = c.get("script").getAsInt();
            int rang = c.get("step").getAsInt();
            Database.ObjectDef def = glf.objects().get(type);
            List<Database.AnimStep> scenario = script == 0 ? def.idleAnim() : def.activeAnim();
            if (rang >= scenario.size()) {
                ecarts += note(ecarts, "objet %d, script %d : pas %d absent, le script n'en"
                        + " porte que %d", type, script, rang, scenario.size());
                continue;
            }
            Database.AnimStep pas = scenario.get(rang);
            Appearance vu = Appearance.of(pas, def.gfxType());

            if (vu.vector()) {
                modeles++;
            } else if (vu.glare()) {
                halos++;
            } else {
                sprites++;
            }

            ecarts += compare(ecarts, type, script, rang, "feuille", vu.sheet(),
                    c.get("sheet").getAsInt());
            ecarts += compare(ecarts, type, script, rang, "drapeaux", vu.flags(),
                    c.get("flags").getAsInt());
            ecarts += compare(ecarts, type, script, rang, "image", vu.frame(),
                    c.get("frame").getAsInt());
            ecarts += compare(ecarts, type, script, rang, "demi-largeur", vu.halfWidth(),
                    c.get("halfWidth").getAsInt());
            ecarts += compare(ecarts, type, script, rang, "demi-hauteur", vu.halfDepth(),
                    c.get("halfDepth").getAsInt());
            // Ce que la routine AVANCE, et que la simulation porte deja.
            ecarts += compare(ecarts, type, script, rang, "pas suivant", pas.next(),
                    c.get("next").getAsInt());
            int hauteur = (short) (HAUTEUR_SEMEE + 2 * pas.delta());
            ecarts += compare(ecarts, type, script, rang, "hauteur", hauteur,
                    c.get("height").getAsInt());
            int angle = def.gfxType() == 1
                    ? (ANGLE_SEME + pas.word2()) & 0xFFFF : ANGLE_SEME;
            ecarts += compare(ecarts, type, script, rang, "angle", angle,
                    c.get("angle").getAsInt());
        }

        System.out.printf("%n%d appels : %d sprites, %d modeles, %d halos, %d ecart(s) -> %s%n",
                cas.size(), sprites, modeles, halos, ecarts, ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    /** La hauteur de depart que l'oracle seme. */
    private static final int HAUTEUR_SEMEE = 1000;

    /** L'angle de depart que l'oracle seme. */
    private static final int ANGLE_SEME = 777;

    private static int compare(int dejaVus, int type, int script, int pas, String quoi,
            int mien, int oracle) {
        if (mien == oracle) {
            return 0;
        }
        return note(dejaVus, "objet %d, script %d, pas %d : %s = %d, l'oracle dit %d",
                type, script, pas, quoi, mien, oracle);
    }

    private static int note(int dejaVus, String format, Object... args) {
        if (dejaVus < 15) {
            System.out.printf("    " + format + "%n", args);
        }
        return 1;
    }
}
