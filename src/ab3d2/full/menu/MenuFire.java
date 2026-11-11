package ab3d2.full.menu;

/**
 * LE FEU DU MENU, et les huit plans de bits qui le portent.
 *
 * <p>L'ecran du menu n'est pas une image : c'est une image a HUIT PLANS DE BITS de 320 sur 256,
 * et chaque pixel y est un nombre de huit bits reparti sur huit tableaux separes.
 *
 * <ul>
 *   <li><b>plans 0 et 1</b> — le FOND, qui defile. Chaque plan est garde DEUX FOIS de suite,
 *       l'un sous l'autre : le defilement d'une ligne par image reboucle alors tout seul, sans
 *       jamais rien recopier ;</li>
 *   <li><b>plans 2 a 4</b> — le FEU, refait a chaque image ;</li>
 *   <li><b>plans 5 a 7</b> — la POLICE, c'est-a-dire le texte du menu.</li>
 * </ul>
 *
 * <h2>Le feu n'est pas un automate</h2>
 *
 * <p>On s'attend a un automate cellulaire — chaque pixel regarde ses voisins du dessous. Ce
 * n'est pas cela du tout. Ce sont <b>trois blits du blitter Amiga par image</b> :
 *
 * <pre>D = A_decale | (B &amp; C)</pre>
 *
 * <p>ou A est un plan de POLICE, B une source de bruit calculee une fois pour toutes, et C le
 * plan de feu lu UNE LIGNE PLUS BAS. De la trois consequences qu'aucun automate ne donnerait :
 *
 * <ol>
 *   <li><b>le texte brule</b>, parce que les lettres sont la source du feu ;</li>
 *   <li>les flammes MONTENT, parce que C est lu une ligne plus bas que D s'ecrit ;</li>
 *   <li>elles VACILLENT, parce que les trois pointeurs de source tournent a chaque image et que
 *       le decalage du canal A suit un cycle de quatre — un bit, quinze bits, rien, rien.</li>
 * </ol>
 *
 * <h2>Le seul point qui ne peut pas etre exact</h2>
 *
 * <p>{@code mnu_dofire} ajoute au compteur de bruit la position du FAISCEAU VIDEO
 * ({@code vhposr}), que personne ne peut reproduire sans tube cathodique. Le portage litteral
 * lui substitue {@code 0xA5 + (compteur & 0xFF)}, et cette conversion substitue la meme chose —
 * c'est l'oracle {@code menuCheck} qui fige ce choix. Cela ne deplace que la fenetre de bruit
 * lue : la logique du feu, elle, est exacte.
 *
 * <h2>Ce qui n'est pas porte</h2>
 *
 * <p>{@code mnu_plot} seme cinquante etincelles de Lissajous dans les plans de feu. Le portage
 * litteral l'a essaye puis retire, et la raison vaut d'etre connue : le blit logiciel ne
 * REFROIDIT pas comme le materiel. Une source fixe reste bornee, mais une source mobile laisse
 * derriere elle une colonne qui monte jusqu'en haut, et l'ecran se remplit en quelques
 * secondes. Deux corrections ont ete tentees — un bruit dynamique, un masque de densite — et
 * toutes deux ont echoue. Les rallumer demande de reproduire d'abord la statistique du bruit
 * materiel, ce qui est un autre sujet.
 */
public final class MenuFire {

    /** La largeur de l'ecran du menu. */
    public static final int WIDTH = 320;

    /** Sa hauteur. */
    public static final int HEIGHT = 256;

    /** Une ligne de plan, en octets. */
    public static final int ROWSIZE = WIDTH / 8;

    /** Un plan entier. */
    public static final int PLANESIZE = ROWSIZE * HEIGHT;

    /** {@code mnu_speed} : de combien de lignes le feu monte par passe. */
    private static final int SPEED = 1;

    /** {@code mnu_size} : la hauteur traitee par le blit. */
    private static final int SIZE = 256;

    /** Ou la source de bruit commence, apres les six plans. */
    private static final int NOISE = 6 * PLANESIZE;

    /** Sa taille : {@code 40*256 + 8192} octets. */
    public static final int NOISE_SIZE = ROWSIZE * 256 + 8192;

    /** La police est un atlas de 320 sur 176, trois plans. */
    private static final int FONT_PLANE = ROWSIZE * 176;

    /** Vingt glyphes par rangee dans l'atlas, et le premier est l'espace. */
    private static final int GLYPHS_PER_ROW = 20;

    /** La hauteur d'un glyphe. */
    private static final int GLYPH = 16;

