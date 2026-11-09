package ab3d2.full.view;

import ab3d2.full.render.Text;

/**
 * LES MESSAGES du jeu : ce qui s'ecrit en haut a gauche quand on ramasse ou qu'on lit.
 *
 * <p>Port de {@code c/message.c}, voie plein ecran. Le systeme tient en trois idees :
 *
 * <ul>
 *   <li>un ANNEAU de huit lignes. Pousser une ligne avance d'un cran, et la plus ancienne
 *       disparait. Rien n'est efface : l'anneau se parcourt de la plus vieille a la plus
 *       recente, et les cases vides sont sautees ;</li>
 *   <li>un BATTEMENT de deux secondes qui pousse une ligne VIDE. C'est ainsi, et seulement
 *       ainsi, que les messages finissent par s'en aller : rien ne les efface, ils sont
 *       repousses hors de l'anneau par du vide ;</li>
 *   <li>une ligne trop longue est COUPEE, au plus en cinq morceaux, chacun sur une ligne. La
 *       coupe se fait a la largeur reelle du texte, pas au nombre de caracteres.</li>
 * </ul>
 *
 * <p>Le jeu mesure le battement a l'horloge, pas aux images ; c'est pourquoi l'appelant donne
 * ici l'heure en millisecondes. A cinquante images par seconde les deux coincident, mais une
 * image qui traine ne doit pas ralentir les messages.
 */
public final class MessageBoard {

    /** MSG_LINE_BUFFER_SIZE : la taille de l'anneau. */
    private static final int SIZE = 8;
    private static final int MASK = SIZE - 1;

    /** MSG_SCROLL_PERIOD_MS. */
    public static final long SCROLL_MS = 2000;

    /** MSG_MAX_LINES_SMALL : combien de morceaux au plus pour une ligne coupee. */
    private static final int MAX_PIECES = 4;

    /** msg_TagPens : l'encre de chaque etiquette. */
    private static final int[] TAG_PENS = {255, 254, 125, 252};

    /** MSG_TAG_NARRATIVE : le texte du niveau, en vert intense. */
    public static final int NARRATIVE = 0;

    /** MSG_TAG_DEFAULT : tout le reste, en demi-vert. */
    public static final int DEFAULT = 1;

    /** MSG_TAG_OPTIONS : les reglages, en gris-bleu. */
    public static final int OPTIONS = 2;

    /** MSG_TAG_OTHER. */
    public static final int OTHER = 3;

    /**
     * La longueur en-dessous de laquelle une ligne tient a coup sur.
     *
     * <p>{@code (SCREEN_WIDTH / MAX_PROP_CHAR_WIDTH) - 2} : le pire caractere de la police fait
     * sept pixels de large, donc quarante-quatre d'entre eux tiennent quoi qu'il arrive.
     */
    private static final int FIT_CHARS = 320 / 7 - 2;

    /** La largeur ou l'on coupe : l'ecran moins une case de glyphe de chaque cote. */
    private static final int FIT_PIXELS = 320 - 2 * Text.CHAR_W;

    private final Text police;
    private final String[] lignes = new String[SIZE];
    private final int[] etiquettes = new int[SIZE];

    /** msg_Buffer.lineNumber : la derniere case ecrite. */
    private int courante = SIZE - 1;

    /** Quand le prochain battement aura lieu. Nul au depart : le premier a lieu aussitot. */
    private long prochain;

    public MessageBoard(Text police) {
        this.police = police;
    }

    /** msg_NextLineNumber, en plein ecran. */
    private static int suivante(int n) {
        return (n + 1) & MASK;
    }

    private void pousser(String texte, int etiquette) {
        courante = suivante(courante);
        lignes[courante] = texte;
        etiquettes[courante] = etiquette;
    }

    /**
     * {@code Msg_PushLine} : une ligne, coupee si elle ne tient pas.
     *
     * @param texte      la ligne
     * @param etiquette {@link #NARRATIVE}, {@link #DEFAULT}, {@link #OPTIONS} ou {@link #OTHER}
     */
    public void push(String texte, int etiquette) {
        if (texte == null) {
            return;
        }
        int longueur = texte.length();
        if (longueur <= FIT_CHARS) {
            pousser(texte, etiquette);
            return;
        }
        int[] curseur = {0};
        int morceaux = MAX_PIECES;
        int depuis = 0;
        int reste = longueur;
        do {
            int tient = police.split(curseur, texte, reste, FIT_PIXELS);
            pousser(texte.substring(depuis, Math.min(depuis + tient, longueur)), etiquette);
            depuis = curseur[0];
            reste -= tient;
        } while (curseur[0] >= 0 && morceaux-- != 0);
    }

    /**
     * {@code Msg_Tick} : toutes les deux secondes, une ligne VIDE entre dans l'anneau et
     * repousse les autres.
     */
    public void tick(long maintenant) {
        if (maintenant >= prochain) {
            prochain = maintenant + SCROLL_MS;
            pousser(null, 0);
        }
    }

    /**
     * {@code Msg_RenderFullsccreenBuffer} : les lignes, de la plus vieille a la plus recente.
     *
     * @param dst   le tampon d'ecran
     * @param span  la distance d'une ligne de pixels a la suivante
     * @param marge la bande noire du haut, nulle en plein ecran sans letterbox
     */
    public void draw(byte[] dst, int span, int marge) {
        int derniere = suivante(courante);
        int at = derniere;
        int y = marge + Text.MARGIN;
        do {
            if (lignes[at] != null) {
                police.prop(dst, span, lignes[at].length(), lignes[at], Text.MARGIN, y,
                        TAG_PENS[etiquettes[at]]);
                y += Text.CHAR_H + Text.LINE_SPACING;
            }
            at = suivante(at);
        } while (at != derniere);
    }
}
