package ab3d2.full.render;

import ab3d2.full.defs.Database;

import static ab3d2.full.M68k.divs;
import static ab3d2.full.M68k.muls;
import static ab3d2.full.M68k.mulu;
import static ab3d2.full.M68k.negw;
import static ab3d2.full.M68k.s16;
import static ab3d2.full.M68k.setw;

/**
 * Ou se pose un MIROITEMENT — la lueur que porte un tir, une flamme, une lampe.
 *
 * <p>Ce n'est pas une variante du sprite : c'est une routine entiere a part, et le jeu y
 * branche par un test d'une brutalite exemplaire. Il regarde le mot double a l'offset huit de
 * l'enregistrement — c'est-a-dire la feuille suivie des drapeaux et de l'image — et le compare
 * a zero. S'il est NEGATIF, c'est un miroitement. Autrement dit : le signe du rang de feuille,
 * lu comme le signe d'un nombre quatre fois plus grand. {@link Appearance} nie ce rang
 * precisement pour armer ce bit, et la premiere chose que fait cette routine est de le nier a
 * nouveau pour le retrouver.
 *
 * <h2>Ce qui tombe, par rapport au sprite</h2>
 *
 * <p>La pose de {@link Bitmap} rend quatorze valeurs ; celle-ci en rend huit. Tombent :
 *
 * <ul>
 *   <li><b>la luminosite</b>. Il n'y a ni mot de luminosite propre ni rampe choisie par la
 *       distance : la routine SAUTE le mot — {@code addq #2,a0} la ou le sprite lit — et ne
 *       consulte jamais la table des rampes. Une lueur ne s'assombrit pas en s'eloignant ;</li>
 *   <li><b>le retournement</b>. Aucun drapeau n'est lu, aucune bande n'est parcourue a
 *       l'envers ;</li>
 *   <li><b>la palette de la feuille</b>. Le sprite ajoute l'adresse de sa palette a son
 *       pointeur de couleurs ; ici ce pointeur ne bouge pas, et il designe la table des
 *       TEXTURES, commune a tout le niveau. Voir {@link GlarePaint}.</li>
 * </ul>
 *
 * <h2>Et ce qui change sans tomber</h2>
 *
 * <p>Deux details, tous deux consequences du mot de drapeaux inutilise :
 *
 * <ul>
 *   <li>l'image se lit en MOT la ou le sprite lit un octet. Le mot a l'offset dix porte les
 *       drapeaux en haut et l'image en bas : une chose qui aurait des drapeaux designerait donc
 *       une image deux cent cinquante-six rangs plus loin, c'est-a-dire dans une autre
 *       feuille ;</li>
 *   <li>le saut de bandes du rognage par la gauche se fait par {@code lea (a5,d1.w*4)}, la ou
 *       le sprite decale d'abord le compte de deux bits DANS UN MOT. Le sprite peut donc perdre
 *       les deux bits de poids fort de son saut ; le miroitement, jamais. C'est la seule
 *       difference de CALCUL entre les deux poses.</li>
 * </ul>
 *
 * <h2>Ce que le corpus ne separe pas</h2>
 *
 * <p>Trois mille vingt-quatre vues, sept mille cinq cent vingt et une poses, six mille huit
 * cent trente-trois rectangles de pixels, aucun ecart. Sur vingt-neuf mutations, vingt-six
 * mordent ; les trois autres se mesurent.
 *
 * <p>Deux tiennent a la pose, et toutes deux a un debordement qui n'arrive pas :
 *
 * <ul>
 *   <li>le saut de bandes du rognage par la gauche peut s'ecrire comme celui du sprite, avec
 *       sa troncature en mot. Il y a quatre-vingt-cinq rognages par la gauche dans tout le
 *       releve et le plus grand saute CENT CINQUANTE bandes, la ou la troncature ne commence a
 *       mordre qu'au-dela de huit mille cent quatre-vingt-onze. On garde la forme de
 *       l'original, qui ne suppose rien ;</li>
 *   <li>la ligne de texture peut se lire sur le mot double entier plutot que sur son mot bas.
 *       Le mot HAUT du depart vaut zero-huit-mille sur les six mille huit cent trente-trois
 *       poses dessinees — c'est le demi-tour que la routine ajoute, et rien d'autre —, et
 *       zero-huit-mille decale de dix-sept bits fait exactement deux puissance trente-deux,
 *       c'est-a-dire zero. Les deux ecritures donnent donc la meme adresse. Elles cesseraient
 *       de le faire si le pas vertical multiplie par les lignes rognees depassait
 *       soixante-cinq mille, ce qu'aucune echelle du jeu ne produit.</li>
 * </ul>
 *
 * <p>La troisieme est celle que {@link BitmapPaint} connait deja : l'octet de format d'une
 * bande est lu SIGNE et ne vaut jamais que zero, un ou deux. Sur les huit mille cinq cent
 * cinquante-deux bandes non vides des quatorze feuilles — six mille cinq cent soixante et une
 * de format zero, mille soixante-cinq de format un, neuf cent vingt-six de format deux —,
 * aucune n'approche cent vingt-huit.
 */
public final class Glare {

