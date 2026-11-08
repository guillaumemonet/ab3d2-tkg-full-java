package ab3d2.full.render;

import ab3d2.full.SinCos;
import ab3d2.full.level.Level;
import ab3d2.full.sim.DynLight;

import java.util.List;

import static ab3d2.full.M68k.asrw;
import static ab3d2.full.M68k.muls;
import static ab3d2.full.M68k.s16;
import static ab3d2.full.M68k.setw;

/**
 * L'ECLAIRAGE : combien de lumiere arrive en chaque point du decor.
 *
 * <p>Le rendu ne demande jamais « quelle est la lumiere ici ». Il lit une table, et cette table
 * est refaite a chaque image. Elle porte, pour chaque secteur, DIX points de bord et pour
 * chacun QUATRE valeurs : au sol, au plafond, et les deux memes a l'etage superieur. C'est elle
 * que les murs consultent pour leurs quatre coins, elle que les rendus interpoles attendent, et
 * elle que les lumieres mobiles modifient.
 *
 * <p>Trois choses s'y ajoutent, dans cet ordre.
 *
 * <p><b>Les lampes qui clignotent.</b> Sept sequences sont ecrites en dur dans le jeu — cinq
 * pulsations dephasees et deux scintillements — et chacune avance d'un cran par image, en
 * boucle. Un secteur ou un point ne nomme pas une luminosite mais, dans l'octet de poids fort
 * de sa valeur, l'une de ces sept sequences. Pour un SECTEUR la sequence remplace la valeur ;
 * pour un POINT elle s'y melange, l'octet fort portant alors deux champs — le quartet bas
 * nomme la sequence, le quartet haut donne son poids, de un a seize seiziemes. C'est ce qui
 * permet a une meme lampe de battre fort pres d'elle et a peine plus loin.
 *
 * <p><b>Le recalcul par image.</b> Il ne porte que sur les secteurs POTENTIELLEMENT VISIBLES
 * depuis celui du joueur. Les autres gardent ce qu'ils avaient : personne ne les regarde. Une
 * valeur brute tient sur un octet signe ; la table la porte a l'echelle du rendu en la
 * multipliant par trois cent quatre-vingt-dix-sept, en ecartant les valeurs negatives de six
 * cents, puis en decalant le tout de trois cents.
 *
 * <p>Ce decalage n'est pas une commodite d'ecriture : <b>trois cents est le maximum de
 * lumiere</b>, et plus la valeur monte, plus le point est sombre. Une lumiere mobile fait donc
 * DESCENDRE la valeur vers trois cents, et s'y arrete.
 *
 * <p><b>Les lumieres mobiles.</b> Un projectile en vol, une explosion, un monstre a torche :
 * ils eclairent les points de bord des secteurs potentiellement visibles depuis le leur, en
 * fonction de leur distance de Manhattan — la somme des deux ecarts, sans racine carree — et de
 * l'ecart de hauteur. Une torche ajoute un CONE : on tourne l'ecart dans son repere, et plus le
 * point s'ecarte de l'axe du regard, plus sa distance effective grandit.
 *
 * <p>Deux bizarreries du code d'origine sont reproduites telles quelles.
 *
 * <ul>
 *   <li>Quand les dix points de bord d'un secteur sont tous consommes — c'est-a-dire quand la
 *       liste ne s'est pas terminee d'elle-meme — la variante a cone retombe dans la boucle
 *       ORDINAIRE : les secteurs suivants sont alors eclaires tout autour, sans cone.</li>
 *   <li>Quand un point tombe derriere la torche, le compte des points restants est repris par
 *       un registre qui porte alors un ECART DE PROFONDEUR. Le compte peut devenir enorme, et
 *       la lumiere deborde sur les points de bord — et sur les luminosites — des secteurs
 *       suivants. Les tables sont mises a plat pour que ce debordement retombe juste.</li>
 * </ul>
 *
 * <p>Ne sont pas portes l'assombrissement ({@code darken_points}) ni l'eclair
 * ({@code Flash}) : tous deux indexent la table par la liste de points PROPRE au secteur, a
 * raison de deux mots par point, alors que la table en compte quatre par point de BORD. Ils
 * ecrivent donc en dehors des emplacements du secteur qu'ils pretendent eclairer, et rien dans
 * la simulation portee ne les appelle.
 */
public final class Lights implements ab3d2.full.sim.ZoneLight {

    /** Combien de points de bord par secteur, et combien de valeurs par point. */
    public static final int POINTS = 10;
    public static final int SLOTS = 4;

    /** Les quatre valeurs d'un point : sol, plafond, sol superieur, plafond superieur. */
    public static final int FLOOR = 0;
    public static final int ROOF = 1;
    public static final int UPPER_FLOOR = 2;
    public static final int UPPER_ROOF = 3;

