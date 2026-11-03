package ab3d2.full.render;

import ab3d2.full.defs.Database;

/**
 * Ce qu'une chose MONTRE a l'image, pour le pas d'animation ou elle en est.
 *
 * <p>C'est le chainon qui manquait entre la simulation et le dessin. {@link Bitmap} sait poser
 * un sprite pourvu qu'on lui dise quelle feuille, quelle image et quelles demi-tailles ; le
 * fichier de niveau, lui, ne porte rien de tout cela — ces champs sont a ZERO au chargement, et
 * c'est l'animation qui les remplit a chaque image. Les voici.
 *
 * <p>Port de la partie DESSIN de {@code animObj} ({@code DEFANIMOBJ} / {@code ACTANIMOBJ}).
 * Le reste de cette routine — avancer au pas suivant, cumuler la hauteur, faire tourner un
 * modele — est de la simulation, et {@link ab3d2.full.sim.ObjectAnim} le porte deja.
 *
 * <h2>Trois facons d'etre vu</h2>
 *
 * <p>La definition de l'objet dit laquelle des trois, et les trois ne se ressemblent pas :
 *
 * <ul>
 *   <li><b>un sprite</b> : la feuille est le rang tel quel, les demi-tailles viennent du pas ;
 *   </li>
 *   <li><b>un modele</b> : les demi-tailles valent {@code $FFFF}. Ce n'est pas une taille, c'est
 *       une MARQUE — deux cent cinquante-cinq de demi-largeur veut dire « ceci n'est pas une
 *       image », et le rendu part alors vers les modeles en trois dimensions ;</li>
 *   <li><b>un miroitement</b> : le rang de feuille est NIE. Le mot devient negatif, son bit de
 *       poids fort s'arme, et c'est a ce bit que le rendu reconnait un halo. Nier un petit
 *       nombre pour armer un drapeau : le jeu entier est fait de cette economie-la.</li>
 * </ul>
 *
 * @param sheet     le mot de feuille, tel que l'enregistrement le porte — negatif pour un
 *                  miroitement
 * @param flags     les drapeaux de dessin ; toujours nuls ici, l'animation les efface
 * @param frame     l'image dans la feuille
 * @param halfWidth la demi-largeur dans le monde, ou deux cent cinquante-cinq pour un modele
 * @param halfDepth la demi-hauteur dans le monde
 *
 * <h2>Ce que le corpus ne separe pas</h2>
 *
 * <p>Douze cents appels — trente definitions, deux scripts, vingt pas —, aucun ecart. Sur onze
 * mutations, neuf mordent. Les deux autres se mesurent : les halos n'emploient qu'UN seul rang
 * de feuille dans tout le jeu, le huitieme, qui est justement celle qui s'appelle
 * {@code GLARE} ; et les sprites n'emploient que les sept premiers. Avec d'aussi petits
 * nombres, nier sans etendre le signe donne la meme chose, et le mot negatif qui en sort a
 * tous ses bits de poids fort armes — reconnaitre le halo sur le quatorzieme bit plutot que
 * sur le quinzieme ne se voit donc pas non plus. On garde la forme de l'original, qui ne
 * suppose rien de la taille des rangs.
 *
 * <p>Six cent quarante des douze cents pas designent un MODELE en trois dimensions, et
 * vingt-neuf un halo : la moitie de ce que ce jeu montre n'est pas une image plate.
 */
public record Appearance(int sheet, int flags, int frame, int halfWidth, int halfDepth) {

    /** La demi-largeur qui ne designe pas une taille mais un modele en trois dimensions. */
    public static final int VECTOR_MARK = 255;

    /** Le type de dessin d'un modele, dans la definition de l'objet. */
    private static final int GFX_VECTOR = 1;

    /**
     * Ce qu'un pas d'animation donne a voir.
     *
     * @param pas  le pas ou la chose en est
     * @param gfxType ce que la definition de l'objet dit de son dessin : moins d'un pour un
     *                sprite, un pour un modele, plus d'un pour un miroitement
     */
    public static Appearance of(Database.AnimStep pas, int gfxType) {
        if (gfxType < GFX_VECTOR) {                    // .bitmap
            // move.l #0,8(a0) ; move.b (a3,d0.w),9(a0) : l'octet BAS du mot de feuille.
            return new Appearance(pas.gfx() & 0xFF, 0, pas.frame(),
                    (pas.word2() >>> 8) & 0xFF, pas.word2() & 0xFF);
        }
        if (gfxType == GFX_VECTOR) {                   // .vector
            // move.w #$ffff,6(a0) : la marque, et non une taille.
            return new Appearance(pas.gfx() & 0xFF, 0, pas.frame(), VECTOR_MARK, VECTOR_MARK);
        }
        // .glare : move.b (a3,d0.w),d1 SIGNE ; ext.w ; neg.w ; move.w d1,8(a0)
        int feuille = (-(byte) pas.gfx()) & 0xFFFF;
        return new Appearance(feuille, 0, pas.frame(),
                (pas.word2() >>> 8) & 0xFF, pas.word2() & 0xFF);
    }

    /** Vrai si ce n'est pas une image mais un modele en trois dimensions. */
    public boolean vector() {
        return halfWidth == VECTOR_MARK;
    }

    /** Vrai si c'est un halo, que le rendu dessine par une tout autre voie. */
    public boolean glare() {
        return (sheet & 0x8000) != 0;
    }
}