    /** Combien d'images par feuille, dans la table de la base de liaison. */
    private static final int FRAMES_PER_SHEET = 32;

    /** La correction d'aspect du plein ecran, en mille vingt-quatriemes. */
    private static final int ASPECT = 927;

    private final Database glf;
    private final ScaleTable scale;

    private boolean full = true;
    private int centreX;
    private int centreY;
    private int eyeY;
    private int roomTop;
    private int roomBottom;
    private int topClip;
    private int bottomClip;
    private int leftClip;
    private int rightClip;
    private int offScale;
    private int offFrame;

    public Glare(Database glf, ScaleTable scale) {
        this.glf = glf;
        this.scale = scale;
    }

    /**
     * Pose la vue : ce qui ne change pas d'un miroitement a l'autre.
     *
     * <p>Les memes bornes que {@link Bitmap#view}, et pour cause : les deux routines les lisent
     * aux memes endroits.
     */
    public void view(boolean full, int centreX, int centreY, int eyeY, int roomTop,
            int roomBottom, int topClip, int bottomClip, int leftClip, int rightClip) {
        this.full = full;
        this.centreX = centreX;
        this.centreY = centreY;
        this.eyeY = eyeY;
        this.roomTop = roomTop;
        this.roomBottom = roomBottom;
        this.topClip = topClip;
        this.bottomClip = bottomClip;
        this.leftClip = leftClip;
        this.rightClip = rightClip;
    }

    /** Combien de fois le rapport d'echelle est sorti de sa table. */
    public int offScale() {
        return offScale;
    }

    /** Combien de fois l'image designee est sortie de la table des images. */
    public int offFrame() {
        return offFrame;
    }

    /** Remet ces comptes a zero. */
    public void clearOffTable() {
        offScale = 0;
        offFrame = 0;
    }

    /**
     * Ou le miroitement se pose, une fois rogne.
     *
     * <p>Les memes valeurs que {@link Bitmap.Placed}, moins les six qui n'ont pas de sens ici :
     * la rampe, le retournement, les deux palettes, le melange, la luminosite.
     *
     * @param left      la colonne de depart a l'ecran
     * @param top       la ligne de depart
     * @param columns   combien de colonnes, apres rognage
     * @param rows      combien de lignes, apres rognage
     * @param strip     le rang de la premiere bande a lire
     * @param stripStep de combien avancer dans les bandes par colonne, en virgule fixe
     * @param texStart  ou commencer dans la colonne de texture
     * @param texStep   de combien avancer par ligne, les deux moities echangees
     */
    public record Placed(int left, int top, int columns, int rows, int strip, int stripStep,
            int texStart, int texStep) { }

    /**
     * Le rang de feuille d'un miroitement : le mot nie, comme la routine le retrouve.
     *
     * @param sheet le mot de feuille, tel que l'enregistrement le porte
     */
    public static int sheetOf(int sheet) {
        return negw(setw(0, sheet)) & 0xFFFF;          // neg.w d7
    }

