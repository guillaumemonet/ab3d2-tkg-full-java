package ab3d2.full.render;

import ab3d2.full.level.Level;
import ab3d2.full.level.WallTexture;

import static ab3d2.full.M68k.asrw;
import static ab3d2.full.M68k.s16;
import static ab3d2.full.M68k.setw;

/**
 * La mise en couleurs d'un mur degrade : d'une colonne d'ecran aux pixels.
 *
 * <p>{@link WallPaint} choisit une rampe d'ombrage pour toute la colonne et alterne deux rampes
 * voisines en damier pour faire croire a un entre-deux. Ici, pas de damier : la colonne part
 * d'une rampe en haut et arrive a une autre en bas, et le rendu PARCOURT les rampes au fil des
 * pixels. C'est plus juste, et cela coute une division par colonne — celle du pas de degrade.
 *
 * <p>Trois differences avec le rendu simple, toutes consequentes.
 *
 * <p><b>La luminosite ne passe plus par une table.</b> Le rendu simple nomme une rampe par
 * {@code SCALE}, quatre-vingt-une entrees qui repetent chaque rampe deux fois pour que le
 * damier ait deux voisines a alterner. Ici la rampe se calcule : la luminosite est bornee a
 * SOIXANTE-DEUX, non a soixante-quatre, et divisee par deux. Trente-deux rampes, de zero a
 * trente et un, sans table et sans repetition.
 *
 * <p><b>La position dans la texture est rangee a l'endroit.</b> Le rendu simple met la partie
 * entiere dans le mot FAIBLE pour que la retenue de la fraction y entre d'elle-meme ; ici elle
 * est dans le mot FORT et le pas s'ajoute d'un simple {@code add.l}. Les deux arrivent au meme
 * resultat par deux chemins differents, et c'est le genre de detail qui ne se devine pas.
 *
 * <p><b>Le troisieme champ de texel garde cinq bits.</b> Le rendu simple en laisse passer six —
 * il decale un octet de deux et ne masque rien. Celui-ci masque. Le bit en trop vaut zero
 * partout dans le jeu — quatre millions sept cent soixante-dix-huit mille texels releves, pas
 * un — de sorte que les deux chemins s'accordent, mais ils ne le font pas pour la meme
 * raison.
 *
 * <p>Ne sont pas portes ici, comme dans {@link WallPaint} : le mode demi-hauteur, le mode
 * double largeur, et le dedoublonnage d'abscisses de la vue encadree.
 */
public final class GouraudWallPaint {

    /**
     * Ce qu'une colonne a peint, et ce qui l'a decide.
     *
     * @param y        la premiere ligne peinte, apres decoupe
     * @param x        l'abscisse a l'ecran
     * @param height   combien de pixels
     * @param start    ou commence le degrade, en rampes multipliees par trente-deux
     * @param step     de combien il avance par pixel
     * @param field    lequel des trois champs d'un mot porte le texel
     * @param chunk    ou commence la colonne de texture dans le fichier
     * @param position ou l'on entre dans cette colonne : partie entiere dans le mot FORT
     * @param vstep    de combien on avance dans la texture par pixel
     */
    public record Column(int y, int x, int height, int start, int step, int field, int chunk,
            int position, int vstep) { }

    /** La luminosite la plus forte que ce rendu accepte : voir l'ecart avec {@link WallPaint}. */
    private static final int MAX_SHADE = 62;

    /**
     * Combien de couleurs dans une rampe, et le decalage qui multiplie par ce nombre.
     *
     * <p>Trente-deux couleurs, et une rampe pese deux fois plus d'octets : chaque couleur en
     * occupe deux, dont le rendu ne lit que le premier.
     */
    private static final int RAMP_SHIFT = 5;
    private static final int RAMP = 1 << RAMP_SHIFT;

    /**
     * Ce que le masque du degrade laisse passer : la rampe, et rien de la fraction.
     *
     * <p>L'accumulateur porte la rampe multipliee par trente-deux, ce qui laisse cinq bits sous
     * elle ; le masque les jette avant que le texel ne vienne s'y ajouter.
     */
    private static final int RAMP_MASK = 0xFFFF - (RAMP - 1);

    private static final int[] DIV_THREE = new int[660];

    static {
        for (int i = 0; i < DIV_THREE.length; i++) {
            DIV_THREE[i] = ((i / 3) << 8) | (i % 3);
        }
    }

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

    public GouraudWallPaint(Screen screen) {
        this.screen = screen;
    }

    /** La fenetre verticale et l'inclinaison du regard ; voir {@link WallPaint#eye}. */
    public void eye(int topClip, int bottomClip, int lookOffset, boolean fullScreen) {
        this.topClip = topClip;
        this.bottomClip = bottomClip;
        this.lookOffset = lookOffset;
        this.fullScreen = fullScreen;
    }

