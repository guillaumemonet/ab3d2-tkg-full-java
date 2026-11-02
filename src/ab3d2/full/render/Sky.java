package ab3d2.full.render;

import ab3d2.full.level.Backdrop;

import static ab3d2.full.M68k.muls;

/**
 * Le ciel : le fond que le rendu pose avant toute autre chose.
 *
 * <p>C'est la seule chose dessinee qui ne soit pas une surface du niveau. Elle n'a ni distance,
 * ni ombrage, ni decoupage, ni meme de projection : on recopie trois cent vingt colonnes de
 * l'image, l'une apres l'autre, et c'est tout. Ce qui viendra ensuite — murs, sols, objets —
 * la recouvrira partout ou le niveau est ferme ; ce qui reste visible est le ciel.
 *
 * <p>Deux choses seulement decident de ce qu'on voit :
 *
 * <ul>
 *   <li>l'ORIENTATION donne la colonne de depart. Elle est masquee a douze bits alors que le
 *       tour en compte treize : l'image fait DEUX tours pendant que le joueur en fait un. Six
 *       cent quarante-huit colonnes pour un demi-tour, et pas de bord — on reboucle sur la
 *       premiere ;</li>
 *   <li>l'HORIZON donne a la fois la hauteur dessinee, un peu plus que lui, et le decalage
 *       vertical dans l'image : le haut de l'ecran tombe sur la ligne {@code 240 - horizon} de
 *       la colonne. Le ciel descend donc exactement jusqu'a l'horizon, et pas plus bas.</li>
 * </ul>
 *
 * <h2>Le debordement d'une colonne sur l'autre</h2>
 *
 * <p>La boucle avance par groupes de QUATRE lignes — un mot double lu d'un coup, quatre octets
 * poses sur quatre lignes voisines — et elle en fait {@code horizon/4 + 1}, soit toujours un
 * groupe de trop. Elle lit donc {@code 244 - (horizon modulo 4)} octets dans une colonne qui
 * n'en compte que deux cent quarante : les dernieres lignes de chaque colonne de l'ecran
 * viennent du HAUT de la colonne suivante de l'image.
 *
 * <p>Cela se voit a peine — le haut du ciel et son bas se ressemblent — mais c'est systematique
 * et non accidentel : ce debordement a lieu pour chacune des trois cent vingt colonnes, a
 * chaque image.
 *
 * <p>Et pour la DERNIERE colonne de l'image, il n'y a pas de colonne suivante : la lecture sort
 * de l'image d'un a quatre octets — exactement {@code 4 - (horizon modulo 4)}, et seulement
 * quand les trois cent vingt colonnes recopiees atteignent le bout de l'image, soit a peu pres
 * une orientation sur deux. L'original y prend ce que l'allocateur avait laisse derriere — rien
 * ne le definit. On y met zero, et on COMPTE ces octets ({@link #offImage()}) pour que l'ecart
 * reste visible au lieu de se taire : trois cent quarante sur les dix millions de pixels
 * du corpus.
 */
public final class Sky {

    /** Combien de secteurs la table d'extinction peut en porter. */
    private static final int MAX_ZONES = 512;

    /** Le masque de l'orientation : douze bits, la ou le tour en compte treize. */
    private static final int ANGLE_MASK = 4095;

    private final Backdrop image;

    /**
     * Les secteurs ou le ciel ne se dessine pas.
     *
     * <p>Le jeu la remplit depuis un fichier {@code properties.dat} range avec le niveau —
     * qu'AUCUN des seize niveaux publies ne porte : chacun n'a que ses cinq fichiers de
     * geometrie. Elle reste donc vide de bout en bout, et l'extinction par secteur n'est qu'un
     * outil d'auteur reste dans le code, que le jeu n'offrait qu'a une touche de deboguage.
     *
     * <p>C'est pourquoi la seule mutation qui survive au corpus est celle qui supprime ce test
     * : aucune donnee du jeu ne peut la mettre en defaut.
     */
    private final boolean[] disabled = new boolean[MAX_ZONES];