    /**
     * Pose un miroitement, ou rend {@code null} s'il ne se voit pas.
     *
     * @param t    la chose, dont le rang de feuille doit etre negatif
     * @param view la rotation, qui donne profondeur et abscisse
     */
    public Placed place(Bitmap.Thing t, ObjectView view) {
        int d1 = view.rotZ(t.point());                 // move.w 2(a1,d0.w*8),d1
        if (d1 <= Bitmap.NEAR) {
            return null;                               // ble object_behind
        }
        if (full) {
            d1 = muls(d1, ASPECT) >> 8 >> 2;           // muls #927,d1 ; asr.l #8 ; asr.l #2
        }

        // Le haut et le bas de la PIECE, a cette profondeur.
        int d6 = divs(roomTop - eyeY, d1);
        d6 = setw(d6, d6 + centreY);
        if (s16(d6) >= s16(bottomClip)) {
            return null;                               // bge object_behind
        }
        if (s16(d6) < s16(topClip)) {
            d6 = setw(d6, topClip);
        }
        int clipT = s16(d6);                           // draw_ObjClipT_w
        d6 = divs(roomBottom - eyeY, d1);
        d6 = setw(d6, d6 + centreY);
        if (s16(d6) <= s16(topClip)) {
            return null;                               // ble object_behind
        }
        if (s16(d6) > s16(bottomClip)) {
            d6 = setw(d6, bottomClip);
        }
        int clipB = s16(d6);                           // draw_ObjClipB_w

        int d0 = view.worldX(t.point()) + (s16(t.auxX()) << 7); // add.l d2,d0

        // addq #2,a0 : le mot de luminosite est SAUTE, et la table des rampes jamais ouverte.

        // La hauteur et l'abscisse a l'ecran.
        int d2 = setw(0, t.height());
        d2 = setw(d2, d2 + t.auxY());
        d2 = s16(d2) << 7;
        d2 -= eyeY;
        d2 = divs(d2, d1);
        d2 = setw(d2, d2 + centreY);
        d0 = divs(d0, d1);
        d0 = setw(d0, d0 + centreX);

        // La feuille, retrouvee en niant le rang ; l'image, lue en MOT.
        int sheet = sheetOf(t.sheet());
        int frameWord = ((t.flags() & 0xFF) << 8) | (t.frame() & 0xFF);
        Database.Frame f = frame(sheet, frameWord);
        if (f == null) {
            return null;
        }

        // La demi-taille a l'ecran.
        int d3 = t.halfWidth() << 7;                   // lsl.l #7,d3
        int d4 = t.halfDepth() << 7;
        d3 = divs(d3, d1);
        d4 = divs(d4, d1);
        d2 = setw(d2, d2 - d4);                        // le haut
        d0 = setw(d0, d0 - d3);                        // la gauche
        if (s16(d0) >= s16(rightClip)) {
            return null;                               // bge object_behind
        }
        d3 = setw(d3, d3 + d3);                        // la largeur entiere
        if (s16(d2) >= clipB) {
            return null;                               // bge object_behind
        }
        d4 = setw(d4, d4 + d4);                        // la hauteur entiere

        // Aucun retournement : la bande de depart est celle de l'image, telle quelle.
        int strip = f.strip();

        // Les deux entrees de la table des pas.
        int wide = s16(f.half() * 2 - 1);
        if (t.halfWidth() == 0) {
            return null;                               // beq object_behind
        }
        int horiz = Bitmap.divu(mulu(s16(d1), wide), t.halfWidth());
        int tall = s16(f.halfH() * 2 - 1);
        if (t.halfDepth() == 0) {
            return null;
        }
        int vert = Bitmap.divu(mulu(s16(d1), tall), t.halfDepth());
        int hStep = step(horiz);                       // (a2).l
        int vStep = step(vert);                        // (a3).l

        // Le rognage par le haut et par le bas.
        int d7 = 0;
        if (s16(d2) < clipT) {
            d2 = setw(d2, d2 - clipT);
            d4 = setw(d4, d4 + d2);
            if (s16(d4) <= 0) {
                return null;                           // ble object_behind
            }
            d7 = setw(0, -s16(d2));                    // move.w d2,d7 ; neg.w d7
            d2 = setw(d2, clipT);
        }
        d6 = setw(0, clipB);
        d6 = setw(d6, d6 - d2);
        if (s16(d4) > s16(d6)) {
            d4 = setw(d4, d6);
        }
        d4 = setw(d4, d4 - 1);                         // subq #1,d4
        if (s16(d4) < 0) {
            return null;                               // blt object_behind
        }
        int top = s16(d2);

        // Le rognage par la gauche. Le saut de bandes se fait d'un seul geste — un index de
        // mot multiplie par quatre dans une adresse —, sans le decalage en mot qui fait perdre
        // deux bits au sprite ordinaire.
        if (s16(d0) < s16(leftClip)) {
            d0 = setw(d0, d0 - leftClip);
            d3 = setw(d3, d3 + d0);
            if (s16(d3) <= 0) {
                return null;                           // ble object_behind
            }
            int n = -s16(d0);
            int hi = muls(hStep >> 16, n);
            int lo = mulu(hStep & 0xFFFF, n) >>> 16;
            strip += s16(setw(hi, hi + lo));           // lea (a5,d1.w*4),a5
            d0 = setw(d0, leftClip);
        }
        int left = s16(d0);

        // Le rognage par la droite.
        d6 = setw(0, d0);
        d6 = setw(d6, d6 + d3);
        d6 = setw(d6, d6 - rightClip);
        if (s16(d6) >= 0) {
            d3 = setw(d3, d3 - 1);
            d3 = setw(d3, d3 - d6);
        }

        // Ou commencer dans la colonne de texture.
        int vhi = muls(vStep >> 16, d7);
        int vlo = mulu(vStep & 0xFFFF, d7) >>> 16;
        int d5 = setw(vhi, vhi + vlo);
        d5 = setw(d5, d5 + f.down());
        d5 += 0x80000000;

        int texStep = (vStep >>> 16) | (vStep << 16);  // swap d2

        return new Placed(left, top, s16(d3) + 1, s16(d4) + 1, strip, hStep, d5, texStep);
    }

    /**
     * L'image designee, ou {@code null} si elle tombe hors de la table.
     *
     * <p>L'index est un MOT, et la table n'a que trente feuilles de trente-deux images : un mot
     * de drapeaux non nul y fait sortir des que la feuille est assez haute. Le jeu, lui, lirait
     * ce qui suit la table en memoire.
     */
    private Database.Frame frame(int sheet, int frameWord) {
        int flat = sheet * FRAMES_PER_SHEET + (frameWord & 0xFFFF);
        if (flat < 0 || flat >= glf.frames().size() * FRAMES_PER_SHEET) {
            offFrame++;
            return null;
        }
        return glf.frames().get(flat / FRAMES_PER_SHEET).get(flat % FRAMES_PER_SHEET);
    }

    /** Le pas de la table, ou son dernier quand l'index la depasse — voir {@link Bitmap}. */
    private int step(int at) {
        if (at >= ScaleTable.SIZE) {
            offScale++;
            return scale.step(ScaleTable.SIZE - 1);
        }
        return scale.step(at);
    }
}
