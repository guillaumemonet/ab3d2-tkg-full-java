package ab3d2.full.render;

import ab3d2.full.defs.Database;

import static ab3d2.full.M68k.divs;
import static ab3d2.full.M68k.muls;
import static ab3d2.full.M68k.mulu;
import static ab3d2.full.M68k.s16;
import static ab3d2.full.M68k.setw;

/**
 * Ou se pose un sprite, a quelle echelle, et par quel bout de sa texture commencer.
 *
 * <p>C'est a {@link Sprites} ce que {@link Wall} est a {@link WallPaint} : la geometrie, pas les
 * pixels. Le sprite est une image plate toujours face a l'oeil ; tout le travail consiste a
 * trouver le rectangle qu'elle occupe a l'ecran, a le rogner, et a dire de combien avancer dans
 * la texture par colonne et par ligne.
 *
 * <h2>Trois echelles, trois divisions</h2>
 *
 * <p>La profondeur sert trois fois, et chaque fois differemment. Elle donne d'abord la hauteur
 * a l'ecran du haut et du bas de la PIECE, ce qui borne le sprite verticalement. Elle donne
 * ensuite la position et la demi-taille du sprite lui-meme, par la meme division. Elle donne
 * enfin, melangee a la taille de l'image, l'index d'entree dans {@link ScaleTable} — d'ou le
 * pas de texture.
 *
 * <p>En plein ecran, la profondeur recoit d'abord une correction de plus : neuf cent vingt-sept
 * mille vingt-quatriemes, soit un peu moins d'un dixieme de reduction. C'est la troisieme
 * correction d'aspect du moteur, et elle ne ressemble a aucune des deux autres.
 *
 * <h2>Une table de luminosite qu'on peut depasser</h2>
 *
 * <p>La rampe de couleurs se choisit par {@code profondeur/64 + luminosite propre}, borne par le
 * bas a zero et par le haut... par rien du tout. La table n'a que quatre-vingt-un mots ; au-dela
 * le jeu lit ce qui la suit en memoire. {@link #offTable()} compte l'evenement, et il est
 * frequent : un sprite un peu lointain y tombe toujours.
 *
 * <h2>Ce que le corpus ne separe pas</h2>
 *
 * <p>Sur trente-huit mutations, trente-trois mordent. Des cinq qui restent, deux demandent une
 * valeur EXACTE — une profondeur de vingt-cinq tout juste, une hauteur egale au bas de la
 * fenetre — et une demanderait de sauter plus de huit mille bandes d'un coup, ce qu'aucune
 * echelle ne produit. La quatrieme est une mutation ratee de ma part, qui reecrit un calcul
 * par lui-meme.
 *
 * <p>La cinquieme merite mieux qu'une excuse : c'est le debordement de {@code divu}. Il est
 * reel et porte, mais INOBSERVABLE ici, et cela se demontre — quand il a lieu, l'index retenu
 * vaut le mot bas du produit, c'est-a-dire soixante-cinq mille moins la profondeur, donc
 * toujours au-dela des huit mille entrees de la table des pas. Les deux ecritures sortent donc
 * de la table ensemble, et la verification les ecarte ensemble.
 */
public final class Bitmap {

    /** En deca de cette profondeur, le sprite est derriere l'oeil. */
    public static final int NEAR = 25;

    /** La correction d'aspect du plein ecran, en mille vingt-quatriemes. */
    private static final int ASPECT = 927;

    /**
     * La table des rampes de luminosite : quatre-vingt-un mots.
     *
     * <p>Un mot a zero, puis DEUX mots pour chacun des trente niveaux, puis vingt mots au
     * dernier. Elle avance donc deux fois plus lentement que son index, et sature vingt crans
     * avant sa fin — une facon de dire que les objets lointains ont tous la meme teinte.
     */
    private static final int[] RAMPS = ramps();

    private static int[] ramps() {
        int[] out = new int[81];
        int at = 0;
        out[at++] = 0;
        for (int n = 1; n <= 30; n++) {
            out[at++] = 64 * n;
            out[at++] = 64 * n;
        }
        while (at < out.length) {
            out[at++] = 64 * 31;
        }
        return out;
    }

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
    private int offTable;
    private int offScale;

    public Bitmap(Database glf, ScaleTable scale) {
        this.glf = glf;
        this.scale = scale;
    }

    /**
     * Pose la vue : ce qui ne change pas d'un sprite a l'autre.
     *
     * @param full       plein ecran, ou vue encadree
     * @param centreX    le milieu de l'ecran
     * @param centreY    l'horizon
     * @param eyeY       la hauteur de l'oeil, en virgule fixe comme le jeu la tient
     * @param roomTop    le haut de la piece, en hauteur du monde
     * @param roomBottom le bas de la piece
     * @param topClip    la fenetre verticale du secteur
     * @param bottomClip la fenetre verticale du secteur
     * @param leftClip   la fenetre horizontale
     * @param rightClip  la fenetre horizontale
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

    /** Combien de fois la luminosite est sortie de sa table. */
    public int offTable() {
        return offTable;
    }

