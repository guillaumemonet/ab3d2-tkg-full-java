package ab3d2.full.sound;

import ab3d2.full.Data;

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
 * Verifie LE MOTEUR AUDIO, de la table des rampes aux octets que la puce recoit.
 *
 * <p>Trois choses sont eprouvees :
 *
 * <ol>
 *   <li>la TABLE DES RAMPES : soixante-cinq volumes de deux cent cinquante-six valeurs ;</li>
 *   <li>les SOIXANTE-QUATRE BRUITAGES, par leur longueur, leurs premiers et derniers octets et
 *       une signature de toute l'onde. C'est le depaqueteur a differences de Fibonacci et son
 *       ecretage qui passent la ;</li>
 *   <li>une SUITE d'appels — poser un bruit, melanger une trame — ou l'on compare apres chaque
 *       pas les huit cases, les huit voix, et les deux cents octets de chacun des quatre
 *       canaux.</li>
 * </ol>
 *
 * <p>La suite joue sur des echantillons INVENTES, que l'oracle donne. Ce n'est pas une
 * facilite : le melangeur lit toujours une trame entiere et depasse donc la fin du dernier
 * echantillon, la ou le jeu d'origine lit un tas dont personne ne sait rien. Avec des ondes
 * posees pour l'occasion, suivies de silence des deux cotes, la derniere trame redevient
 * comparable comme les autres.
 */
public final class PaulaCheck {

