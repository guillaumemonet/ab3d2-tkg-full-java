package ab3d2.full.render;

import ab3d2.full.M68k;

/**
 * LE TABLEAU DE BORD : la bordure, ses deux compteurs, ses dix cases d'armes, et le reticule.
 *
 * <p>Port de la voie RTG de {@code c/draw.c} et de {@code modules/draw.s}. Le jeu n'a pas de
 * panneau separe : il a une IMAGE de trois cent vingt sur deux cent cinquante-six, posee une
 * fois pour toutes dans l'ecran, et la vue en trois dimensions vient se peindre par-dessus. En
 * plein ecran la vue couvre les deux cent trente-deux premieres lignes ; ce qui reste de
 * l'image — vingt-quatre lignes en bas — est le bandeau qu'on voit.
 *
 * <h2>Deux chiffres et dix cases</h2>
 *
 * <p>Les chiffres ne sont pas une police : ce sont cinq jeux de dix dessins, et le jeu choisit
 * le jeu avant de choisir le dessin.
 *
 * <ul>
 *   <li>les COMPTEURS (munitions, energie) ont deux jeux de huit sur sept : l'ordinaire, et
 *       celui de l'ALERTE, pris des que la valeur tombe a neuf ou moins. Trois chiffres, et la
 *       valeur est plafonnee a neuf cent quatre-vingt-dix-neuf ;</li>
 *   <li>les CASES D'ARMES ont trois jeux de huit sur cinq : arme absente, arme tenue, arme
 *       choisie. La case numero <i>i</i> montre le chiffre <i>i</i> — c'est le meme dessin qui
 *       sert de numero et de voyant.</li>
 * </ul>
 *
 * <p>Dans le fichier, les dix chiffres d'un jeu sont ENTRELACES : une ligne porte les dix
 * chiffres cote a cote, un octet chacun, et cela pour chacun des huit plans de bits. Les
 * remettre a plat est tout le travail de {@code Draw_Init}.
 *
 * <h2>Ce que le portage fait autrement, sans que cela se voie</h2>
 *
 * <p>Le jeu ne redessine un chiffre que s'il a CHANGE, parce que son image d'ecran persiste
 * d'une image a l'autre. Ici le tampon est efface a chaque image, donc on repose tout a chaque
 * fois. L'oracle le verifie : il releve des cas EN SUITE, ou un etat suit un autre, et le
 * resultat y est exactement celui du dessin complet.
 *
 * <h2>Ce que le corpus ne separe pas</h2>
 *
 * <p>Une seule mutation survit, et elle est equivalente par construction : dans le correctif du
 * regard vertical, faire basculer le ZERO d'une branche a l'autre ne change rien, puisque les
 * deux branches ne different que par une negation et qu'elles partent toutes deux de zero.
 */
public final class Hud {

    /** La largeur de l'ecran. */
    public static final int WIDTH = 320;

    /** Sa hauteur. */
    public static final int HEIGHT = 256;

    /**
     * La premiere ligne que la vue ne recouvre pas.
     *
     * <p>{@code C2P_FS_HEIGHT = FS_HEIGHT - FS_HEIGHT_C2P_DIFF}, soit deux cent quarante moins
     * huit. La vue en DESSINE deux cent quarante, mais le jeu n'en montre que deux cent
     * trente-deux : les huit dernieres passent sous le bandeau.
     */
    public static final int BAR_TOP = 232;

    /** La taille d'un plan de bits de l'ecran. */
    private static final int PLANE = WIDTH * HEIGHT / 8;

    private static final int CHAR_W = 8;
    private static final int CHAR_H = 7;
    private static final int SMALL_W = 8;
    private static final int SMALL_H = 5;
    private static final int SLOTS = 10;
    private static final int COUNT_W = 3 * CHAR_W;

    /** Ou se posent les deux compteurs et les cases. Un nombre negatif part du bord oppose. */
    private static final int AMMO_X = 160;
    private static final int AMMO_Y = -18;
    private static final int ENERGY_X = 272;
    private static final int ENERGY_Y = -18;
    private static final int ITEM_X = 24;
    private static final int ITEM_Y = -16;