    /** Le maximum de lumiere : en dessous de trois cents, rien n'est plus clair. */
    private static final int BRIGHTEST = 300;

    /** Ce qui termine une sequence de clignotement. */
    private static final int LOOP = 999;

    /** Les sept sequences, ecrites en dur dans le jeu. */
    private static final int[][] FLICKER = {
        join(ramp(1, 20), ramp(20, 1)),
        join(ramp(9, 20), ramp(20, 1), ramp(1, 8)),
        join(ramp(17, 20), ramp(20, 1), ramp(1, 16)),
        join(ramp(16, 1), ramp(1, 20), ramp(20, 17)),
        join(ramp(8, 1), ramp(1, 20), ramp(20, 9)),
        join(same(20, 20), new int[] {1}, same(30, 20), new int[] {1},
                same(5, 20), new int[] {1}),
        join(new int[] {-10, -9, -6, -10, -6, -5, -5, -7, -5, -10, -9, -8, -7, -5, -5, -5, -5,
            -5, -5, -5, -5, -6, -7, -8, -9, -5, -10, -9, -10, -6, -5, -5, -5, -5, -5, -5, -5}),
    };

    /**
     * Ou en est chaque sequence, et ce qu'elle vaut a cette image.
     *
     * <p>La table de valeurs compte VINGT entrees pour sept sequences. Les treize autres restent
     * a zero, et un point qui en nomme une — rien ne l'en empeche, le champ a quatre bits — y
     * lit ce zero.
     */
    private final int[] cursor = new int[FLICKER.length];
    private final int[] flicker = new int[20];

    private final Level level;
    private final SinCos sinCos;

    /** Deux valeurs par secteur : l'etage bas, l'etage haut. */
    private final int[] zoneBright;

    /** Quarante valeurs par secteur : dix points de bord, quatre chacun. */
    private final int[] pointBright;

    /** Les memes tables, mises a plat pour qu'un debordement retombe sur le secteur suivant. */
    private final int[] rawBright;
    private final int[] border;

    /** Vrai tant que les lumieres mobiles ont le droit d'eclairer. */
    public boolean enabled = true;

    private int offTable;

    public Lights(Level level, SinCos sinCos) {
        this.level = level;
        this.sinCos = sinCos;
        int zones = level.zones().size();
        zoneBright = new int[2 * zones];
        pointBright = new int[POINTS * SLOTS * zones];
        rawBright = new int[POINTS * SLOTS * zones];
        border = new int[POINTS * zones];
        for (Level.Zone z : level.zones()) {
            List<Integer> raw = z.pointBrights();
            for (int i = 0; i < raw.size(); i++) {
                rawBright[z.id() * POINTS * SLOTS + i] = raw.get(i);
            }
            List<Integer> pts = z.borderPoints();
            for (int i = 0; i < pts.size(); i++) {
                border[z.id() * POINTS + i] = pts.get(i);
            }
        }
    }

    /** La luminosite d'un secteur : {@code upper} pour son etage haut. */
    public int zoneBright(int zone, boolean upper) {
        return zoneBright[zone * 2 + (upper ? 1 : 0)];
    }

    /** La luminosite d'un point de bord, dans l'un de ses quatre emplacements. */
    public int pointBright(int zone, int point, int slot) {
        return pointBright[zone * POINTS * SLOTS + point * SLOTS + slot];
    }

    /** La table entiere, pour qui la lit d'un seul tenant. */
    public int[] pointBrights() {
        return pointBright;
    }

    /** Ce que vaut une sequence de clignotement a cette image. */
    public int flicker(int which) {
        return flicker[which];
    }

    /**
     * Combien de fois une lumiere mobile a debordé des tables.
     *
     * <p>Le debordement d'un secteur sur le suivant est reproduit — les tables sont mises a
     * plat pour cela. Mais le debordement du DERNIER secteur ne tombe nulle part : sur l'Amiga
     * il atteindrait ce que l'assembleur avait range la, et ce n'est pas ce qu'il y a ici. On
     * renonce et on compte.
     */
    public int offTable() {
        return offTable;
    }

    public void clearOffTable() {
        offTable = 0;
    }

    // ===== les lampes qui clignotent =====

    /**
     * Avance les sept sequences d'un cran.
     *
     * <p>Une sequence qui atteint sa marque de fin reprend a son debut DANS LA MEME IMAGE : elle
     * ne perd donc pas une image a chaque tour.
     */
    public void tick() {
        for (int i = 0; i < FLICKER.length; i++) {
            int v = FLICKER[i][cursor[i]++];
            if (v == LOOP) {
                cursor[i] = 0;
                v = FLICKER[i][cursor[i]++];
            }
            flicker[i] = v;
        }
    }