    /** Le pan que les prochaines bandes habillent ; voir {@link WallPaint#wall}. */
    public void wall(Level.Wall wall, WallTexture texture, int yOffset) {
        this.texture = texture;
        this.fromTile = wall.textureX();
        this.heightMask = wall.heightMask();
        this.heightShift = wall.heightShift();
        this.widthMask = wall.widthMask();
        this.totalYOffset = s16(wall.textureY() + yOffset) & heightMask;
    }

    /** Combien de colonnes ont ete abandonnees faute de pouvoir lire une table. */
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
     * rien par la decoupe, de hauteur nulle avant decoupe, ou lisant hors d'une table.
     */
    public Column paint(GouraudWall.Strip strip) {
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

        // --- les deux bouts du degrade ---
        // La borne est a soixante-deux : la rampe la plus claire est la trente et unieme, et le
        // rendu simple n'en atteint une trente-deuxieme que parce que sa table se repete.
        // Les deux luminosites n'arrivent ici que sur un OCTET, comme dans WallPaint : le
        // corpus ne s'en approche pas, quatre-vingt-dix-huit pour borne sur cent vingt-sept.
        int base = asrw(setw(0, strip.depth()), 7);
        int lower = shade(s16(base + (byte) (strip.bright() >> 16)));
        int upper = shade(s16(base + (byte) (strip.upperBright() >> 16)));
        int start = upper << 16;

        // --- le decoupage vertical ---
        int top = strip.topY();
        int bottom = s16(strip.bottomY() + 1);
        int span = s16(bottom - top);
        if (span == 0) {
            return null;                               // rien a parcourir, et pas de division
        }
        int step = ((lower - upper) << 16) / span;
        if (bottom < topClip || top > bottomClip) {
            return null;
        }
        if (bottom > bottomClip) {
            bottom = bottomClip;
        }
        int y = top;
        if (y < topClip) {
            start += step * s16(topClip - y);
            y = topClip;
        }
        int height = s16(bottom - y);
        if (height <= 0) {
            return null;
        }

        // --- le pas dans la texture, et ou l'on y entre ---
        int depth = strip.depth();
        int twice = s16(depth + depth);
        int far = fullScreen ? s16(depth + twice) : twice;
        if (twice < 0 || twice >= CONSTANT.length
                || far < 0 || 2 * far + 1 >= CONSTANT.length) {
            offTable++;
            return null;
        }
        int vstep = CONSTANT[twice];
        int entry = CONSTANT[2 * far + 1];

        int position = swap(vstep * s16(y + lookOffset) + entry);
        position = setw(position, s16(position + totalYOffset) & heightMask);
        position = swap(position);                     // la partie entiere revient en haut

        // Le degrade compte en rampes multipliees par trente-deux : c'est l'unite dans laquelle
        // la couleur s'indexe, et la mise a l'echelle se fait une fois pour toutes.
        start <<= RAMP_SHIFT;
        step <<= RAMP_SHIFT;
        int depart = position;

        // --- la boucle texel ---
        byte[] pixels = screen.pixels();
        int to = screen.line(y) + x;
        int accumulator = start;
        for (int n = 0; n < height; n++) {
            position = swap(position);
            position = setw(position, position & heightMask);
            int word = texture.word(chunk + ((short) position) * 2);
            if (word < 0) {
                offTable++;
                return null;
            }
            position = swap(position);
            int texel = switch (field) {
                case 0 -> word & 31;
                case 1 -> (word >>> 5) & 31;
                default -> (word >>> 10) & 31;
            };
            // L'original ajoute le texel sur un OCTET pour deux des trois champs. La rampe y
            // occupe les trois bits hauts, le texel les cinq bas : rien ne deborde de l'octet,
            // et l'addition large donne le meme resultat.
            int index = s16((swap(accumulator) & RAMP_MASK) + texel);
            if (index < 0 || index * 2 >= WallTexture.CHUNK) {
                offTable++;
                return null;
            }
            pixels[to] = (byte) texture.ramp(index * 2);
            to += Screen.WIDTH;
            accumulator += step;
            position += vstep;
        }
        return new Column(y, x, height, start, step, field, chunk, depart, vstep);
    }

    /** Une luminosite bornee puis ramenee a un rang de rampe. */
    private static int shade(int value) {
        if (value < 0) {
            value = 0;
        }
        if (value >= MAX_SHADE) {
            value = MAX_SHADE;
        }
        return s16(value) >> 1;
    }

    /** {@code swap} : les deux mots d'un mot double echangent leur place. */
    private static int swap(int v) {
        return (v >>> 16) | (v << 16);
    }
}