    /** Combien de fois le rapport d'echelle est sorti de sa table. */
    public int offScale() {
        return offScale;
    }

    /** Remet les deux comptes a zero. */
    public void clearOffTable() {
        offTable = 0;
        offScale = 0;
    }

    /**
     * Ce qu'il faut savoir d'une chose pour la poser a l'ecran.
     *
     * <p>Rien ici ne vient du fichier de niveau seul : la feuille, l'image et les drapeaux sont
     * de l'etat d'animation, et la demi-largeur et la demi-profondeur viennent de la definition
     * de l'objet. C'est la simulation qui les fournit.
     *
     * @param point      le rang du point, pour y prendre la profondeur et l'abscisse
     * @param bright     la luminosite propre, ajoutee a celle de la distance
     * @param height     la hauteur au-dessus du sol
     * @param halfWidth  la demi-largeur dans le monde, en octet
     * @param halfDepth  la demi-hauteur dans le monde, en octet
     * @param sheet      la feuille de sprites. Son bit de poids fort marque un sprite de
     *                   MIROITEMENT, qui se dessine tout autrement
     * @param flags      bit sept : image retournee. Les sept autres, moins deux : zero a trois
     *                   designent une palette eclairee, quatre et au-dela un melange additif
     * @param frame      l'image en cours dans la feuille
     * @param auxX       l'ecart d'une chose auxiliaire, zero sinon
     * @param auxY       l'ecart d'une chose auxiliaire, zero sinon
     */
    public record Thing(int point, int bright, int height, int halfWidth, int halfDepth,
            int sheet, int flags, int frame, int auxX, int auxY) {

        /** Vrai si la chose est un sprite de miroitement, dessine par une autre voie. */
        public boolean glare() {
            return (sheet & 0x8000) != 0;
        }
    }

    /**
     * Ou le sprite se pose, une fois rogne.
     *
     * @param left      la colonne de depart a l'ecran
     * @param top       la ligne de depart
     * @param columns   combien de colonnes, apres rognage
     * @param rows      combien de lignes, apres rognage
     * @param strip     le rang de la premiere bande a lire
     * @param stripStep de combien avancer dans les bandes par colonne, en virgule fixe
     * @param texStart  ou commencer dans la colonne de texture
     * @param texStep   de combien avancer par ligne, les deux moities echangees
     * @param ramp      le decalage de rampe dans la palette de la feuille
     * @param flip      l'image est retournee
     * @param light     l'image prend une palette eclairee
     * @param additive  l'image se melange a ce qui est dessous
     * @param lightPal  laquelle des quatre palettes eclairees
     * @param bright    la luminosite retenue, AVANT d'etre bornee a zero
     */
    public record Placed(int left, int top, int columns, int rows, int strip, int stripStep,
            int texStart, int texStep, int ramp, boolean flip, boolean light, boolean additive,
            int lightPal, int bright) { }

