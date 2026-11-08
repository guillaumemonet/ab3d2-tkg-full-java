package ab3d2.full.level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Une feuille de sprites : trois fichiers, et une facon de ranger les pixels qui n'appartient
 * qu'a elle.
 *
 * <p>Les murs et les sols ont une texture par fichier. Les sprites en ont TROIS, du meme nom et
 * d'extensions differentes, et aucun des trois ne ressemble a une image :
 *
 * <ul>
 *   <li>le {@code .WAD} porte les pixels, mais pas en pixels : voir plus bas ;</li>
 *   <li>le {@code .PTR} est une table de BANDES, un mot double par bande verticale. Les
 *       vingt-quatre bits de poids faible disent ou la bande commence dans le premier fichier,
 *       l'octet de poids fort dit COMMENT l'y lire. Une entree nulle est une bande vide — une
 *       colonne entierement transparente, qui ne coute rien ;</li>
 *   <li>le {@code .256PAL} donne les rampes de couleurs, soixante-quatre octets par niveau de
 *       luminosite. Deux mille quarante-huit octets suffisent aux trente-deux niveaux ; les
 *       feuilles de huit mille en portent quatre jeux, pour les images qu'une lumiere
 *       colore.</li>
 * </ul>
 *
 * <h2>Cinq bits, trois par mot</h2>
 *
 * <p>Un texel de sprite ne compte que CINQ bits — trente-deux teintes, zero voulant dire
 * transparent. Trois d'entre eux tiennent donc dans un mot de seize bits, et c'est ainsi
 * qu'ils sont ranges : non pas trois pixels voisins d'une meme bande, mais le meme pixel de
 * TROIS BANDES VOISINES. Les trois bandes partagent un seul flux, et l'octet de format de la
 * table dit laquelle des trois on lit — celle des bits quatorze a dix, celle des bits neuf a
 * cinq, ou celle des bits quatre a zero.
 *
 * <p>Le bit quinze ne sert a rien. Cinq fois trois font quinze, et le seizieme est perdu.
 *
 * @param wad    les pixels, tels quels
 * @param strips la table des bandes, un entier par bande
 * @param pal    les rampes de couleurs, telles quelles
 */
public record SpriteSheet(byte[] wad, int[] strips, byte[] pal) {

    /** Ce que pese une des quatre palettes eclairees. */
    public static final int LIT_PAL = 256;

    /** Ce que pese une rampe dans une palette eclairee : huit couleurs. */
    public static final int LIT_RAMP = 8;


    /** Lit les trois fichiers d'une feuille. */
    public static SpriteSheet read(Path wadFile, Path ptrFile, Path palFile) throws IOException {
        byte[] ptr = Files.readAllBytes(ptrFile);
        int[] strips = new int[ptr.length / 4];
        for (int i = 0; i < strips.length; i++) {
            strips[i] = ((ptr[i * 4] & 0xFF) << 24) | ((ptr[i * 4 + 1] & 0xFF) << 16)
                    | ((ptr[i * 4 + 2] & 0xFF) << 8) | (ptr[i * 4 + 3] & 0xFF);
        }
        return new SpriteSheet(Files.readAllBytes(wadFile), strips, Files.readAllBytes(palFile));
    }

    /** L'entree de la bande {@code i}, ou zero si elle tombe hors de la table. */
    public int strip(int i) {
        if (i < 0 || i >= strips.length) {
            return 0;
        }
        return strips[i];
    }

    /** Vrai si la bande {@code i} tombe hors de la table. */
    public boolean beyond(int i) {
        return i < 0 || i >= strips.length;
    }

    /**
     * Le texel de la bande, sur ses cinq bits.
     *
     * @param format l'octet de poids fort de l'entree, lu comme un octet SIGNE
     * @param at     ou commence la bande, en octets
     * @param row    la ligne dans la bande
     * @return la teinte, de zero a trente-et-un, ou {@code -1} si la lecture sort du fichier
     */
    public int texel(int format, int at, int row) {
        int i = at + row * 2;
        if (format > 1) {                              // bgt .third_third
            return i < 0 || i >= wad.length ? -1 : (wad[i] & 0xFF) >>> 2 & 0x1F;
        }
        if (format == 1) {                             // beq .second_third
            if (i < 0 || i + 1 >= wad.length) {
                return -1;
            }
            return (((wad[i] & 0xFF) << 8 | (wad[i + 1] & 0xFF)) >>> 5) & 0x1F;
        }
        return i + 1 < 0 || i + 1 >= wad.length ? -1 : wad[i + 1] & 0x1F;
    }

    /**
     * Le melange de cette teinte avec ce qui est deja a l'ecran, ou {@code -1} hors de la table.
     *
     * <p>Le melange additif ne lit pas une rampe mais une TABLE A DEUX ENTREES : la teinte en
     * haut, l'octet deja pose en bas, trente-deux fois deux cent cinquante-six valeurs. Il lui
     * faut donc huit mille cent quatre-vingt-douze octets de palette, quatre fois ce dont les
     * autres se contentent — et de fait, deux feuilles seulement en portent autant, celle des
     * gros projectiles et celle des explosions. Ce sont exactement les deux qui brulent.
     *
     * @param index la teinte fois deux cent cinquante-six, plus l'octet de l'ecran
     */
    public int blend(int index) {
        if (index < 0 || index >= pal.length) {
            return -1;
        }
        return pal[index] & 0xFF;
    }

    /**
     * L'octet PLEIN qu'une image eclairee pose a cette ligne, ou {@code -1} hors du fichier.
     *
     * <p>L'image eclairee ne partage rien avec les autres : pas de format, pas de tiers, pas
     * de bandes a trois sous-pixels par mot. Un octet entier par ligne, et un pas d'UN octet.
     * C'est pourquoi sa teinte va jusqu'a deux cent cinquante-cinq quand les autres s'arretent
     * a trente et un.
     *
     * @param at  l'adresse de la bande, telle que la table la donne — SANS masque
     * @param row la ligne dans la bande
     */
    public int litTexel(int at, int row) {
        int i = at + row;
        return i < 0 || i >= wad.length ? -1 : wad[i] & 0xFF;
    }

    /**
     * Une des huit couleurs d'une rampe de la palette eclairee, ou {@code -1} hors de la table.
     *
     * <p>Les huit mille cent quatre-vingt-douze octets que lit {@link #blend} et les mille
     * vingt-quatre que lit celle-ci sont le MEME fichier, parcouru de deux facons. Ici la
     * geometrie est a trois etages : laquelle des quatre palettes eclairees, puis laquelle des
     * trente-deux rampes, puis laquelle des huit couleurs. Quatre fois trente-deux fois huit
     * font mille vingt-quatre — et de fait les feuilles des aliens pesent exactement cela.
     *
     * @param lightPal laquelle des quatre palettes, que les drapeaux de la chose designent
     * @param bright   la rampe, deja ramenee entre zero et trente et un
     * @param k        laquelle des huit couleurs
     */
    public int lit(int lightPal, int bright, int k) {
        int i = lightPal * LIT_PAL + bright * LIT_RAMP + k;
        return i < 0 || i >= pal.length ? -1 : pal[i] & 0xFF;
    }

    /** La couleur d'ecran que la rampe donne a cette teinte, ou {@code -1} hors de la table. */
    public int colour(int ramp, int texel) {
        int i = ramp + texel * 2;
        if (i < 0 || i >= pal.length) {
            return -1;
        }
        return pal[i] & 0xFF;
    }
}
