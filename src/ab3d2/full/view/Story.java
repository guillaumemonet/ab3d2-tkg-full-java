package ab3d2.full.view;

import ab3d2.full.Data;
import ab3d2.full.render.Screen;

/**
 * LE TEXTE QUI OUVRE UN NIVEAU.
 *
 * <p>Seize lignes de recit, montrees avant de jouer. Sur l'Amiga elles s'affichaient PENDANT
 * que la disquette chargeait le niveau : le texte n'etait pas une pause, c'etait l'attente
 * elle-meme, habillee. Ici le chargement ne prend rien, et l'ecran se garde donc jusqu'a ce
 * qu'on valide — ou huit secondes, pour qui ne touche a rien.
 *
 * <h2>Pourquoi six cent quarante pixels</h2>
 *
 * <p>Le jeu ouvre pour ce texte un ecran DEUX FOIS plus fin que celui du jeu, et sa police y
 * est taillee. La rendre directement en trois cent vingt reviendrait a jeter un pixel sur
 * deux et a hacher les lettres fines. On la rend donc a sa largeur, puis on reunit les paires
 * de pixels par un OU — un trait qui tombe entre deux colonnes survit, plus pale qu'il ne
 * devrait, mais lisible. C'est la seule facon honnete de descendre d'une resolution a l'autre
 * sans retoucher la police.
 *
 * <h2>La couleur</h2>
 *
 * <p>Le texte n'est pas blanc : il MONTE vers un vert franc, en huit pas d'une image chacun.
 * Le jeu ne retouche pas les pixels pour cela — il pose un mot couleur dans le registre que la
 * copper list reserve a l'encre du texte, et le fond reste noir. On fait de meme : les pixels
 * valent zero ou un, et c'est {@link #argb(int)} qui change.
 */
public final class Story {

    /** Combien de lignes un niveau raconte. */
    public static final int LINES = 16;

    /** La taille d'une ligne dans le fichier : deux octets d'en-tete et quatre-vingts de texte. */
    private static final int RECORD = 82;

    /** Combien de caracteres une ligne porte. */
    private static final int TEXT = 80;

    /** La hauteur d'une ligne, qui est aussi celle d'un glyphe. */
    private static final int ROW = 16;

    /** Le premier caractere que la police porte, et le dernier. */
    private static final int FIRST_CHAR = 32;
    private static final int LAST_CHAR = 127;

    /** Ce qu'un glyphe occupe : seize lignes d'un mot. */
    private static final int GLYPH = ROW * 2;

    /** La largeur de l'ecran ou le texte se rend, avant d'etre ramene a celle du jeu. */
    private static final int WIDE = Screen.WIDTH * 2;

    /**
     * Le fondu : {@code move.w #$10,d0} puis {@code add.w #$121,d0}, huit fois.
     *
     * <p>Ce sont des mots couleur Amiga {@code $0RGB} — $010, $131, $252, $373, $494, $5B5,
     * $6D6, et pour finir <b>$7F7</b>. Les quartets ne debordent pas l'un sur l'autre sur ces
     * huit pas, ce qui est sans doute pourquoi le pas vaut $121 et non $111.
     */
    private static final int COLOUR_START = 0x010;
    private static final int COLOUR_STEP = 0x121;
    private static final int FADE = 8;

    /**
     * Au bout de combien d'images l'ecran se rend de lui-meme.
     *
     * <p>L'original n'en a pas besoin : il attend la disquette. Huit secondes, c'est le temps
     * qu'un chargement prenait.
     */
    private static final int PATIENCE = 400;

    /** Le texte des seize niveaux, ou {@code null} si le fichier manque. */
    private final byte[] text;

    /** La police, et la largeur propre de chacun de ses glyphes. */
    private final byte[] font;
    private final byte[] widths;

    /** L'ecran rendu, un octet par pixel : zero ou un. */
    private final byte[] pixels = new byte[Screen.WIDTH * Screen.HEIGHT];

    /** Depuis combien d'images l'ecran est la. */
    private int age;

    /** Vrai tant qu'il y a quelque chose a montrer. */
    private boolean showing;

    public Story() {
        this.font = Data.endFont();
        this.widths = Data.charWidths();
        this.text = Data.story();
    }

    /** Vrai si le fichier de texte est la : sans lui, il n'y a rien a montrer. */
    public boolean available() {
        return text != null && font != null && widths != null;
    }

    /** Vrai tant que l'ecran doit rester. */
    public boolean showing() {
        return showing;
    }

    /**
     * Prepare le texte d'un niveau.
     *
     * @return vrai s'il y a quelque chose a montrer
     */
    public boolean begin(int level) {
        age = 0;
        showing = false;
        if (!available()) {
            return false;
        }
        int base = level * RECORD * LINES;
        if (base < 0 || base + RECORD * LINES > text.length) {
            return false;                              // un niveau que le fichier ne couvre pas
        }
        render(base);
        showing = true;
        return true;
    }

