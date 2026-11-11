package ab3d2.full.level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Les huit images de l'ondulation de l'eau.
 *
 * <p>Un seul fichier de soixante-cinq mille cinq cent trente-six octets, range exactement comme
 * {@link FloorTexture} : un quadrillage de deux cent cinquante-six sur deux cent cinquante-six,
 * lu quatre octets a la fois. Mais ce que la case porte n'est pas une couleur, c'est une
 * RANGEE : le mot qu'on y lit designe l'une des rampes de miroitement de la table d'ombrage.
 *
 * <p>Les huit images ne sont pas huit fichiers mais huit DEPARTS dans celui-la : deux octets
 * d'ecart pour passer de l'une a l'autre dans un meme groupe de quatre, deux cent cinquante-six
 * pour changer de groupe. Les quatre positions d'un groupe et les quatre rangees se croisent,
 * et l'eau avance d'une image par battement sans qu'aucune donnee ne bouge.
 *
 * <p>Les valeurs vont de zero a deux mille trois cent quatre, et toujours par multiples de deux
 * cent cinquante-six : l'octet HAUT porte la rangee, l'octet bas est vide. Le rendu les double
 * et ne garde que cet octet haut, ce qui donne une rangee paire entre dix et dix-huit.
 *
 * @param bytes le fichier entier, tel quel
 */
public record WaterFrames(byte[] bytes) {

    /** Les huit departs, dans l'ordre ou l'animation les prend. */
    public static final int[] STARTS = {0, 2, 256, 258, 512, 514, 768, 770};

    /** Lit le fichier {@code waterfile}. */
    public static WaterFrames of(byte[] bytes) {
        return new WaterFrames(bytes);
    }

    /** Le mot a cet emplacement, ou {@code -1} s'il tombe hors du fichier. */
    public int word(int at) {
        if (at < 0 || at + 1 >= bytes.length) {
            return -1;
        }
        return ((bytes[at] & 0xFF) << 8) | (bytes[at + 1] & 0xFF);
    }
}