    /** Au-dessous de ce compte, le chiffre passe a l'alerte. */
    private static final int LOW_AMMO = 9;
    private static final int LOW_ENERGY = 9;

    /** Ce que trois chiffres peuvent dire. */
    private static final int LIMIT = 999;

    /**
     * Les encres du reticule ({@code Draw_CrosshairPens_vb}).
     *
     * <p>La premiere est ZERO et ce n'est pas une couleur : c'est l'absence de reticule.
     */
    private static final int[] CROSSHAIR_PENS = {0, 255, 254, 190, 25, 250, 133, 69};

    /** Le reglage joueur par defaut : vert intense ({@code Prefs_CrossHairColour_b = 1}). */
    public static final int CROSSHAIR_DEFAULT = 1;

    private final byte[] border = new byte[WIDTH * HEIGHT];
    private final byte[] digitsGood = new byte[CHAR_W * CHAR_H * SLOTS];
    private final byte[] digitsWarn = new byte[CHAR_W * CHAR_H * SLOTS];
    private final byte[] digitsItem = new byte[SMALL_W * SMALL_H * SLOTS];
    private final byte[] digitsItemFound = new byte[SMALL_W * SMALL_H * SLOTS];
    private final byte[] digitsItemSelected = new byte[SMALL_W * SMALL_H * SLOTS];

    /** Le petit tampon ou les chiffres se rangent avant d'aller a l'ecran. */
    private final byte[] scratch = new byte[SMALL_H * SMALL_W * SLOTS];

    /**
     * @param packed {@code draw_BorderPacked_vb} : huit plans de bits de l'ecran entier
     * @param chars  {@code draw_BorderChars_vb} : les cinq jeux de chiffres, entrelaces
     */
    public Hud(byte[] packed, byte[] chars) {
        if (packed.length < 8 * PLANE) {
            throw new IllegalArgumentException("bordure tronquee : " + packed.length);
        }
        planarToChunky(border, packed, 0, WIDTH * HEIGHT);

        // L'ordre des cinq jeux dans le fichier n'est pas celui qu'on croit : les trois jeux de
        // cases viennent d'abord, puis l'alerte, puis l'ordinaire.
        convertDigits(digitsWarn, chars, 15 * CHAR_W * SLOTS, CHAR_W, CHAR_H);
        convertDigits(digitsGood, chars, 15 * CHAR_W * SLOTS + CHAR_H * CHAR_W * SLOTS,
                CHAR_W, CHAR_H);
        convertDigits(digitsItem, chars, 0, SMALL_W, SMALL_H);
        convertDigits(digitsItemFound, chars, SMALL_H * SLOTS * SMALL_W, SMALL_W, SMALL_H);
        convertDigits(digitsItemSelected, chars, SMALL_H * SLOTS * SMALL_W * 2,
                SMALL_W, SMALL_H);
    }

    /** L'image de bordure, en octets d'ecran. */
    public byte[] border() {
        return border;
    }

    /** Les chiffres ordinaires d'un compteur. */
    public byte[] digitsGood() {
        return digitsGood;
    }

    /** Ceux de l'alerte. */
    public byte[] digitsWarn() {
        return digitsWarn;
    }

    /** Les cases d'armes : absente, tenue, choisie. */
    public byte[] digitsItem() {
        return digitsItem;
    }

    public byte[] digitsItemFound() {
        return digitsItemFound;
    }

    public byte[] digitsItemSelected() {
        return digitsItemSelected;
    }

    /**
     * Pose la bordure ENTIERE ({@code Draw_ResetGameDisplay}, voie RTG).
     *
     * <p>Le jeu ne le fait qu'au chargement du niveau ; la vue recouvre ensuite le haut a
     * chaque image.
     */
    public void reset(byte[] dst, int span) {
        for (int y = 0; y < HEIGHT; y++) {
            System.arraycopy(border, y * WIDTH, dst, y * span, WIDTH);
        }
    }

    /** Pose le seul BANDEAU, c'est-a-dire ce que la vue ne recouvre pas. */
    public void bar(byte[] dst, int span) {
        for (int y = BAR_TOP; y < HEIGHT; y++) {
            System.arraycopy(border, y * WIDTH, dst, y * span, WIDTH);
        }
    }

