package ab3d2.full;

import ab3d2.full.M68k;
import ab3d2.full.level.Level;
import ab3d2.full.level.SpriteSheet;
import ab3d2.full.render.Bitmap;
import ab3d2.full.render.BitmapPaint;
import ab3d2.full.render.ObjectView;
import ab3d2.full.render.ScaleTable;
import ab3d2.full.render.Screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Verifie les PIXELS DU SPRITE : la boucle simple et le melange additif.
 *
 * <p>Un sprite ne couvre pas tout ce qu'il touche — la teinte zero est transparente — et la
 * verification n'a donc de sens que si le tampon est connu avant son passage. On y pose le
 * meme motif que l'oracle, sur le rectangle du sprite et sur lui seul. Le melange additif, lui,
 * LIT ce tampon autant qu'il l'ecrit : le motif n'y est plus une precaution mais la moitie de
 * l'epreuve.
 *
 * <p>Les deux boucles se partagent le releve. L'additif demande une palette de huit mille cent
 * quatre-vingt-douze octets, et deux feuilles seulement en portent autant — les gros
 * projectiles et les explosions. L'oracle seme donc l'additif sur celles-la et la boucle simple
 * sur les douze autres, comme le jeu le fait.
 *
 * <p>Chaque sprite est confronte SEUL : c'est ce qui permet de dire lequel se trompe, et de
 * combien.
 */
public final class BitmapPaintCheck {

    /** La hauteur du tampon. */
    private static final int HAUTEUR = 256;

