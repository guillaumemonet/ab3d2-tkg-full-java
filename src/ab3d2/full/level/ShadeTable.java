package ab3d2.full.level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * La table d'ombrage des sols, des plafonds et des modeles en relief.
 *
 * <p>Soixante-quatre rangees de deux cent cinquante-six octets. Une rangee est une palette
 * entiere, vue a une certaine lumiere : {@code rangee[texel]} rend le rang de palette a
 * afficher. Eclairer n'est donc pas calculer, c'est changer de rangee.
 *
 * <p>Les trente-deux PREMIERES rangees servent ailleurs — sprites, carte, modeles. Celles des
 * sols commencent a la trente-deuxieme, et c'est de la que part {@link #FLOOR}.
 *
 * <p>Le rendu plat des sols avance de DEUX rangees par niveau d'eclairage, la ou le rendu
 * interpole en avance d'une. Il n'atteint donc qu'une rangee sur deux, et sa luminosite — qui
 * monte jusqu'a vingt-huit — sort du bloc des qu'elle depasse quinze. Voir
 * {@code FlatPaint.offTable()}.
 */
public record ShadeTable(byte[] bytes) {

    /** Combien de rangees, et combien d'octets chacune. */
    public static final int ROWS = 64;
    public static final int ROW = 256;

    /** Ou commencent les rangees des sols. */
    public static final int FLOOR = 32 * ROW;

    /** Lit la table. */
    public static ShadeTable read(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        if (raw.length < ROWS * ROW) {
            throw new IOException("table d'ombrage trop courte : " + file);
        }
        return new ShadeTable(raw);
    }

    /** Le rang de palette a cet emplacement, ou {@code -1} s'il tombe hors de la table. */
    public int colour(int at) {
        return at < 0 || at >= bytes.length ? -1 : bytes[at] & 0xFF;
    }
}
