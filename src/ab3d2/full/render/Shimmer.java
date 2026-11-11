package ab3d2.full.render;

/**
 * La DEMATERIALISATION : l'image qui se delite quand le joueur se teleporte, ou quand il
 * atteint la sortie du niveau.
 *
 * <p>C'est l'effet le moins couteux du jeu, et le plus malin. Il ne calcule rien : il REGARDE
 * l'image de travers. Une table de decalages, lue au fil de l'image, dit pour chaque groupe de
 * huit pixels ou aller chercher ses couleurs. A la premiere image les decalages sont presque
 * nuls et l'on voit l'image telle quelle ; a la huitieme ils sont larges et il ne reste qu'une
 * bouillie de la bonne couleur moyenne.
 *
 * <p>La table ({@code shimmerfile}, huit mille cent quatre-vingt-douze octets) tient HUIT blocs
 * de mille vingt-quatre. L'indice d'image choisit le bloc ; dans le bloc, chaque groupe de huit
 * pixels consomme SIX octets — un mot pour les quatre premiers, un long dont on ne garde que le
 * mot bas pour les quatre suivants.
 *
 * <h2>Trois details qui ne vont pas de soi</h2>
 *
 * <ul>
 *   <li>le pointeur de SOURCE avance de huit APRES les deux lectures ({@code addq #8,a0}) : les
 *       decalages sont donc relatifs au groupe courant, pas au debut de la ligne. Oublier cet
 *       {@code addq} reduit l'ecran a des trainees horizontales ;</li>
 *   <li>le decalage dans la table s'enroule a la fin de chaque ligne ({@code and.l #255*2}),
 *       pas a la fin du bloc : les lignes se repetent par paquets de quarante groupes ;</li>
 *   <li>l'original melange aussi les BITS entre plans ; ici l'ecran est en octets, un pixel est
 *       un rang de palette, et ce melange-la n'a pas d'objet.</li>
 * </ul>
 *
 * <p>Porte de {@code c2p_Convert1xTeleFx} (chunky.s, {@code NEWCHUNKYTEL}).
 */
public final class Shimmer {

    /** Le nombre de pas de l'effet : la table en porte autant de blocs. */
    public static final int FRAMES = 8;

    /** La taille d'un bloc de la table, en octets. */
    private static final int BLOCK = 1024;

    /** Les pixels traites ensemble : quatre d'un cote, quatre de l'autre. */
    private static final int GROUP = 8;

    /** Ce qu'un groupe consomme dans la table : un mot, puis un long. */
    private static final int PER_GROUP = 6;

    /** {@code and.l #255*2} : l'enroulement du decalage, a chaque fin de ligne. */
    private static final int LINE_WRAP = 0x1FE;

    private final byte[] table;
    private final byte[] work = new byte[Screen.WIDTH * Screen.HEIGHT];

    /** @param table le contenu de {@code shimmerfile} */
    public Shimmer(byte[] table) {
        this.table = table;
    }

    /**
     * L'image regardee de travers.
     *
     * @param src   l'ecran tel qu'il vient d'etre peint
     * @param frame le pas de l'effet, de zero (presque net) a sept (illisible)
     * @return un tampon interne, reutilise d'une image a l'autre
     */
    public byte[] apply(byte[] src, int frame) {
        int bloc = Math.max(0, Math.min(FRAMES - 1, frame)) * BLOCK;
        int pos = 0;                                   // le decalage courant dans la table
        for (int y = 0; y < Screen.HEIGHT; y++) {
            int ligne = y * Screen.WIDTH;
            int a0 = ligne;                            // la source, qui avance de huit en huit
            for (int g = 0; g < Screen.WIDTH / GROUP; g++) {
                int decA = s16(bloc + pos);            // move.w (a6)+,d0
                pos += 2;
                int decB = s16(bloc + pos + 2);        // move.l (a6)+,d1 ; d1.w = le mot BAS
                pos += 4;
                int de = ligne + g * GROUP;
                int srcA = a0 + decA;                  // (a0,d0.w)
                int srcB = a0 + 4 + decB;              // 4(a0,d1.w)
                for (int i = 0; i < 4; i++) {
                    work[de + i] = src[borne(srcA + i)];
                    work[de + 4 + i] = src[borne(srcB + i)];
                }
                a0 += GROUP;                           // addq #8,a0 : APRES les deux lectures
            }
            pos &= LINE_WRAP;                          // and.l #255*2 : l'enroulement par ligne
        }
        return work;
    }

    /** Un mot signe de la table. */
    private int s16(int at) {
        if (at < 0 || at + 1 >= table.length) {
            return 0;
        }
        return (short) (((table[at] & 0xFF) << 8) | (table[at + 1] & 0xFF));
    }

    /**
     * Un decalage peut sortir de l'ecran, et le jeu s'en moque : il lirait la memoire d'a cote.
     * Ici on borne, faute de pouvoir lire ailleurs — l'image en est indiscernable, puisque les
     * pixels concernes sont ceux des bords et qu'ils sont deja brouilles.
     */
    private static int borne(int i) {
        if (i < 0) {
            return 0;
        }
        return i >= Screen.WIDTH * Screen.HEIGHT ? Screen.WIDTH * Screen.HEIGHT - 1 : i;
    }
}
