package ab3d2.full.render;

import ab3d2.full.level.FloorTexture;
import ab3d2.full.level.Level;
import ab3d2.full.level.ShadeTable;

/**
 * La mise en couleurs d'un sol ou d'un plafond : d'une tranche d'ecran aux pixels.
 *
 * <p>Pendant du {@link WallPaint}, et son contraire en presque tout. Un mur peint une colonne
 * et change d'ombrage a chaque pixel ; un sol peint une LIGNE et garde le meme d'un bout a
 * l'autre, parce que toute la ligne est a la meme distance. Il n'y a donc ici ni damier, ni
 * table de pas, ni retenue a propager : une boucle de quelques instructions, et c'est tout.
 *
 * <p>Ce qui s'y joue est ailleurs.
 *
 * <p><b>Quatre tuiles dans le meme quadrillage.</b> Les seize textures de sol ne se suivent pas :
 * elles sont entrelacees quatre par quatre, chacune occupant un octet sur quatre. Le rendu
 * avance donc de quatre en quatre, et le numero de tuile n'est pas un numero mais un
 * DEPLACEMENT dans le bloc — ce qui rend le changement de tuile gratuit : une adresse de depart
 * differente, la meme boucle.
 *
 * <p><b>Une position de texture en deux moities qui ne se parlent pas.</b> L'ordonnee occupe le
 * mot de poids fort, l'abscisse celui de poids faible, et les deux avancent d'une seule addition
 * de mot double. Rien n'empeche l'une de deborder dans l'autre : c'est le masque
 * {@code 0x3FFF3FFF}, applique a chaque pixel, qui les tient separees. Chaque moitie garde
 * quatorze bits, dont six pour la partie entiere — soit soixante-quatre texels, la largeur
 * d'une tuile — et huit pour la fraction.
 *
 * <p><b>Un octet de texture EST un rang de palette.</b> Contrairement aux murs, qui rangent un
 * rang de rampe sur cinq bits, un sol porte directement une couleur. L'eclairage se fait donc
 * apres coup, en choisissant une rangee de la {@link ShadeTable} : eclairer, ici, c'est
 * traduire une palette entiere par une autre.
 *
 * <p>Ne sont pas portees ici les surfaces d'EAU, qui ont leur propre boucle : celle-la fait
 * onduler la ligne avant de la lire et attend l'animation. {@link Flat} en produit pourtant les
 * tranches — elles seront peintes quand l'eau le sera.
 */
public final class FlatPaint {

    /**
     * De combien la table d'ombrage avance par niveau de luminosite.
     *
     * <p>DEUX rangees, non une. Le rendu interpole des sols avance d'une seule, et c'est lui qui
     * donne son echelle au bloc : trente-deux rangees pour trente-deux niveaux. Le rendu plat,
     * qui ne porte qu'une luminosite par ligne, saute donc une rangee sur deux et n'en atteint
     * que seize — alors que sa luminosite monte jusqu'a vingt-huit. Voir {@link #offTable()}.
     */
    private static final int STEP = 2 * ShadeTable.ROW;

    /** Le masque qui tient l'ordonnee et l'abscisse chacune de son cote du mot double. */
    private static final int BOTH = 0x3FFF3FFF;

    private final Screen screen;

    private FloorTexture tiles;
    private ShadeTable shades;
    private int tile;

    private int offTable;

    public FlatPaint(Screen screen) {
        this.screen = screen;
    }

    /** La tuile dont les prochaines tranches sont faites. */
    public void flat(Level.Flat flat, FloorTexture tiles, ShadeTable shades) {
        this.tiles = tiles;
        this.shades = shades;
        this.tile = flat.tile();
    }

    /**
     * Combien de tranches ont ete abandonnees faute de pouvoir lire la table d'ombrage.
     *
     * <p>Pas une precaution : une consequence. La luminosite d'une ligne va jusqu'a
     * vingt-huit, le bloc des sols ne porte que trente-deux rangees et le rendu plat en saute
     * une sur deux — au-dela de quinze, il lit donc APRES la table. Ce qui suit en memoire
     * n'est pas la meme chose ici que sur l'Amiga ; on compte au lieu d'inventer.
     *
     * <p>Cela n'arrive qu'aux lignes les plus lointaines, celles qui bordent l'horizon : neuf
     * tranches sur quatorze mille quatre cent quarante-six, dans le corpus de reference.
     */
    public int offTable() {
        return offTable;
    }

    /** Remet ce compte a zero. */
    public void clearOffTable() {
        offTable = 0;
    }

    /**
     * Peint une tranche, et rend le nombre de pixels ecrits.
     *
     * <p>{@code -1} quand la tranche n'a pas pu etre peinte, sa luminosite designant une
     * rangee qui n'existe pas.
     */
    public int paint(Flat.Span span) {
        int row = ShadeTable.FLOOR + STEP * span.bright();
        if (row < 0 || row + 255 >= shades.bytes().length) {
            offTable++;
            return -1;
        }
        int width = span.width();
        byte[] pixels = screen.pixels();
        int to = screen.line(span.y()) + span.left();
        int uv = span.texture();
        int step = span.step();
        for (int n = 0; n < width; n++) {
            // L'ordonnee garde son octet HAUT, l'abscisse ne donne que le sien : ce sont les
            // deux parties entieres, accolees pour ne faire qu'un seul index.
            int at = ((uv >>> 16) & 0xFF00) | ((uv & 0xFFFF) >>> 8);
            int texel = tiles.texel(tile + at * 4);
            if (texel < 0) {
                offTable++;
                return -1;
            }
            pixels[to++] = (byte) shades.colour(row + texel);
            uv = (uv + step) & BOTH;
        }
        return width;
    }
}
