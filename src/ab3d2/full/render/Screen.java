package ab3d2.full.render;

/**
 * Le tampon ou le rendu depose ses pixels.
 *
 * <p>Un octet par pixel : non pas une couleur mais un RANG dans la palette de l'ecran. Le
 * tampon fait toujours trois cent vingt colonnes, que la vue soit en plein ecran ou encadree —
 * dans le second cas le jeu n'en remplit qu'une partie, mais la largeur d'une ligne ne change
 * pas.
 *
 * <p>La table des debuts de ligne ne couvre que <b>deux cent cinquante-six</b> lignes, soit la
 * hauteur du tampon et pas une de plus. Le jeu ne verifie rien : c'est le decoupage vertical,
 * en amont, qui garantit qu'aucune ligne ne sort.
 */
public final class Screen {

    /** La largeur d'une ligne, en octets. */
    public static final int WIDTH = 320;

    /** La hauteur du tampon. */
    public static final int HEIGHT = 256;

    /** La hauteur reellement dessinee en plein ecran, et en vue encadree. */
    public static final int FULL_HEIGHT = 240;
    public static final int SMALL_HEIGHT = 160;

    /** Ou commence chaque ligne. */
    private static final int[] LINE = new int[HEIGHT];

    static {
        for (int y = 0; y < HEIGHT; y++) {
            LINE[y] = y * WIDTH;
        }
    }

    private final byte[] pixels = new byte[WIDTH * HEIGHT];

    /** Les pixels, dans l'ordre des lignes. */
    public byte[] pixels() {
        return pixels;
    }

    /** Ou commence la ligne {@code y}. */
    public int line(int y) {
        return LINE[y];
    }

    /** Efface tout. */
    public void clear() {
        java.util.Arrays.fill(pixels, (byte) 0);
    }
}
