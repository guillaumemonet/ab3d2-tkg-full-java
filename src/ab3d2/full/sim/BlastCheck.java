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
import java.util.List;

/**
 * Verifie le SOUFFLE : ce qu'une explosion fait a tout ce qui l'entoure.
 *
 * <p>Mille explosions sur six niveaux, et pour chacune : les degats et les trois reculs de
 * chaque monstre, ceux du joueur, la poussee de chaque projectile pesant, le fait que les
 * CHOSES ne prennent rien, et les six flammes semees ensuite — position, secteur, hauteur,
 * etage — ainsi que l'etat ou le generateur de hasard est laisse.
 *
 * <p>La flamme est la plus exigeante des six : elle passe par la COLLISION, de sorte qu'une
 * explosion contre un mur sort ses flammes du mur, et que le secteur qu'elles portent n'est pas
 * celui du tir. La comparer, c'est comparer {@code MoveObject} aussi — et c'est d'ici que
 * {@link MoveCheck} est parti : les flammes etaient le seul endroit du portage ou la collision
 * se voyait, et elles ne tombaient pas juste.
 *
 * <h2>Deux souffles, et ce qu'on seme entre les deux</h2>
 *
 * <p>Chaque cas allume DEUX explosions, depuis deux points differents, et fait encaisser le
 * corps du joueur apres chacune. Il en faut deux : un recul se POSE et ne s'ajoute pas, et une
 * seule explosion ne saurait pas faire la difference. Et les deux encaissements montrent qu'un
 * champ d'impact se vide : s'il restait, le second coup porterait le premier avec lui.
 *
 * <p>La scene n'est jamais a zero. Les projectiles semes ont des vitesses non nulles, fractions
 * comprises ; le corps du joueur bouge deja ; un monstre est pose SANS points de vie, et change
 * d'un cas a l'autre ; un cas sur sept, le pool du joueur est presque plein, de sorte que les
 * flammes s'arretent faute de place. Sans tout cela, ajouter et remplacer donneraient la meme
 * chose, et la moitie du corpus de mutations survivrait sans rien prouver.
 *
 * <h2>Ce que le corpus ne separe pas</h2>
 *
 * <p>Mille huit explosions, six niveaux, aucun ecart. Sur soixante-cinq mutations,
 * cinquante-cinq mordent ; les dix survivantes se mesurent :
 *
 * <ul>
 *   <li>la racine du souffle rend UN et non zero pour une somme nulle — les deux tombent dans
 *       le meme pas de huit, et le diviseur plancher de deux cent cinquante-six les egalise ;</li>
 *   <li>son plancher a un ne mord que si un pas de Newton atterrit pile sur zero : aucune des
 *       six mille distances mesurees ne le fait ;</li>
 *   <li>le plafond des degats compare {@code >=} la ou {@code >} suffirait : a egalite les deux
 *       posent la MEME valeur. La mutation ne change rien, par construction ;</li>
 *   <li>garder le mot entier des degats au lieu de l'octet ne se voit pas : tout ce qui les lit
 *       ensuite masque a l'octet de toute facon ;</li>
 *   <li>le test « le joueur est-il dans un secteur » ne se franchit jamais : l'oracle ne le
 *       place jamais dehors, et dans le jeu son entite n'en sort qu'a sa MORT — ou la partie
 *       est finie ;</li>
 *   <li>le decalage du hasard horizontal d'une flamme en mot plutot qu'en long ne mord pas : un
 *       octet signe multiplie par six au plus ne sort pas des seize bits ;</li>
 *   <li>le « zero devient deux » d'une flamme demande un produit exactement nul, et la suite du
 *       generateur n'en donne aucun sur six mille tirages ;</li>
 *   <li>la hauteur d'une flamme est relue APRES la collision, mais {@code MoveObject} ne la
 *       change dans aucune de ces scenes ;</li>
 *   <li>ecrire la vitesse horizontale du corps en long au lieu du mot fort demanderait une
 *       retenue hors des seize bits : il y faudrait une vitesse que le jeu n'atteint pas ;</li>
 *   <li>la gerbe de MORCEAUX herite de la moitie du recul du mort — c'est le seul endroit du
 *       jeu qui relise {@code EntT_ImpactX_w} d'un monstre — et rien ici ne la declenche. Elle
 *       n'a pas d'oracle a elle.</li>
 * </ul>
 */
