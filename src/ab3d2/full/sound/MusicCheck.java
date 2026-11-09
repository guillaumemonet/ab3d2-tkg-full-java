package ab3d2.full.sound;

import ab3d2.full.Data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifie LE LECTEUR DE MUSIQUE, image par image.
 *
 * <p>On joue mille images — vingt secondes de morceau — et on compare apres chacune tout ce que
 * le lecteur tient : sa vitesse, sa position dans le morceau et dans le motif, son compteur,
 * ses drapeaux, l'etat de ses quatre voix, et surtout les REGISTRES de la puce, qui sont sa
 * seule sortie.
 *
 * <p>Deux series : celle du JEU, ou une seule voix joue sur le canal zero, et celle de la
 * musique de fin, ou les quatre jouent. La seconde ne sert a rien en jeu, mais elle eprouve
 * les trois voix que la premiere laisse dormir.
 */
public final class MusicCheck {

    private MusicCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.music", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=musicplay");
            System.exit(1);
        }
        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }

        Path f = Data.asset("tkg2:music/packedtest");
        if (f == null || !Files.isRegularFile(f)) {
            System.out.println("module introuvable");
            System.exit(1);
        }
        byte[] module = Files.readAllBytes(f);
        if (module.length != root.get("moduleLength").getAsInt()) {
            System.out.printf("module de %d octets, l'oracle en donne %d%n", module.length,
                    root.get("moduleLength").getAsInt());
            System.exit(1);
        }

        int ecarts = 0;
        int images = 0;
        ecarts += serie("le morceau du jeu, une voix", module, false,
                root.getAsJsonArray("single"));
        images += root.getAsJsonArray("single").size();
        ecarts += serie("le morceau du jeu, quatre voix", module, true,
                root.getAsJsonArray("all"));
        images += root.getAsJsonArray("all").size();

        // Les modules FABRIQUES : un par famille d'effets. Le morceau du jeu ne tient qu'une
        // note et deux fondus, et n'eprouverait ni l'arpege, ni les glissandos, ni le vibrato,
        // ni les ruptures.
        JsonArray faits = root.getAsJsonArray("made");
        for (int i = 0; i < faits.size(); i++) {
            JsonObject c = faits.get(i).getAsJsonObject();
            byte[] m = java.util.Base64.getDecoder().decode(c.get("module").getAsString());
            ecarts += serie("module " + i + ", une voix", m, false,
                    c.getAsJsonArray("single"));
            ecarts += serie("module " + i + ", quatre voix", m, true,
                    c.getAsJsonArray("all"));
            images += c.getAsJsonArray("single").size() + c.getAsJsonArray("all").size();
        }

        System.out.printf("%n%d etats compares sur %d morceaux, %d ecart(s) -> %s%n",
                images, 1 + faits.size(), ecarts, ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    private static int serie(String nom, byte[] module, boolean toutes, JsonArray pas) {
        // Le module tel qu'il est donne, pour la signature.
        Paula paula = new Paula(Samples.of(java.util.List.of()));
        Music music = new Music(module);
        music.useAllChannels = toutes;
        music.init(paula);

        int ecarts = 0;
        for (int i = 0; i < pas.size(); i++) {
            JsonObject c = pas.get(i).getAsJsonObject();
            if (c.get("frame").getAsInt() >= 0) {
                music.tick(paula);
            }
            String quoi = nom + ", image " + c.get("frame").getAsInt();
            if (c.has("moduleHash")) {
                // mt_init ECRIT dans les donnees : il met a zero les deux premiers mots de
                // chaque echantillon, pour que la boucle d'un echantillon sans reprise se
                // taise au lieu de repeter son attaque.
                long somme = 0;
                byte[] d = music.data();
                for (int k = 0; k < module.length; k++) {
                    somme = somme * 131 + d[k];
                }
                if (somme != c.get("moduleHash").getAsLong()) {
                    ecarts += eqLong(quoi + " : signature du module", somme,
                            c.get("moduleHash").getAsLong());
                }
            }
            ecarts += eq(quoi + " : vitesse", music.speed(), c.get("speed").getAsInt());
            ecarts += eq(quoi + " : position", music.songPosition(),
                    c.get("songpos").getAsInt());
            ecarts += eq(quoi + " : ligne", music.patternPosition(),
                    c.get("pattpos").getAsInt());
            ecarts += eq(quoi + " : compteur", music.counter(), c.get("counter").getAsInt());
            ecarts += eq(quoi + " : rupture", music.broken() ? 0xFF : 0,
                    c.get("brk").getAsInt());
            ecarts += eq(quoi + " : fin atteinte", music.reachedEnd() ? 0xFF : 0,
                    c.get("reachedEnd").getAsInt());
            ecarts += eq(quoi + " : canaux en marche", paula.dmaconBits(),
                    c.get("dmacon").getAsInt());

            JsonArray canaux = c.getAsJsonArray("channels");
            for (int ch = 0; ch < 4; ch++) {
                JsonObject k = canaux.get(ch).getAsJsonObject();
                ecarts += eq(quoi + " : canal " + ch + " adresse",
                        paula.hwStart(ch, music.nullSample()), k.get("lc").getAsInt());
                ecarts += eq(quoi + " : canal " + ch + " longueur", paula.hwWords(ch),
                        k.get("len").getAsInt());
                ecarts += eq(quoi + " : canal " + ch + " periode", paula.hwPeriod(ch),
                        k.get("per").getAsInt());
                ecarts += eq(quoi + " : canal " + ch + " volume", paula.volume(ch),
                        k.get("vol").getAsInt());
                ecarts += eq(quoi + " : canal " + ch + " segment",
                        paula.hwSegStart(ch, music.nullSample()), k.get("segLc").getAsInt());
                ecarts += eq(quoi + " : canal " + ch + " longueur du segment",
                        paula.hwSegWords(ch), k.get("segLen").getAsInt());
            }

            JsonArray voix = c.getAsJsonArray("voices");
            for (int v = 0; v < 4; v++) {
                JsonObject k = voix.get(v).getAsJsonObject();
                String ou = quoi + " : voix " + v;
                ecarts += eq(ou + " note", music.note(v), k.get("note").getAsInt());
                ecarts += eq(ou + " commande", music.cmd(v), k.get("cmd").getAsInt());
                ecarts += eq(ou + " depart", music.start(v), k.get("start").getAsInt());
                ecarts += eq(ou + " longueur", music.length(v), k.get("length").getAsInt());
                ecarts += eq(ou + " boucle", music.loopStart(v), k.get("loopStart").getAsInt());
                ecarts += eq(ou + " longueur de boucle", music.loopLength(v),
                        k.get("loopLength").getAsInt());
                ecarts += eq(ou + " periode", music.period(v), k.get("period").getAsInt());
                ecarts += eq(ou + " volume", music.volume(v), k.get("volume").getAsInt());
                ecarts += eq(ou + " sens du glissando", music.portDir(v),
                        k.get("portDir").getAsInt());
                ecarts += eq(ou + " vitesse du glissando", music.portSpeed(v),
                        k.get("portSpeed").getAsInt());
                ecarts += eq(ou + " cible du glissando", music.portTarget(v),
                        k.get("portTarget").getAsInt());
                ecarts += eq(ou + " vibrato", music.vibParam(v), k.get("vibParam").getAsInt());
                ecarts += eq(ou + " pas du vibrato", music.vibPos(v),
                        k.get("vibPos").getAsInt());
            }
        }
        return ecarts;
    }

    private static int compte;

    private static int eqLong(String quoi, long mien, long oracle) {
        if (mien == oracle) {
            return 0;
        }
        if (compte < 15) {
            System.out.printf("    %s = %d, l'oracle dit %d%n", quoi, mien, oracle);
        }
        compte++;
        return 1;
    }

    private static int eq(String quoi, int mien, int oracle) {
        if (mien == oracle) {
            return 0;
        }
        if (compte < 15) {
            System.out.printf("    %s = %d, l'oracle dit %d%n", quoi, mien, oracle);
        }
        compte++;
        return 1;
    }
}
