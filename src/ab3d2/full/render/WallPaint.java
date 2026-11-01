package ab3d2.full.render;

import ab3d2.full.level.Level;
import ab3d2.full.level.WallTexture;

import static ab3d2.full.M68k.asrw;
import static ab3d2.full.M68k.s16;
import static ab3d2.full.M68k.setw;

/**
 * La mise en couleurs d'un mur : d'une colonne d'ecran aux pixels.
 *
 * <p>{@link Wall} dit OU va chaque colonne ; cette classe dit ce qu'on y voit. Elle prend une
 * bande telle que la projection l'a laissee — une abscisse, une abscisse de texture, une
 * distance, un haut, un bas, une luminosite — et ecrit les octets dans le {@link Screen}.
 *
 * <p>Trois choses s'y jouent, et aucune n'est evidente.
 *
 * <p><b>Trois colonnes par mot.</b> Une texture ne range pas ses pixels par lignes mais par
 * groupes de trois colonnes, trois valeurs de cinq bits serrees dans un meme mot de seize. La
 * colonne demandee se divise donc par trois : le quotient designe le groupe, le reste designe
 * lequel des trois champs lire. Le jeu ne divise pas — il lit le quotient ET le reste dans une
 * table de six cent soixante entrees faite pour cela — et il ne lit pas non plus le champ : il
 * saute vers l'une de trois boucles qui ne different que par le decalage. Trois boucles pour
 * eviter un decalage variable, c'est le genre d'echange qu'un 68000 impose.
 *
 * <p><b>Deux rampes qui alternent.</b> Une texture porte trente-deux rampes d'ombrage et la
 * distance en reclame soixante-cinq niveaux. L'ecart se comble en damier : la luminosite
 * nomme une rampe, et la rampe immediatement au-dessous, puis chaque pixel prend l'une ou
 * l'autre tour a tour. Le premier pixel d'une colonne prend la rampe haute si la somme de son
 * abscisse et de son ordonnee est paire — d'ou les deux echanges, un pour la parite de
 * l'abscisse, un pour celle de la premiere ligne apres decoupe. Sur les rangs PAIRS les deux
 * rampes sont la meme et le damier ne se voit pas ; il n'apparait que sur les rangs impairs,
 * c'est-a-dire a mi-chemin entre deux rampes.
 *
 * <p><b>Une position de texture en virgule fixe, rangee a l'envers.</b> L'ordonnee dans la
 * texture et sa partie fractionnaire tiennent dans un seul mot double, mais le mot de poids
 * FAIBLE porte la partie entiere et celui de poids fort la fraction. Ce n'est pas une
 * bizarrerie : la retenue qui sort de l'addition de la fraction est exactement celle qui doit
 * entrer dans la partie entiere, et le processeur la transmet d'une instruction a la suivante.
 * Le pas et le depart se lisent dans une table de huit mille entrees indexee par la distance,
 * ce qui epargne une division par colonne.
 *
 * <p>Un detail de cette table ne s'invente pas : le PAS se lit au rang de la distance, mais le
 * DEPART se lit au rang de la distance multipliee par trois en plein ecran, par deux en vue
 * encadree. C'est la seule difference entre les deux variantes du code d'origine.
 *
 * <p>Ne sont pas portes ici : le mode demi-hauteur et le mode double largeur, variantes de
 * resolution hors cible, et le dedoublonnage d'abscisses que seule la vue encadree pratique.
 */
public final class WallPaint {

    /**
     * Ce qu'une colonne a peint, et ce qui l'a decide.
     *
     * <p>Le produit de cette classe est dans le tampon, pas ici. Mais une colonne peinte ne se
     * raconte pas depuis ses pixels : on ne saurait dire, en les regardant, quelle rampe a
     * servi ni ou la texture a ete lue. Ce compte rendu dit l'un et l'autre, et c'est ce qui
     * permet de verifier le placage ailleurs qu'a l'oeil.
     *
     * @param y        la premiere ligne peinte, apres decoupe
     * @param x        l'abscisse a l'ecran
     * @param height   combien de pixels
     * @param light    la rampe d'ombrage des pixels de parite paire, en octets depuis le debut
     * @param dim      celle des autres ; les deux se valent sur les rangs pairs
     * @param field    lequel des trois champs d'un mot porte le texel
     * @param chunk    ou commence la colonne de texture dans le fichier
     * @param position ou l'on entre dans cette colonne : partie entiere en bas, fraction en haut
     * @param step     de combien de lignes de texture on avance par pixel
     * @param fraction et de quelle fraction de ligne
     */
    public record Column(int y, int x, int height, int light, int dim, int field, int chunk,
            int position, int step, int fraction) { }

