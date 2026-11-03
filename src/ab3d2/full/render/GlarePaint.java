package ab3d2.full.render;

import ab3d2.full.level.ShadeTable;
import ab3d2.full.level.SpriteSheet;

import static ab3d2.full.M68k.s16;
import static ab3d2.full.M68k.setw;

/**
 * Les pixels d'un MIROITEMENT : la lueur qui teinte ce qu'elle couvre sans le cacher.
 *
 * <p>Le parcours est celui de {@link BitmapPaint} — meme accumulateur de bandes, meme lecture
 * des texels sur cinq bits, meme accumulateur vertical en deux morceaux. Une seule ligne
 * differe, et elle dit tout ce que le miroitement est.
 *
 * <h2>Une table qui n'appartient pas aux sprites</h2>
 *
 * <p>Un sprite ordinaire traduit sa teinte par la rampe de SA feuille. Un miroitement ne
 * consulte pas sa feuille du tout : il entre dans la table d'ombrage des TEXTURES — celle des
 * murs, des sols et des modeles —, a deux entrees, la teinte et l'octet DEJA POSE. Lire ce qui
 * est dessous pour decider ce qu'on ecrit : voila pourquoi une lueur colore le decor au lieu
 * de le remplacer.
 *
 * <p>L'index se fabrique ainsi : la teinte monte de neuf bits, l'octet de l'ecran occupe le
 * bas, et le tout est pris cinq cent douze octets AVANT la table. Ce recul est une economie —
 * la teinte zero ne se dessine jamais, et commencer a un plutot qu'a zero epargne une rangee
 * entiere. En rangees de deux cent cinquante-six octets, le miroitement n'atteint donc que les
 * rangees PAIRES : deux par teinte, dont la seconde ne sert a personne.
 *
 * <h2>La teinte zero reste transparente</h2>
 *
 * <p>C'est la difference avec le melange additif, qui lui couvre tout son rectangle. Le
 * miroitement saute le zero comme le sprite ordinaire : une lueur a une forme.
 */
public final class GlarePaint {

    /** De combien la table recule : la rangee de la teinte zero, qui ne sert jamais. */
    private static final int BACK = 512;

    private final Screen screen;
    private int offSheet;
    private int offStrip;

    public GlarePaint(Screen screen) {
        this.screen = screen;
    }

    /** Combien de lectures sont sorties du fichier de pixels de la feuille. */
    public int offSheet() {
        return offSheet;
    }

    /** Combien de bandes sont tombees hors de la table des bandes. */
    public int offStrip() {
        return offStrip;
    }

    /** Remet ces comptes a zero. */
    public void clearOffSheet() {
        offSheet = 0;
        offStrip = 0;
    }

    /**
     * Pose les pixels d'un miroitement deja place.
     *
     * @param p      ce que {@link Glare} a decide
     * @param sheet  la feuille de sprites, dont seuls les PIXELS servent — pas la palette
     * @param shades la table d'ombrage des textures, celle du niveau
     */
    public void paint(Glare.Placed p, SpriteSheet sheet, ShadeTable shades) {
        byte[] pixels = screen.pixels();
        int d7 = 0;                                    // l'accumulateur de bande
        int column = 0;                                // a1 : la colonne a l'ecran

        for (int c = p.columns(); c > 0; c--) {        // draw_right_side_glare (dbra d3)
            int strip = p.strip() + s16(d7 >>> 16);    // lea (a5,d7.w*4),a5
            d7 += p.stripStep();                       // add.l a2,d7

            int to = screen.line(p.top()) + p.left() + s16(column);
            column++;

            int entry = sheet.strip(strip);
            if (sheet.beyond(strip)) {
                offStrip++;
            }
            if (entry == 0) {
                continue;                              // .blank_strip
            }
            int format = (byte) (entry >>> 24);        // move.b (a5),d1 — octet SIGNE
            int at = entry & 0xFFFFFF;                 // and.l #$ffffff,d1

            int frac = p.texStart();                   // d6
            int row = p.texStart();                    // d1
            for (int r = p.rows(); r > 0; r--) {       // .draw_vertical_strip_n (dbra d4)
                int texel = sheet.texel(format, at, row & 0xFFFF);
                if (texel < 0) {
                    offSheet++;
                } else if (texel != 0) {               // beq.s .skip_black
                    // lsl.w #8,d0 ; add.w d0,d0 : la teinte monte de NEUF bits.
                    int d0 = setw(0, texel << 8);
                    d0 = setw(d0, d0 + d0);
                    // move.b (a6),d0 : l'octet deja pose occupe le bas.
                    d0 = (d0 & 0xFFFFFF00) | (pixels[to] & 0xFF);
                    int colour = shades.colour(s16(d0) - BACK);
                    if (colour < 0) {
                        // Pas un depassement : une faute. Une teinte vaut de un a
                        // trente-et-un et l'ecran de zero a deux cent cinquante-cinq, donc
                        // l'index tient TOUJOURS dans les seize mille octets de la table.
                        // Le compter avec les autres reviendrait a offrir un refuge aux
                        // fautes de calcul d'index.
                        throw new IllegalStateException("miroitement hors de la table"
                                + " d'ombrage : " + (s16(d0) - BACK));
                    }
                    pixels[to] = (byte) colour;        // move.b (a4,d0.w),(a6)
                }
                to += Screen.WIDTH;                    // adda.w #SCREEN_WIDTH,a6

                // add.l d2,d6 : la fraction, et la retenue qui en sort.
                long somme = (frac & 0xFFFFFFFFL) + (p.texStep() & 0xFFFFFFFFL);
                int retenue = (int) (somme >>> 32);
                frac = (int) somme;
                // addx.w d2,d1 : la ligne, le pas entier, et cette retenue.
                row = setw(row, (row & 0xFFFF) + (p.texStep() & 0xFFFF) + retenue);
            }
        }
    }
}
