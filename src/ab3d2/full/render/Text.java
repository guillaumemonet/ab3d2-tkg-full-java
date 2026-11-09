package ab3d2.full.render;

/**
 * Le TEXTE a l'ecran : la police des messages, et l'espacement qui la rend proportionnelle.
 *
 * <p>Port de la partie texte de {@code c/draw.c}. La police est la plus simple qui soit — deux
 * cent cinquante-six glyphes de huit sur huit, un BIT par pixel, huit octets par glyphe — et
 * tout le soin est ailleurs, dans une table de deux cent cinquante-six octets qui donne, pour
 * chaque code de caractere, deux quartets :
 *
 * <ul>
 *   <li>le quartet HAUT : de combien avancer apres avoir pose le glyphe ;</li>
 *   <li>le quartet BAS : de combien RECULER avant de le poser.</li>
 * </ul>
 *
 * <p>C'est cette paire, et elle seule, qui fait la police proportionnelle : le glyphe reste
 * dessine dans sa case de huit, mais on le pose decale a gauche et on avance d'autant moins.
 * Un {@code i} avance de trois, un {@code A} de six.
 *
 * <p>Deux details du code d'origine qu'on garde tels quels :
 *
 * <ul>
 *   <li>le recul se prend sur le quartet bas ENTIER ({@code & 0xF}) alors que la pose du glyphe
 *       lit le meme octet sur TROIS bits ({@code & 7}). Les deux s'accordent parce que la table
 *       ne porte que des reculs de zero a deux — on garde les deux masques ;</li>
 *   <li>une ligne de glyphe entierement vide n'est pas parcourue, donc le compte de largeur
 *       n'y descend pas. Cela ne se voit pas : le compte est remis a chaque ligne.</li>
 * </ul>
 *
 * <h2>Ce que le corpus ne separe pas</h2>
 *
 * <p>Trois mutations survivent, et toutes trois se MESURENT dans la police elle-meme :
 *
 * <ul>
 *   <li>dessiner une colonne de trop ne se voit pas : sur les cent quatre-vingt-dix caracteres
 *       imprimables, AUCUN n'a de pixel allume dans la colonne qui suit sa largeur declaree.
 *       Les glyphes sont exactement aussi larges que la table le dit ;</li>
 *   <li>tenir l'espace (vingt-six) pour imprimable ne se voit pas non plus : son glyphe est
 *       entierement nul. Idem pour l'espace insecable (cent soixante), qui borne la plage
 *       haute ;</li>
 *   <li>en revanche l'autre bord se voit : les codes de commande et la plage cent
 *       vingt-sept a cent cinquante-neuf portent, eux, de VRAIS dessins. Elargir l'imprimable
 *       de ce cote-la se remarque aussitot, et le corpus le verifie.</li>
 * </ul>
 */
public final class Text {

    /** La hauteur d'un glyphe, DRAW_MSG_CHAR_H. */
    public static final int CHAR_H = 8;

    /** La largeur de la case d'un glyphe, DRAW_MSG_CHAR_W. */
    public static final int CHAR_W = 8;

    /** La marge laissee au bord de l'ecran, DRAW_TEXT_MARGIN. */
    public static final int MARGIN = 4;

    /** L'interligne, DRAW_TEXT_Y_SPACING. */
    public static final int LINE_SPACING = 2;

    private final byte[] font;
    private final byte[] spacing;

    /**
     * @param font    {@code draw_ScrollChars_vb} : huit octets par code de caractere
     * @param spacing {@code draw_GlyphSpacing_vb} : un octet par code de caractere
     */
    public Text(byte[] font, byte[] spacing) {
        if (font.length < 256 * CHAR_H || spacing.length < 256) {
            throw new IllegalArgumentException("police ou espacement tronques");
        }
        this.font = font;
        this.spacing = spacing;
    }

    /**
     * Vrai si le caractere se dessine ({@code Draw_IsPrintable}).
     *
     * <p>Le jeu suppose le jeu de caracteres latin de l'Amiga : tout ce qui est au-dessus de
     * cent soixante est une lettre accentuee, et se dessine.
     */
    public static boolean printable(int charCode) {
        return (charCode > 0x20 && charCode < 0x7F) || charCode > 0xA0;
    }

    /** De combien ce caractere fait avancer. */
    public int advance(int charCode) {
        return (spacing[charCode & 0xFF] & 0xFF) >>> 4;
    }

    /** La largeur qu'occupera ce texte, {@code Draw_CalcPropWidth}. */
    public int width(String texte, int maxLen) {
        int largeur = 0;
        for (int i = 0; i < texte.length() && i < maxLen; i++) {
            int c = texte.charAt(i) & 0xFF;
            if (c == 0) {
                break;                                 // while (charCode = *textPtr++)
            }
            largeur += advance(c);
        }
        return largeur;
    }