    /** Les deux plans du fond, chacun garde deux fois. */
    private final byte[] screen = new byte[4 * PLANESIZE];

    /** Les plans du feu (0 a 2), de la police (3 a 5), puis la source de bruit. */
    private final byte[] more = new byte[NOISE + NOISE_SIZE];

    /** L'atlas de police, trois plans de 320 sur 176. */
    private final byte[] font;

    /** {@code mnu_sourceptrs} : les trois plans de police, dans l'ordre du moment. */
    private final int[] sourcePtrs = {
        3 * PLANESIZE + SPEED * ROWSIZE,
        4 * PLANESIZE + SPEED * ROWSIZE,
        5 * PLANESIZE + SPEED * ROWSIZE,
    };

    private int rnd;
    private int rndPtr;
    private int count;

    /** {@code main_counter} : le feu n'avance qu'une image sur deux. */
    private long counter;

    /** {@code mnu_screenpos} : quelle ligne du fond se trouve en haut. */
    private int screenPos;

    /**
     * @param background {@code menu/back2.raw}, deux plans de 320 sur 256
     * @param font       {@code menu/font16x16.raw2}, trois plans de 320 sur 176
     */
    public MenuFire(byte[] background, byte[] font) {
        this.font = font;
        // mnu_init : le fond est ecrit DEUX FOIS, l'un sous l'autre.
        for (int i = 0; i < PLANESIZE; i++) {
            byte p0 = background[i];
            byte p1 = background[PLANESIZE + i];
            screen[i] = p0;
            screen[PLANESIZE + i] = p0;
            screen[2 * PLANESIZE + i] = p1;
            screen[3 * PLANESIZE + i] = p1;
        }
        initRnd();
    }

    /**
     * {@code mnu_initrnd} : la source de bruit.
     *
     * <p>Une table de PARITE de deux cent cinquante-six octets — la parite des bits 0, 2, 3 et
     * 5 — puis un registre a decalage de graine {@code 'TBL!'} qu'on deroule dix-huit mille
     * quatre cent trente-deux fois. C'est la piece la plus facile a porter de travers, et tout
     * le feu en depend : la table de parite n'a pas de sens isolement, elle n'existe que pour
     * que le registre boucle sur une longue periode.
     */
    private void initRnd() {
        byte[] parite = new byte[256];
        for (int d0 = 255; d0 >= 0; --d0) {                // .parityloop
            int d1 = d0 & 1;
            d1 ^= (d0 >> 2) & 1;
            d1 ^= (d0 >> 3) & 1;
            d1 ^= (d0 >> 5) & 1;
            parite[d0] = (byte) d1;
        }
        int d3 = 0x54424C21;                               // move.l #'TBL!',d3
        int at = NOISE;
        for (int n = NOISE_SIZE; n > 0; --n) {             // move.w #40*256+8192-1,d0
            int d2 = d3;
            int d1 = d2 & 0xFF;                            // move.b d2,d1
            d2 &= 0xFFFFFFFE;                              // and.b #$fe,d2
            d1 = parite[d1] & 0xFF;                        // move.b (a4,d1.l),d1
            d2 = (d2 & ~0xFF) | ((d2 | d1) & 0xFF);        // or.b d1,d2
            d2 = rorw1(d2);                                // ror.w #1,d2
            d2 = (d2 >>> 16) | (d2 << 16);                 // swap.w d2
            d1 = d2 & 0xFF;
            d2 &= 0xFFFFFFFE;
            d1 = parite[d1] & 0xFF;
            d2 = (d2 & ~0xFF) | ((d2 | d1) & 0xFF);
            d2 = rorw1(d2);
            d3 = d2;                                       // move.l d2,d3
            d1 = (d2 & 0xFFFF) >>> 8;                      // move.w d2,d1 ; lsr.w #8,d1
            d2 = (d2 & ~0xFFFF) | ((d2 | d1) & 0xFFFF);    // or.w d1,d2
            d1 = (d2 >>> 16) | (d2 << 16);                 // move.l d2,d1 ; swap.w d1
            d2 = (d2 & ~0xFFFF) | ((d2 | d1) & 0xFFFF);    // or.w d1,d2
            more[at++] = (byte) d2;                        // move.b d2,(a0)+
        }
    }

    /** {@code ror.w #1} : la rotation d'un cran du seul mot bas, le mot haut intact. */
    private static int rorw1(int v) {
        int lw = v & 0xFFFF;
        lw = ((lw >>> 1) | (lw << 15)) & 0xFFFF;
        return (v & ~0xFFFF) | lw;
    }