    /**
     * Une image de tableau de bord : le bandeau, puis ce qu'il porte.
     *
     * @param slots    pour chacune des dix armes, vrai si le joueur la tient
     * @param selected celle qu'il a en main
     */
    public void frame(byte[] dst, int span, int ammo, int energy, boolean[] slots,
            int selected) {
        bar(dst, span);
        items(dst, span, slots, selected, screenX(ITEM_X), screenY(ITEM_Y));
        counter(dst, span, ammo, LOW_AMMO, screenX(AMMO_X), screenY(AMMO_Y));
        counter(dst, span, energy, LOW_ENERGY, screenX(ENERGY_X), screenY(ENERGY_Y));
    }

    /** {@code draw_UpdateCounter_RTG} : trois chiffres, d'un jeu ou de l'autre. */
    public void counter(byte[] dst, int span, int count, int limit, int x, int y) {
        count &= 0xFFFF;                               // UWORD
        int[] chiffres = valueToDigits(count);
        byte[] jeu = count > limit ? digitsGood : digitsWarn;

        for (int d = 0; d < 3; d++) {
            renderDigit(scratch, d * CHAR_W, COUNT_W, jeu, chiffres[d], CHAR_W, CHAR_H);
        }
        for (int ligne = 0; ligne < CHAR_H; ligne++) {
            System.arraycopy(scratch, ligne * COUNT_W, dst, (y + ligne) * span + x, COUNT_W);
        }
    }

    /** {@code draw_UpdateItems_RTG} : dix cases, chacune montrant son propre numero. */
    public void items(byte[] dst, int span, boolean[] slots, int selected, int x, int y) {
        int large = SMALL_W * SLOTS;
        for (int i = 0; i < SLOTS; i++) {
            byte[] jeu = selected == i ? digitsItemSelected
                    : slots != null && i < slots.length && slots[i] ? digitsItemFound
                    : digitsItem;
            renderDigit(scratch, i * SMALL_W, large, jeu, i, SMALL_W, SMALL_H);
        }
        for (int ligne = 0; ligne < SMALL_H; ligne++) {
            System.arraycopy(scratch, ligne * large, dst, (y + ligne) * span + x, large);
        }
    }

    /**
     * Le RETICULE : huit pixels, et rien de plus ({@code Draw_Crosshair}, modules/draw.s).
     *
     * <p>Deux croix en diagonale, l'une a deux pixels du centre, l'autre a quatre — le centre
     * lui-meme reste vide, pour ne pas cacher ce qu'on vise.
     *
     * @param colour     le reglage joueur, de zero a sept ; zero eteint le reticule
     * @param bottomY    {@code Vid_BottomY_w} : la hauteur de la vue
     * @param stopOffset {@code STOPOFFSET} : le regard vertical, en unites du jeu
     * @param fullScreen vrai en plein ecran : le regard vertical y decale le reticule
     */
    public void crosshair(byte[] dst, int span, int colour, int centreX, int bottomY,
            int stopOffset, boolean fullScreen) {
        int pen = CROSSHAIR_PENS[colour & 7];          // and.b #7,d0 ; paranoia
        if (pen == 0) {
            return;                                    // tst.b (a1) ; beq .done
        }
        int at = centreX;                              // add.w Vid_CentreX_w,a0
        int d0 = M68k.muls(bottomY, WIDTH / 2);        // muls.w #SCREEN_WIDTH/2,d0
        if (fullScreen) {
            // Le correctif du plein ecran : le regard vertical deplace le reticule de deux
            // lignes par neuf unites, arrondi au nombre PAIR inferieur.
            int d1 = stopOffset;
            if (d1 > 0) {
                d1 = M68k.muls(d1, 256 / 9);           // muls.w #(256/9),d1
                d1 >>= 8;                              // asr.l #8,d1
                d1 &= ~1;                              // and.b #$fe,d1
                d1 = M68k.muls(d1, WIDTH);             // muls.w #SCREEN_WIDTH,d1
                d0 = M68k.setw(d0, d0 + d1);           // add.w d1,d0
            } else {
                d1 = M68k.negw(d1);                    // neg.w d1
                d1 = M68k.muls(d1, 256 / 9);
                d1 >>= 8;
                d1 &= ~1;
                d1 = M68k.muls(d1, WIDTH);
                d0 = M68k.setw(d0, d0 - d1);           // sub.w d1,d0
            }
        }
        at += d0;                                      // add.l d0,a0

        dst[at - 4 * span - 4] = (byte) pen;
        dst[at - 4 * span + 4] = (byte) pen;
        dst[at - 2 * span - 2] = (byte) pen;
        dst[at - 2 * span + 2] = (byte) pen;
        dst[at + 2 * span - 2] = (byte) pen;
        dst[at + 2 * span + 2] = (byte) pen;
        dst[at + 4 * span - 4] = (byte) pen;
        dst[at + 4 * span + 4] = (byte) pen;
    }