    private BitmapPaintCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.bitmappaint", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=bitmappaint");
            System.exit(1);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        JsonObject champs = root.getAsJsonObject("objects");
        JsonArray cas = root.getAsJsonArray("cases");

        ObjectView view = new ObjectView(Data.sinCos());
        Bitmap bitmap = new Bitmap(Data.database(), new ScaleTable());
        Screen screen = new Screen();
        BitmapPaint paint = new BitmapPaint(screen);
        Base64.Decoder decodeur = Base64.getDecoder();

        int ecarts = 0;
        int sprites = 0;
        int additifs = 0;
        long pixels = 0;
        int horsFeuille = 0;
        int horsBande = 0;
        int horsTable = 0;
        char courant = 0;
        int ecartsNiveau = 0;

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
            JsonArray objets = champs.getAsJsonArray(String.valueOf(lettre));
            JsonArray attendus = c.getAsJsonArray("placed");
            JsonArray octets = c.getAsJsonArray("pixels");

            List<ObjectView.Thing> choses = new ArrayList<>(objets.size());
            for (int e = 0; e < objets.size(); e++) {
                Level.Entity ent = lvl.entities().get(e);
                choses.add(new ObjectView.Thing(ent.x(), ent.z(), ent.zone(), ent.zone(),
                        ent.upperZone(), ent.kind() == Level.Kind.AUX, ent.point()));
            }
            view.update(choses, lvl.objectPoints(), c.get("x").getAsInt(),
                    c.get("z").getAsInt(), c.get("angle").getAsInt(), 0, true);
            bitmap.view(true, 160, 120, 0, -100 * 1024, 100 * 1024, 0, 240,
                    c.get("leftClip").getAsInt(), c.get("rightClip").getAsInt());

            for (int e = 0; e < objets.size(); e++) {
                if (attendus.get(e).isJsonNull()) {
                    continue;                          // laisse de cote par l'oracle
                }
                JsonArray o = objets.get(e).getAsJsonArray();
                boolean aux = o.get(8).getAsInt() == 3;
                Bitmap.Thing t = new Bitmap.Thing(o.get(0).getAsInt(), o.get(1).getAsInt(),
                        o.get(2).getAsInt(), o.get(3).getAsInt(), o.get(4).getAsInt(),
                        o.get(5).getAsInt(), o.get(6).getAsInt(), o.get(7).getAsInt(),
                        aux ? o.get(9).getAsInt() : 0, aux ? o.get(10).getAsInt() : 0);
                bitmap.clearOffTable();
                Bitmap.Placed p = bitmap.place(t, view);
                if (bitmap.offTable() > 0 || bitmap.offScale() > 0) {
                    // La luminosite ou l'echelle est sortie de sa table : la rampe que
                    // l'original retient vient d'ailleurs que de la table, et les pixels
                    // qu'il pose n'ont pas de sens a confronter.
                    horsTable++;
                    continue;
                }
                JsonArray b = attendus.get(e).getAsJsonArray();
                if (p == null) {
                    ecartsNiveau += note(ecarts + ecartsNiveau, "%c angle %d, objet %d : ecarte,"
                            + " l'oracle le pose", lettre, c.get("angle").getAsInt(), e);
                    continue;
                }
                int haut = b.get(0).getAsInt();
                int colonne = b.get(1).getAsInt();
                int colonnes = b.get(2).getAsInt();
                int lignes = b.get(3).getAsInt();
                if (p.top() != haut || p.left() != colonne || p.columns() != colonnes
                        || p.rows() != lignes) {
                    ecartsNiveau += note(ecarts + ecartsNiveau, "%c angle %d, objet %d : pose en"
                            + " (%d, %d) %dx%d, l'oracle dit (%d, %d) %dx%d", lettre,
                            c.get("angle").getAsInt(), e, p.top(), p.left(), p.columns(),
                            p.rows(), haut, colonne, colonnes, lignes);
                    continue;
                }

                SpriteSheet feuille = Data.sheet(o.get(5).getAsInt() & 0x7FFF);
                if (horsBande(p, feuille)) {
                    horsBande++;
                    continue;
                }

                motif(screen, haut, colonne, colonnes, lignes);
                paint.clearOffSheet();
                if (p.additive()) {
                    paint.paintAdditive(p, feuille);
                } else {
                    paint.paint(p, feuille);
                }
                if (paint.offSheet() > 0) {
                    // La rampe de luminosite sort de la palette de la feuille — plusieurs
                    // n'en portent que seize niveaux la ou le calcul en designe trente-deux —
                    // ou la bande sort du fichier de pixels. L'original y lit la memoire
                    // voisine ; on ne confronte pas du hasard.
                    horsFeuille++;
                    continue;
                }
                sprites++;
                if (p.additive()) {
                    additifs++;
                }

                byte[] attendu = decodeur.decode(octets.get(e).getAsString());
                pixels += attendu.length;
                boolean casse = false;
                for (int l = 0; l < lignes && !casse; l++) {
                    for (int k = 0; k < colonnes; k++) {
                        byte eu = screen.pixels()[screen.line(haut + l) + colonne + k];
                        if (eu != attendu[l * colonnes + k]) {
                            ecartsNiveau += note(ecarts + ecartsNiveau, "%c angle %d, objet %d,"
                                    + " pixel (%d, %d) : %d, l'oracle dit %d", lettre,
                                    c.get("angle").getAsInt(), e, k, l, eu & 0xFF,
                                    attendu[l * colonnes + k] & 0xFF);
                            casse = true;
                            break;
                        }
                    }
                }
            }
        }
        if (courant != 0) {
            ligne(courant, ecartsNiveau);
            ecarts += ecartsNiveau;
        }

        System.out.printf("%n%d vues, %d sprites (dont %d additifs), %d pixels, %d hors"
                + " feuille, %d hors bande, %d hors table, %d ecart(s) -> %s%n", cas.size(),
                sprites, additifs, pixels, horsFeuille, horsBande, horsTable, ecarts,
                ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    /**
     * Vrai si le sprite lit une bande au-dela de la table des bandes de sa feuille.
     *
     * <p>Le cas existe — six cent quatre-vingts sprites du releve le font — et l'original y
     * lit la memoire qui suit le fichier. On les ecarte, mais on decide ICI, en refaisant le
     * parcours des bandes, et non d'apres ce que la routine eprouvee a lu.
     *
     * <p>La difference n'est pas de style. Laisser la routine decider donnait a une faute un
     * moyen de se cacher : une bande prise sur la mauvaise moitie de l'accumulateur sort de la
     * table, le sprite est ecarte, et rien ne le contredit. Une mutation qui RETIRE des cas
     * passait ainsi au travers du corpus.
     */
    private static boolean horsBande(Bitmap.Placed p, SpriteSheet feuille) {
        int acc = 0;
        for (int k = p.columns(); k > 0; k--) {
            if (feuille.beyond(p.strip() + M68k.s16(acc >>> 16))) {
                return true;
            }
            acc += p.stripStep();
        }
        return false;
    }

    /** Le motif de depart, fonction des coordonnees ABSOLUES de l'ecran. */
    private static void motif(Screen screen, int haut, int colonne, int colonnes, int lignes) {
        byte[] pixels = screen.pixels();
        for (int l = 0; l < lignes; l++) {
            int y = haut + l;
            if (y < 0 || y >= HAUTEUR) {
                continue;
            }
            for (int k = 0; k < colonnes; k++) {
                int x = colonne + k;
                pixels[screen.line(y) + x] = (byte) ((x * 7 + y * 13) & 0xFF);
            }
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