    /** {@code getrnd} : avance la fenetre lue dans la source de bruit, de cinq en cinq. */
    private void getRnd() {
        rndPtr = NOISE + (rnd & 8190);                     // and.l #8190,d0
        rnd = (rnd + 5) & 0xFFFF;                          // addq.w #5,mnu_rnd
    }

    /**
     * Une image du menu ({@code WaitTOF} puis {@code mnu_vblint}) : le compteur avance, le fond
     * defile d'une ligne, et le feu avance une image sur deux.
     */
    public void frame() {
        counter++;                                         // main_counter++
        screenPos = (screenPos + 1) & 0xFFFF;              // mnu_movescreen
        doFire();                                          // mnu_dofire
    }

    /** Quelle ligne du fond se trouve en haut de l'ecran. */
    public int scrollLine() {
        return screenPos & 255;
    }

    public long counter() {
        return counter;
    }

    /** {@code mnu_dofire}. */
    private void doFire() {
        if ((counter & 1) != 0) {                          // if (main_counter & 1) return
            return;
        }
        // mnu_rnd += vhposr : sans faisceau video, un substitut qui varie par image.
        rnd = (rnd + 0xA5 + (int) (counter & 0xFF)) & 0xFFFF;

        int s0 = sourcePtrs[0];                            // la rotation des trois sources
        sourcePtrs[0] = sourcePtrs[1];
        sourcePtrs[1] = sourcePtrs[2];
        sourcePtrs[2] = s0;

        int cnt = count & 3;                               // cnt = mnu_count++ & 3
        count = (count + 1) & 0xFFFF;
        int ash;
        int subtract = 0;
        switch (cnt) {
            case 0 -> ash = 1;                             // bltcon0 $1ff8 : ASH = 1
            case 1 -> {
                ash = 15;                                  // $fff8 : ASH = 15
                subtract = -2;                             // et le pointeur A avance de deux
            }
            default -> ash = 0;                            // $0ff8 : pas de decalage
        }
        for (int i = 0; i < 3; i++) {
            getRnd();
            fireBlit(sourcePtrs[i] - subtract, rndPtr,
                    i * PLANESIZE + SPEED * ROWSIZE, i * PLANESIZE, ash);
        }
    }

    /**
     * Le blit : deux cent cinquante-cinq lignes de vingt mots, {@code D = A | (B & C)}.
     *
     * <p>Le canal A passe par le BARREL SHIFTER du blitter, qui n'est pas un decalage
     * ordinaire : les bits qui sortent d'un mot entrent dans le suivant. C'est ce report qui
     * fait pencher les flammes au lieu de les hacher, et l'oublier donne un feu qui monte tout
     * droit.
     */
    private void fireBlit(int aBase, int bBase, int cBase, int dBase, int ash) {
        int mots = (SIZE - SPEED) * (ROWSIZE / 2);         // 255 lignes de 20 mots
        int precedent = 0;
        for (int k = 0; k < mots; k++) {
            int a = word(aBase + k * 2);
            int decale = (((precedent << 16) | a) >>> ash) & 0xFFFF;
            precedent = a;
            int d = decale | (word(bBase + k * 2) & word(cBase + k * 2));
            more[dBase + k * 2] = (byte) (d >> 8);
            more[dBase + k * 2 + 1] = (byte) d;
        }
    }

    private int word(int at) {
        return ((more[at] & 0xFF) << 8) | (more[at + 1] & 0xFF);
    }

    // ------------------------------------------------- les plans de police

    /** {@code mnu_cls} : efface les trois plans de police. */
    public void clearText() {
        java.util.Arrays.fill(more, 3 * PLANESIZE, 6 * PLANESIZE, (byte) 0);
    }

    /**
     * {@code mnu_printxy} : ecrit une chaine dans les plans de police.
     *
     * <p>Un caractere en dessous de l'espace n'est pas imprime : c'est un RETOUR A LA LIGNE, et
     * il descend de vingt pixels — pas seize, bien que les glyphes en fassent seize. Le jeu
     * aere son menu avec ces quatre pixels.
     *
     * @param texte  la chaine ; les caracteres en dessous de 32 reviennent a la ligne
     * @param xOctet l'abscisse, en OCTETS (un octet = huit pixels)
     * @param y      l'ordonnee, en pixels
     */
    public void print(String texte, int xOctet, int y) {
        int depart = y * ROWSIZE + xOctet;
        int at = depart;
        int ligne = depart;
        for (int i = 0; i < texte.length(); i++) {
            int c = texte.charAt(i) & 0xFF;
            int glyphe = c - 32;                           // sub.w #32,d2
            if (glyphe < 0) {                              // bge .ok : sinon, retour a la ligne
                ligne += 20 * ROWSIZE;                     // a1 = a2 + 20*40
                at = ligne;
                continue;
            }
            putGlyph(glyphe, at);
            at += 2;                                       // addq.l #2,a1
        }
    }