    /**
     * Une image de plus.
     *
     * @param valider ce que le joueur vient de demander : passer
     * @return vrai tant que l'ecran reste
     */
    public boolean frame(boolean valider) {
        if (!showing) {
            return false;
        }
        age++;
        // Pendant le fondu, rien ne passe : l'original non plus ne regarde la manette qu'une
        // fois sa couleur posee.
        if (age > FADE && (valider || age >= PATIENCE)) {
            showing = false;
        }
        return showing;
    }

    /** L'ecran, un octet par pixel. */
    public byte[] pixels() {
        return pixels;
    }

    /** La couleur d'un rang : le fond est noir, l'encre monte vers le vert. */
    public int argb(int rang) {
        if (rang == 0) {
            return 0xFF000000;
        }
        int mot = COLOUR_START + Math.min(age, FADE - 1) * COLOUR_STEP;
        // Un quartet Amiga s'etend a l'octet en le repetant, soit une multiplication par dix-sept.
        int r = ((mot >> 8) & 0xF) * 17;
        int v = ((mot >> 4) & 0xF) * 17;
        int b = (mot & 0xF) * 17;
        return 0xFF000000 | (r << 16) | (v << 8) | b;
    }

    // ===== le rendu =====

    /** Rend les seize lignes, en six cent quarante, puis les ramene a trois cent vingt. */
    private void render(int base) {
        byte[] large = new byte[WIDE * Screen.HEIGHT];
        for (int line = 0; line < LINES; line++) {
            int at = base + line * RECORD;
            // L'octet de tete nomme la police. Il vaut zero sur les deux mille cinq cent
            // soixante lignes du fichier, et seule la police zero existe.
            boolean centre = text[at + 1] != 0;
            draw(large, at + 2, line * ROW, centre);
        }
        // Le passage en trois cent vingt : deux pixels pour un, reunis par un OU.
        for (int y = 0; y < Screen.HEIGHT; y++) {
            int from = y * WIDE;
            int to = y * Screen.WIDTH;
            for (int x = 0; x < Screen.WIDTH; x++) {
                pixels[to + x] = (byte) ((large[from + x * 2] | large[from + x * 2 + 1]) & 1);
            }
        }
    }

    /** Une ligne de texte, a sa place. */
    private void draw(byte[] large, int at, int y, boolean centre) {
        int x = centre ? WIDE / 2 - trimmed(at) / 2 : 0;
        if (x < 0) {
            x = 0;
        }
        for (int i = 0; i < TEXT; i++) {
            int c = text[at + i] & 0xFF;
            if (c == 0) {
                return;                                // le zero ferme la ligne
            }
            if (c < FIRST_CHAR || c > LAST_CHAR) {
                x += widths[0] & 0xFF;                 // tout ce qui ne se peint pas vaut un blanc
                continue;
            }
            x += glyph(large, c, x, y);
        }
    }

    /**
     * La largeur d'une ligne, les blancs de FIN non comptes.
     *
     * <p>Le centrage se fait sur ce que la ligne OCCUPE, pas sur ce qu'elle contient : une
     * ligne suivie de vingt espaces se centre comme si elle s'arretait au dernier mot.
     */
    private int trimmed(int at) {
        int sum = 0;
        int last = 0;
        for (int i = 0; i < TEXT; i++) {
            int c = text[at + i] & 0xFF;
            if (c < FIRST_CHAR || c > LAST_CHAR) {
                c = FIRST_CHAR;
            }
            sum += widths[c - FIRST_CHAR] & 0xFF;
            if (c != FIRST_CHAR) {
                last = sum;
            }
        }
        return last;
    }

    /**
     * Un glyphe, et sa largeur.
     *
     * <p>Le glyphe est cale a DROITE dans son mot, sur sa largeur propre : le pixel de rang
     * {@code k} est le bit {@code largeur - 1 - k}. C'est l'insertion de champ de bits du
     * code d'origine, qui pose les bits BAS.
     */
    private int glyph(byte[] large, int c, int x, int y) {
        int w = widths[c - FIRST_CHAR] & 0xFF;
        int from = (c - FIRST_CHAR) * GLYPH;
        for (int row = 0; row < ROW; row++) {
            int mot = ((font[from + row * 2] & 0xFF) << 8) | (font[from + row * 2 + 1] & 0xFF);
            if (mot == 0) {
                continue;
            }
            int yy = y + row;
            if (yy >= Screen.HEIGHT) {
                break;
            }
            int to = yy * WIDE;
            for (int px = 0; px < w; px++) {
                if (((mot >> (w - 1 - px)) & 1) != 0) {
                    int xx = x + px;
                    if (xx >= 0 && xx < WIDE) {
                        large[to + xx] = 1;
                    }
                }
            }
        }
        return w;
    }
}
