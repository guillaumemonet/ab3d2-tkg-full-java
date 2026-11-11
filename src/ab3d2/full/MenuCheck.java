package ab3d2.full;

import ab3d2.full.menu.MenuFire;
import ab3d2.full.menu.MenuPalette;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * LE MENU, confronte au portage plan par plan.
 *
 * <p>Quatre choses sont eprouvees, et chacune peut echouer seule :
 *
 * <ol>
 *   <li>la SOURCE DE BRUIT — dix-huit mille quatre cent trente-deux octets d'un registre a
 *       decalage. Si elle differe d'un seul octet, le feu entier differe ;</li>
 *   <li>le FOND dedouble — quarante mille neuf cent soixante octets ;</li>
 *   <li>les trois plans de POLICE apres impression d'une chaine connue. La police est la
 *       SOURCE du feu : une lettre mal posee fait bruler le mauvais endroit ;</li>
 *   <li>les trois plans de FEU a six instants — zero, une, deux, trois, huit et trente-deux
 *       images. Les quatre premiers couvrent le cycle entier des decalages.</li>
 * </ol>
 *
 * <p>Et la PALETTE, deux cent cinquante-six couleurs calculees et non lues.
 */
public final class MenuCheck {

    private MenuCheck() {
    }

    private static int ecarts;
    private static int octets;

    public static void main(String[] args) throws Exception {
        Path ref = Path.of(System.getProperty("ab3d2.menufx", "menufx.json"));
        JsonObject racine;
        try (Reader r = Files.newBufferedReader(ref)) {
            racine = JsonParser.parseReader(r).getAsJsonObject();
        }
        Base64.Decoder d = Base64.getDecoder();

        MenuFire feu = new MenuFire(Data.menu("back2.raw"), Data.menu("font16x16.raw2"));

        comparer("la source de bruit", d.decode(racine.get("noise").getAsString()), feu.noise());
        comparer("le fond dedouble", d.decode(racine.get("background").getAsString()),
                feu.background());

        // La palette : deux cent cinquante-six couleurs calculees.
        MenuPalette pal = new MenuPalette(Data.menu("back.pal"), Data.menu("firepal.pal2"),
                Data.menu("font16x16.pal2"));
        JsonArray couleurs = racine.getAsJsonArray("palette");
        for (int c = 0; c < couleurs.size(); c++) {
            octets++;
            long attendu = couleurs.get(c).getAsLong() & 0xFFFFFF;
            if (attendu != (pal.at(c) & 0xFFFFFF)) {
                if (ecarts < 10) {
                    System.out.printf("  ECART palette rang %d : attendu %06X, obtenu %06X%n",
                            c, attendu, pal.at(c) & 0xFFFFFF);
                }
                ecarts++;
            }
        }

        // La police : la meme chaine, au meme endroit.
        feu.clearText();
        feu.print(racine.get("text").getAsString(), racine.get("textX").getAsInt(),
                racine.get("textY").getAsInt());
        comparer("les plans de police", d.decode(racine.get("fontPlanes").getAsString()),
                feu.fontPlanes());

        // Le feu, instant par instant.
        int courant = 0;
        for (var e : racine.getAsJsonArray("steps")) {
            JsonObject pas = e.getAsJsonObject();
            int cible = pas.get("frame").getAsInt();
            while (courant < cible) {
                feu.frame();
                courant++;
            }
            if (feu.counter() != pas.get("counter").getAsLong()) {
                System.out.printf("  ECART compteur a l'image %d : attendu %d, obtenu %d%n",
                        cible, pas.get("counter").getAsLong(), feu.counter());
                ecarts++;
            }
            if (feu.scrollLine() != (pas.get("screenpos").getAsInt() & 255)) {
                System.out.printf("  ECART defilement a l'image %d : attendu %d, obtenu %d%n",
                        cible, pas.get("screenpos").getAsInt() & 255, feu.scrollLine());
                ecarts++;
            }
            comparer("le feu a l'image " + cible,
                    d.decode(pas.get("firePlanes").getAsString()), feu.firePlanes());
        }

        System.out.printf("menuCheck : %d octets compares, %d ecarts%n", octets, ecarts);
        if (ecarts != 0) {
            System.exit(1);
        }
        System.out.println("PASS");
    }

    private static void comparer(String quoi, byte[] attendu, byte[] obtenu) {
        if (attendu.length != obtenu.length) {
            System.out.printf("  ECART %s : %d octets attendus, %d obtenus%n",
                    quoi, attendu.length, obtenu.length);
            ecarts++;
            return;
        }
        int premiers = 0;
        for (int i = 0; i < attendu.length; i++) {
            octets++;
            if (attendu[i] != obtenu[i]) {
                if (premiers < 4 && ecarts < 20) {
                    System.out.printf("  ECART %s, octet %d : attendu %02X, obtenu %02X%n",
                            quoi, i, attendu[i] & 0xFF, obtenu[i] & 0xFF);
                    premiers++;
                }
                ecarts++;
            }
        }
    }
}