    /**
     * L'echelle d'ombrage, telle que la macro {@code SCALE} la deroule.
     *
     * <p>Quatre-vingt-une entrees pour soixante-cinq luminosites utiles : chaque rampe y figure
     * DEUX fois, de sorte qu'un rang impair et le rang pair qui le precede nomment deux rampes
     * voisines — c'est de la que vient le damier. Au-dela de la trente et unieme rampe la table
     * se repete, parce qu'il n'y en a pas d'autre.
     */
    private static final int[] SCALE = new int[81];

    static {
        for (int i = 1; i < SCALE.length; i++) {
            SCALE[i] = WallTexture.RAMP_SIZE * Math.min((i + 1) / 2, WallTexture.RAMP_COUNT - 1);
        }
    }

    /** La luminosite la plus forte que la table d'ombrage accepte. */
    private static final int MAX_SHADE = 64;

    /**
     * Le quotient et le reste de la division par trois, pour six cent soixante colonnes.
     *
     * <p>Le quotient dans l'octet haut, le reste dans l'octet bas. Une colonne au-dela de la
     * table lirait ce qui la suit en memoire : on le compte plutot que de l'inventer.
     */
    private static final int[] DIV_THREE = new int[660];

    static {
        for (int i = 0; i < DIV_THREE.length; i++) {
            DIV_THREE[i] = ((i / 3) << 8) | (i % 3);
        }
    }

    /**
     * La table des pas et des departs, une paire de mots doubles par rang.
     *
     * <p>Pour chaque rang {@code n}, le jeu calcule un inverse puis l'inverse de cet inverse —
     * deux divisions entieres qui ne se simplifient pas, parce que chacune perd sa partie
     * fractionnaire. On refait le calcul tel quel.
     */
    private static final int[] CONSTANT = new int[2 * 8192];

    static {
        for (int n = 1; n <= 8192; n++) {
            int c = (16384 * 64) / n;
            int e = (64 * 64 * 65536) / c;
            CONSTANT[(n - 1) * 2] = e;
            CONSTANT[(n - 1) * 2 + 1] = (((c >> 1) - 40 * 64) * e) >> 6;
        }
    }

    private final Screen screen;

    // La vue, posee une fois par image.
    private int topClip;
    private int bottomClip;
    private int lookOffset;
    private boolean fullScreen = true;

    // Le pan en cours.
    private WallTexture texture;
    private int fromTile;
    private int totalYOffset;
    private int heightMask;
    private int heightShift;
    private int widthMask;

    private int offTable;

    public WallPaint(Screen screen) {
        this.screen = screen;
    }

    /**
     * La fenetre verticale et l'inclinaison du regard.
     *
     * @param topClip    la premiere ligne que l'on a le droit de peindre
     * @param bottomClip la premiere que l'on n'a plus le droit de peindre
     * @param lookOffset de combien de lignes le regard s'ecarte de l'horizontale
     * @param fullScreen vrai en plein ecran, faux pour la vue encadree
     */
    public void eye(int topClip, int bottomClip, int lookOffset, boolean fullScreen) {
        this.topClip = topClip;
        this.bottomClip = bottomClip;
        this.lookOffset = lookOffset;
        this.fullScreen = fullScreen;
    }

    /**
     * Le pan que les prochaines bandes habillent.
     *
     * @param yOffset le decalage vertical propre au pan, celui qu'une porte ou un ascenseur
     *                fait varier pendant que le mur bouge
     */
    public void wall(Level.Wall wall, WallTexture texture, int yOffset) {
        this.texture = texture;
        this.fromTile = wall.textureX();
        this.heightMask = wall.heightMask();
        this.heightShift = wall.heightShift();
        this.widthMask = wall.widthMask();
        this.totalYOffset = s16(wall.textureY() + yOffset) & heightMask;
    }

    /**
     * Combien de colonnes ont ete abandonnees faute de pouvoir lire une table.
     *
     * <p>Zero presque toujours, mais pas toujours — et ce n'est pas une precaution de
     * programmeur prudent. La table des pas porte huit mille rangs, et le DEPART s'y lit au rang
     * de la distance multipliee par trois : au-dela de deux mille sept cent trente, le code
     * d'origine lit donc ce qui SUIT la table. Ce qui la suit, en memoire, est le tampon de
     * subdivision des murs — celui que le pan en cours de dessin vient de remplir. La texture
     * des murs tres lointains se decale ainsi au gre de la geometrie qu'on dessine, et cela se
     * voit dans le jeu.
     *
     * <p>Il n'y a rien a reproduire la : ce que lit l'original depend de la disposition exacte
     * de sa memoire, que ce depot n'a pas. On renonce a peindre la colonne et on la compte,
     * comme {@link Wall#outOfTable()} compte les morceaux trop larges.
     */
    public int offTable() {
        return offTable;
    }