    /** Pose un glyphe de l'atlas dans les trois plans de police. */
    private void putGlyph(int glyphe, int at) {
        int off = (glyphe / GLYPHS_PER_ROW) * (ROWSIZE * GLYPH)
                + (glyphe % GLYPHS_PER_ROW) * 2;           // divu #20 : ligne et colonne
        if (off < 0 || off + ROWSIZE * (GLYPH - 1) + 1 >= FONT_PLANE) {
            return;                                        // glyphe hors de l'atlas
        }
        for (int y = 0; y < GLYPH; y++) {
            if (at < 0 || at + 1 >= PLANESIZE) {
                return;
            }
            for (int p = 0; p < 3; p++) {
                int src = p * FONT_PLANE + off;
                more[(3 + p) * PLANESIZE + at] = font[src];
                more[(3 + p) * PLANESIZE + at + 1] = font[src + 1];
            }
            off += ROWSIZE;
            at += ROWSIZE;
        }
    }

    /**
     * {@code mnu_copycredz} : pose l'image des credits dans les plans de police, a la ligne 32.
     *
     * <p>Elle y brule exactement comme du texte, puisque le feu ne sait pas faire la difference.
     *
     * @param credits {@code menu/credits_only.raw}, trois plans de 320 sur 192
     */
    public void putCredits(byte[] credits) {
        final int plan = ROWSIZE * 192;
        int at = 32 * ROWSIZE;
        for (int i = 0; i < plan; i++) {
            for (int p = 0; p < 3; p++) {
                more[(3 + p) * PLANESIZE + at + i] = credits[p * plan + i];
            }
        }
    }

    // ------------------------------------------------- la composition

    /**
     * {@code Vid_PresentMenu} : les huit plans deviennent un octet par pixel.
     *
     * <p>Le fond est lu avec son decalage de defilement ; c'est pour cela qu'il a ete garde
     * deux fois.
     *
     * @param out un tampon de 320 par 256 octets, chaque octet un rang de la palette du menu
     */
    public void composite(byte[] out) {
        int scroll = scrollLine() * ROWSIZE;
        int p0 = scroll;
        int p1 = scroll + PLANESIZE * 2;
        int pi = 0;
        for (int y = 0; y < HEIGHT; y++) {
            int ro = y * ROWSIZE;
            for (int xb = 0; xb < ROWSIZE; xb++) {
                int b0 = screen[p0 + ro + xb] & 0xFF;
                int b1 = screen[p1 + ro + xb] & 0xFF;
                int b2 = more[ro + xb] & 0xFF;
                int b3 = more[PLANESIZE + ro + xb] & 0xFF;
                int b4 = more[2 * PLANESIZE + ro + xb] & 0xFF;
                int b5 = more[3 * PLANESIZE + ro + xb] & 0xFF;
                int b6 = more[4 * PLANESIZE + ro + xb] & 0xFF;
                int b7 = more[5 * PLANESIZE + ro + xb] & 0xFF;
                for (int bit = 7; bit >= 0; bit--) {
                    out[pi++] = (byte) (((b0 >> bit) & 1)
                            | (((b1 >> bit) & 1) << 1)
                            | (((b2 >> bit) & 1) << 2)
                            | (((b3 >> bit) & 1) << 3)
                            | (((b4 >> bit) & 1) << 4)
                            | (((b5 >> bit) & 1) << 5)
                            | (((b6 >> bit) & 1) << 6)
                            | (((b7 >> bit) & 1) << 7));
                }
            }
        }
    }

    // ------------------------------------------------- pour l'oracle seul

    /** La source de bruit, telle que {@code mnu_initrnd} l'a ecrite. */
    public byte[] noise() {
        byte[] out = new byte[NOISE_SIZE];
        System.arraycopy(more, NOISE, out, 0, NOISE_SIZE);
        return out;
    }

    /** Les trois plans de feu. */
    public byte[] firePlanes() {
        byte[] out = new byte[3 * PLANESIZE];
        System.arraycopy(more, 0, out, 0, out.length);
        return out;
    }

    /** Les trois plans de police. */
    public byte[] fontPlanes() {
        byte[] out = new byte[3 * PLANESIZE];
        System.arraycopy(more, 3 * PLANESIZE, out, 0, out.length);
        return out;
    }

    /** Les deux plans du fond, dedoubles. */
    public byte[] background() {
        return screen.clone();
    }
}
