package ab3d2.full.level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Le bloc de textures des modeles en relief.
 *
 * <p>Un seul fichier pour tout le jeu, cent trente et un mille soixante-douze octets, soit
 * exactement DEUX banques de soixante-quatre kilo-octets. Les murs n'y touchent pas : ils ont
 * leurs propres fichiers. Ce bloc-ci ne sert qu'a {@code doapoly}.
 *
 * <h2>Seize textures entrelacees par banque</h2>
 *
 * <p>Une banque porte seize textures de soixante-quatre par soixante-quatre, et elles ne se
 * suivent pas : elles sont ENTRELACEES, texel par texel. Le rendu lit
 * {@code (base + deplacement)[(v * 256 + u) * 4]}, c'est-a-dire
 * {@code v * 1024 + u * 4 + deplacement}. Une ligne pese donc mille vingt-quatre octets, et
 * les seize deplacements {@code k * 256 + j} — avec {@code j} et {@code k} de zero a trois —
 * la couvrent exactement une fois chacun.
 *
 * <p>L'entrelacement n'est pas une fantaisie : il permet de construire l'adresse d'un texel
 * SANS masque ni multiplication. Le rendu tient {@code u} et {@code v} en virgule fixe dans
 * deux registres, les masque d'un coup a vingt-deux bits, decale celui de {@code v} de huit
 * places et ECRASE l'octet bas par {@code u}. Le mot obtenu vaut {@code v * 256 + u}, et le
 * mode d'adressage du processeur le multiplie par quatre au passage. Rien d'autre a faire —
 * au prix d'un rangement ou chaque texture est eparpillee de quatre en quatre.
 *
 * <p>Sur les trente-deux textures que les deux banques portent, le jeu en nomme
 * vingt-sept : treize dans la premiere, quatorze dans la seconde.
 *
 * @param bytes le fichier entier, tel quel
 */
public record TextureMaps(byte[] bytes) {

    /** Combien d'octets par banque. */
    public static final int BANK = 65536;

    /** Combien d'octets par ligne de texture, les quatre entrelacements compris. */
    public static final int LINE = 1024;

    /** Combien de texels de cote. */
    public static final int SIDE = 64;

    /** Lit le bloc. */
    public static TextureMaps read(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        if (raw.length < 2 * BANK) {
            throw new IOException("bloc de textures trop court : " + file);
        }
        return new TextureMaps(raw);
    }

    /**
     * Le texel a cet emplacement.
     *
     * @param at l'emplacement de la texture : son deplacement dans le bloc, la seconde banque
     *           comprise — c'est ce que rend {@code ModelFace.texture(face)}
     * @param uv le mot que le rendu compose, {@code v * 256 + u}
     */
    public int texel(int at, int uv) {
        return bytes[at + uv * 4] & 0xFF;              // move.b (a0,d0.w*4),d3
    }

    /** Combien de texels le bloc porte en tout, entrelacement compris. */
    public int size() {
        return bytes.length;
    }
}
