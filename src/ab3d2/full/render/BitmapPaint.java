package ab3d2.full.render;

import ab3d2.full.level.SpriteSheet;

import static ab3d2.full.M68k.s16;
import static ab3d2.full.M68k.setw;

/**
 * Les pixels d'un sprite : la boucle simple, celle qui pose l'image telle quelle.
 *
 * <p>Elle est a {@link Bitmap} ce que {@link WallPaint} est a {@link Wall}. Trois autres
 * boucles existent — le miroitement, le melange additif, l'image eclairee — et elles ne sont
 * pas celle-ci : chacune remplace la derniere ligne, celle qui depose l'octet.
 *
 * <h2>Une colonne d'ecran, une bande de texture</h2>
 *
 * <p>Le sprite ne se deforme pas : chaque colonne de l'ecran prend une BANDE de la feuille,
 * designee par un accumulateur en virgule fixe dont on ne garde que la moitie haute. Une bande
 * dont la table donne zero est entierement transparente : la colonne est sautee d'un bloc, et
 * c'est la seule optimisation de toute la routine.
 *
 * <p>Le zero est aussi la transparence au niveau du TEXEL : la teinte zero ne s'ecrit pas, et
 * ce qui est dessous reste. C'est pourquoi les sprites se dessinent apres le decor, et du plus
 * loin au plus proche.
 *
 * <h2>Un accumulateur en deux morceaux</h2>
 *
 * <p>L'avance verticale ne tient pas dans un registre. Le jeu en garde deux : un mot double
 * pour la fraction, un mot pour la ligne. A chaque pixel il ajoute le pas au premier, puis au
 * second le pas ENTIER plus la retenue du premier — un {@code addx.w} apres un {@code add.l}.
 *
 * <p>Et le pas sert aux deux : {@link Bitmap} le range les deux moities echangees, de sorte que
 * sa moitie basse porte la partie entiere et sa haute la fraction. L'addition longue ajoute
 * donc AUSSI la partie entiere dans la moitie basse de l'accumulateur de fraction, ou elle ne
 * sert a rien — mais ou elle finit par deborder, et ce debordement-la remonte dans la fraction.
 * Ce n'est pas une erreur du portage : c'est ce que le processeur fait, et le resultat en
 * depend.
 *
 * <h2>Deux precautions que les donnees rendent inutiles</h2>
 *
 * <p>L'octet de format est lu SIGNE et l'adresse de la bande masquee a vingt-quatre bits.
 * Aucune des deux ne sert : sur les onze mille trois cents bandes des quatorze feuilles, le
 * format ne vaut que zero, un ou deux — jamais cent vingt-huit ou plus —, et la plus grande
 * adresse est de cent quarante mille, la ou le vingt-quatrieme bit en demanderait huit
 * millions. Les deux mutations correspondantes sont les seules a survivre au corpus, et c'est
 * demontrable plutot que mesure.
 */
public final class BitmapPaint {

    private final Screen screen;
    private int offSheet;

    public BitmapPaint(Screen screen) {
        this.screen = screen;
    }

    /** Combien de lectures sont sorties de la feuille ou de sa palette. */
    public int offSheet() {
        return offSheet;
    }

    /** Remet ce compte a zero. */
    public void clearOffSheet() {
        offSheet = 0;
    }

    /**
     * Pose les pixels d'un sprite deja place.
     *
     * @param p     ce que {@link Bitmap} a decide
     * @param sheet la feuille de sprites
     */
    public void paint(Bitmap.Placed p, SpriteSheet sheet) {
        byte[] pixels = screen.pixels();
        int d7 = 0;                                    // l'accumulateur de bande
        int column = 0;                                // a1 : la colonne a l'ecran

        for (int c = p.columns(); c > 0; c--) {        // draw_right_side (dbra d3)
            // La moitie HAUTE de l'accumulateur donne la bande : un swap, et le mot bas.
            int strip = p.strip() + s16(d7 >>> 16);    // lea (a5,d7.w*4),a5
            d7 += p.stripStep();                       // add.l a2,d7

            int to = screen.line(p.top()) + p.left() + s16(column);
            column++;

            int entry = sheet.strip(strip);
            if (sheet.beyond(strip)) {
                offSheet++;
            }
            if (entry == 0) {
                continue;                              // .blank_strip : colonne transparente
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
                    int colour = sheet.colour(p.ramp(), texel);
                    if (colour < 0) {
                        offSheet++;
                    } else {
                        pixels[to] = (byte) colour;    // move.b (a4,d0.w*2),(a6)
                    }
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