    private PaulaCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.paula", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=paula");
            System.exit(1);
        }
        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }

        int ecarts = 0;

        // 1. La table des rampes.
        Samples vide = Samples.of(List.of());
        byte[] table = new Paula(vide).table();
        ecarts += compare("la table des rampes", table,
                Base64.getDecoder().decode(root.get("table").getAsString()));

        // 2. Les vrais bruitages, depaquetes.
        Samples banque = Samples.load(Data.database().soundFiles(), Data::asset);
        JsonArray bruits = root.getAsJsonArray("samples");
        int trouves = 0;
        List<String> remarques = new ArrayList<>();
        for (int i = 0; i < bruits.size(); i++) {
            JsonObject c = bruits.get(i).getAsJsonObject();
            int attendu = c.get("length").getAsInt();
            if (attendu > 0) {
                trouves++;
            }
            if (banque.length(i) != attendu) {
                if (attendu == 0 && banque.length(i) > 0) {
                    // Les entrees 60 et 61 ne sont PAS des noms de bruitage : ce sont le
                    // pave de sol et les cartes de textures, atteints parce que la table
                    // deborde. Les deux hotes ne resolvent pas « TKG1: » de la meme
                    // facon, et rien ne joue jamais ces rangs-la.
                    remarques.add(String.format("bruitage %d (%s) : %d octets ici, rien"
                            + " dans l'oracle — ce n'est pas un bruitage", i,
                            c.get("file").getAsString(), banque.length(i)));
                    continue;
                }
                ecarts += note("bruitage %d (%s) : %d octets, l'oracle en donne %d", i,
                        c.get("file").getAsString(), banque.length(i), attendu);
                continue;
            }
            if (attendu == 0) {
                continue;
            }
            long somme = 0;
            byte[] onde = banque.data(i);
            for (int k = 0; k < attendu; k++) {
                somme = somme * 131 + onde[k];
            }
            if (somme != c.get("hash").getAsLong()) {
                ecarts += note("bruitage %d (%s) : signature %d, l'oracle dit %d", i,
                        c.get("file").getAsString(), somme, c.get("hash").getAsLong());
            }
            ecarts += compareSlice("bruitage " + i + " : debut", onde, 0,
                    Base64.getDecoder().decode(c.get("head").getAsString()));
            ecarts += compareSlice("bruitage " + i + " : fin", onde,
                    Math.max(0, attendu - 64),
                    Base64.getDecoder().decode(c.get("tail").getAsString()));
        }

        // 3. La suite d'appels, sur des ondes inventees.
        List<byte[]> ondes = new ArrayList<>();
        JsonArray faux = root.getAsJsonArray("fake");
        for (int i = 0; i < faux.size(); i++) {
            ondes.add(Base64.getDecoder()
                    .decode(faux.get(i).getAsJsonObject().get("wave").getAsString()));
        }
        Paula paula = new Paula(Samples.of(ondes));

        // 3a. Le balayage de l'arithmetique, toutes cases prises : seuls l'importance et le
        // partage gauche/droite bougent.
        JsonArray balayage = root.getAsJsonArray("sweep");
        for (int i = 0; i < balayage.size(); i++) {
            JsonObject c = balayage.get(i).getAsJsonObject();
            paula.reset(0xFF, true);
            paula.play(0, c.get("x").getAsInt(), c.get("z").getAsInt(),
                    c.get("vol").getAsInt(), 1, false);
            String quoi = String.format("balayage x %d, z %d, volume %d", c.get("x").getAsInt(),
                    c.get("z").getAsInt(), c.get("vol").getAsInt());
            ecarts += eq(quoi + " : importance", paula.noiseloud(),
                    c.get("noiseloud").getAsInt());
            ecarts += eq(quoi + " : decalage gauche", paula.leftOffset(),
                    c.get("leftOffset").getAsInt());
            ecarts += eq(quoi + " : decalage droit", paula.rightOffset(),
                    c.get("rightOffset").getAsInt());
        }

        JsonArray pas = root.getAsJsonArray("steps");
        int poses = 0;
        int melanges = 0;
        for (int i = 0; i < pas.size(); i++) {
            JsonObject c = pas.get(i).getAsJsonObject();
            int op = c.get("op").getAsInt();
            String quoi = "pas " + i;
            switch (op) {
                case 0 -> {
                    paula.reset(c.get("busyMask").getAsInt(),
                            c.get("stereo").getAsInt() != 0);
                    quoi += " (remise a zero)";
                }
                case 1 -> {
                    paula.play(c.get("sample").getAsInt(), c.get("x").getAsInt(),
                            c.get("z").getAsInt(), c.get("vol").getAsInt(),
                            c.get("id").getAsInt(), c.get("notIfPlaying").getAsInt() != 0);
                    quoi += String.format(" (bruit %d, x %d, z %d, volume %d, id %d)",
                            c.get("sample").getAsInt(), c.get("x").getAsInt(),
                            c.get("z").getAsInt(), c.get("vol").getAsInt(),
                            c.get("id").getAsInt());
                    ecarts += eq(quoi + " : importance", paula.noiseloud(),
                            c.get("noiseloud").getAsInt());
                    ecarts += eq(quoi + " : decalage gauche", paula.leftOffset(),
                            c.get("leftOffset").getAsInt());
                    ecarts += eq(quoi + " : decalage droit", paula.rightOffset(),
                            c.get("rightOffset").getAsInt());
                    poses++;
                }
                default -> {
                    paula.mix();
                    quoi += " (melange)";
                    JsonArray canaux = c.getAsJsonArray("channels");
                    for (int k = 0; k < 4; k++) {
                        JsonObject ch = canaux.get(k).getAsJsonObject();
                        ecarts += eq(quoi + " : volume du canal " + k, paula.volume(k),
                                ch.get("vol").getAsInt());
                        ecarts += compare(quoi + " : octets du canal " + k, paula.channel(k),
                                Base64.getDecoder().decode(ch.get("bytes").getAsString()));
                    }
                    melanges++;
                }
            }
            JsonArray cases = c.getAsJsonArray("slots");
            for (int k = 0; k < 8; k++) {
                JsonObject s = cases.get(k).getAsJsonObject();
                ecarts += eq(quoi + " : case " + k + " prise",
                        paula.claimed(k) ? 1 : 0, s.get("busy").getAsInt());
                ecarts += eq(quoi + " : case " + k + " importance",
                        paula.importance(k), s.get("importance").getAsInt());
                ecarts += eq(quoi + " : case " + k + " identifiant",
                        paula.id(k), s.get("id").getAsInt());
            }
            JsonArray voix = c.getAsJsonArray("voices");
            for (int k = 0; k < 8; k++) {
                JsonObject v = voix.get(k).getAsJsonObject();
                boolean gauche = k < 4;
                int rang = gauche ? k : k - 4;
                ecarts += eq(quoi + " : voix " + k + " bruitage",
                        paula.voiceSample(gauche, rang), v.get("sample").getAsInt());
                ecarts += eq(quoi + " : voix " + k + " position",
                        paula.voicePos(gauche, rang), v.get("pos").getAsInt());
                ecarts += eq(quoi + " : voix " + k + " fin",
                        paula.voiceEnd(gauche, rang), v.get("end").getAsInt());
                ecarts += eq(quoi + " : voix " + k + " volume",
                        paula.voiceVolume(gauche, rang), v.get("vol").getAsInt());
            }
        }

        if (!remarques.isEmpty()) {
            System.out.println();
            System.out.println("Remarques (ce que les deux hotes ne lisent pas pareil) :");
            for (String r : remarques) {
                System.out.println("  " + r);
            }
        }

        System.out.printf("%n%d bruitages depaquetes, %d points de calcul, %d bruits poses,"
                + " %d trames melangees, %d ecart(s) -> %s%n", trouves, balayage.size(),
                poses, melanges, ecarts, ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    private static int compte;

    private static int eq(String quoi, int mien, int oracle) {
        if (mien == oracle) {
            return 0;
        }
        return note("%s = %d, l'oracle dit %d", quoi, mien, oracle);
    }

    private static int compare(String quoi, byte[] mien, byte[] oracle) {
        if (mien.length != oracle.length) {
            return note("%s : %d octets, l'oracle en donne %d", quoi, mien.length,
                    oracle.length);
        }
        for (int i = 0; i < mien.length; i++) {
            if (mien[i] != oracle[i]) {
                return note("%s : octet %d = %d, l'oracle dit %d", quoi, i, mien[i],
                        oracle[i]);
            }
        }
        return 0;
    }

    private static int compareSlice(String quoi, byte[] mien, int at, byte[] oracle) {
        for (int i = 0; i < oracle.length; i++) {
            if (mien[at + i] != oracle[i]) {
                return note("%s : octet %d = %d, l'oracle dit %d", quoi, at + i, mien[at + i],
                        oracle[i]);
            }
        }
        return 0;
    }

    private static int note(String format, Object... args) {
        if (compte < 15) {
            System.out.printf("    " + format + "%n", args);
        }
        compte++;
        return 1;
    }
}