    /** Combien d'octets ont ete lus hors de l'image depuis la derniere remise a zero. */
    private int offImage;

    public Sky(Backdrop image) {
        this.image = image;
    }

    /** Eteint ou rallume le ciel dans un secteur. */
    public void disable(int zone, boolean off) {
        disabled[zone] = off;
    }

    /** Combien d'octets ont ete lus hors de l'image. */
    public int offImage() {
        return offImage;
    }

    /** Remet ce compte a zero. */
    public void clearOffImage() {
        offImage = 0;
    }

    /**
     * Pose le ciel sur tout le haut de l'ecran.
     *
     * @param screen  le tampon
     * @param zone    le secteur ou se tient l'oeil — c'est lui qui peut eteindre le ciel
     * @param angle   l'orientation du joueur, sur un tour de huit mille cent quatre-vingt-douze
     * @param centreY l'horizon, en lignes depuis le haut de l'ecran
     * @param full    vrai en plein ecran, faux dans la vue encadree
     */
    public void draw(Screen screen, int zone, int angle, int centreY, boolean full) {
        if (disabled[zone]) {
            return;                                    // btst.b d3,(a5) ; bne (rien a faire)
        }

        // La colonne de depart. L'angle est masque a douze bits, multiplie par la largeur de
        // l'image puis ramene de douze bits : six cent quarante-huit colonnes par demi-tour.
        int column = muls(angle & ANGLE_MASK, Backdrop.WIDTH) >> 8 >> 4;
        int from = muls(column, Backdrop.HEIGHT);      // muls #SKY_BACKDROP_H,d5
        if (!full) {
            small(screen, from, centreY);              // tst.b Vid_FullScreen_b ; beq (petit)
            return;
        }

        // Le haut de l'ecran tombe sur la ligne 240 - horizon de la colonne : on vise la
        // colonne SUIVANTE et on remonte de l'horizon, ce qui revient au meme.
        int at = from + Backdrop.HEIGHT - centreY;     // a1 = image + d5 + H - centreY
        int wrap = Backdrop.HEIGHT - centreY;          // a5 = image + H - centreY
        int end = Backdrop.WIDTH * Backdrop.HEIGHT;    // a3 = image + W*H
        int groups = centreY >> 2;                     // d7 = horizon/4 (un groupe de PLUS suit)

        byte[] pixels = screen.pixels();
        int to = 0;                                    // a0 = le debut du tampon
        for (int x = Screen.WIDTH - 1; x >= 0; x--) {  // .horline (dbra d4)
            int src = at;                              // a4 = a1
            int dst = to;                              // a2 = a0
            for (int g = groups; g >= 0; g--) {        // .vertline (dbra d3)
                // Un mot double lu d'un coup, ses quatre octets poses sur quatre lignes
                // voisines. L'original les sort dans le desordre — move.b, swap, lsr.l #8,
                // swap — pour n'avoir jamais a decaler de plus de huit bits ; le resultat est
                // simplement les quatre octets dans l'ordre, sur les quatre lignes dans
                // l'ordre.
                pixels[dst + Screen.WIDTH * 3] = (byte) octet(src + 3);
                pixels[dst + Screen.WIDTH] = (byte) octet(src + 1);
                pixels[dst] = (byte) octet(src);
                pixels[dst + Screen.WIDTH * 2] = (byte) octet(src + 2);
                src += 4;                              // move.l (a4)+,d0
                dst += Screen.WIDTH * 4;               // adda.w #SCREEN_WIDTH*4,a2
            }
            at += Backdrop.HEIGHT;                     // add.w #SKY_BACKDROP_H,a1
            if (at >= end) {                           // cmp.l a1,a3 ; bgt.s .noend
                at = wrap;                             // move.l a5,a1
            }
            to += 1;                                   // addq.w #1,a0
        }
    }