    /** Remet ce compte a zero. */
    public void clearOffTable() {
        offTable = 0;
    }

    /**
     * Peint une colonne, et rend le compte rendu de ce qu'elle a fait.
     *
     * <p>{@code null} quand la colonne ne peint rien : hors de la fenetre verticale, reduite a
     * rien par la decoupe, ou lisant hors d'une table.
     */
    public Column paint(Wall.Strip strip) {
        int x = strip.x();

        // --- la colonne de texture, et ou la lire ---
        int column = s16((strip.textureX() & widthMask) + fromTile);
        int at = s16(column + column);
        if (at < 0 || at / 2 >= DIV_THREE.length) {
            offTable++;
            return null;
        }
        int packed = DIV_THREE[at / 2];
        int group = packed >>> 8;
        int field = packed & 0xFF;
        int chunk = WallTexture.CHUNK + (s16(group + group) << heightShift);

        // --- l'ombrage : la distance, puis la luminosite propre au pan ---
        // La luminosite n'arrive ici que sur un OCTET : l'original etend l'octet faible du mot,
        // ce qui efface ce que la subdivision y a fait deborder. Le corpus ne s'en approche pas
        // — quatre-vingt-quatre pour borne, sur cent vingt-sept.
        int shade = s16(asrw(setw(0, strip.depth()), 7) + (byte) (strip.bright() >> 16));
        if (shade < 0) {
            shade = 0;
        }
        // La borne est a soixante-quatre et non a soixante-trois, mais la table se repete au-dela
        // de la trente et unieme rampe : les deux bornes nomment la meme rampe.
        if (shade > MAX_SHADE) {
            shade = MAX_SHADE;
        }
        int light = SCALE[shade];
        int dim = SCALE[shade & 0xFE];
        if ((x & 1) != 0) {
            int swap = light;
            light = dim;
            dim = swap;
        }

        // --- le decoupage vertical ---
        int top = strip.topY();
        int bottom = s16(strip.bottomY() + 1);
        if (bottom < topClip || top > bottomClip) {
            return null;
        }
        if (bottom > bottomClip) {
            bottom = bottomClip;
        }
        int y = Math.max(top, topClip);
        if ((y & 1) != 0) {
            int swap = light;
            light = dim;
            dim = swap;
        }
        int height = s16(bottom - y);
        if (height <= 0) {
            return null;
        }

        // --- le pas dans la texture, et ou l'on y entre ---
        int depth = strip.depth();
        int twice = s16(depth + depth);
        int span = fullScreen ? s16(depth + twice) : twice;
        if (twice < 0 || twice >= CONSTANT.length
                || span < 0 || 2 * span + 1 >= CONSTANT.length) {
            offTable++;
            return null;
        }
        int step = CONSTANT[twice];
        int start = CONSTANT[2 * span + 1];

        int position = swap(step * s16(y + lookOffset) + start);
        position = setw(position, s16(position + totalYOffset) & heightMask);
        int entry = position;
        int whole = setw(0, step >>> 16);          // le signe est sans objet : setw n'en garde rien
        int fraction = step << 16;

        // --- la boucle texel ---
        byte[] pixels = screen.pixels();
        int to = screen.line(y) + x;
        boolean high = true;
        for (int n = 0; n < height; n++) {
            position = setw(position, position & heightMask);
            int word = texture.word(chunk + ((short) position) * 2);
            if (word < 0) {
                offTable++;
                return null;
            }
            // Le troisieme champ garde SIX bits, non cinq : l'original decale un octet de deux
            // et ne masque rien, de sorte que le seizieme bit du mot — celui que l'encodage
            // n'utilise pas — entrerait dans l'index. Il reste a zero partout ou le troisieme
            // champ se lit : quatre millions huit cent mille texels releves, aucun.
            int texel = switch (field) {
                case 0 -> word & 31;
                case 1 -> (word >>> 5) & 31;
                default -> (word >>> 10) & 63;
            };
            long sum = (position & 0xFFFFFFFFL) + (fraction & 0xFFFFFFFFL);
            position = (int) sum;
            pixels[to] = (byte) texture.ramp((high ? light : dim) + texel * 2);
            to += Screen.WIDTH;
            position = setw(position, position + whole + (sum > 0xFFFFFFFFL ? 1 : 0));
            high = !high;
        }
        return new Column(y, x, height, light, dim, field, chunk, entry, whole, fraction);
    }

    /** {@code swap} : les deux mots d'un mot double echangent leur place. */
    private static int swap(int v) {
        return (v >>> 16) | (v << 16);
    }
}