    // ===== le recalcul par image =====

    /**
     * Refait la table pour tous les secteurs potentiellement visibles depuis {@code from}.
     *
     * <p>Les autres gardent ce qu'ils avaient : c'est voulu, et c'est pourquoi la table n'est
     * jamais entierement vraie — seulement vraie la ou l'on regarde.
     *
     * <p>Les secteurs sont parcourus DEUX fois, les luminosites de secteur d'abord et celles de
     * points ensuite, parce que le code d'origine le fait ainsi.
     */
    public void frame(int zone) {
        if (zone >= 0 && zone < level.zones().size()) {
            frame(level.zones().get(zone));
        }
    }

    /** computeZoneBrightness, le secteur deja trouve. */
    public void frame(Level.Zone from) {
        for (Level.Visible v : from.visible()) {
            Level.Zone z = level.zones().get(v.zone());
            zoneBright[v.zone() * 2] = zoneValue(z.brightness());
            zoneBright[v.zone() * 2 + 1] = zoneValue(z.upperBrightness());
        }
        for (Level.Visible v : from.visible()) {
            int at = v.zone() * POINTS * SLOTS;
            for (int i = 0; i < POINTS * SLOTS; i++) {
                pointBright[at + i] = pointValue(rawBright[at + i]);
            }
        }
    }

    /**
     * La luminosite d'un secteur.
     *
     * <p>Une valeur NEGATIVE est une luminosite fixe et s'en tient la. Une valeur positive dont
     * l'octet fort n'est pas nul nomme une sequence, qui remplace la valeur toute entiere — pas
     * de melange ici, contrairement aux points.
     */
    private int zoneValue(int brightness) {
        int v = s16(brightness);
        if (v >= 0) {
            int which = (v & 0xFFFF) >>> 8;
            // Aucun secteur du jeu ne nomme de sequence : ce chemin est porte mais jamais pris,
            // sur les cinq mille cent trente-six secteurs du corpus.
            if ((byte) which != 0) {
                v = flickerAt(which - 1);
            }
        }
        return s16(muls(v, 410) >> 8);
    }

    /**
     * La luminosite d'un point.
     *
     * <p>Le test qui decide s'il y a une sequence porte sur l'octet BAS : un point dont la
     * luminosite fixe est negative ne clignote jamais, quoi que porte son octet fort. Et le
     * melange lui-meme est ramene a un OCTET avant d'etre mis a l'echelle, ce qui le fait
     * reboucler s'il deborde.
     */
    private int pointValue(int raw) {
        int v = raw;
        if ((byte) v >= 0) {
            int high = (v & 0xFFFF) >>> 8;
            if ((byte) high != 0) {
                int weight = (high >>> 4) + 1;
                int fixed = (byte) v;
                int blend = asrw(muls(s16(flickerAt((high & 0xF) - 1) - fixed), weight), 4);
                v = setw(v, fixed + s16(blend));
            }
        }
        v = (byte) v;
        v = muls(v, 397) >> 8;
        if (s16(v) < 0) {
            v = s16(v - 600);
        }
        return s16(v + BRIGHTEST);
    }

    /** Ce que vaut une sequence, zero pour celles qui n'existent pas. */
    private int flickerAt(int which) {
        return which >= 0 && which < flicker.length ? flicker[which] : 0;
    }

    // ===== les lumieres mobiles =====

    @Override
    public void brighten(int bright, int x, int z, int zone, int brightY) {
        if (!enabled || s16(bright) > 0) {
            return;                                    // positif = un assombrissement, non porte
        }
        walk(bright, x, z, zone, brightY, -1);
    }

    @Override
    public void brightenAngle(int bright, int x, int z, int zone, int angle, int brightY) {
        if (!enabled) {
            return;
        }
        walk(bright, x, z, zone, brightY, angle);
    }

