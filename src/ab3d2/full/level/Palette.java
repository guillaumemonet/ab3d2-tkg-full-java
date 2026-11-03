package ab3d2.full.level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Les deux cent cinquante-six couleurs de l'ecran.
 *
 * <p>Tout le rendu travaille en octets : un pixel n'est pas une couleur mais un RANG dans cette
 * table. Les textures, les rampes de luminosite, les melanges additifs ne manipulent jamais
 * autre chose. La couleur n'apparait qu'au tout dernier moment, quand il faut montrer l'image —
 * et c'est ici.
 *
 * <p>Le fichier range chaque composante sur un MOT dont seul l'octet bas sert : mille cinq cent
 * trente-six octets pour ce qui tiendrait en sept cent soixante-huit. C'est la forme que
 * l'Amiga attendait, et le jeu la lit telle quelle.
 *
 * @param rgb une couleur par rang, en {@code 0xAARRGGBB} opaque
 */
public record Palette(int[] rgb) {

    /** Combien de couleurs. */
    public static final int SIZE = 256;

    /** Lit la table depuis le fichier du jeu. */
    public static Palette read(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        int[] out = new int[SIZE];
        for (int i = 0; i < SIZE; i++) {
            int r = raw[(i * 3) * 2 + 1] & 0xFF;
            int g = raw[(i * 3 + 1) * 2 + 1] & 0xFF;
            int b = raw[(i * 3 + 2) * 2 + 1] & 0xFF;
            out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return new Palette(out);
    }

    /** La couleur d'un rang. */
    public int at(int index) {
        return rgb[index & 0xFF];
    }
}
