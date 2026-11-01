package ab3d2.full.level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Les textures de SOL : seize tuiles de soixante-quatre sur soixante-quatre, dans un seul bloc.
 *
 * <p>Le bloc fait soixante-cinq mille cinq cent trente-six octets, soit exactement les seize
 * tuiles. Un octet par texel, et cet octet est deja un rang de palette — contrairement aux
 * murs, qui rangent un rang de RAMPE sur cinq bits. L'eclairage d'un sol ne se choisit donc pas
 * dans la texture mais dans la {@link ShadeTable}, apres coup.
 *
 * <p>Les tuiles sont ENTRELACEES quatre par quatre : quatre tuiles se partagent un meme
 * quadrillage, chacune occupant un octet sur quatre. C'est pourquoi le rendu avance de quatre
 * en quatre et pourquoi le numero de tuile n'est pas un numero mais un DEPLACEMENT :
 * {@code banque * 256 + rang}, ou le rang choisit l'octet dans le groupe de quatre et la banque
 * choisit le quart du bloc.
 *
 * <p>Une ligne de texels fait deux cent cinquante-six texels — quatre fois la largeur d'une
 * tuile — pour la meme raison.
 */
public record FloorTexture(String name, byte[] bytes) {

    /** La taille du bloc : seize tuiles de soixante-quatre sur soixante-quatre. */
    public static final int SIZE = 16 * 64 * 64;

    /** Lit un bloc de textures de sol. */
    public static FloorTexture read(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        if (raw.length < SIZE) {
            throw new IOException("bloc de sols trop court : " + file);
        }
        return new FloorTexture(file.getFileName().toString(), raw);
    }

    /** L'octet a cet emplacement, ou {@code -1} s'il tombe hors du bloc. */
    public int texel(int at) {
        return at < 0 || at >= bytes.length ? -1 : bytes[at] & 0xFF;
    }
}
