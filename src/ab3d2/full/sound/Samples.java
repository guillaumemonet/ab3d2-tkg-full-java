package ab3d2.full.sound;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * LES SOIXANTE-QUATRE BRUITAGES, et le dépaquetage qui les rend audibles.
 *
 * <p>Les fichiers ne portent pas des octets d'onde mais un flux de DIFFERENCES, quatre bits par
 * echantillon, lues dans une table de seize valeurs qui est une suite de Fibonacci signee :
 * {@code -34 -21 -13 -8 -5 -3 -2 -1 0 1 2 3 5 8 13 21}. Un octet donne donc deux echantillons,
 * et chacun est le precedent plus ce que la table dit. Le premier octet du flux, lui, est la
 * valeur de depart.
 *
 * <p>Deux details du chargeur d'origine ({@code io_LoadSample}, file_io.s:294) :
 *
 * <ul>
 *   <li>le resultat est ECRETE a l'intervalle de moins soixante-quatre a soixante-trois. Les
 *       differences peuvent faire sortir le cumul de l'octet ; le jeu ne le corrige pas, il le
 *       coupe, APRES coup, sur tout l'echantillon ;</li>
 *   <li>le compte de boucle est decremente sur un MOT alors que la taille est un long. Aucun
 *       bruitage du jeu n'atteint les soixante-cinq mille octets, donc cela ne se voit pas.</li>
 * </ul>
 *
 * <h2>Le rembourrage</h2>
 *
 * <p>Le melangeur lit par trames de deux cents octets et ne s'arrete qu'APRES avoir depasse la
 * fin : il lit donc jusqu'a cent quatre-vingt-dix-neuf octets au-dela de l'echantillon. Sur
 * l'Amiga ces octets-la sont ceux du tas, et personne ne sait ce qu'ils valent. Ici chaque
 * echantillon est suivi de silence, ce qui est la seule chose qu'on puisse decider.
 */
public final class Samples {

    /** NUM_SFX. */
    public static final int COUNT = 64;

    /** La suite qui sert de table de differences. */
    private static final int[] FIB = {-34, -21, -13, -8, -5, -3, -2, -1, 0, 1, 2, 3, 5, 8, 13, 21};

    /** L'en-tete d'un echantillon compresse : {@code 'CSFX'}. */
    private static final int MAGIC = 0x43534658;

    /** De quoi absorber la lecture au-dela de la fin. */
    private static final int PAD = 256;

    private final byte[][] data = new byte[COUNT][];
    private final int[] len = new int[COUNT];
    private final String[] names = new String[COUNT];

    private Samples() {
    }

    /**
     * Charge les bruitages nommes par la base de liaison.
     *
     * @param files   les soixante-quatre noms, dans l'ordre
     * @param resolve ce qui transforme un nom Amiga en fichier, ou null s'il n'existe pas
     */
    public static Samples load(List<String> files, Function<String, Path> resolve) {
        Samples s = new Samples();
        for (int i = 0; i < COUNT; i++) {
            String nom = i < files.size() ? files.get(i) : "";
            s.names[i] = nom;
            byte[] brut = null;
            if (!nom.isBlank()) {                      // tst.b (a0) ; beq.s .skip
                // Toutes les entrees ne sont pas des noms de fichier : la cinquante-neuvieme
                // est un COMMENTAIRE laisse dans la table (« * RESERVED FOR FOOTSTEP SOUNDS *»)
                // et les deux suivantes debordent sur les noms du pave de sol et des textures.
                // Le jeu les passe au systeme de fichiers comme les autres, et n'obtient rien.
                Path f;
                try {
                    f = resolve.apply(nom);
                } catch (RuntimeException e) {
                    f = null;
                }
                if (f != null && Files.isRegularFile(f)) {
                    try {
                        brut = Files.readAllBytes(f);
                    } catch (IOException e) {
                        throw new UncheckedIOException("bruitage illisible : " + f, e);
                    }
                }
            }
            byte[] onde = brut == null ? new byte[0] : decode(brut);
            s.len[i] = onde.length;
            s.data[i] = new byte[onde.length + PAD];
            System.arraycopy(onde, 0, s.data[i], 0, onde.length);
        }
        return s;
    }

