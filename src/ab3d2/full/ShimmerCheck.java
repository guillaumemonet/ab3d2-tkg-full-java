package ab3d2.full;

import ab3d2.full.render.Shimmer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * La DEMATERIALISATION, confrontee au portage pixel par pixel.
 *
 * <p>Trois motifs, huit pas chacun, deux cent quarante-cinq mille sept cent soixante pixels par
 * pas. Rien n'est echantillonne : chaque octet du resultat est compare.
 *
 * <p>L'epreuve est severe parce que les motifs sont choisis pour l'etre — un damier ou chaque
 * octet differe de ses voisins, des bandes qui disent leur numero de ligne, et du bruit. Un
 * decalage d'un seul pixel, horizontal ou vertical, ne passe aucun des trois.
 */
public final class ShimmerCheck {

    private ShimmerCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path ref = Path.of(System.getProperty("ab3d2.shimmer", "shimmer.json"));
        JsonObject racine;
        try (Reader r = Files.newBufferedReader(ref)) {
            racine = JsonParser.parseReader(r).getAsJsonObject();
        }

        Shimmer shimmer = new Shimmer(Data.shimmer());
        Base64.Decoder decodeur = Base64.getDecoder();

        int cas = 0;
        int pixels = 0;
        int ecarts = 0;
        for (var e : racine.getAsJsonArray("cases")) {
            JsonObject c = e.getAsJsonObject();
            String motif = c.get("pattern").getAsString();
            byte[] source = decodeur.decode(c.get("source").getAsString());
            JsonArray images = c.getAsJsonArray("frames");
            for (int f = 0; f < images.size(); f++) {
                byte[] attendu = decodeur.decode(images.get(f).getAsString());
                // Le tampon est reutilise d'un appel a l'autre : on le recopie avant de
                // comparer, sinon l'appel suivant l'aurait deja ecrase.
                byte[] obtenu = shimmer.apply(source, f).clone();
                cas++;
                for (int i = 0; i < attendu.length; i++) {
                    pixels++;
                    if (attendu[i] != obtenu[i]) {
                        if (ecarts < 10) {
                            System.out.printf("  ECART %s pas %d, pixel %d (ligne %d, colonne"
                                    + " %d) : attendu %d, obtenu %d%n",
                                    motif, f, i, i / 320, i % 320,
                                    attendu[i] & 0xFF, obtenu[i] & 0xFF);
                        }
                        ecarts++;
                    }
                }
            }
        }

        int tableAttendue = racine.get("tableBytes").getAsInt();
        if (Data.shimmer().length < tableAttendue) {
            System.out.printf("  ECART table : %d octets, %d attendus%n",
                    Data.shimmer().length, tableAttendue);
            ecarts++;
        }

        System.out.printf("shimmerCheck : %d images, %d pixels, %d ecarts%n",
                cas, pixels, ecarts);
        if (ecarts != 0) {
            System.exit(1);
        }
        System.out.println("PASS");
    }
}
