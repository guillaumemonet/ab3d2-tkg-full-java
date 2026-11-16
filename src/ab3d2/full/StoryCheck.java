package ab3d2.full;

import ab3d2.full.view.Story;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * LE RECIT QUI OUVRE LES NIVEAUX, confronte au portage.
 *
 * <p>Le texte seul ne prouverait rien du DESSIN. La police est proportionnelle, le centrage se
 * fait sur ce que la ligne OCCUPE et non sur ce qu'elle contient, le glyphe est cale a droite
 * dans son mot, et l'ecran passe de six cent quarante pixels a trois cent vingt par un OU de
 * paires. Autant de facons de se tromper qui laissent le texte intact.
 *
 * <p>On compare donc, pour chacun des seize niveaux, le nombre de pixels allumes et une
 * empreinte de l'ecran entier — plus les huit couleurs du fondu, qui ne sont pas un degrade de
 * gris mais une montee vers un vert franc.
 *
 * <p>Une empreinte ne dit pas OU est l'ecart. Pour le voir : {@code gradle -p full storyShot
 * -Plevel=N} d'un cote, {@code gradle -p java menuTest -PintroText=N} de l'autre.
 */
public final class StoryCheck {

    private StoryCheck() {
    }

    private static int ecarts;

    public static void main(String[] args) throws Exception {
        Path ref = Path.of(System.getProperty("ab3d2.storyscreens", "storyscreens.json"));
        JsonObject racine;
        try (Reader r = Files.newBufferedReader(ref)) {
            racine = JsonParser.parseReader(r).getAsJsonObject();
        }

        Story story = new Story();
        if (!story.available()) {
            System.out.println("  pas de TEXT_FILE : rien a eprouver");
            return;
        }

        // Le fondu : huit mots couleur Amiga, un par image.
        JsonArray fondu = racine.getAsJsonArray("fade");
        for (int f = 0; f < fondu.size(); f++) {
            int attendu = fondu.get(f).getAsInt();
            int obtenu = couleurA(story, f);
            if (attendu != obtenu) {
                System.out.printf("  ECART couleur du fondu, image %d : attendu $%03X,"
                        + " obtenu $%03X%n", f, attendu, obtenu);
                ecarts++;
            }
        }

        JsonArray ecrans = racine.getAsJsonArray("screens");
        int vus = 0;
        for (int i = 0; i < ecrans.size(); i++) {
            JsonObject un = ecrans.get(i).getAsJsonObject();
            int niveau = un.get("level").getAsInt();
            if (!story.begin(niveau)) {
                System.out.printf("  ECART niveau %d : aucun ecran rendu%n", niveau);
                ecarts++;
                continue;
            }
            byte[] ecran = story.pixels();
            int allumes = 0;
            long empreinte = 0xCBF29CE484222325L;      // FNV-1a, 64 bits
            for (byte b : ecran) {
                allumes += b & 1;
                empreinte = (empreinte ^ (b & 0xFF)) * 0x100000001B3L;
            }
            int attendus = un.get("lit").getAsInt();
            String attendue = un.get("fingerprint").getAsString();
            if (allumes != attendus) {
                System.out.printf("  ECART niveau %d : %d pixels allumes, %d attendus%n",
                        niveau, allumes, attendus);
                ecarts++;
            } else if (!Long.toHexString(empreinte).equals(attendue)) {
                System.out.printf("  ECART niveau %d : meme nombre de pixels, empreinte"
                        + " differente (%s au lieu de %s)%n",
                        niveau, Long.toHexString(empreinte), attendue);
                ecarts++;
            }
            vus++;
        }

        enchainement();

        System.out.printf("%d ecrans de recit, %d couleurs de fondu, %d ecart(s) -> %s%n",
                vus, fondu.size(), ecarts, ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    /**
     * LE PASSAGE du recit au niveau.
     *
     * <p>Un ecran qui ne se rend pas est un ecran qui ne se quitte pas : le jeu resterait
     * bloque la, sans que rien ne le dise. On eprouve donc l'enchainement lui-meme — le recit
     * s'ouvre, se passe au tir, et le niveau demarre.
     */
    private static void enchainement() {
        ab3d2.full.view.Shell coquille = new ab3d2.full.view.Shell();
        coquille.playNow(0);
        if (coquille.mode() != ab3d2.full.view.Shell.Mode.STORY) {
            System.out.printf("  ECART enchainement : le recit ne s'ouvre pas (%s)%n",
                    coquille.mode());
            ecarts++;
            return;
        }
        ab3d2.full.sim.World.Input rien = new ab3d2.full.sim.World.Input();
        // Pendant le fondu, rien ne doit passer.
        for (int i = 0; i < 8; i++) {
            coquille.frame(rien, false, false, true, false, 0);
        }
        if (coquille.mode() != ab3d2.full.view.Shell.Mode.STORY) {
            System.out.println("  ECART enchainement : le recit se passe PENDANT son fondu");
            ecarts++;
            return;
        }
        for (int i = 0; i < 4 && coquille.mode() == ab3d2.full.view.Shell.Mode.STORY; i++) {
            coquille.frame(rien, false, false, true, false, 0);
        }
        if (coquille.mode() != ab3d2.full.view.Shell.Mode.GAME || coquille.session() == null) {
            System.out.printf("  ECART enchainement : le niveau ne demarre pas (%s)%n",
                    coquille.mode());
            ecarts++;
        }
    }

    /** La couleur de l'encre a l'image {@code f}, en mot Amiga. */
    private static int couleurA(Story story, int f) {
        story.begin(0);
        for (int i = 1; i <= f; i++) {
            story.frame(false);
        }
        return amiga(story.argb(1));
    }

    /** Un ARGB dont les octets sont des quartets etendus, ramene au mot Amiga d'origine. */
    private static int amiga(int argb) {
        return ((((argb >> 16) & 0xFF) / 17) << 8)
                | ((((argb >> 8) & 0xFF) / 17) << 4)
                | ((argb & 0xFF) / 17);
    }
}