public final class BlastCheck {

    /** Le premier emplacement du pool du joueur dans la table d'origine. */
    private static int plrShotSlot;


    private BlastCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.blast", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=blast");
            System.exit(1);
        }
        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        int graine = root.get("seed").getAsInt();

        int ecarts = 0;
        int explosions = 0;
        for (var el : root.getAsJsonArray("levels")) {
            JsonObject n = el.getAsJsonObject();
            explosions += n.getAsJsonArray("cases").size();
            ecarts += unNiveau(n, graine);
        }
        System.out.printf("%n%d explosions sur six niveaux, %d ecart(s) -> %s%n",
                explosions, ecarts, ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    private static int unNiveau(JsonObject n, int graine) {
        char lettre = n.get("level").getAsString().charAt(0);
        Level data = Data.level(lettre);
        Database glf = Data.database();
        World w = new World(data, glf);
        posesDuFichier(w, data);
        plrShotSlot = data.playerEntity() - 60;        // 20 du pool joueur + 40 des aliens

        int ecarts = 0;
        // D'abord : les monstres sont-ils la ou l'oracle les a trouves ? Sans cela, tout le
        // reste comparerait deux scenes differentes.
        List<Aliens.Alien> vivants = enOrdre(w);
        JsonArray attendus = n.getAsJsonArray("aliens");
        if (vivants.size() != attendus.size()) {
            System.out.printf("%s : %d monstres, l'oracle en a %d%n", lettre, vivants.size(),
                    attendus.size());
            return 1;
        }
        for (int i = 0; i < vivants.size(); i++) {
            Aliens.Alien a = vivants.get(i);
            JsonObject o = attendus.get(i).getAsJsonObject();
            ecarts += cmp(lettre + " monstre " + i + " rang", a.index, o.get("slot").getAsInt());
            ecarts += cmp(lettre + " monstre " + i + " x", a.x, o.get("x").getAsInt());
            ecarts += cmp(lettre + " monstre " + i + " z", a.z, o.get("z").getAsInt());
            ecarts += cmp(lettre + " monstre " + i + " zone", a.zone, o.get("zone").getAsInt());
            ecarts += cmp(lettre + " monstre " + i + " hp", a.hitPoints,
                    o.get("hitPoints").getAsInt());
        }
        if (ecarts != 0) {
            return ecarts;
        }

        int k = 0;
        for (var el : n.getAsJsonArray("cases")) {
            ecarts += unCas(lettre + "/" + k++, w, vivants, el.getAsJsonObject(), graine);
            if (ecarts > 40) {
                return ecarts;                         // inutile d'en dire plus
            }
        }
        return ecarts;
    }

    /**
     * Rend aux secteurs les hauteurs que le FICHIER leur donne.
     *
     * <p>Monter un {@link World} fait tourner une image d'animation « pour poser les toits et
     * les sols de depart » : les ascenseurs descendent d'un cran, les portes closes bougent.
     * L'oracle, lui, rejoue le niveau tel que le fichier le pose, sans cette image-la. Tant que
     * les flammes n'etaient pas comparees, la difference ne se voyait pas ; des qu'on les
     * compare, elle se voit partout, car c'est le sol et le plafond d'un secteur qui decident
     * si une arete est un mur ou un passage.
     */
    private static void posesDuFichier(World w, Level data) {
        LevelSim f = LevelSim.of(data);
        for (int i = 0; i < f.zones.length; i++) {
            if (f.zones[i] == null || w.lvl.zones[i] == null) {
                continue;
            }
            w.lvl.zones[i].floorH = f.zones[i].floorH;
            w.lvl.zones[i].roofH = f.zones[i].roofH;
            w.lvl.zones[i].upperFloorH = f.zones[i].upperFloorH;
            w.lvl.zones[i].upperRoofH = f.zones[i].upperRoofH;
        }
    }

    /** Les monstres vivants, dans l'ordre de la table — celui que l'oracle a releve. */
    private static List<Aliens.Alien> enOrdre(World w) {
        return w.aliens.aliens().stream().filter(a -> a.alive && a.zone >= 0).toList();
    }

    private static int unCas(String nom, World w, List<Aliens.Alien> monstres, JsonObject c,
                             int graine) {
        // --- la table remise a zero ---
        for (Aliens.Alien a : w.aliens.aliens()) {
            a.damageTaken = 0;
            a.impactX = 0;
            a.impactZ = 0;
        }
        w.player.damageTaken = 0;
        w.player.impactX = 0;
        w.player.impactZ = 0;
        w.player.impactY = 0;
        for (Shots.Shot s : w.shots.shots()) {
            s.active = false;
            s.zone = -1;
            s.velX = 0;
            s.velZ = 0;
            s.velY = 0;
            s.accY = 0;
            s.heightWord = 0;
            s.popping = false;
            s.anim = 0;
            s.gravity = 0;
            s.enemyFlags = 0;
        }
        // Un monstre sans points de vie, que le souffle doit ignorer.
        JsonObject a = c.getAsJsonObject("after");
        int mort = a.get("deadAlien").getAsInt();

        // --- le joueur, pose la ou l'oracle l'a pose ---
        JsonObject j = c.getAsJsonObject("player");
        w.player.snapXOff = j.get("x").getAsInt() << 16;
        w.player.snapZOff = j.get("z").getAsInt() << 16;
        w.player.height = 0;
        w.player.yOff = j.get("height").getAsInt() << 7;
        w.player.zone = j.get("zone").getAsInt();
        w.player.stoodInTop = j.get("upper").getAsInt() != 0;

        // --- les projectiles semes, avec des vitesses NON NULLES ---
        java.util.Set<Integer> semes = new java.util.HashSet<>();
        for (var el : c.getAsJsonArray("bullets")) {
            JsonObject b = el.getAsJsonObject();
            int emplacement = pool(b.get("slot").getAsInt());
            semes.add(emplacement);
            Shots.Shot t = w.shots.shots().get(emplacement);
            poser(w, t, b);
            int v = b.get("vel").getAsInt();
            t.velX = (s16(v) << 16) | 0x4000;
            t.velZ = (s16(-v - 13) << 16) | 0x8000;
            t.velY = s16(v * 3 - 900);
        }
        semes.add(Shots.POOL - 1);
        // --- le pool presque plein, un cas sur sept ---
        int bouches = a.get("busy").getAsInt();
        JsonObject j2 = c.getAsJsonObject("player");
        for (int k = 0; k < bouches; k++) {
            Shots.Shot t = w.shots.shots().get(1 + k);
            semes.add(1 + k);
            t.active = true;
            t.type = c.get("bulletType").getAsInt();
            t.popping = true;
            t.x = j2.get("x").getAsInt() << 16;
            t.z = j2.get("z").getAsInt() << 16;
            t.heightWord = j2.get("height").getAsInt();
            t.accY = t.heightWord << 7;
            t.zone = j2.get("zone").getAsInt();
            t.upperZone = j2.get("upper").getAsInt() != 0;
        }
        // --- le corps du joueur, qui BOUGE DEJA ---
        w.player.xSpdVal = (300 << 16) | 0x1234;
        w.player.zSpdVal = (-170 << 16) | 0xFEDC;
        w.player.snapYVel = -777;
        w.player.angSpd = 42;
        w.objects.inventory.consumables[0] = 20000;

        int e = 0;
        Shots.Shot src = w.shots.shots().get(Shots.POOL - 1);
        w.nav.seed(graine);
        e += unSouffle(nom + "/un", w, monstres, semes, src, c.getAsJsonObject("origin"),
                c.get("force").getAsInt(), c.get("bulletType").getAsInt(),
                a.getAsJsonObject("one"), mort);
        e += unSouffle(nom + "/deux", w, monstres, semes, src, c.getAsJsonObject("origin2"),
                c.get("force2").getAsInt(), c.get("bulletType").getAsInt(),
                a.getAsJsonObject("two"), mort);
        return e;
    }

    /** Un souffle : on l'allume, on compare, puis le corps l'encaisse. */
    private static int unSouffle(String nom, World w, List<Aliens.Alien> monstres,
                                 java.util.Set<Integer> semes, Shots.Shot src, JsonObject o,
                                 int force, int type, JsonObject a, int mort) {
        for (Aliens.Alien al : monstres) {
            al.hitPoints = al.index == mort ? 0 : Math.max(1, al.hitPoints);
            if (al.index != mort && al.hitPoints == 0) {
                al.hitPoints = 1;
            }
        }
        poser(w, src, o, type);
        w.shots.bulletMoveDefaults();
        w.shots.computeBlast(force, o.get("x").getAsInt(), o.get("z").getAsInt(),
                o.get("height").getAsInt(), o.get("zone").getAsInt(),
                o.get("upper").getAsInt() != 0, type);

        // --- la comparaison ---
        int e = 0;
        JsonArray oracles = a.getAsJsonArray("aliens");
        for (int i = 0; i < monstres.size(); i++) {
            Aliens.Alien m = monstres.get(i);
            JsonObject r = oracles.get(i).getAsJsonObject();
            e += cmp(nom + " monstre " + i + " degats", m.damageTaken & 0xFF,
                    r.get("damage").getAsInt());
            e += cmp(nom + " monstre " + i + " reculX", m.impactX, r.get("impactX").getAsInt());
            e += cmp(nom + " monstre " + i + " reculZ", m.impactZ, r.get("impactZ").getAsInt());
            // Le recul VERTICAL d'un monstre est ecrit et jamais relu : on verifie seulement
            // qu'il n'a pas atterri dans sa vitesse de vol.
            e += cmp(nom + " monstre " + i + " vitesseY", m.velocityY, 0);
        }
        JsonObject pj = a.getAsJsonObject("player");
        e += cmp(nom + " joueur degats", w.player.damageTaken & 0xFF, pj.get("damage").getAsInt());
        e += cmp(nom + " joueur reculX", w.player.impactX, pj.get("impactX").getAsInt());
        e += cmp(nom + " joueur reculZ", w.player.impactZ, pj.get("impactZ").getAsInt());
        e += cmp(nom + " joueur reculY", w.player.impactY, pj.get("impactY").getAsInt());

        // Les CHOSES : aucune explosion ne les touche, jamais.
        for (var el : a.getAsJsonArray("things")) {
            e += cmp(nom + " chose " + el.getAsJsonObject().get("rank").getAsInt(),
                    0, el.getAsJsonObject().get("damage").getAsInt());
        }

        // Le pool : les projectiles pousses ET les flammes semees.
        //
        // Les flammes passent par la COLLISION, et c'est leur position qui est le plus
        // difficile a tenir : elle sort de {@code MoveObject}, pas de l'explosion.
        JsonArray attendu = a.getAsJsonArray("pool");
        int occupes = 0;
        for (Shots.Shot s : w.shots.shots()) {
            if (s.active) {
                occupes++;
            }
        }
        e += cmp(nom + " emplacements occupes", occupes, attendu.size());
        if (e != 0) {
            return e;
        }
        int i = 0;
        for (Shots.Shot s : w.shots.shots()) {
            if (!s.active) {
                continue;
            }
            JsonObject r = attendu.get(i).getAsJsonObject();
            String q = nom + " balle " + i;
            int emplacement = pool(r.get("slot").getAsInt());
            e += cmp(q + " emplacement", emplacement, w.shots.shots().indexOf(s));
            e += cmp(q + " x", s16(s.x >> 16), r.get("x").getAsInt());
            e += cmp(q + " z", s16(s.z >> 16), r.get("z").getAsInt());
            e += cmp(q + " accY", s.accY, r.get("accY").getAsInt());
            e += cmp(q + " hauteur", s.heightWord, r.get("height").getAsInt());
            e += cmp(q + " zone", s.zone, r.get("zone").getAsInt());
            e += cmp(q + " etage", s.upperZone ? 255 : 0, r.get("upper").getAsInt());
            e += cmp(q + " type", s.type, r.get("type").getAsInt());
            e += cmp(q + " vitesseX", s16(s.velX >> 16), r.get("velX").getAsInt());
            e += cmp(q + " vitesseZ", s16(s.velZ >> 16), r.get("velZ").getAsInt());
            e += cmp(q + " vitesseY", s16(s.velY), r.get("velY").getAsInt());
            e += cmp(q + " impact", s.popping ? 1 : 0, r.get("popping").getAsInt());
            e += cmp(q + " pas", s.anim, r.get("anim").getAsInt());
            i++;
        }
        e += cmp(nom + " hasard", w.nav.rand(), suivant(a));

        // L'autre moitie : Plr1_Use encaisse ce que le souffle a pose. Le generateur a deja
        // ete consomme une fois par la comparaison ci-dessus — on le repose.
        w.nav.seed(a.get("rand").getAsInt());
        int degats = w.player.applyDamage(w.objects.inventory, w.nav);
        JsonObject corps = a.getAsJsonObject("body");
        e += cmp(nom + " corps degats", degats, pj.get("damage").getAsInt());
        e += cmp(nom + " corps vitesseX", s16(w.player.xSpdVal >> 16),
                corps.get("xSpd").getAsInt());
        e += cmp(nom + " corps vitesseZ", s16(w.player.zSpdVal >> 16),
                corps.get("zSpd").getAsInt());
        e += cmp(nom + " corps vitesseY", w.player.snapYVel, corps.get("yVel").getAsInt());
        e += cmp(nom + " corps secousse", s16(w.player.angSpd), corps.get("angSpd").getAsInt());
        e += cmp(nom + " corps sante", s16(w.objects.inventory.health()),
                corps.get("health").getAsInt());
        e += cmp(nom + " corps hasard", w.nav.rand(), suivantDe(corps.get("rand").getAsInt()));
        w.nav.seed(corps.get("rand").getAsInt());
        return e;
    }

    /**
     * L'etat du generateur apres l'explosion. L'oracle releve {@code Rand1} ; on le compare au
     * PROCHAIN tirage, qui en decoule, parce que le portage ne le donne pas autrement.
     */
    private static int suivant(JsonObject a) {
        return suivantDe(a.get("rand").getAsInt());
    }

    private static int suivantDe(int graine) {
        int d0 = graine & 0xFFFF;
        d0 = ((d0 << 3) | (d0 >>> 13)) & 0xFFFF;
        return (d0 + 0x2343) & 0xFFFF;
    }

    private static void poser(World w, Shots.Shot s, JsonObject b) {
        poser(w, s, b, b.get("type").getAsInt());
    }

    private static void poser(World w, Shots.Shot s, JsonObject b, int type) {
        s.active = true;
        s.type = type;
        s.x = b.get("x").getAsInt() << 16;
        s.z = b.get("z").getAsInt() << 16;
        s.heightWord = b.get("height").getAsInt();
        s.accY = s.heightWord << 7;
        s.zone = b.get("zone").getAsInt();
        s.upperZone = b.get("upper").getAsInt() != 0;
        Database.BulletDef d = w.glf.bullet(type);
        s.gravity = d == null ? 0 : d.gravity();
    }

    /** De l'emplacement de la table d'origine a l'index du pool du portage. */
    private static int pool(int slot) {
        return slot - plrShotSlot;
    }

    private static int s16(int v) {
        return (short) v;
    }

    private static int cmp(String quoi, int got, int want) {
        if (got == want) {
            return 0;
        }
        System.out.printf("%-48s %6d != %6d%n", quoi, got, want);
        return 1;
    }
}
