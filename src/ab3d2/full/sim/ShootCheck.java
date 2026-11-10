package ab3d2.full.sim;

import ab3d2.full.SinCos;
import ab3d2.full.Data;
import ab3d2.full.defs.Database;
import ab3d2.full.level.Level;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verification du TIR INSTANTANE DU JOUEUR contre l'oracle {@code shoot.json}
 * ({@code rebirth/.../ShootExport.java}).
 *
 * <h2>Ce qui est prouve</h2>
 *
 * <p>Le DE, et rien d'autre. Avec une cible alignee, le jeu d'origine ne trace AUCUN rayon : il
 * tire un de par plomb contre le carre de la distance a plat,
 * {@code (GetRand & $7fff) * 2 > d²/64}, et {@code plr1_HitscanSucceded} ajoute alors un octet
 * de degats et pose le recul {@code (tempxdir << 3) >> 16}. Le portage, lui, choisit sa cible
 * par un rayon — c'est l'ecart assume de la visee souris. Sur les coups ou les deux designent
 * la MEME entite, tout le reste doit coincider : le nombre de plombs qui portent, les degats,
 * le recul, et jusqu'a l'ETAT DU GENERATEUR, qui prouve qu'on a tire autant de des que le jeu.
 *
 * <h2>Les cinq decouvertes du chapitre</h2>
 *
 * <ol>
 *   <li><b>Le fusil du portage ne ratait jamais.</b> Il appliquait {@code N x degats} des que
 *       son rayon touchait, a n'importe quelle distance. Le jeu tire un de par plomb, et la
 *       chance decroit comme {@code 1 - (d/2048)²}.</li>
 *   <li><b>La falaise des 2048 unites.</b> Le plus grand tirage vaut {@code $fffe} ; passe
 *       2048 unites, {@code d²/64} le depasse et le coup ne PEUT plus porter. L'oracle le
 *       mesure : 0 touche sur 1743 plombs tires au-dela.</li>
 *   <li><b>Les monstres sont deux fois plus precis que le joueur.</b>
 *       {@code ai_AttackWithHitScan} roule le meme de avec {@code asl.l #2} au lieu de
 *       {@code asl.l #1} : leur portee utile va a 2896 unites. C'etait deja porte ; seul le
 *       joueur manquait son de, et toute l'asymetrie jouait en sa faveur.</li>
 *   <li><b>Les armes instantanees ne poussaient rien.</b> {@code plr1_HitscanSucceded} pose
 *       {@code EntT_ImpactX/Z_w} a la direction de tir decalee de treize bits — une pichenette
 *       de moins quatre a trois unites, la ou un projectile pousse de sa vitesse entiere. Le
 *       seul lecteur est {@code Anim_ExplodeIntoBits} : un monstre abattu au fusil s'eparpille
 *       presque sur place.</li>
 *   <li><b>Le filtre vertical de l'auto-visee DEBORDE.</b> {@code muls #93,d2} est une
 *       multiplication sur un MOT, et {@code d2} vaut {@code |dy| * 128}, un long. Passe 512
 *       unites d'ecart de hauteur le produit change de signe et le filtre laisse TOUT passer.
 *       Mesure sur 3229 poses : ecart de hauteur 0 -> 793 cibles retenues sur 793 ; ecart 40
 *       -> 688 sur 801 ; ecart 200 -> 610 sur 785 ; ecart <b>1000 -> 850 sur 850</b>. Un
 *       monstre a mille unites au-dessus du joueur est plus facile a viser qu'un monstre a deux
 *       cents. Ce filtre n'est pas repris : il appartient a l'auto-visee, que le portage
 *       remplace par son rayon.</li>
 * </ol>
 *
 * <h2>Ce que l'oracle ne peut pas comparer</h2>
 *
 * <p>Trois familles de coups sont comptees et non comparees, et le harnais les affiche :
 * <ul>
 *   <li>l'auto-visee n'a RIEN retenu (cone, profondeur nulle a bout touchant, ou le filtre
 *       vertical qui deborde) alors que le rayon du portage touche : c'est l'ecart assume ;</li>
 *   <li>le rayon du portage ne rencontre pas l'ancre (un decor entre deux, ou la tolerance de
 *       hauteur) alors que l'auto-visee l'avait retenue ;</li>
 *   <li>le monstre de l'oracle n'existe pas dans le portage (rang absent de la liste).</li>
 * </ul>
 */
public final class ShootCheck {

    private ShootCheck() {
    }

    private static int compares;
    private static int horsVisee;
    private static int horsRayon;
    private static int absents;

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.shoot", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=shoot");
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
        System.out.printf("%n%d coups poses, %d compares, %d ecart(s)%n", coups, compares, ecarts);
        System.out.printf("non compares : %d hors auto-visee, %d hors rayon, %d monstre absent%n",
                horsVisee, horsRayon, absents);
        // Un harnais qui ne compare rien passerait sans rien prouver.
        if (compares < coups / 3) {
            System.out.printf("TROP PEU DE COUPS COMPARES (%d sur %d)%n", compares, coups);
            System.exit(1);
        }
        System.out.println(ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    private static int unNiveau(JsonObject n) {
        char lettre = n.get("level").getAsString().charAt(0);
        Level data = Data.level(lettre);
        Database glf = Data.database();
        World w = new World(data, glf);
        posesDuFichier(w, data);

        int ecarts = 0;
        int k = 0;
        for (var el : n.getAsJsonArray("cases")) {
            ecarts += unCoup(lettre + "/" + k++, w, el.getAsJsonObject());
        }
        System.out.printf("niveau %c : %d coups, %d ecart(s)%n", lettre,
                n.getAsJsonArray("cases").size(), ecarts);
        return ecarts;
    }

    /** Rend aux secteurs les hauteurs que le FICHIER leur donne (voir {@link BlastCheck}). */
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

    private static int unCoup(String nom, World w, JsonObject c) {
        int ancre = c.get("anchor").getAsInt();
        Aliens.Alien cible = monstre(w, ancre);
        if (cible == null) {
            absents++;
            return 0;
        }

        // --- tout remis a plat ---
        for (Aliens.Alien a : w.aliens.aliens()) {
            a.damageTaken = 0;
            a.impactX = 0;
            a.impactZ = 0;
        }
        for (ObjectRuntime.Thing t : w.objects.things()) {
            t.damageTaken = 0;
            t.impactX = 0;
            t.impactZ = 0;
        }
        if (cible.hitPoints == 0) {
            cible.hitPoints = 200;
        }

        // --- le joueur, pose comme l'oracle l'a pose ---
        PlayerSim p = w.player;
        p.xOff = p.snapXOff = c.get("playerX").getAsInt() << 16;
        p.zOff = p.snapZOff = c.get("playerZ").getAsInt() << 16;
        p.yOff = c.get("playerY").getAsInt();
        p.angPos = c.get("angle").getAsInt() & SinCos.MASK;
        p.zone = c.get("playerZone").getAsInt();
        p.stoodInTop = c.get("playerUpper").getAsInt() != 0;

        // La VISEE. L'oracle tire a plat — son de ignore la geometrie — tandis que le portage
        // suit un rayon : pour que les deux designent la meme entite, on pointe le reticule SUR
        // la cible. Une unite monde vaut 64 unites de position et 128 de hauteur de dessin.
        int dist = c.get("dist").getAsInt();
        int oeil = p.yOff + 10 * 128;
        int viseeY = c.get("targetHeight").getAsInt() * 128;
        float pente = dist == 0 ? 0f : (oeil - viseeY) / (128f * dist);

        Inventory inv = new Inventory(Database.MAX_INVENTORY);
        for (int b = 0; b < 20; b++) {
            inv.setAmmo(b, 999);
        }
        int gun = c.get("gun").getAsInt();
        w.nav.seed(c.get("seed").getAsInt());
        w.shots.timeToShoot = 0;
        w.shots.lastHitscanTarget = null;
        w.shots.fire(p, inv, gun, true, pente);

        JsonObject a = c.getAsJsonObject("after");
        boolean visee = a.get("selected").getAsBoolean();
        boolean rayon = w.shots.lastHitscanTarget == cible;
        if (!visee) {
            horsVisee++;
            return 0;
        }
        if (!rayon) {
            horsRayon++;
            return 0;
        }

        compares++;
        int e = cmp(nom + " degats", cible.damageTaken, a.get("anchorDamage").getAsInt());
        e += cmp(nom + " reculX", cible.impactX, a.get("anchorImpactX").getAsInt());
        e += cmp(nom + " reculZ", cible.impactZ, a.get("anchorImpactZ").getAsInt());
        // L'etat du generateur : autant de des tires, dans le meme ordre, qu'au jeu d'origine.
        e += cmp(nom + " generateur", w.nav.etat(), a.get("randAfter").getAsInt());
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