    /**
     * Le ciel de la vue encadree, qui n'est pas le meme dessin a une echelle pres.
     *
     * <p>Il est reduit des DEUX cotes, mais par deux procedes differents et par deux rapports
     * differents, ce qui en dit long sur la facon dont il a ete ecrit.
     *
     * <p>En hauteur, la boucle lit SIX octets par groupe de quatre lignes et n'en garde que
     * quatre : elle saute le premier de chaque trio. Deux tiers, soit le rapport des cent
     * soixante lignes de la vue encadree aux deux cent quarante du plein ecran. L'horizon est
     * recule d'une fois et demie lui-meme pour que le haut de l'image tombe au bon endroit.
     *
     * <p>En largeur, la colonne suivante est prise tantot a une colonne de la precedente,
     * tantot a deux : les trois pas tournent sur eux-memes par deux {@code exg} et donnent le
     * cycle un, un, deux. Quatre colonnes de l'image pour trois de l'ecran, soit deux cent
     * cinquante-six colonnes montrees au lieu de trois cent vingt — la vue encadree est plus
     * etroite que le plein ecran, mais elle montre MOINS de ciel, pas davantage.
     */
    private void small(Screen screen, int from, int centreY) {
        // L'horizon, une fois et demie : move.w d6,d5 ; asr.w #1,d5 ; add.w d5,d6
        int back = centreY + (centreY >> 1);
        int at = from + Backdrop.HEIGHT - back;        // a1 = image + d5 + H - d6
        int wrap = Backdrop.HEIGHT - back;             // a5 = image + H - d6
        int end = Backdrop.WIDTH * Backdrop.HEIGHT;    // a3 = image + W*H
        int groups = centreY >> 2;                     // d7 = horizon/4

        // Les trois pas de colonne, qui tournent sur eux-memes a chaque colonne d'ecran.
        int step1 = Backdrop.HEIGHT;                   // d1
        int step2 = Backdrop.HEIGHT;                   // d2
        int step3 = Backdrop.HEIGHT * 2;               // d5

        byte[] pixels = screen.pixels();
        int to = 0;                                    // a0 = le debut du tampon
        for (int x = View.SMALL_WIDTH - 1; x >= 0; x--) { // .horline (dbra d4, 191)
            int src = at;                              // a4 = a1
            int dst = to;                              // a2 = a0
            for (int g = groups; g >= 0; g--) {        // .vertline (dbra d3)
                // Six octets lus, quatre poses : le premier de chaque trio est jete. Le mot
                // de tete n'est la que pour avancer de deux d'un coup — seul son octet BAS
                // sert.
                pixels[dst] = (byte) octet(src + 1);           // move.w (a4)+,d0 ; move.b d0,(a2)
                pixels[dst + Screen.WIDTH] = (byte) octet(src + 2);     // move.b (a4)+,d0
                pixels[dst + Screen.WIDTH * 2] = (byte) octet(src + 4); // addq #1,a4 ; move.b (a4)+,d0
                pixels[dst + Screen.WIDTH * 3] = (byte) octet(src + 5); // move.b (a4)+,d0
                src += 6;
                dst += Screen.WIDTH * 4;               // adda.w #SCREEN_WIDTH*4,a2
            }
            at += step1;                               // add.w d1,a1
            if (at >= end) {                           // cmp.l a1,a3 ; bgt.s .noend
                at = wrap;                             // move.l a5,a1
            }
            int t = step1;                             // exg d1,d2
            step1 = step2;
            step2 = t;
            t = step2;                                 // exg d2,d5
            step2 = step3;
            step3 = t;
            to += 1;                                   // addq.w #1,a0
        }
    }

    /** L'octet de l'image, zero — et compte — quand la lecture sort de l'image. */
    private int octet(int i) {
        int b = image.at(i);
        if (b < 0) {
            offImage++;
            return 0;
        }
        return b;
    }
}
