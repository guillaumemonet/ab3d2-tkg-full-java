package ab3d2.full.level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Une texture de mur, telle qu'elle est rangee sur la disquette.
 *
 * <p>Le fichier tient en trois morceaux qui se suivent : <b>deux mille quarante-huit octets de
 * rampes d'ombrage</b>, puis les <b>pixels</b>, puis <b>deux octets</b> qui donnent la hauteur.
 * Rien d'autre — ni largeur, ni compteur, ni signature. C'est le pan de mur qui porte les
 * dimensions, sous forme de masques, et le rendu les prend de la sans jamais consulter le
 * fichier.
 *
 * <p>Les pixels sont ranges par <b>groupes de trois colonnes</b> : chaque mot de seize bits
 * porte trois valeurs de cinq bits, une par colonne, et un bit reste perdu. Les hauteurs d'un
 * groupe se suivent avant que le groupe suivant ne commence — c'est un rangement par colonnes,
 * ce qu'un mur reclame, puisqu'il se dessine colonne par colonne. Un groupe de garde termine
 * toujours le fichier, meme quand la largeur tombe juste sur un multiple de trois.
 *
 * <p>Les rampes sont trente-deux suites de trente-deux couleurs, de la plus sombre a la plus
 * claire, chaque couleur occupant deux octets dont le rendu ne lit que le premier. Une texture
 * ne porte donc pas des couleurs mais des RANGS, et c'est la rampe choisie par la distance qui
 * decide de ce qu'on voit.
 *
 * @param name  le nom du fichier, sans chemin ni extension
 * @param bytes le fichier entier, tel quel
 */
public record WallTexture(String name, byte[] bytes) {

    /** Ou commencent les rampes d'ombrage : au tout debut. */
    public static final int RAMPS = 0;

    /** Combien de rampes, et combien d'octets chacune. */
    public static final int RAMP_COUNT = 32;
    public static final int RAMP_SIZE = 64;

    /** Ou commencent les pixels. */
    public static final int CHUNK = RAMP_COUNT * RAMP_SIZE;

    /** Ce que porte le pied du fichier. */
    private static final int FOOTER = 2;

    /** Lit un fichier {@code .256wad}. */
    public static WallTexture read(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        if (raw.length < CHUNK + FOOTER) {
            throw new IOException("texture trop courte : " + file);
        }
        String base = file.getFileName().toString();
        int dot = base.lastIndexOf('.');
        return new WallTexture(dot < 0 ? base : base.substring(0, dot), raw);
    }

    /**
     * La hauteur de la texture, lue au pied du fichier.
     *
     * <p>Le rendu ne s'en sert pas : il prend le masque et le decalage dans le pan de mur. Elle
     * sert a decrire le fichier, et a verifier que les deux disent bien la meme chose.
     */
    public int height() {
        return ((bytes[bytes.length - 2] & 0xFF) << 8) | (bytes[bytes.length - 1] & 0xFF);
    }

    /** Combien de groupes de trois colonnes, groupe de garde compris. */
    public int groups() {
        int h = height();
        return h <= 0 ? 0 : (bytes.length - CHUNK - FOOTER) / (h * 2);
    }

    /** Le mot de seize bits a cet emplacement, ou {@code -1} s'il tombe hors du fichier. */
    public int word(int at) {
        if (at < 0 || at + 1 >= bytes.length) {
            return -1;
        }
        return ((bytes[at] & 0xFF) << 8) | (bytes[at + 1] & 0xFF);
    }

    /** L'octet de couleur a cet emplacement des rampes. */
    public int ramp(int at) {
        return bytes[at] & 0xFF;
    }
}