    /**
     * Pose un sprite, ou rend {@code null} s'il ne se voit pas.
     *
     * @param t    la chose
     * @param view la rotation, qui donne profondeur et abscisse
     */
    public Placed place(Thing t, ObjectView view) {
        int d1 = view.rotZ(t.point());                 // move.w 2(a1,d0.w*8),d1
        if (d1 <= NEAR) {
            return null;                               // ble object_behind
        }
        if (full) {
            d1 = muls(d1, ASPECT) >> 8 >> 2;           // muls #927,d1 ; asr.l #8 ; asr.l #2
        }

        // Le haut et le bas de la PIECE, a cette profondeur : c'est la fenetre verticale du
        // sprite, et elle se calcule par la meme division que tout le reste.
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

        // La rampe : la distance d'abord, la luminosite propre ensuite, et aucune borne haute.
        d6 = setw(0, s16(d1) >> 6);
        d6 = setw(d6, d6 + t.bright());
        int bright = s16(d6);                          // draw_BrightToAdd_w, avant la borne
        if (bright < 0) {
            d6 = 0;                                    // bge.s .brighttoonot ; moveq #0,d6
        }
        int ramp = ramp(s16(d6));

        // pastobjscale : la hauteur et l'abscisse a l'ecran.
        int d2 = setw(0, t.height());
        d2 = setw(d2, d2 + t.auxY());
        d2 = s16(d2) << 7;
        d2 -= eyeY;
        d2 = divs(d2, d1);
        d2 = setw(d2, d2 + centreY);
        d0 = divs(d0, d1);
        d0 = setw(d0, d0 + centreX);

        Database.Frame f = glf.frames().get(t.sheet() & 0x7FFF).get(t.frame());
        boolean flip = (t.flags() & 0x80) != 0;        // btst #7 ; sne draw_FlipIt_b
        boolean light = false;
        boolean additive = false;
        int lightPal = 0;
        int kind = (t.flags() & 127) - 2;              // and.b #127 ; sub.b #2
        if ((byte) kind >= 0) {                        // blt.s .not_a_light
            if ((byte) kind < 4) {
                light = true;
                lightPal = kind;
            } else {
                additive = true;
            }
        }

        // La demi-taille a l'ecran, par la meme division encore.
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

        // La bande de depart, et le bout de l'image si elle est retournee.
        int strip = f.strip();
        if (flip) {
            strip += s16(f.half() * 2 - 1);            // lea (a5,d6.w*4),a5
        }

        // Les deux entrees de la table des pas : l'horizontale et la verticale.
        int wide = s16(f.half() * 2 - 1);
        if (t.halfWidth() == 0) {
            return null;                               // beq object_behind
        }
        int horiz = divu(mulu(s16(d1), wide), t.halfWidth());
        int tall = s16(f.halfH() * 2 - 1);
        if (t.halfDepth() == 0) {
            return null;
        }
        int vert = divu(mulu(s16(d1), tall), t.halfDepth());
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

        // Le rognage par la gauche : on saute d'autant de BANDES que de colonnes perdues.
        if (s16(d0) < s16(leftClip)) {
            d0 = setw(d0, d0 - leftClip);
            d3 = setw(d3, d3 + d0);
            if (s16(d3) <= 0) {
                return null;                           // ble object_behind
            }
            int n = -s16(d0);
            // Le pas est un mot double ; on le multiplie en DEUX fois, la moitie haute
            // signee et la basse non signee, pour n'avoir jamais a multiplier sur trente-deux
            // bits. C'est le decalage de seize bits du produit.
            int hi = muls(hStep >> 16, n);
            int lo = mulu(hStep & 0xFFFF, n) >>> 16;
            int sauts = s16(setw(hi, hi + lo));
            d0 = setw(d0, leftClip);
            sauts = s16(setw(0, sauts << 2)) >> 2;     // asl.w #2 : le decompte deborde ici
            strip += flip ? -sauts : sauts;
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

        // Ou commencer dans la colonne de texture : le pas multiplie par les lignes sautees,
        // plus le decalage propre a l'image, plus une demie pour arrondir.
        int vhi = muls(vStep >> 16, d7);
        int vlo = mulu(vStep & 0xFFFF, d7) >>> 16;
        int d5 = setw(vhi, vhi + vlo);
        d5 = setw(d5, d5 + f.down());
        d5 += 0x80000000;

        int stripStep = flip ? -hStep : hStep;
        int texStep = (vStep >>> 16) | (vStep << 16);  // swap d2

        return new Placed(left, top, s16(d3) + 1, s16(d4) + 1, strip, stripStep, d5, texStep,
                ramp, flip, light, additive, lightPal, bright);
    }

    /**
     * Le pas de la table, ou son dernier quand l'index la depasse.
     *
     * <p>Le rapport qui sert d'index est {@code profondeur * largeur d'image / demi-largeur}.
     * Rien ne le borne : avec une image large et un objet etroit, il passe les huit mille cent
     * quatre-vingt-douze entrees, et l'original va lire tres loin apres sa table. On compte, et
     * la verification laisse ces cas de cote plutot que de confronter du hasard.
     */
    private int step(int at) {
        if (at >= ScaleTable.SIZE) {
            offScale++;
            return scale.step(ScaleTable.SIZE - 1);
        }
        return scale.step(at);
    }

    /**
     * La rampe de la table, ou ce qui la suit en memoire quand l'index la depasse.
     *
     * <p>Un index NEGATIF, lui, n'est pas un depassement mais une faute : la borne basse vient
     * juste de passer, et si elle a manque c'est le portage qui est en cause. On le dit au
     * lieu de le compter avec le reste — sans quoi retirer la borne reviendrait a ajouter des
     * cas ecartes, et la verification n'y verrait rien.
     */
    private int ramp(int at) {
        if (at < 0) {
            throw new IllegalStateException("luminosite negative apres sa borne : " + at);
        }
        if (at >= RAMPS.length) {
            offTable++;
            return RAMPS[RAMPS.length - 1];
        }
        return RAMPS[at];
    }

    /**
     * {@code divu.w}, puis le mot de poids fort jete — comme l'original le fait d'un
     * {@code swap}, {@code clr.w}, {@code swap}.
     *
     * <p>Et surtout : quand le quotient ne tient pas dans un mot, le processeur ABANDONNE la
     * division et laisse le registre tel quel. Ce qu'on garde alors n'est pas un rapport mais
     * le mot bas du PRODUIT — un nombre sans rapport avec l'echelle, qui sert tout de meme
     * d'index dans la table des pas. Rien ne rattrape cela en aval.
     *
     * <p>Il y faut une image de demi-largeur NULLE, c'est-a-dire une case inutilisee de la
     * table des images : la largeur moins une vaut alors moins un, lue comme soixante-cinq
     * mille, et le produit explose.
     *
     * <p>{@link Glare} s'en sert aussi : le miroitement est une routine a part, mais c'est le
     * meme debordement, et il ne doit pas en exister deux ecritures.
     */
    static int divu(int a, int b) {
        long q = (a & 0xFFFFFFFFL) / (b & 0xFFFF);
        if (q > 0xFFFF) {
            return a & 0xFFFF;                         // debordement : l'operande reste
        }
        return (int) q;
    }
}
