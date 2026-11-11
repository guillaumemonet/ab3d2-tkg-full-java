package ab3d2.full;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * La table de sinus du jeu.
 *
 * <p>On la LIT plutot que de la calculer, et ce n'est pas un scrupule d'archiviste : le
 * deplacement du joueur, la trajectoire des projectiles et la visee des monstres dependent de
 * ses arrondis. Une table recalculee en virgule flottante donnerait des valeurs voisines et un
 * jeu different.
 *
 * <p>Elle porte huit mille cent quatre-vingt-douze mots, soit DEUX tours de quatre mille
 * quatre-vingt-seize : le second tour est la copie du premier, ce qui permet de lire un cosinus
 * en avancant d'un quart de tour sans jamais repasser par zero. L'angle n'est d'ailleurs pas un
 * indice mais un DEPLACEMENT EN OCTETS, parce que le code d'origine s'en sert directement comme
 * tel.
 */
public final class SinCos {

    /** Le quart de tour qui separe le cosinus du sinus, en octets. */
    public static final int COSINE_OFFSET = 2048;

    /** Le masque qui ramene un angle dans un tour, en octets. */
    public static final int MASK = 8190;

    private final short[] table;

    private SinCos(short[] table) {
        this.table = table;
    }

    /** Lit la table depuis les donnees d'origine. */
    public static SinCos of(byte[] raw) {
        short[] t = new short[raw.length / 2];
        for (int i = 0; i < t.length; i++) {
            t[i] = (short) (((raw[i * 2] & 0xFF) << 8) | (raw[i * 2 + 1] & 0xFF));
        }
        return new SinCos(t);
    }

    /** Le sinus d'un angle donne en octets. */
    public int sin(int angle) {
        return table[(angle & MASK) >> 1];
    }

    /** Le cosinus d'un angle donne en octets. */
    public int cos(int angle) {
        return table[((angle + COSINE_OFFSET) & MASK) >> 1];
    }

    /**
     * La valeur brute a un deplacement, SANS ramener l'angle dans un tour.
     *
     * <p>Le code d'origine s'autorise a lire au-dela du premier tour, puisque la table en porte
     * deux ; quelques routines en dependent.
     */
    public int value(int angle) {
        return table[(angle & MASK) >> 1];
    }
}