    /**
     * Parcourt les secteurs potentiellement visibles et eclaire leurs points de bord.
     *
     * <p>{@code angle} vaut {@code -1} pour une lumiere qui eclaire tout autour d'elle. La
     * boucle suit celle du code d'origine jusque dans sa facon de perdre le cone : voir la
     * description de la classe.
     */
    private void walk(int bright, int x, int z, int zone, int brightY, int angle) {
        if (zone < 0 || zone >= level.zones().size()) {
            offTable++;
            return;
        }
        List<Level.Visible> seen = level.zones().get(zone).visible();
        boolean cone = angle >= 0;
        for (int n = 0; n < seen.size(); n++) {
            Level.Zone here = level.zones().get(seen.get(n).zone());
            int at = seen.get(n).zone() * POINTS;
            int slot = seen.get(n).zone() * POINTS * SLOTS;
            int left = POINTS - 1;
            while (true) {
                if (at < 0 || at >= border.length || slot < 0 || slot + SLOTS > pointBright.length) {
                    offTable++;
                    return;
                }
                int point = s16(border[at++]);
                if (point < 0) {
                    break;                             // la liste du secteur s'arrete la
                }
                Level.Point p = level.points().get(point);
                int dx = s16(p.x() - x);
                int dz = s16(p.z() - z);
                int near = s16((dx <= 0 ? -dx : dx) + (dz <= 0 ? -dz : dz));
                if (cone) {
                    int ahead = muls(sinCos.cos(angle), dz) + muls(sinCos.sin(angle), dx);
                    // Un point exactement de cote compte comme DERRIERE. Le corpus y passe
                    // quarante-huit fois, et dans aucune la distinction ne change une valeur :
                    // le cone y est si ouvert que la lumiere ne porte plus.
                    if (ahead <= 0) {
                        // Le point est derriere la torche : rien ne s'eclaire, et le compte des
                        // points restants n'avance pas. Ce qu'on decremente est le registre qui
                        // portait l'ecart de PROFONDEUR, et il vient d'etre reecrit : la boucle
                        // ne s'arrete donc que si cet ecart est nul.
                        slot += SLOTS;
                        if (s16(dz - 1) == -1) {
                            break;
                        }
                        continue;
                    }
                    int away = 30 * 65536 - ahead;
                    if (away < 0) {
                        away = 0;
                    }
                    int aside = muls(dz, sinCos.sin(angle)) - muls(dx, sinCos.cos(angle));
                    if (aside <= 0) {
                        aside = -aside;
                    }
                    near = s16(near + (((aside + away) << 2) >>> 16));
                }
                apply(here, slot, bright, near, brightY);
                slot += SLOTS;
                left = s16(left - 1);
                if (left == -1) {
                    cone = false;                      // dix points eclaires : le cone est perdu
                    break;
                }
            }
        }
    }

    /**
     * Pose la lumiere sur les quatre valeurs d'un point.
     *
     * <p>Les deux premieres ne s'ecrivent que si la source est DANS l'etage bas du secteur, les
     * deux autres que si elle est dans l'etage haut. Une lumiere a hauteur de plafond n'eclaire
     * donc pas le sol de l'etage au-dessus.
     */
    private void apply(Level.Zone here, int slot, int bright, int near, int brightY) {
        if (!(brightY > here.floor()) && !(brightY < here.ceiling())) {
            one(slot + ROOF, here.ceiling(), true, bright, near, brightY);
            one(slot + FLOOR, here.floor(), false, bright, near, brightY);
        }
        if (!(brightY > here.upperFloor()) && !(brightY < here.upperCeiling())) {
            one(slot + UPPER_FLOOR, here.upperFloor(), false, bright, near, brightY);
            one(slot + UPPER_ROOF, here.upperCeiling(), true, bright, near, brightY);
        }
    }

    /**
     * Une valeur, eclairee.
     *
     * <p>La valeur rangee est d'abord rendue POSITIVE, et ce changement de signe est definitif :
     * le signe disait qu'aucune lumiere mobile n'avait encore touche ce point, et il ne le dira
     * plus.
     */
    private void one(int slot, int y, boolean above, int bright, int near, int brightY) {
        int gap = y - brightY;
        if (above) {
            if (gap > 0) {
                return;                                // la source est sous ce plafond
            }
            gap = -gap;
        } else if (gap < 0) {
            return;                                    // la source est sous ce sol
        }
        int v = s16(asrw(setw(0, s16(near + (gap >> 7))), 5) + bright);
        if (v >= 0) {
            return;                                    // trop loin pour que cela se voie
        }
        int was = s16(pointBright[slot]);
        if (was < 0) {
            pointBright[slot] = s16(-was);
        }
        v = s16(v + pointBright[slot]);
        if (v < BRIGHTEST) {
            v = BRIGHTEST;
        }
        pointBright[slot] = v;
    }

    private static int[] ramp(int from, int to) {
        int n = Math.abs(to - from) + 1;
        int step = to >= from ? 1 : -1;
        int[] out = new int[n];
        for (int i = 0, v = from; i < n; i++, v += step) {
            out[i] = v;
        }
        return out;
    }

    private static int[] same(int n, int v) {
        int[] out = new int[n];
        java.util.Arrays.fill(out, v);
        return out;
    }

    private static int[] join(int[]... parts) {
        int n = 1;
        for (int[] p : parts) {
            n += p.length;
        }
        int[] out = new int[n];
        int at = 0;
        for (int[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        out[at] = LOOP;
        return out;
    }
}
