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
import java.util.HashMap;
import java.util.Map;

/**
 * Verifie le COUP AU BUT : qui une balle touche, et ce qu'elle lui laisse.
 *
 * <p>Deux mille quatre cents coups sur six niveaux. Pour chacun : les degats, le RECUL et les
 * points de vie de chaque monstre et de chaque chose cassable du niveau, ceux du corps du
 * joueur, et l'etat complet de la balle apres son image de vol — secteur, position, vitesses,
 * hauteur, animation, duree de vie.
 *
 * <h2>Ce que cet oracle a trouve</h2>
 *
 * <p><b>UNE boucle, et non trois.</b> {@code notasplut} parcourt la table des objets entiere
 * et applique la meme ligne a tout ce qu'il retient — {@code add.b} sur les degats,
 * {@code move.w ShotT_VelocityX_w(a0),EntT_ImpactX_w(a3)} sur le recul. Le portage, lui, avait
 * coupe cette boucle en trois : le joueur d'abord, puis les monstres, puis les choses. Trois
 * ecarts en sont sortis.
 *
 * <p><b>Le monstre ne reculait pas.</b> Les choses recevaient leur recul, le corps du joueur
 * aussi ; les monstres, non. Cela ne se voit qu'a la MORT — le recul d'un monstre n'est relu
 * qu'une fois, par {@code Anim_ExplodeIntoBits}, qui en donne la MOITIE a chacun des morceaux
 * (voir {@link BitsCheck}). Un alien abattu de face s'eparpillait donc exactement comme un
 * alien abattu de dos, et la gerbe ne partait jamais dans l'axe du tir.
 *
 * <p><b>Le corps du joueur passait en tete.</b> Il a son rang dans la table comme une caisse,
 * et ce rang vient APRES tous les monstres et toutes les choses (releve sur les six niveaux :
 * derniere cible au rang 77, 120, 150, 179, 188, 163 ; corps du joueur au rang 140, 181, 224,
 * 255, 256, 312) et AVANT les pools. Le sortir en tete le faisait encaisser a la place du
 * monstre qui le precede — c'est la premiere entite retenue qui arrete tout.
 *
 * <p><b>Le mur n'arrete pas le balayage.</b> {@code .hitsomething} pose l'impact, joue le
 * bruit, allume le souffle — puis tombe dans {@code .nothitwall}, ou le fanion de duree de vie
 * vient d'etre efface, et de la dans {@code lab}, qui mene a {@code notasplut} comme si de
 * rien n'etait. Le portage s'arretait des que la balle avait touche quelque chose : une
 * roquette qui creve contre le mur derriere un alien ne lui faisait rien.
 *
 * <p><b>Une distance nulle rend UN.</b> La copie inline de la racine carree pose
 * {@code move.l #1,d0} AVANT de tester la somme, et saute a {@code .oksqr} en la gardant. Le
 * portage rendait zero, et le rayon de recherche valait alors {@code (0+80)} au carre au lieu
 * de {@code (1+80)} : une balle immobile ne touchait pas ce qui etait a quatre-vingt-une
 * unites d'elle.
 *
 * <p><b>Les degats du joueur ne debordaient pas.</b> Toutes les ecritures du jeu sont
 * {@code add.b}, {@code move.b} ou {@code clr.b} : un OCTET, qui boucle. Quatre sites du
 * portage ajoutaient sans boucler sur le corps du joueur — ici, la morsure d'un alien, son
 * tir au juge et les degats du sol. Trois cent cinquante-neuf points de degats en font cent
 * trois.
 *
 * <h2>Ce qu'on a mesure en passant</h2>
 *
 * <ul>
 *   <li>les SEIZE niveaux du jeu ne portent que SEPT choses cassables en tout — quatre dans
 *       le niveau g, une dans m, une dans n, une dans o. Toute la branche des choses tient a
 *       cela ;</li>
 *   <li>une SEULE entite de tout le jeu porte un octet d'etage autre que zero ou deux cent
 *       cinquante-cinq (niveau n, rang 24, qui vaut un) — et elle n'est pas cassable.</li>
 * </ul>
 *
 * <h2>Ce que le corpus ne separe pas</h2>
 *
 * <p>Les reglages d'un cas sont tires par un generateur a part, et non pris sur l'indice de
 * boucle : la boucle interieure a soixante-dix pas, et un reglage pris sur cinq valeurs
 * devient alors une fonction de l'elan. Le premier corpus ecrit comme cela ne posait JAMAIS le
 * corps du joueur sur la cible autrement que lorsque les drapeaux d'ennemi l'excluaient — pas
 * un seul des deux mille quatre cents coups ne l'avait touche, et l'ordre de la table ne
 * prouvait rien. De meme, les ancres ont du etre choisies en prenant d'ABORD les choses : les
 * niveaux commencent par leurs monstres, et pas une ancre n'en etait une.
 *
 * <p>Trois mille trois cent soixante coups, six niveaux, aucun ecart. Sur cinquante-neuf
 * mutations, cinquante mordent ; les neuf survivantes se mesurent :
 *
 * <ul>
 *   <li>le garde {@code tst.l EntT_EnemyFlags_l} est REDONDANT : une balle sans drapeaux
 *       echoue de toute facon au {@code btst} de chaque famille, et ne retient rien ;</li>
 *   <li>trois mutations de l'ordre du joueur ne changent rien, parce que son corps est le
 *       DERNIER des entites que ce portage modelise : le tester a son rang, en queue de
 *       boucle, ou a un rang inclusif revient au meme. Les seuls rangs superieurs au sien
 *       sont ceux des emplacements d'aliens ENGENDRES, qui suivent les pools ;</li>
 *   <li>l'etage d'une chose compare par OU EXCLUSIF ou par oui-ou-non : une seule entite du
 *       jeu porterait la difference, et elle n'est pas cassable (ci-dessus) ;</li>
 *   <li>la tolerance du corps du joueur portee de quarante a quarante et un : il faudrait que
 *       sa distance perpendiculaire vaille exactement quarante et un ALORS QUE rien d'autre
 *       n'est touche — aucun des trois mille trois cent soixante coups ne le fait. La porter
 *       a quatre-vingts, elle, mord ;</li>
 *   <li>le produit vectoriel pris dans l'autre sens : la valeur absolue suit, c'est une
 *       egalite exacte ;</li>
 *   <li>{@code MOVING} relu du segment REEL au lieu du segment VOULU : il faudrait une balle
 *       dont le deplacement voulu soit non nul et le deplacement obtenu nul, c'est-a-dire
 *       arretee net par un mur sans avoir avance d'une seule unite. Aucune des trois mille
 *       trois cent soixante ne l'est. Le portage pose quand meme le fanion comme le jeu, AVANT
 *       la collision.</li>
 * </ul>
 *
 * <pre>gradle -p full hitCheck</pre>
 */
