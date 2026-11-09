package ab3d2.full;

import ab3d2.full.render.Hud;
import ab3d2.full.render.Text;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Verifie le TABLEAU DE BORD, pixel par pixel.
 *
 * <p>Quatre choses y sont eprouvees, et chacune contre les octets que le portage fidele pose
 * vraiment a l'ecran :
 *
 * <ol>
 *   <li>la BORDURE, quatre-vingt-un mille neuf cent vingt octets, depuis ses huit plans de
 *       bits ;</li>
 *   <li>les CINQ JEUX DE CHIFFRES, remis a plat depuis leur entrelacement par dix ;</li>
 *   <li>le BANDEAU en entier — pas seulement les trois rectangles ecrits — pour chaque etat de
 *       munitions, d'energie, d'armes tenues et d'arme choisie ;</li>
 *   <li>le RETICULE et une ligne de TEXTE en police proportionnelle.</li>
 * </ol>
 *
 * <p>Les derniers cas du bandeau sont des cas EN SUITE : l'oracle y a joue un etat avant celui
 * qu'il releve. Le portage, lui, ne connait que l'etat final et redessine tout. Qu'ils passent
 * est la preuve que la paresse du jeu d'origine — ne redessiner un chiffre que s'il a change —
 * ne change rien a ce qu'on voit.
 */
public final class HudCheck {

    private static final int SPAN = Hud.WIDTH;