    /**
     * Une banque faite d'ondes DEJA depaquetees, pour le harnais de verification.
     *
     * <p>Le melangeur ne connait des echantillons que leurs octets et leur longueur : des
     * ondes inventees l'eprouvent aussi bien que les vraies, et elles ont l'avantage de
     * rendre comparable la derniere trame, celle qui lit au-dela de la fin.
     */
    public static Samples of(List<byte[]> ondes) {
        Samples s = new Samples();
        for (int i = 0; i < COUNT; i++) {
            byte[] onde = i < ondes.size() ? ondes.get(i) : new byte[0];
            s.names[i] = "";
            s.len[i] = onde.length;
            s.data[i] = new byte[onde.length + PAD];
            System.arraycopy(onde, 0, s.data[i], 0, onde.length);
        }
        return s;
    }

    /**
     * Le dépaquetage d'un fichier {@code CSFX}, ou le fichier tel quel s'il n'en est pas un.
     */
    static byte[] decode(byte[] f) {
        if (f.length < 9 || lire32(f, 0) != MAGIC) {
            return f;                                  // deja des octets d'onde
        }
        int taille = lire32(f, 4);                     // move.l (a0)+,d0 : la taille depackee
        if (taille <= 0 || taille > 1 << 24) {
            return new byte[0];
        }
        byte[] out = new byte[taille];
        int at = 8;
        int reste = (short) (taille - 2);              // sub.w #2,d0 : un MOT, pas un long
        int d1 = f[at++];                              // premiere valeur, telle quelle
        out[0] = (byte) d1;
        int ecrit = 1;
        while (ecrit < taille && at < f.length) {
            int d2 = f[at++] & 0xFF;                   // move.b (a0)+,d2 ; and.w #$00ff,d2
            d1 = (byte) (d1 + FIB[(d2 >> 4) & 0xF]);   // premiere difference
            out[ecrit++] = (byte) d1;
            if (reste-- == 0) {                        // dbra d0,.continue
                break;                                 // .sample_finished
            }
            if (ecrit >= taille) {
                break;
            }
            d1 = (byte) (d1 + FIB[d2 & 0xF]);          // seconde difference
            out[ecrit++] = (byte) d1;
            if (reste-- == 0) {
                break;
            }
        }
        // .clip_loop : l'onde est ramenee dans l'intervalle que la puce sait jouer.
        for (int i = 0; i < out.length; i++) {
            int v = out[i];
            if (v > 63) {                              // cmp.b #64,d1 ; blt.s .not_too_big
                v = 63;
            }
            if (v < -64) {                             // cmp.b #-64,d1 ; bge.s .not_too_small
                v = -64;
            }
            out[i] = (byte) v;
        }
        return out;
    }

    private static int lire32(byte[] f, int at) {
        return ((f[at] & 0xFF) << 24) | ((f[at + 1] & 0xFF) << 16)
                | ((f[at + 2] & 0xFF) << 8) | (f[at + 3] & 0xFF);
    }

    /**
     * Les octets d'un bruitage, suivis de leur rembourrage.
     *
     * <p>Un rang hors des soixante-quatre rend le silence : le jeu, lui, lirait l'adresse zero.
     */
    public byte[] data(int num) {
        return num >= 0 && num < COUNT ? data[num] : data[0];
    }

    /** Sa longueur utile, sans le rembourrage. */
    public int length(int num) {
        return num >= 0 && num < COUNT ? len[num] : 0;
    }

    /** Le nom du fichier d'origine. */
    public String name(int num) {
        return num >= 0 && num < COUNT ? names[num] : "";
    }

    /** Le rang d'un tampon d'echantillon, ou moins un s'il n'en est pas un. */
    public int indexOf(byte[] d) {
        for (int i = 0; i < COUNT; i++) {
            if (data[i] == d) {
                return i;
            }
        }
        return -1;
    }

    /** Combien de bruitages ont vraiment ete trouves. */
    public int found() {
        int n = 0;
        for (int i = 0; i < COUNT; i++) {
            if (len[i] > 0) {
                n++;
            }
        }
        return n;
    }
}