public final class HitCheck {

    private HitCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.hit", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=hit");
            System.exit(1);
        }
        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }

        int ecarts = 0;
        int coups = 0;
        for (var el : root.getAsJsonArray("levels")) {
            JsonObject n = el.getAsJsonObject();
            coups += n.getAsJsonArray("cases").size();
            ecarts += unNiveau(n);
        }
        System.out.printf("%n%d coups sur six niveaux, %d ecart(s) -> %s%n",
                coups, ecarts, ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    /** Les points de vie d'origine de chaque cible, par rang de table. */
    private static final Map<Integer, Integer> VIES = new HashMap<>();

    private static int unNiveau(JsonObject n) {
        char lettre = n.get("level").getAsString().charAt(0);
        Level data = Data.level(lettre);
        Database glf = Data.database();
        World w = new World(data, glf);
        posesDuFichier(w, data);

        // La scene doit d'abord etre la MEME : si une cible n'est pas ou l'oracle l'a trouvee,
        // tout le reste comparerait deux niveaux differents.
        int ecarts = 0;
        JsonArray attendues = n.getAsJsonArray("targets");
        VIES.clear();
        for (var el : attendues) {
            JsonObject o = el.getAsJsonObject();
            int slot = o.get("slot").getAsInt();
            VIES.put(slot, o.get("hitPoints").getAsInt());
            int type = o.get("typeId").getAsInt();
            if (type == 0) {
                Aliens.Alien a = monstre(w, slot);
                if (a == null) {
                    System.out.printf("%s : monstre %d introuvable%n", lettre, slot);
                    return 1;
                }
                ecarts += cmp(lettre + " monstre " + slot + " x", a.x, o.get("x").getAsInt());
                ecarts += cmp(lettre + " monstre " + slot + " z", a.z, o.get("z").getAsInt());
                ecarts += cmp(lettre + " monstre " + slot + " hauteur", (short) a.height,
                        o.get("height").getAsInt());
                ecarts += cmp(lettre + " monstre " + slot + " zone", a.zone,
                        o.get("zone").getAsInt());
            } else {
                ObjectRuntime.Thing t = chose(w, slot);
                if (t == null) {
                    System.out.printf("%s : chose %d introuvable%n", lettre, slot);
                    return 1;
                }
                ecarts += cmp(lettre + " chose " + slot + " x", t.entity.x(),
                        o.get("x").getAsInt());
                ecarts += cmp(lettre + " chose " + slot + " z", t.entity.z(),
                        o.get("z").getAsInt());
                ecarts += cmp(lettre + " chose " + slot + " hauteur", (short) t.height,
                        o.get("height").getAsInt());
                ecarts += cmp(lettre + " chose " + slot + " zone", t.zone,
                        o.get("zone").getAsInt());
            }
        }
        if (ecarts != 0) {
            return ecarts;
        }

        int k = 0;
        for (var el : n.getAsJsonArray("cases")) {
            ecarts += unCoup(lettre + "/" + k++, w, el.getAsJsonObject());
            if (ecarts > 40) {
                return ecarts;                         // inutile d'en dire plus
            }
        }
        return ecarts;
    }

    /**
     * Rend aux secteurs les hauteurs que le FICHIER leur donne : monter un {@link World} fait
     * tourner une image d'animation, l'oracle non. Voir {@link BlastCheck}.
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

    private static Aliens.Alien monstre(World w, int slot) {
        for (Aliens.Alien a : w.aliens.aliens()) {
            if (a.index == slot) {
                return a;
            }
        }
        return null;
    }

    private static ObjectRuntime.Thing chose(World w, int slot) {
        for (ObjectRuntime.Thing t : w.objects.things()) {
            if (t.rank == slot) {
                return t;
            }
        }
        return null;
    }

    private static int unCoup(String nom, World w, JsonObject c) {
        // --- tout remis a plat ---
        for (Aliens.Alien a : w.aliens.aliens()) {
            a.damageTaken = 0;
            a.impactX = 0;
            a.impactZ = 0;
            Integer vie = VIES.get(a.index);
            if (vie != null) {
                a.hitPoints = Math.max(1, vie);
            }
        }
        for (ObjectRuntime.Thing t : w.objects.things()) {
            t.damageTaken = 0;
            t.impactX = 0;
            t.impactZ = 0;
            Integer vie = VIES.get(t.rank);
            if (vie != null) {
                t.hitPoints = Math.max(1, vie);
            }
        }
        int mort = c.get("deadTarget").getAsInt();
        if (mort >= 0) {
            Aliens.Alien a = monstre(w, mort);
            if (a != null) {
                a.hitPoints = 0;
            }
            ObjectRuntime.Thing t = chose(w, mort);
            if (t != null) {
                t.hitPoints = 0;
            }
        }
        for (Shots.Shot s : w.shots.shots()) {
            s.active = false;
            s.zone = -1;
            s.popping = false;
            s.anim = 0;
            s.brightness = 0;
        }

        // --- le corps du joueur ---
        w.player.damageTaken = 0;
        w.player.impactX = 0;
        w.player.impactZ = 0;
        w.player.snapXOff = c.get("playerX").getAsInt() << 16;
        w.player.snapZOff = c.get("playerZ").getAsInt() << 16;
        w.player.height = 0;                           // la hauteur de dessin est yOff >> 7
        w.player.yOff = c.get("playerHeight").getAsInt() << 7;
        w.player.zone = c.get("playerZone").getAsInt();
        w.player.stoodInTop = c.get("playerUpper").getAsInt() != 0;

        // --- la balle, seule a voler ---
        Shots.Shot s = w.shots.shots().get(0);         // le premier emplacement du pool JOUEUR
        s.active = true;
        s.popping = false;
        s.anim = 0;
        s.type = c.get("bulletType").getAsInt();
        s.zone = c.get("zone").getAsInt();
        s.upperZone = c.get("upper").getAsInt() != 0;
        s.x = c.get("x").getAsInt() << 16;
        s.z = c.get("z").getAsInt() << 16;
        s.velX = c.get("velX").getAsInt() << 16;
        s.velZ = c.get("velZ").getAsInt() << 16;
        s.velY = c.get("velY").getAsInt();
        s.power = c.get("power").getAsInt();
        s.enemyFlags = c.get("enemyFlags").getAsInt();
        s.lifetime = c.get("lifetime").getAsInt();
        s.gravity = 0;
        s.bounceHoriz = false;
        s.bounceVert = false;
        int h = c.get("height").getAsInt();
        s.accY = h << 7;
        s.heightWord = h;
        w.shots.tempFrames = c.get("frames").getAsInt();

        w.shots.update();

        JsonObject a = c.getAsJsonObject("after");
        int e = compareBalle(nom + " coup1", s, a.getAsJsonObject("bullet1"));

        // Ce que le PREMIER coup, a lui seul, a touche.
        JsonObject h1 = a.getAsJsonObject("hit1");
        int blesse = -1;
        int mal = 0;
        for (var el : a.getAsJsonArray("targets")) {
            int slot = el.getAsJsonObject().get("slot").getAsInt();
            Aliens.Alien m = monstre(w, slot);
            int d = m != null ? m.damageTaken : chose(w, slot).damageTaken;
            if (d != 0) {
                blesse = slot;
                mal = d;
                break;
            }
        }
        e += cmp(nom + " coup1 cible", blesse, h1.get("slot").getAsInt());
        e += cmp(nom + " coup1 degats", mal, h1.get("damage").getAsInt());
        e += cmp(nom + " coup1 joueur degats", w.player.damageTaken,
                h1.get("playerDamage").getAsInt());
        e += cmp(nom + " coup1 joueur reculX", w.player.impactX,
                h1.get("playerImpactX").getAsInt());
        e += cmp(nom + " coup1 joueur reculZ", w.player.impactZ,
                h1.get("playerImpactZ").getAsInt());

        // --- le SECOND coup : meme balle, autre cap, autre force, rien d'efface entre les
        // deux. Les degats s'AJOUTENT et le recul se REMPLACE ; un coup unique ne saurait pas
        // les distinguer, et la moitie du corpus de mutations survivait sans rien prouver.
        s.active = true;
        s.popping = false;
        s.anim = 0;
        s.zone = c.get("zone").getAsInt();
        s.upperZone = c.get("upper").getAsInt() != 0;
        s.power = c.get("power2").getAsInt();
        s.lifetime = c.get("lifetime").getAsInt();
        s.x = c.get("x2").getAsInt() << 16;
        s.z = c.get("z2").getAsInt() << 16;
        s.velX = c.get("velX2").getAsInt() << 16;
        s.velZ = c.get("velZ2").getAsInt() << 16;
        s.velY = 0;
        s.accY = h << 7;
        s.heightWord = h;

        w.shots.update();

        // --- ce qu'il en reste ---
        for (var el : a.getAsJsonArray("targets")) {
            JsonObject o = el.getAsJsonObject();
            int slot = o.get("slot").getAsInt();
            Aliens.Alien m = monstre(w, slot);
            int dmg;
            int ix;
            int iz;
            int pv;
            if (m != null) {
                dmg = m.damageTaken;
                ix = m.impactX;
                iz = m.impactZ;
                pv = m.hitPoints & 0xFF;
            } else {
                ObjectRuntime.Thing t = chose(w, slot);
                if (t == null) {
                    System.out.printf("%s : cible %d introuvable%n", nom, slot);
                    return 1;
                }
                dmg = t.damageTaken;
                ix = t.impactX;
                iz = t.impactZ;
                pv = t.hitPoints & 0xFF;
            }
            e += cmp(nom + " cible " + slot + " degats", dmg, o.get("damage").getAsInt());
            e += cmp(nom + " cible " + slot + " reculX", ix, o.get("impactX").getAsInt());
            e += cmp(nom + " cible " + slot + " reculZ", iz, o.get("impactZ").getAsInt());
            e += cmp(nom + " cible " + slot + " pv", pv, o.get("hitPoints").getAsInt());
            if (e > 12) {
                return e;
            }
        }
        JsonObject j = a.getAsJsonObject("player");
        e += cmp(nom + " joueur degats", w.player.damageTaken, j.get("damage").getAsInt());
        e += cmp(nom + " joueur reculX", w.player.impactX, j.get("impactX").getAsInt());
        e += cmp(nom + " joueur reculZ", w.player.impactZ, j.get("impactZ").getAsInt());

        e += compareBalle(nom + " coup2", s, a.getAsJsonObject("bullet"));
        return e;
    }

    /** L'etat d'une balle apres son image de vol. */
    private static int compareBalle(String nom, Shots.Shot s, JsonObject b) {
        int zone = s.active ? s.zone : -1;             // FREE_ENT pose un secteur negatif
        int e = cmp(nom + " balle zone", zone, b.get("zone").getAsInt());
        e += cmp(nom + " balle x", s.x, b.get("x").getAsInt());
        e += cmp(nom + " balle z", s.z, b.get("z").getAsInt());
        e += cmp(nom + " balle velX", s.velX, b.get("velX").getAsInt());
        e += cmp(nom + " balle velZ", s.velZ, b.get("velZ").getAsInt());
        e += cmp(nom + " balle velY", (short) s.velY, b.get("velY").getAsInt());
        e += cmp(nom + " balle accY", s.accY, b.get("accY").getAsInt());
        e += cmp(nom + " balle hauteur", (short) s.heightWord, b.get("height").getAsInt());
        if (zone >= 0) {
            // Une balle liberee garde ses octets d'animation dans le jeu ; le portage, lui, les
            // remet a zero en liberant. Il n'y a rien a comparer la.
            e += cmp(nom + " balle etat", s.popping ? 1 : 0, b.get("status").getAsInt());
            e += cmp(nom + " balle anim", s.anim & 0xFF, b.get("anim").getAsInt());
            e += cmp(nom + " balle duree", (short) s.lifetime, b.get("lifetime").getAsInt());
        }
        return e;
    }

    private static int cmp(String quoi, int vu, int attendu) {
        if (vu == attendu) {
            return 0;
        }
        System.out.printf("%s : %d, attendu %d%n", quoi, vu, attendu);
        return 1;
    }
}
