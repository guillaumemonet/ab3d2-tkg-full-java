package ab3d2.full.menu;

/**
 * LA PALETTE DU MENU : deux cent cinquante-six couleurs qui n'existent pas dans un fichier.
 *
 * <p>Le jeu ne stocke pas cette palette ; il la CALCULE au demarrage du menu
 * ({@code mnu_createpalette}), a partir de trois petites palettes — quatre couleurs pour le
 * fond, huit pour le feu, huit pour la police. Et le calcul dit tout de la facon dont les trois
 * couches se superposent :
 *
 * <ul>
 *   <li>si l'un des trois bits HAUTS est pose, c'est de la POLICE, et elle l'emporte sur tout
 *       le reste — le texte n'est jamais teinte par ce qu'il recouvre ;</li>
 *   <li>sinon, si l'un des bits du FEU est pose, la couleur est un MELANGE : la teinte du feu
 *       ajoutee a celle du fond, chaque composante bornee a deux cent cinquante-cinq. Une
 *       flamme ne remplace donc pas le fond, elle l'eclaire ;</li>
 *   <li>sinon, c'est le fond seul.</li>
 * </ul>
 *
 * <p>Le vert a un traitement a part dans le melange : il n'entre qu'aux TROIS QUARTS
 * ({@code (g * 3) / 4}), la ou le rouge et le bleu entrent entiers. C'est ce quart retire qui
 * fait virer les flammes a l'orange au lieu du jaune, et c'est la seule ligne du jeu qui decide
 * de la couleur du feu.
 */
public final class MenuPalette {

    /** Les couleurs, en {@code 0xRRGGBB}. */
    private final int[] couleurs = new int[256];

    /**
     * @param fond   {@code menu/back.pal}, quatre longs
     * @param feu    {@code menu/firepal.pal2}, huit longs
     * @param police {@code menu/font16x16.pal2}, huit longs
     */
    public MenuPalette(byte[] fond, byte[] feu, byte[] police) {
        for (int c = 0; c < 256; c++) {                    // for (WORD c = 0; c < 256; ++c)
            if ((c & 0xE0) != 0) {                         // if (c & 0xe0) : la POLICE
                couleurs[c] = lire(police, c >> 5);
            } else if ((c & 0x1C) != 0) {                  // if (c & 0x1c) : le FEU, melange
                int c1 = lire(feu, (c & 0x1C) >> 2);
                int c2 = lire(feu, c & 3);
                int r = Math.min(255, (c1 >>> 16) + (c2 >>> 16));
                int g = Math.min(255, (((c1 >> 8) & 0xFF) * 3) / 4 + ((c2 >> 8) & 0xFF));
                int b = Math.min(255, (c1 & 0xFF) + (c2 & 0xFF));
                couleurs[c] = (r << 16) | (g << 8) | b;
            } else {                                       // le FOND seul
                couleurs[c] = lire(fond, c & 3);
            }
        }
    }

    /** Un long gros-boutiste de la palette source ; hors table, le noir. */
    private static int lire(byte[] pal, int i) {
        int at = i * 4;
        if (pal == null || at + 3 >= pal.length) {
            return 0;
        }
        return ((pal[at] & 0xFF) << 24) | ((pal[at + 1] & 0xFF) << 16)
                | ((pal[at + 2] & 0xFF) << 8) | (pal[at + 3] & 0xFF);
    }

    /** La couleur du rang {@code i}, en {@code 0xRRGGBB}. */
    public int at(int i) {
        return couleurs[i & 0xFF];
    }

    /**
     * La couleur du rang {@code i}, assombrie par le fondu.
     *
     * <p>Le jeu ne fond pas l'image, il fond la PALETTE — {@code LoadRGB32} recharge les
     * couleurs multipliees par le facteur, ce qui coute trois multiplications par image au lieu
     * de quatre-vingt mille. Le resultat est le meme et le prix n'a rien a voir.
     *
     * @param fondu de zero (noir) a 256 (couleur pleine)
     */
    public int at(int i, int fondu) {
        if (fondu >= 256) {
            return at(i);
        }
        int c = at(i);
        int r = ((c >>> 16) & 0xFF) * fondu >> 8;
        int g = ((c >> 8) & 0xFF) * fondu >> 8;
        int b = (c & 0xFF) * fondu >> 8;
        return (r << 16) | (g << 8) | b;
    }
}