    private HudCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.hudraster", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=hudraster");
            System.exit(1);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }

        Hud hud = new Hud(Data.borderPacked(), Data.borderChars());
        Text texte = new Text(Data.scrollFont(), Data.glyphSpacing());

        int ecarts = 0;
        ecarts += compare("la bordure", hud.border(), bytes(root, "border"));

        JsonObject chiffres = root.getAsJsonObject("digits");
        ecarts += compare("les chiffres ordinaires", hud.digitsGood(), bytes(chiffres, "good"));
        ecarts += compare("les chiffres d'alerte", hud.digitsWarn(), bytes(chiffres, "warn"));
        ecarts += compare("les cases vides", hud.digitsItem(), bytes(chiffres, "item"));
        ecarts += compare("les cases tenues", hud.digitsItemFound(),
                bytes(chiffres, "itemFound"));
        ecarts += compare("la case choisie", hud.digitsItemSelected(),
                bytes(chiffres, "itemSelected"));

        int barTop = root.get("barTop").getAsInt();
        byte[] ecran = new byte[Hud.WIDTH * Hud.HEIGHT];

        JsonArray cas = root.getAsJsonArray("cases");
        int suites = 0;
        for (int i = 0; i < cas.size(); i++) {
            JsonObject c = cas.get(i).getAsJsonObject();
            int ammo = c.get("ammo").getAsInt();
            int energy = c.get("energy").getAsInt();
            int masque = c.get("slots").getAsInt();
            int choisie = c.get("selected").getAsInt();
            if (c.has("prevAmmo")) {
                suites++;
            }
            // Le tampon est SEME, et non efface : le bandeau doit poser lui-meme chacune de ses
            // vingt-quatre lignes, y compris les huit dernieres que l'image de bordure laisse
            // noires. Un tampon efface ne distinguerait pas « pose du noir » de « ne pose rien ».
            semer(ecran);
            boolean[] armes = new boolean[10];
            for (int k = 0; k < 10; k++) {
                armes[k] = (masque >> k & 1) != 0;
            }
            hud.frame(ecran, SPAN, ammo, energy, armes, choisie);

            byte[] attendu = Base64.getDecoder().decode(c.get("bar").getAsString());
            byte[] mien = new byte[attendu.length];
            System.arraycopy(ecran, barTop * SPAN, mien, 0, attendu.length);
            ecarts += compare(String.format("bandeau %d (munitions %d, energie %d, armes %03X,"
                    + " choisie %d)", i, ammo, energy, masque, choisie), mien, attendu);
        }

        JsonArray reticules = root.getAsJsonArray("crosshair");
        for (int i = 0; i < reticules.size(); i++) {
            JsonObject c = reticules.get(i).getAsJsonObject();
            semer(ecran);
            hud.crosshair(ecran, SPAN, c.get("colour").getAsInt(), Hud.WIDTH / 2, 240,
                    c.get("stopOffset").getAsInt(), true);
            ecarts += compareEcrits(String.format("reticule %d (encre %d, regard %d)", i,
                    c.get("colour").getAsInt(), c.get("stopOffset").getAsInt()), ecran, c);
        }

        JsonArray lignes = root.getAsJsonArray("text");
        for (int i = 0; i < lignes.size(); i++) {
            JsonObject c = lignes.get(i).getAsJsonObject();
            String mot = c.get("text").getAsString();
            semer(ecran);
            int reste = texte.prop(ecran, SPAN, c.get("maxLen").getAsInt(), mot,
                    c.get("x").getAsInt(), c.get("y").getAsInt(), c.get("pen").getAsInt());
            String quoi = String.format("texte %d (« %s », encre %d, x %d)", i, mot,
                    c.get("pen").getAsInt(), c.get("x").getAsInt());
            if (reste != c.get("rest").getAsInt()) {
                ecarts += note(ecarts, "%s : reste %d, l'oracle dit %d", quoi, reste,
                        c.get("rest").getAsInt());
            }
            int largeur = texte.width(mot, c.get("maxLen").getAsInt());
            if (largeur != c.get("width").getAsInt()) {
                ecarts += note(ecarts, "%s : largeur %d, l'oracle dit %d", quoi, largeur,
                        c.get("width").getAsInt());
            }
            ecarts += compareEcrits(quoi, ecran, c);
        }

        JsonArray decoupes = root.getAsJsonArray("splits");
        int morceaux = 0;
        for (int i = 0; i < decoupes.size(); i++) {
            JsonObject c = decoupes.get(i).getAsJsonObject();
            String mot = c.get("text").getAsString();
            int largeur = c.get("width").getAsInt();
            JsonArray attendus = c.getAsJsonArray("pieces");
            int[] curseur = {0};
            int reste = mot.length();
            for (int k = 0; k < attendus.size(); k++) {
                JsonObject m = attendus.get(k).getAsJsonObject();
                String quoi = String.format("decoupe %d (« %s », %d px), morceau %d", i, mot,
                        largeur, k);
                if (curseur[0] != m.get("from").getAsInt()) {
                    ecarts += note(ecarts, "%s : part du rang %d, l'oracle dit %d", quoi,
                            curseur[0], m.get("from").getAsInt());
                    break;
                }
                int tient = texte.split(curseur, mot, reste, largeur);
                if (tient != m.get("fit").getAsInt()) {
                    ecarts += note(ecarts, "%s : %d caractere(s), l'oracle dit %d", quoi, tient,
                            m.get("fit").getAsInt());
                }
                if (curseur[0] != m.get("next").getAsInt()) {
                    ecarts += note(ecarts, "%s : suivant au rang %d, l'oracle dit %d", quoi,
                            curseur[0], m.get("next").getAsInt());
                }
                reste -= tient;
                morceaux++;
                if (curseur[0] < 0) {
                    break;
                }
            }
        }

        System.out.printf("%n%d etats de bandeau (dont %d en suite), %d reticules,"
                + " %d lignes de texte, %d morceaux de decoupe, %d ecart(s) -> %s%n",
                cas.size(), suites, reticules.size(), lignes.size(), morceaux, ecarts,
                ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    /**
     * Le damier seme dans le tampon avant chaque appel.
     *
     * <p>Aucune de ses valeurs n'est nulle, et c'est tout l'interet : on releve ensuite ce qui
     * a CHANGE, sur l'ecran entier. Une ecriture hors de l'endroit prevu se verrait, et une
     * encre nulle posee par erreur aussi — sur un tampon efface, elle passerait inapercue.
     */
    private static void semer(byte[] ecran) {
        for (int i = 0; i < ecran.length; i++) {
            ecran[i] = (byte) graine(i);
        }
    }

    private static int graine(int at) {
        return (at * 7 + (at / SPAN) * 13) % 251 + 1;
    }

    /** Tout ce que la routine a ecrit dans l'ecran entier, compare a ce que l'oracle a releve. */
    private static int compareEcrits(String quoi, byte[] ecran, JsonObject c) {
        JsonArray attendu = c.getAsJsonArray("written");
        StringBuilder mien = new StringBuilder();
        int poses = 0;
        for (int i = 0; i < ecran.length; i++) {
            int v = ecran[i] & 0xFF;
            if (v != graine(i)) {
                if (poses * 2 < attendu.size()
                        && (attendu.get(poses * 2).getAsInt() != i
                            || attendu.get(poses * 2 + 1).getAsInt() != v)) {
                    return note(compte, "%s : pixel %d = %d, l'oracle pose %d = %d", quoi, i, v,
                            attendu.get(poses * 2).getAsInt(),
                            attendu.get(poses * 2 + 1).getAsInt());
                }
                poses++;
                if (mien.length() < 60) {
                    mien.append(i).append('=').append(v).append(' ');
                }
            }
        }
        if (poses * 2 != attendu.size()) {
            return note(compte, "%s : %d pixel(s) ecrit(s) [%s], l'oracle en pose %d", quoi,
                    poses, mien.toString().trim(), attendu.size() / 2);
        }
        return 0;
    }

    private static byte[] bytes(JsonObject o, String quoi) {
        return Base64.getDecoder().decode(o.get(quoi).getAsString());
    }

    private static int compte;

    private static int compare(String quoi, byte[] mien, byte[] oracle) {
        if (mien.length != oracle.length) {
            return note(compte, "%s : %d octets, l'oracle en donne %d", quoi, mien.length,
                    oracle.length);
        }
        for (int i = 0; i < mien.length; i++) {
            if (mien[i] != oracle[i]) {
                return note(compte, "%s : octet %d = %d, l'oracle dit %d", quoi, i,
                        mien[i] & 0xFF, oracle[i] & 0xFF);
            }
        }
        return 0;
    }

    private static int note(int dejaVus, String format, Object... args) {
        if (compte < 15) {
            System.out.printf("    " + format + "%n", args);
        }
        compte++;
        return 1;
    }
}
