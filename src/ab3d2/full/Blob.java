package ab3d2.full;

import java.nio.charset.StandardCharsets;

/**
 * Lecture d'un fichier d'origine, en grand-boutien.
 *
 * <p>À ne pas confondre avec la RAM plate du portage : il n'y a pas d'espace d'adressage global
 * ici. Un {@code Blob} est <b>un fichier</b> et rien d'autre, ses offsets sont ceux de ce
 * fichier, et il ne sert qu'à en tirer des objets. Rien n'écrit dedans.
 *
 * <p>C'est légitime parce que le chargeur d'origine ne transforme pas ces octets : il se
 * contente d'ajouter l'adresse de base aux offsets que le fichier porte lui-même (hires.s, la
 * mise en place des pointeurs {@code Lvl_*}). Lire le fichier directement donne donc exactement
 * ce que le jeu lisait en mémoire.
 */
public final class Blob {

    private final byte[] b;
    private final String nom;

    public Blob(byte[] bytes, String nom) {
        this.b = bytes;
        this.nom = nom;
    }

    public int size() {
        return b.length;
    }

    public String name() {
        return nom;
    }

    private void check(int off, int len) {
        if (off < 0 || off + len > b.length) {
            throw new IndexOutOfBoundsException(
                    String.format("%s : lecture de %d octets en 0x%X, hors du fichier (%d octets)",
                            nom, len, off, b.length));
        }
    }

    /** Octet non signé. */
    public int u8(int off) {
        check(off, 1);
        return b[off] & 0xFF;
    }

    /** Octet signé. */
    public int s8(int off) {
        check(off, 1);
        return b[off];
    }

    /** Mot de 16 bits, non signé. */
    public int u16(int off) {
        check(off, 2);
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    /** Mot de 16 bits, signé. */
    public int s16(int off) {
        return (short) u16(off);
    }

    /** Mot long de 32 bits. */
    public int s32(int off) {
        check(off, 4);
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    /**
     * Chaîne de longueur fixe, coupée au premier zéro et débarrassée des espaces de remplissage.
     * Le jeu stocke son texte en latin-1.
     */
    public String text(int off, int len) {
        check(off, len);
        int fin = 0;
        while (fin < len && b[off + fin] != 0) {
            fin++;
        }
        return new String(b, off, fin, StandardCharsets.ISO_8859_1).stripTrailing();
    }
}