    /** {@code draw_ValueToDigits} : trois chiffres, du plus fort au plus faible. */
    public static int[] valueToDigits(int value) {
        if (value > LIMIT) {
            value = LIMIT;
        }
        int[] d = new int[3];
        d[2] = value % 10;
        value /= 10;
        d[1] = value % 10;
        value /= 10;
        d[0] = value;
        return d;
    }

    /** Un chiffre, du jeu choisi vers le petit tampon. */
    private static void renderDigit(byte[] dst, int at, int span, byte[] jeu, int digit,
            int w, int h) {
        int from = digit * w * h;
        for (int y = 0; y < h; y++) {
            System.arraycopy(jeu, from + y * w, dst, at + y * span, w);
        }
    }

    /** {@code x >= 0 ? x : Vid_ScreenWidth + x}. */
    private static int screenX(int x) {
        return x >= 0 ? x : WIDTH + x;
    }

    /** {@code y >= 0 ? y : Vid_ScreenHeight + y}. */
    private static int screenY(int y) {
        return y >= 0 ? y : HEIGHT + y;
    }

    /**
     * Huit plans de bits vers un octet par pixel ({@code draw_PlanarToChunky}).
     *
     * <p>Le plan numero <i>b</i> donne le bit numero <i>b</i> du rang de palette ; le bit de
     * poids fort d'un octet est le pixel de GAUCHE.
     */
    private static void planarToChunky(byte[] dst, byte[] src, int at, int pixels) {
        for (int x = 0; x < pixels / 8; x++) {
            for (int p = 0; p < 8; p++) {
                int rang = 0;
                int bit = 1 << (7 - p);
                for (int b = 0; b < 8; b++) {
                    if ((src[at + b * PLANE + x] & bit) != 0) {
                        rang |= 1 << b;
                    }
                }
                dst[x * 8 + p] = (byte) rang;
            }
        }
    }

    /**
     * Un jeu de dix chiffres, de l'entrelacement du fichier a dix dessins consecutifs
     * ({@code draw_ConvertBorderDigitsToChunky}).
     *
     * <p>Le chiffre <i>d</i> commence un octet plus loin que le precedent, et ses huit plans
     * sont espaces de dix octets : c'est que la ligne porte les dix chiffres COTE A COTE, pour
     * chacun des huit plans. Passer d'une ligne a la suivante coute donc dix fois la largeur.
     */
    private static void convertDigits(byte[] dst, byte[] src, int at, int w, int h) {
        int[] planes = new int[8];
        int out = 0;
        for (int d = 0; d < SLOTS; d++) {
            for (int p = 0; p < 8; p++) {
                planes[p] = at + d + p * SLOTS;
            }
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w / 8; x++) {
                    for (int p = 0; p < 8; p++) {
                        int rang = 0;
                        int bit = 1 << (7 - p);
                        for (int b = 0; b < 8; b++) {
                            if ((src[planes[b] + x] & bit) != 0) {
                                rang |= 1 << b;
                            }
                        }
                        dst[out + x * 8 + p] = (byte) rang;
                    }
                }
                for (int p = 0; p < 8; p++) {
                    planes[p] += w * SLOTS;
                }
                out += w;
            }
        }
    }
}
