package ab3d2.full;

import ab3d2.full.M68k;
import ab3d2.full.level.Level;
import ab3d2.full.level.SpriteSheet;
import ab3d2.full.render.Bitmap;
import ab3d2.full.render.BitmapPaint;
import ab3d2.full.render.LitPalette;
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
 * Verifie l'image ECLAIREE : la palette qu'elle se fabrique, puis les pixels qu'elle pose.
 *
 * <p>C'est la quatrieme et derniere des boucles de sprite, et la seule qui ne lise aucune
 * palette toute faite. Seize des vingt monstres du jeu se dessinent ainsi : ce n'est pas un cas
 * de bord, c'est la voie ordinaire des aliens.
 *
 * <h2>Deux epreuves, pas une</h2>
 *
 * <p>On confronte d'abord les deux cent trente-deux octets de palette, puis le rectangle de
 * pixels. Les separer n'est pas un luxe : une erreur dans le calcul d'eclairage et une erreur
 * dans la boucle de pixels donnent toutes deux des pixels faux, et rien ne les distinguerait.
 *
 * <h2>Les anneaux viennent de l'oracle</h2>
 *
 * <p>La palette ne depend des anneaux d'eclairage que par leurs soixante-quatre octets. On les
 * prend donc tels que l'oracle les a releves, plutot que de refaire le chemin qui y mene : ce
 * chemin a sa propre verification, et l'y melanger ne ferait que rendre les ecarts illisibles.
 *
 * <h2>La trainee</h2>
 *
 * <p>Le tampon du jeu fait trois cent quatre-vingt-douze octets, la boucle n'en remplit que
 * deux cent trente-deux, et l'octet du sprite va jusqu'a deux cent cinquante-cinq. Ce qui se
 * trouve au-dela est, dans le jeu, ce que le sprite PRECEDENT y a laisse — la source l'a
 * remarque et a note {@code FIXME: illegal reads} en face de la ligne. Rien ne le definit, et
 * deux programmes differents ne sauraient en heriter la meme chose. L'oracle le met donc a zero
 * avant chaque sprite, et ce portage ne l'ecrit jamais : la confrontation porte sur ce que la
 * routine ECRIT, et le tampon entier est compare, trainee comprise.
 */
public final class BitmapLitCheck {

    /** La hauteur du tampon. */
    private static final int HAUTEUR = 256;

