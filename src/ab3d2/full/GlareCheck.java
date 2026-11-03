package ab3d2.full;

import ab3d2.full.M68k;
import ab3d2.full.level.Level;
import ab3d2.full.level.SpriteSheet;
import ab3d2.full.render.Bitmap;
import ab3d2.full.render.Glare;
import ab3d2.full.render.GlarePaint;
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
 * Verifie le MIROITEMENT : la pose ET les pixels, car c'est une seule routine.
 *
 * <p>Huit valeurs de pose par miroitement — le coin, la taille, la bande de depart, les deux
 * pas —, puis le rectangle de pixels quand l'oracle a pu le garder. La lueur LIT l'octet
 * qu'elle recouvre pour decider ce qu'elle ecrit : le motif pose avant son passage n'est donc
 * pas une precaution contre la transparence, c'est la moitie de ce qu'on eprouve.
 */
public final class GlareCheck {

    /** La hauteur du tampon. */
    private static final int HAUTEUR = 256;

    /** Combien d'images par feuille. */
    private static final int IMAGES = 32;

    private GlareCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.glare", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=glare");
            System.exit(1);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        JsonObject champs = root.getAsJsonObject("objects");
        JsonArray cas = root.getAsJsonArray("cases");

        ObjectView view = new ObjectView(Data.sinCos());
        Glare glare = new Glare(Data.database(), new ScaleTable());
        Screen screen = new Screen();
        GlarePaint paint = new GlarePaint(screen);
        Base64.Decoder decodeur = Base64.getDecoder();

        int ecarts = 0;
        int poses = 0;
        int dessines = 0;
        long pixels = 0;
        int horsTable = 0;
        int horsBande = 0;
        int horsImage = 0;
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
            glare.view(true, c.get("centreX").getAsInt(), c.get("centreY").getAsInt(),
                    c.get("eyeY").getAsInt(), -100 * 1024, 100 * 1024, 0, 240,
                    c.get("leftClip").getAsInt(), c.get("rightClip").getAsInt());

            for (int e = 0; e < objets.size(); e++) {
                JsonArray o = objets.get(e).getAsJsonArray();
                boolean aux = o.get(8).getAsInt() == 3;
                Bitmap.Thing t = new Bitmap.Thing(o.get(0).getAsInt(), o.get(1).getAsInt(),
                        o.get(2).getAsInt(), o.get(3).getAsInt(), o.get(4).getAsInt(),
                        o.get(5).getAsInt(), o.get(6).getAsInt(), o.get(7).getAsInt(),
                        aux ? o.get(9).getAsInt() : 0, aux ? o.get(10).getAsInt() : 0);
                if (horsImage(o)) {
                    horsImage++;
                    continue;
                }
                glare.clearOffTable();
                Glare.Placed p = glare.place(t, view);
                if (glare.offScale() > 0) {
                    // Le rapport d'echelle est sorti de sa table : l'original y lit de la
                    // memoire voisine, et ce qu'il en tire n'a pas de sens a confronter.
                    horsTable++;
                    continue;
                }
                boolean pose = !attendus.get(e).isJsonNull();
                if (p == null) {
                    if (pose) {
                        ecartsNiveau += note(ecarts + ecartsNiveau, "%c angle %d, objet %d :"
                                + " ecarte, l'oracle le pose", lettre,
                                c.get("angle").getAsInt(), e);
                    }
                    continue;
                }
                if (!pose) {
                    ecartsNiveau += note(ecarts + ecartsNiveau, "%c angle %d, objet %d : pose,"
                            + " l'oracle l'ecarte", lettre, c.get("angle").getAsInt(), e);
                    continue;
                }
                JsonArray b = attendus.get(e).getAsJsonArray();
                int[] mien = {p.top(), p.left(), p.columns(), p.rows(), p.strip(),
                    p.stripStep(), p.texStart(), p.texStep()};
                String[] noms = {"ligne", "colonne", "colonnes", "lignes", "bande",
                    "pas de bande", "depart", "pas de ligne"};
                boolean casse = false;
                for (int k = 0; k < mien.length; k++) {
                    if (mien[k] != b.get(k).getAsInt()) {
                        ecartsNiveau += note(ecarts + ecartsNiveau, "%c angle %d, objet %d :"
                                + " %s = %d, l'oracle dit %d", lettre,
                                c.get("angle").getAsInt(), e, noms[k], mien[k],
                                b.get(k).getAsInt());
                        casse = true;
                        break;
                    }
                }
                if (casse) {
                    continue;
                }
                poses++;

                if (octets.get(e).isJsonNull()) {
                    continue;                          // pixels laisses de cote par l'oracle
                }
                SpriteSheet feuille = Data.sheet(Glare.sheetOf(o.get(5).getAsInt()));
                if (horsFeuille(p, feuille)) {
                    horsBande++;
                    continue;
                }
                int haut = b.get(0).getAsInt();
                int colonne = b.get(1).getAsInt();
                int colonnes = b.get(2).getAsInt();
                int lignes = b.get(3).getAsInt();
                motif(screen, haut, colonne, colonnes, lignes);
                paint.clearOffSheet();
                paint.paint(p, feuille, Data.shades());
                dessines++;

                byte[] attendu = decodeur.decode(octets.get(e).getAsString());
                pixels += attendu.length;
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

        System.out.printf("%n%d vues, %d poses, %d dessines, %d pixels, %d hors echelle,"
                + " %d hors feuille, %d hors image, %d ecart(s) -> %s%n", cas.size(), poses,
                dessines, pixels, horsTable, horsBande, horsImage, ecarts,
                ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    /**
     * Vrai si le miroitement sort de sa feuille — table des bandes ou fichier de pixels.
     *
     * <p>Les deux cas existent : six cents poses du releve lisent une bande au-dela de la
     * table, une centaine un pixel au-dela du fichier, et l'original y prend la memoire
     * voisine. On les ecarte, mais on DECIDE ici, en refaisant le parcours, et non d'apres ce
     * que la routine eprouvee a lu.
     *
     * <p>C'est une copie de la boucle de {@link GlarePaint}, et c'est voulu : la lecon du
     * melange additif est qu'une faute ne doit jamais pouvoir prononcer son propre
     * acquittement. Quatre mutations ont survecu a ce corpus avant cette separation — toutes
     * les quatre faisaient sortir de la feuille les cas memes qui les auraient montrees.
     */
    private static boolean horsFeuille(Glare.Placed p, SpriteSheet feuille) {
        int acc = 0;
        for (int c = p.columns(); c > 0; c--) {
            int bande = p.strip() + M68k.s16(acc >>> 16);
            acc += p.stripStep();
            if (feuille.beyond(bande)) {
                return true;
            }
            int entree = feuille.strip(bande);
            if (entree == 0) {
                continue;
            }
            int format = (byte) (entree >>> 24);
            int at = entree & 0xFFFFFF;
            int frac = p.texStart();
            int row = p.texStart();
            for (int r = p.rows(); r > 0; r--) {
                if (feuille.texel(format, at, row & 0xFFFF) < 0) {
                    return true;
                }
                long somme = (frac & 0xFFFFFFFFL) + (p.texStep() & 0xFFFFFFFFL);
                int retenue = (int) (somme >>> 32);
                frac = (int) somme;
                row = M68k.setw(row, (row & 0xFFFF) + (p.texStep() & 0xFFFF) + retenue);
            }
        }
        return false;
    }

    /**
     * Vrai si l'image designee tombe hors de la table des images.
     *
     * <p>Calcule ICI, d'apres les champs semes, et non d'apres ce que la pose a trouve : une
     * feuille mal retrouvee designerait une image lointaine, et se ferait ecarter au lieu
     * d'etre contredite.
     */
    private static boolean horsImage(com.google.gson.JsonArray o) {
        int rang = M68k.negw(M68k.setw(0, o.get(5).getAsInt())) & 0xFFFF;
        int image = ((o.get(6).getAsInt() & 0xFF) << 8) | (o.get(7).getAsInt() & 0xFF);
        return rang * IMAGES + image >= Data.database().frames().size() * IMAGES;
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
