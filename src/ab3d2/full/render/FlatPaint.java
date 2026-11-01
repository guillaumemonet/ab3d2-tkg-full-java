package ab3d2.full.render;

import ab3d2.full.level.FloorTexture;
import ab3d2.full.level.Level;
import ab3d2.full.SinCos;
import ab3d2.full.level.ShadeTable;
import ab3d2.full.level.WaterFrames;

import static ab3d2.full.M68k.asrw;
import static ab3d2.full.M68k.divs;
import static ab3d2.full.M68k.muls;
import static ab3d2.full.M68k.s16;
import static ab3d2.full.M68k.setw;

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
 * <p><b>Deux eclairages, une seule boucle.</b> Le code d'origine n'a qu'une routine de texels
 * pour les sols, et elle lit TOUJOURS la luminosite interpolee. Le rendu plat s'en sort en lui
 * donnant une pente nulle et en rangeant sa rangee d'ombrage dans le pointeur de table ; le
 * rendu degrade laisse le pointeur au debut du bloc et fait tout varier. C'est pourquoi les
 * deux avancent d'un nombre de rangees different — deux pour l'un, une pour l'autre — alors
 * qu'ils peignent avec le meme code.
 *
 * <p><b>L'eau ne se peint pas, elle se reflete.</b> Sa boucle ne lit aucune texture de sol :
 * elle relit l'ECRAN, quelques lignes plus loin, et repasse l'octet trouve par une rampe de
 * miroitement. Il faut donc que le reste de la scene soit deja dessine — l'eau se pose
 * par-dessus, et c'est pourquoi elle a sa propre boucle plutot qu'un reglage de l'autre.
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

    /** Ce que la distance ajoute au miroitement, et la borne ou cet ajout s'arrete. */
    private static final int GLARE_MASK = 0x3F00;
    private static final int GLARE_MAX = 5 * 512;

    /** De combien de lignes le reflet est decale, au minimum. */
    private static final int LIFT = 4;

    /** Le masque qui ramene un angle dans un tour, en octets. */
    private static final int ANGLE_MASK = 8191;

    private final Screen screen;

    private FloorTexture tiles;
    private ShadeTable shades;
    private int tile;

    // Ce que l'eau demande en plus : les images de l'ondulation, les compteurs qui les
    // animent, et la table de sinus qui dit de combien le reflet se decale.
    private WaterFrames ripples;
    private Water anim;
    private SinCos sinCos;

    private int offTable;

    public FlatPaint(Screen screen) {
        this.screen = screen;
    }

    /** De quoi peindre l'eau : les images de l'ondulation et ce qui les fait bouger. */
    public void water(WaterFrames ripples, Water anim, SinCos sinCos) {
        this.ripples = ripples;
        this.anim = anim;
        this.sinCos = sinCos;
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
     * Peint une tranche a l'ombrage INTERPOLE, et rend le nombre de pixels ecrits.
     *
     * <p>La rangee d'ombrage n'est plus fixe : elle part de {@link Flat.Span#leftBright} et
     * avance de {@link Flat.Span#brightSpeed} a chaque pixel, en huit-huit. Le pointeur de
     * table reste au debut du bloc des sols, et c'est la luminosite qui porte le deplacement —
     * d'UNE rangee par niveau, la ou le rendu plat en saute deux.
     */
    /**
     * Peint une tranche d'EAU, et rend le nombre de pixels ecrits.
     *
     * <p>Aucune texture n'est lue : chaque pixel reprend celui qui est deja a l'ecran quelques
     * lignes plus loin, et le repasse par une rampe de miroitement. Deux choses decident de
     * cette rampe — la distance, et l'ondulation lue dans l'image en cours.
     *
     * <p>{@code null} quand le reflet irait chercher hors de l'image.
     */
    public Reflection paintWater(Flat.Span span, boolean above) {
        int distance = span.distance();

        // Le MIROITEMENT : plus la surface est loin, plus la rampe choisie est claire, jusqu'a
        // une borne. Le masque ne garde que six bits de la distance, ce qui fait de ce reflet
        // un escalier de onze marches et non une pente.
        int glare = s16(setw(0, (distance & GLARE_MASK) * 2));
        if (glare >= GLARE_MAX) {
            glare = GLARE_MAX;
        }


        // De combien de LIGNES le reflet est decale. L'angle avance d'un battement a l'autre et
        // se melange a la distance : chaque ligne ondule a son propre rythme, et les lignes
        // lointaines ondulent moins, parce qu'on divise par leur distance.
        int wave = sinCos.sin(s16(setw(0, distance << 7) + anim.angle()) & ANGLE_MASK);
        int lines = s16(asrw(setw(0, divs(wave, s16(distance + 300))), 5) + LIFT);
        if (lines >= span.toBottom()) {
            lines = s16(span.toBottom() - 1);
        }
        // Le decalage est ensuite ramene a un MOT : au-dela de cent deux lignes il changerait
        // de signe et le reflet se prendrait de l'autre cote. Cela n'arrive pas — le reflet ne
        // depasse jamais SIX lignes sur les dix mille tranches du corpus, parce que la division
        // par la distance l'ecrase — mais le mot est la dans l'original et on le garde.
        int lift = s16(above ? -muls(lines, Screen.WIDTH) : muls(lines, Screen.WIDTH));

        int width = span.width();
        byte[] pixels = screen.pixels();
        int to = screen.line(span.y()) + span.left();
        int uv = span.texture() + anim.offset();       // le premier pixel n'est pas masque
        int step = span.step();
        for (int n = 0; n < width; n++) {
            int at = ((uv >>> 16) & 0xFF00) | ((uv & 0xFFFF) >>> 8);
            int ripple = ripples.word(anim.start() + at * 4);
            if (ripple < 0) {
                offTable++;
                return null;
            }
            uv = (uv + step) & BOTH;
            int from = to + lift;
            if (from < 0 || from >= pixels.length) {
                offTable++;
                return null;                           // le reflet sortirait de l'image
            }
            // La rangee vient de l'ondulation, la colonne de ce qui est DEJA a l'ecran. Les
            // deux se logent dans un seul mot, et l'octet bas de l'ondulation est jete pour
            // faire place a la colonne — il est nul dans les trente-deux mille mots du fichier,
            // qui sont tous des multiples de deux cent cinquante-six.
            int row = s16(setw(0, ripple * 2)) & 0xFF00;
            int colour = shades.colour(ShadeTable.GLARE + glare + row + (pixels[from] & 0xFF));
            if (colour < 0) {
                offTable++;
                return null;
            }
            pixels[to++] = (byte) colour;
        }
        return new Reflection(glare, lift, width);
    }

    /**
     * Ce qu'une tranche d'eau a reflete, et ce qui l'a decide.
     *
     * @param glare  la rampe de miroitement que la distance a choisie, en octets
     * @param lift   de combien de pixels le reflet a ete pris plus loin dans le tampon
     * @param pixels combien de pixels ont ete ecrits
     */
    public record Reflection(int glare, int lift, int pixels) { }

    public int paintGouraud(Flat.Span span) {
        int width = span.width();
        byte[] pixels = screen.pixels();
        int to = screen.line(span.y()) + span.left();
        int uv = span.texture();
        int step = span.step();
        int bright = span.leftBright();
        int speed = span.brightSpeed();
        for (int n = 0; n < width; n++) {
            int at = ((uv >>> 16) & 0xFF00) | ((uv & 0xFFFF) >>> 8);
            int texel = tiles.texel(tile + at * 4);
            if (texel < 0) {
                offTable++;
                return -1;
            }
            // La rangee et le texel se logent dans un seul mot : l'octet HAUT de la luminosite
            // par-dessus, le texel par-dessous. C'est le meme geste que le rendu plat, sauf que
            // l'octet haut change ici a chaque pixel.
            int colour = shades.colour(ShadeTable.FLOOR + s16((bright & 0xFF00) | texel));
            if (colour < 0) {
                offTable++;
                return -1;
            }
            pixels[to++] = (byte) colour;
            uv = (uv + step) & BOTH;
            bright = setw(bright, bright + speed);
        }
        return width;
    }

    /**
     * Peint une tranche a l'ombrage PLAT, et rend le nombre de pixels ecrits.
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
