package ab3d2.full.level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * L'image du ciel : une bande que le rendu pose en fond avant toute autre chose.
 *
 * <p>Elle est rangee COLONNE par colonne et non ligne par ligne — deux cent quarante octets
 * d'affilee font une colonne, et il y en a six cent quarante-huit. C'est l'inverse de tout le
 * reste du jeu, et c'est voulu : le ciel se lit verticalement, une colonne de l'image donnant
 * une colonne de l'ecran, de sorte que tourner sur soi-meme ne revient qu'a glisser dans le
 * fichier.
 *
 * <p>Six cent quarante-huit colonnes pour un demi-tour : l'image fait DEUX tours pendant que le
 * joueur en fait un. Il n'y a pas de bord — la derniere colonne est suivie de la premiere.
 *
 * <p>Chaque octet est un rang dans la palette de l'ecran, tel quel : le ciel ne passe par
 * aucune table d'ombrage et ne connait ni distance ni lumiere.
 *
 * @param bytes le fichier entier, tel quel
 */
public record Backdrop(byte[] bytes) {

    /** Combien de colonnes. */
    public static final int WIDTH = 648;

    /** La hauteur d'une colonne, en octets. */
    public static final int HEIGHT = 240;

    /** Lit le fichier {@code rawbackpacked}. */
    public static Backdrop read(Path file) throws IOException {
        return new Backdrop(Files.readAllBytes(file));
    }

    /** L'octet a cet emplacement, ou {@code -1} s'il tombe hors de l'image. */
    public int at(int i) {
        if (i < 0 || i >= bytes.length) {
            return -1;
        }
        return bytes[i] & 0xFF;
    }
}
