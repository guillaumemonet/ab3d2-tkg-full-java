package ab3d2.full.sim;

import ab3d2.full.Data;
import ab3d2.full.defs.Database;
import ab3d2.full.level.Level;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifie la GERBE DE MORCEAUX : {@code Anim_ExplodeIntoBits} (newanims.s:605).
 *
 * <p>Ce qu'un monstre laisse en mourant. La routine est courte et sans parametre — tout lui
 * arrive par des globales — et c'est le SEUL endroit du jeu qui relise {@code EntT_ImpactX_w}
 * d'une entite : le dernier coup encaisse, celui qui l'a tuee, repart de moitie dans chaque
 * morceau. Un alien abattu de face ne gicle pas comme un alien abattu de dos.
 *
 * <h2>Ce qu'on seme</h2>
 *
 * <p>Le pool des projectiles aliens est SALI avant chaque gerbe : aucun champ n'y est a zero,
 * les fractions de position et de vitesse sont garnies, les deux octets de rebond sont armes,
 * le pas d'animation et la duree de vie portent le rang de l'emplacement. Sans cela, la moitie
 * de ce que la routine ne fait PAS passerait pour ce qu'elle fait — elle n'ecrit que des MOTS,
 * et tout ce qui est sous le mot fort appartient au tir precedent.
 *
 * <p>L'occupation du pool tourne sur huit motifs, du pool vide au pool plein, en passant par
 * un emplacement sur deux et par les dix-huit premiers pris : c'est le seul moyen d'eprouver
 * la marche d'avancement, qui ne revient jamais en arriere et qui compte ses vingt
 * emplacements avec un {@code dbra} PARTAGE entre les sauts et les poses.
 *
 * <p>Les reculs vont de zero aux bornes du mot signe, et les hauteurs aussi : {@code add.w #6}
 * sur une hauteur de trente-deux mille sept cent soixante-sept REBOUCLE, et la hauteur
 * d'accumulation qui en sort est negative.
 *
 * <h2>Les deux fautes du portage</h2>
 *
 * <ul>
 *   <li>un morceau heritait du REBOND de sa definition de balle. La routine efface les deux
 *       octets de {@code ShotT_Flags_w} : un morceau ne rebondit pas au plafond, quoi qu'en
 *       dise sa definition ;</li>
 *   <li>sa hauteur de dessin valait six de trop : {@code 4(a5)} recoit la hauteur du mort TELLE
 *       QUELLE, et c'est seulement la hauteur d'accumulation qui monte de six.</li>
 * </ul>
 *
 * <p>Et trois oublis, tous du meme genre : les fractions de position et de vitesse, le pas
 * d'animation et la gravite n'etaient pas laisses tranquilles.
 *
 * <h2>La faute de l'original</h2>
 *
 * <p>Voir {@code Shots.typoDuType} : {@code move.b #2,16(a2)} ecrit le type d'objet a seize
 * octets du POINT au lieu de seize octets de l'ENTITE. Le type manquant ne se voit pas — le
 * pool arrive du fichier de niveau avec le bon — mais l'ecriture tombe sur l'abscisse du point
 * deux rangs plus loin, et ce projectile-la saute a l'autre bout du niveau.
 *
 * <h2>Ce que le corpus ne separe pas</h2>
 *
 * <p>Deux mille six cent quatre-vingt-huit gerbes, six niveaux, aucun ecart. Le debordement du
 * type sur les DEUX derniers emplacements du pool tombe sur les objets auxiliaires qui le
 * suivent dans la table, et ceux-la ne sont pas portes : on le releve sans le comparer.
 *
 * <p>{@code ShotT_Worry_b} et {@code ObjT_TypeID_b} ne sont pas non plus compares : le portage
 * n'a pas de champ pour le premier (seuls les ALIENS y passent, newanims.s:1822) et la routine
 * n'ecrit jamais le second.
 *
 * <pre>gradle -p full bitsCheck</pre>
 */
public final class BitsCheck {

    private BitsCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.bits", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=bits");
            System.exit(1);
        }
        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }

        int ecarts = 0;
        int gerbes = 0;
        for (var el : root.getAsJsonArray("levels")) {
            JsonObject n = el.getAsJsonObject();
            gerbes += n.getAsJsonArray("cases").size();
            ecarts += unNiveau(n);
        }
        System.out.printf("%n%d gerbes sur six niveaux, %d ecart(s) -> %s%n",
                gerbes, ecarts, ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    private static int unNiveau(JsonObject n) {
        char lettre = n.get("level").getAsString().charAt(0);
        Level data = Data.level(lettre);
        Database glf = Data.database();
        LevelSim lvl = LevelSim.of(data);
        // Pas de {@link World} : la routine ne touche ni au decor ni aux animations, et monter
        // un monde ferait tourner une image qui deplacerait les ascenseurs.
        Shots shots = new Shots(lvl, glf, Data.sinCos(), data);
        Nav nav = new Nav(data);
        shots.nav = nav;

        int ecarts = 0;
        int montres = 0;
        for (var el : n.getAsJsonArray("cases")) {
            int e = unCas(shots, nav, el.getAsJsonObject(), lettre, montres < 6);
            if (e != 0) {
                montres++;
            }
            ecarts += e;
        }
        System.out.printf("%s  %4d gerbes  %s%n", lettre,
                n.getAsJsonArray("cases").size(), ecarts == 0 ? "OK" : ecarts + " ecart(s)");
        return ecarts;
    }

    private static int unCas(Shots shots, Nav nav, JsonObject c, char lettre, boolean bavard) {
        var pool = shots.shots();
        int busy = c.get("busy").getAsInt();
        int zone = c.get("zone").getAsInt();
        int zoneOccupee = c.get("busyZone").getAsInt();
        for (int i = 0; i < Shots.PLR_POOL; i++) {
            pool.get(i).active = false;                // le pool du JOUEUR ne sert pas ici
        }
        for (int k = 0; k < Shots.ALIEN_POOL; k++) {
            salir(pool.get(Shots.PLR_POOL + k), k, (busy & (1 << k)) != 0, zoneOccupee);
        }
        nav.seed(c.get("seed").getAsInt());

        shots.explodeIntoBits(c.get("splat").getAsInt(), c.get("count").getAsInt(),
                c.get("newx").getAsInt(), c.get("newz").getAsInt(),
                c.get("height").getAsInt(), zone, c.get("upper").getAsInt() != 0,
                c.get("impactX").getAsInt(), c.get("impactZ").getAsInt());

        JsonObject a = c.getAsJsonObject("after");
        JsonArray attendu = a.getAsJsonArray("pool");
        int e = 0;
        for (int k = 0; k < Shots.ALIEN_POOL; k++) {
            Shots.Shot s = pool.get(Shots.PLR_POOL + k);
            JsonObject r = attendu.get(k).getAsJsonObject();
            String q = lettre + " " + c.get("count").getAsInt() + "/"
                    + Integer.toHexString(busy) + " #" + k;
            e += cmp(q + " secteur", s.zone, r.get("zone").getAsInt());
            e += cmp(q + " pris", s.active ? 1 : 0, r.get("zone").getAsInt() >= 0 ? 1 : 0);
            e += cmp(q + " x", s.x, r.get("x").getAsInt());
            e += cmp(q + " z", s.z, r.get("z").getAsInt());
            e += cmp(q + " vitesseX", s.velX, r.get("velX").getAsInt());
            e += cmp(q + " vitesseZ", s.velZ, r.get("velZ").getAsInt());
            e += cmp(q + " vitesseY", s.velY, r.get("velY").getAsInt());
            e += cmp(q + " puissance", s.power, r.get("power").getAsInt());
            e += cmp(q + " impact", s.popping ? 255 : 0, r.get("status").getAsInt());
            e += cmp(q + " definition", s.type, r.get("size").getAsInt());
            e += cmp(q + " hauteurAcc", s.accY, r.get("accY").getAsInt());
            e += cmp(q + " anim", s.anim, r.get("anim").getAsInt());
            e += cmp(q + " gravite", s.gravity, r.get("gravity").getAsInt());
            e += cmp(q + " duree", s.lifetime, r.get("lifetime").getAsInt());
            int flags = r.get("flags").getAsInt() & 0xFFFF;
            e += cmp(q + " rebondH", s.bounceHoriz ? 1 : 0, (flags >> 8) != 0 ? 1 : 0);
            e += cmp(q + " rebondV", s.bounceVert ? 1 : 0, (flags & 0xFF) != 0 ? 1 : 0);
            e += cmp(q + " etage", s.upperZone ? 255 : 0, r.get("upper").getAsInt());
            e += cmp(q + " cibles", s.enemyFlags, r.get("enemyFlags").getAsInt());
            e += cmp(q + " hauteur", s.heightWord, r.get("height").getAsInt());
            if (e != 0 && !bavard) {
                return e;
            }
        }
        // Le hasard : on ne peut pas le lire, mais on peut tirer le suivant des deux cotes.
        e += cmp(lettre + " hasard", nav.rand(), suivantDe(a.get("rand").getAsInt()));
        nav.seed(a.get("rand").getAsInt());
        return e;
    }

    /**
     * L etat STALE d un emplacement du pool : rien a zero, fractions comprises.
     *
     * <p>Les memes valeurs que l oracle pose dans la memoire plate, champ pour champ.
     */
    private static void salir(Shots.Shot s, int k, boolean busy, int zone) {
        s.active = busy;
        s.zone = busy ? zone : -1;
        s.velX = ((700 + k * 13) << 16) | ((0x1234 + k * 0x111) & 0xFFFF);
        s.velZ = ((-400 - k * 7) << 16) | ((0xFEDC - k * 0x101) & 0xFFFF);
        s.velY = 333 - k * 5;
        s.power = 0x55;                                // l octet FORT du mot ShotT_Power_w
        s.popping = busy;
        s.type = 7;
        s.accY = 123456 + k * 97;
        s.anim = k & 0xFF;
        s.gravity = 40 + k;
        s.lifetime = 500 + k;
        s.bounceHoriz = true;                          // ShotT_Flags_w = $0101
        s.bounceVert = true;
        s.upperZone = false;
        s.enemyFlags = 0x12345678;
        s.heightWord = 900 + k;
        s.x = ((1000 + k * 3) << 16) | 0xABCD;
        s.z = ((-2000 - k * 5) << 16) | 0x4321;
    }

    /** GetRand, pour comparer le tirage SUIVANT de part et d autre. */
    private static int suivantDe(int graine) {
        int d0 = graine & 0xFFFF;
        d0 = ((d0 << 3) | (d0 >>> 13)) & 0xFFFF;
        return (d0 + 0x2343) & 0xFFFF;
    }

    private static int cmp(String quoi, int got, int want) {
        if (got == want) {
            return 0;
        }
        System.out.printf("  %-44s %d != %d%n", quoi, got, want);
        return 1;
    }
}
