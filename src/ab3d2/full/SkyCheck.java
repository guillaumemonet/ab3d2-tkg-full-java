package ab3d2.full;

import ab3d2.full.render.Screen;
import ab3d2.full.render.Sky;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Verifie le CIEL : le fond que le rendu pose avant toute autre chose.
 *
 * <p>Il n'y a rien a relever en cours de route — pas de tranche, pas de distance, pas de pas de
 * texture. Le ciel est une recopie, et c'est la recopie entiere qu'on confronte : ligne par
 * ligne, octet par octet, sur toute la hauteur qu'il couvre.
 *
 * <p>Le releve descend QUATRE LIGNES plus bas que le ciel ne devrait aller, et ces
 * quatre-la doivent etre restees vierges : c'est ce qui interdit au rendu de deborder vers le
 * bas sans qu'on le voie.
 *
 * <p>Le corpus fait varier les deux seules choses dont il depend : l'orientation, par un pas
 * premier avec le tour pour qu'aucune colonne de depart ne revienne, et l'horizon — dont les
 * quatre premieres valeurs ne dessinent qu'un groupe de quatre lignes, mais font varier de
 * combien d'octets la lecture deborde de la colonne.
 */
public final class SkyCheck {

    private SkyCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.sky", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=sky");
            System.exit(1);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        JsonArray cas = root.getAsJsonArray("cases");

        Screen screen = new Screen();
        Sky sky = new Sky(Data.sky());
        Base64.Decoder decodeur = Base64.getDecoder();

        int ecarts = 0;
        long pixels = 0;
        int horsImage = 0;
        for (int i = 0; i < cas.size(); i++) {
            JsonObject c = cas.get(i).getAsJsonObject();
            int angle = c.get("angle").getAsInt();
            int centreY = c.get("centreY").getAsInt();
            int lignes = c.get("lines").getAsInt() + c.get("guard").getAsInt();

            screen.clear();
            sky.clearOffImage();
            sky.draw(screen, 0, angle, centreY);
            horsImage += sky.offImage();

            byte[] attendu = decodeur.decode(c.get("pixels").getAsString());
            if (attendu.length != lignes * Screen.WIDTH) {
                ecarts += note(ecarts, "angle %4d horizon %3d : l'oracle donne %d octets,"
                        + " %d lignes en font %d", angle, centreY, attendu.length, lignes,
                        lignes * Screen.WIDTH);
                continue;
            }
            pixels += attendu.length;
            boolean casse = false;
            for (int p = 0; p < attendu.length && !casse; p++) {
                byte eu = screen.pixels()[p];
                if (eu != attendu[p]) {
                    ecarts += note(ecarts, "angle %4d horizon %3d, ligne %d colonne %d : %d,"
                            + " l'oracle dit %d", angle, centreY, p / Screen.WIDTH,
                            p % Screen.WIDTH, eu & 0xFF, attendu[p] & 0xFF);
                    casse = true;
                }
            }
        }

        System.out.printf("%n%d poses, %d pixels, %d octets hors image, %d ecart(s) -> %s%n",
                cas.size(), pixels, horsImage, ecarts, ecarts == 0 ? "PASS" : "FAIL");
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
}