    /**
     * Combien de caracteres tiennent dans une largeur donnee ({@code Draw_CalcPropTextSplit}).
     *
     * <p>C'est ce qui coupe un texte long en lignes. Deux soins, et c'est tout ce qui separe
     * cette routine d'un simple compte :
     *
     * <ul>
     *   <li>elle ne coupe pas un MOT : si le caractere ou l'on s'arrete se dessine, on recule
     *       jusqu'apres le dernier qui ne se dessinait pas — l'espace ;</li>
     *   <li>elle ne laisse pas d'espace en TETE du morceau suivant.</li>
     * </ul>
     *
     * @param curseur un tableau d'un element : en entree le rang du premier caractere, en
     *                sortie celui du morceau suivant, ou moins un s'il n'y en a plus
     * @param texte   le texte entier
     * @param reste   combien de caracteres restent a placer
     * @param largeur la largeur disponible, en pixels
     * @return combien de caracteres tiennent
     */
    public int split(int[] curseur, String texte, int reste, int largeur) {
        // Le raccourci : au pire, chaque caractere prend une case pleine.
        if (reste * CHAR_W <= largeur) {
            curseur[0] = -1;
            return reste;
        }
        int depart = curseur[0];
        int at = depart;
        int dernierBlanc = -1;
        int pris = 0;
        int c = 0;
        while (reste != 0 && pris < largeur) {
            int ch = at < texte.length() ? texte.charAt(at) & 0xFF : 0;
            if (ch == 0) {                             // (charCode = *textPtr) != 0
                c = 0;
                break;
            }
            c = ch;
            if (!printable(c)) {
                dernierBlanc = at;
            }
            pris += advance(c);
            reste--;
            at++;
        }
        if (pris > largeur) {                          // charCode = *(--textPtr)
            at--;
            c = at < texte.length() ? texte.charAt(at) & 0xFF : 0;
            reste++;
        }
        if (dernierBlanc >= 0 && reste > 1 && printable(c)) {
            at = dernierBlanc + 1;                     // on ne coupe pas le mot
        }
        int tient = at - depart;
        boolean fini = c == 0 || reste == 0;
        if (!fini) {
            while (reste > 0) {
                int ch = at < texte.length() ? texte.charAt(at) & 0xFF : 0;
                if (printable(ch)) {
                    break;
                }
                at++;
                reste--;
            }
        }
        curseur[0] = fini ? -1 : at;
        return tient;
    }

    /**
     * Pose une ligne de texte en police proportionnelle ({@code Draw_ChunkyTextProp}).
     *
     * @param dst    le tampon d'ecran, un octet par pixel
     * @param span   la distance d'une ligne a la suivante
     * @param maxLen combien de caracteres au plus
     * @param texte  la ligne
     * @param x      ou commencer
     * @param y      sur quelle ligne
     * @param pen    le rang de palette a poser
     * @return le rang du caractere suivant, ou moins un si la ligne a ete posee en entier
     */
    public int prop(byte[] dst, int span, int maxLen, String texte, int x, int y, int pen) {
        int at = span * y + x;                         // drawPtr += drawSpan * yPos + xPos
        int i = 0;
        int reste = maxLen;
        int c;
        // L'ordre du test compte : le caractere est LU, donc consomme, AVANT qu'on regarde
        // s'il reste de la place. Une ligne tronquee reprend donc apres lui, pas sur lui.
        while (true) {
            c = i < texte.length() ? texte.charAt(i++) & 0xFF : 0;  // charCode = *textPtr++
            if (c == 0 || reste-- <= 0) {              // && maxLen-- > 0
                break;
            }
            int glyphSpacing = spacing[c] & 0xFF;
            if (printable(c)) {
                glyph(dst, at - (glyphSpacing & 0xF), span, c, pen);
            }
            at += glyphSpacing >>> 4;
        }
        return c != 0 ? i : -1;                        // return charCode ? textPtr : NULL
    }

    /**
     * Pose un glyphe, et SEULEMENT ses pixels allumes : le fond reste ce qu'il etait.
     *
     * <p>{@code draw_ChunkyGlyph}. Le code d'origine est une cascade de cas qui entre au rang
     * de la marge et retombe de l'un a l'autre jusqu'a epuiser la largeur du glyphe ; c'est la
     * boucle ci-dessous, decalage de bit compris.
     */
    private void glyph(byte[] dst, int at, int span, int charCode, int pen) {
        int glyphAt = charCode << 3;                   // &draw_ScrollChars_vb[charCode << 3]
        int marge = spacing[charCode] & 0x7;           // glyphLMargin
        int largeur = (((spacing[charCode] & 0xFF) >>> 4) - 1) & 0xFF;   // glyphWidth, UBYTE
        for (int ligne = 0; ligne < CHAR_H; ligne++) {
            int bits = font[glyphAt + ligne] & 0xFF;
            int reste = largeur;
            if (bits != 0) {                           // if (plane)
                for (int p = marge; p < 8; p++) {
                    if ((bits & (128 >>> p)) != 0) {
                        dst[at + p] = (byte) pen;
                    }
                    if (--reste == 0) {
                        break;                         // if (!--width) break
                    }
                }
            }
            at += span;
        }
    }
}