    private BitmapLitCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.bitmaplit", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=bitmaplit");
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
        LitPalette pals = new LitPalette(Data.guff());
        Base64.Decoder decodeur = Base64.getDecoder();

        int ecarts = 0;
        int sprites = 0;
        long pixels = 0;
        long bornees = 0;
        int horsFeuille = 0;
        int horsGrille = 0;
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
            JsonArray entrees = c.getAsJsonArray("inputs");
            JsonArray anneaux = c.getAsJsonArray("rings");
            JsonArray socles = c.getAsJsonArray("bases");
            JsonArray clartes = c.getAsJsonArray("ramps");
            JsonArray palettes = c.getAsJsonArray("pals");
            JsonArray octets = c.getAsJsonArray("pixels");
            int angle = c.get("angle").getAsInt();

            List<ObjectView.Thing> choses = new ArrayList<>(objets.size());
            for (int e = 0; e < objets.size(); e++) {
                Level.Entity ent = lvl.entities().get(e);
                choses.add(new ObjectView.Thing(ent.x(), ent.z(), ent.zone(), ent.zone(),
                        ent.upperZone(), ent.kind() == Level.Kind.AUX, ent.point()));
            }
            view.update(choses, lvl.objectPoints(), c.get("x").getAsInt(),
                    c.get("z").getAsInt(), angle, 0, true);
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
                    horsTable++;
                    continue;
                }
                JsonArray b = attendus.get(e).getAsJsonArray();
                if (p == null) {
                    ecartsNiveau += note(ecarts + ecartsNiveau, "%c angle %d, objet %d : ecarte,"
                            + " l'oracle le pose", lettre, angle, e);
                    continue;
                }
                int haut = b.get(0).getAsInt();
                int colonne = b.get(1).getAsInt();
                int colonnes = b.get(2).getAsInt();
                int lignes = b.get(3).getAsInt();
                if (p.top() != haut || p.left() != colonne || p.columns() != colonnes
                        || p.rows() != lignes) {
                    ecartsNiveau += note(ecarts + ecartsNiveau, "%c angle %d, objet %d : pose en"
                            + " (%d, %d) %dx%d, l'oracle dit (%d, %d) %dx%d", lettre, angle, e,
                            p.top(), p.left(), p.columns(), p.rows(), haut, colonne, colonnes,
                            lignes);
                    continue;
                }

                // Ce que la POSE a decide de l'eclairage : si cela diverge, la palette qui suit
                // divergerait pour une raison qui n'est pas la sienne. On le dit ici.
                JsonArray dit = entrees.get(e).getAsJsonArray();
                int ditFlip = dit.get(6).getAsInt();
                int ditBright = dit.get(7).getAsInt();
                int ditPal = dit.get(8).getAsInt();
                if (!p.light() || (p.flip() ? 1 : 0) != (ditFlip != 0 ? 1 : 0)
                        || p.bright() != ditBright || p.lightPal() != ditPal) {
                    ecartsNiveau += note(ecarts + ecartsNiveau, "%c angle %d, objet %d : eclaire"
                            + " %b retourne %b clarte %d palette %d, l'oracle dit vrai %d %d %d",
                            lettre, angle, e, p.light(), p.flip(), p.bright(), p.lightPal(),
                            ditFlip, ditBright, ditPal);
                    continue;
                }

                SpriteSheet feuille = Data.sheet(o.get(5).getAsInt() & 0x7FFF);

                // ---- la palette ----
                byte[] ring = decodeur.decode(anneaux.get(e).getAsString());
                pals.clearCounts();
                pals.build(ring, dit.get(5).getAsInt(), p.flip(), p.bright(), p.lightPal(),
                        feuille);
                // La TACHE : si l'ORACLE l'a puisee hors de la grille, il a lu la memoire
                // voisine et il n'y a rien a confronter. C'est lui qui en decide, et non nous :
                // laisser le portage s'exclure lui-meme donnerait a une faute qui deplace la
                // tache le moyen de se taire. Une mutation faisait exactement cela.
                int socle = socles.get(e).getAsInt();
                if (socle < 0 || socle + (LitPalette.SIDE - 1) * 16 + 15 >= Data.guff().length) {
                    horsGrille++;
                    continue;
                }
                if (pals.base() != socle) {
                    ecartsNiveau += note(ecarts + ecartsNiveau, "%c angle %d, objet %d : tache"
                            + " puisee en %d, l'oracle dit %d", lettre, angle, e, pals.base(),
                            socle);
                    continue;
                }
                byte[] attenduRampes = decodeur.decode(clartes.get(e).getAsString());
                int fausse = -1;
                for (int k = 0; k < attenduRampes.length; k++) {
                    if (pals.ramp(k) != (attenduRampes[k] & 0xFF)) {
                        fausse = k;
                        break;
                    }
                }
                if (fausse >= 0) {
                    ecartsNiveau += note(ecarts + ecartsNiveau, "%c angle %d, objet %d : clarte"
                            + " [%d] = %d, l'oracle dit %d", lettre, angle, e, fausse,
                            pals.ramp(fausse), attenduRampes[fausse] & 0xFF);
                    continue;
                }
                if (pals.offGuff() > 0) {
                    ecartsNiveau += note(ecarts + ecartsNiveau, "%c angle %d, objet %d : %d"
                            + " lecture(s) hors de la grille, l'oracle n'en fait aucune",
                            lettre, angle, e, pals.offGuff());
                    continue;
                }
                if (pals.offPal() > 0) {
                    // La feuille ne porte pas les quatre jeux eclaires : l'original lit au-dela
                    // du fichier. Les clartes, elles, viennent d'etre confrontees.
                    horsFeuille++;
                    continue;
                }
                bornees += pals.clamped();
                byte[] attenduPal = decodeur.decode(palettes.get(e).getAsString());
                byte[] euPal = pals.bytes();
                int faux = -1;
                for (int k = 0; k < attenduPal.length; k++) {
                    if (euPal[k] != attenduPal[k]) {
                        faux = k;
                        break;
                    }
                }
                if (faux >= 0) {
                    ecartsNiveau += note(ecarts + ecartsNiveau, "%c angle %d, objet %d : palette"
                            + " [%d] = %d, l'oracle dit %d", lettre, angle, e, faux,
                            euPal[faux] & 0xFF, attenduPal[faux] & 0xFF);
                    continue;
                }

                // ---- les pixels ----
                if (horsBande(p, feuille)) {
                    horsBande++;
                    continue;
                }
                motif(screen, haut, colonne, colonnes, lignes);
                paint.clearOffSheet();
                paint.paintLit(p, feuille, pals);
                if (paint.offSheet() > 0) {
                    horsFeuille++;
                    continue;
                }
                sprites++;

                byte[] attendu = decodeur.decode(octets.get(e).getAsString());
                pixels += attendu.length;
                boolean casse = false;
                for (int l = 0; l < lignes && !casse; l++) {
                    for (int k = 0; k < colonnes; k++) {
                        byte eu = screen.pixels()[screen.line(haut + l) + colonne + k];
                        if (eu != attendu[l * colonnes + k]) {
                            ecartsNiveau += note(ecarts + ecartsNiveau, "%c angle %d, objet %d,"
                                    + " pixel (%d, %d) : %d, l'oracle dit %d", lettre, angle, e,
                                    k, l, eu & 0xFF, attendu[l * colonnes + k] & 0xFF);
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

        System.out.printf("%n%d vues, %d sprites eclaires, %d pixels, %d clartes bornees,"
                + " %d hors grille, %d hors feuille, %d hors bande, %d hors table,"
                + " %d ecart(s) -> %s%n",
                cas.size(), sprites, pixels, bornees, horsGrille, horsFeuille, horsBande,
                horsTable, ecarts, ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    /**
     * Vrai si le sprite lit une bande au-dela de la table des bandes de sa feuille.
     *
     * <p>On refait ICI le parcours des bandes, plutot que de demander a la routine eprouvee ce
     * qu'elle a lu : laisser la routine decider de son propre acquittement donnerait a une
     * faute un moyen de se cacher.
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
