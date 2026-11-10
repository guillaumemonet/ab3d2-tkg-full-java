package ab3d2.full.sim;

import ab3d2.full.Data;
import ab3d2.full.level.Level;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifie les PORTES et les ASCENSEURS : {@code DoorRoutine} (newanims.s:1237) et
 * {@code LiftRoutine} (newanims.s:869).
 *
 * <p>Ce sont les deux seules machines du jeu qui deplacent le DECOR. Une porte est une zone
 * dont le toit descend, un ascenseur une zone dont le sol monte, et la collision lit ces memes
 * hauteurs : un pas de ces routines change le monde sous les pieds de tout ce qui s'y trouve.
 *
 * <h2>On ne rejoue pas une image, on rejoue une SUITE</h2>
 *
 * <p>Tout y est de la memoire : la position, la vitesse, le compte a rebours de refermeture,
 * l'armement des aretes. Cent soixante images par scene, parce que ce compte a rebours monte
 * jusqu'a deux cents et qu'une scene trop courte ne verrait jamais une porte se refermer.
 *
 * <p>Le declenchement est un HANDSHAKE : la collision pose un bit dans {@code EdgeT_Flags_w}
 * au contact, la routine le lit, le DESARME ({@code $8000}) et applique la vitesse si le bit
 * tombe dans le masque. L'oracle arme donc les aretes lui-meme, tantot a chaque image (un
 * joueur qui reste contre la porte), tantot une seule fois (il passe et s'en va).
 *
 * <h2>Les quatre fautes trouvees</h2>
 *
 * <ul>
 *   <li>une porte dont le SECOND octet de condition est arme ne se referme JAMAIS d'elle-meme.
 *       {@code tstdoortoclose} ne remet pas {@code d4} a zero en entrant : il y trouve cet
 *       octet et se contente d'y poser un quand la duree n'est pas ecoulee. Soixante-dix-neuf
 *       portes du jeu ont cet octet arme, dont quatorze avec une vraie vitesse de fermeture.
 *       Le portage les refermait — et l'ORACLE aussi, jusqu'a ce chapitre ;</li>
 *   <li>{@code Anim_DoorAndLiftLocks_l} est un seul long dont les portes lisent le mot FORT et
 *       les ascenseurs le mot FAIBLE. {@link World} donnait le long entier aux portes, ce qui
 *       les verrouillait avec les cles des ascenseurs et ne verrouillait aucun ascenseur ;</li>
 *   <li>l'ordre : {@code objmoveanim} fait les portes PUIS les ascenseurs, et remet la vitesse
 *       du sol a zero entre les deux ;</li>
 *   <li>{@code plr1_StoodOnLift_b} est POSE OU LEVE a chaque ascenseur, pas seulement pose :
 *       apres la boucle il dit « le joueur est sur le DERNIER », pas « sur un ».</li>
 * </ul>
 *
 * <h2>Ce qui n'est pas compare</h2>
 *
 * <p>Les enregistrements GRAPHIQUES que les deux routines deforment (le decalage vertical de
 * la texture d'un panneau, {@code 12(a1)} et {@code 20/24(a1)}) : le portage les recalcule au
 * dessin. Les bruitages, dont le harnais est muet. Et {@code DoWaterAnims}, que
 * {@code LiftRoutine} appelle en finissant et que le portage n'a pas : l'eau ne monte pas.
 *
 * <pre>gradle -p full animsCheck</pre>
 */
public final class AnimsCheck {

    private AnimsCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.anims", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=anims");
            System.exit(1);
        }
        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        int images = root.get("frames").getAsInt();

        int ecarts = 0;
        int scenes = 0;
        for (var el : root.getAsJsonArray("levels")) {
            JsonObject n = el.getAsJsonObject();
            scenes += n.getAsJsonArray("cases").size();
            ecarts += unNiveau(n, images);
        }
        System.out.printf("%n%d scenes de %d images, %d ecart(s) -> %s%n",
                scenes, images, ecarts, ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    private static int unNiveau(JsonObject n, int images) {
        char lettre = n.get("level").getAsString().charAt(0);
        Level data = Data.level(lettre);
        List<Level.Liftable> portes = data.doors() == null ? List.of() : data.doors();
        List<Level.Liftable> asc = data.lifts() == null ? List.of() : data.lifts();

        int ecarts = 0;
        // D'abord : le portage lit-il les MEMES portes que l'oracle ? Sans cela tout le reste
        // comparerait deux niveaux differents.
        ecarts += defs(lettre + " portes", portes, n.getAsJsonArray("doors"));
        ecarts += defs(lettre + " ascenseurs", asc, n.getAsJsonArray("lifts"));
        if (ecarts != 0) {
            return ecarts;
        }

        List<Integer> aretes = new ArrayList<>();
        for (var e : n.getAsJsonArray("edges")) {
            aretes.add(e.getAsInt());
        }

        int k = 0;
        for (var el : n.getAsJsonArray("cases")) {
            ecarts += uneScene(lettre + "/" + k++, data, portes, asc, aretes,
                    el.getAsJsonObject(), images);
            if (ecarts > 30) {
                return ecarts;                         // inutile d'en dire plus
            }
        }
        System.out.printf("%s  %2d portes  %2d ascenseurs  %s%n", lettre, portes.size(),
                asc.size(), ecarts == 0 ? "OK" : ecarts + " ecart(s)");
        return ecarts;
    }

    private static int defs(String quoi, List<Level.Liftable> l, JsonArray a) {
        int e = cmp(quoi + " nombre", l.size(), a.size());
        if (e != 0) {
            return e;
        }
        for (int i = 0; i < l.size(); i++) {
            Level.Liftable x = l.get(i);
            JsonObject o = a.get(i).getAsJsonObject();
            e += cmp(quoi + " " + i + " bas", x.bottom(), o.get("bottom").getAsInt());
            e += cmp(quoi + " " + i + " haut", x.top(), o.get("top").getAsInt());
            e += cmp(quoi + " " + i + " vOuv", x.openSpeed(), o.get("openSpeed").getAsInt());
            e += cmp(quoi + " " + i + " vFerm", x.closeSpeed(), o.get("closeSpeed").getAsInt());
            e += cmp(quoi + " " + i + " duree", x.openDuration(),
                    o.get("openDuration").getAsInt());
            e += cmp(quoi + " " + i + " position", x.position(), o.get("position").getAsInt());
            e += cmp(quoi + " " + i + " vitesse", x.speed(), o.get("speed").getAsInt());
            e += cmp(quoi + " " + i + " secteur", x.zone(), o.get("zone").getAsInt());
            // Le portage range les secteurs par IDENTIFIANT et l'original par rang dans la
            // table des pointeurs : s'ils divergeaient, toutes les hauteurs seraient fausses.
            e += cmp(quoi + " " + i + " idSecteur", x.zone(), o.get("zoneId").getAsInt());
            e += cmp(quoi + " " + i + " cond1", x.openBits(), o.get("openBits").getAsInt());
            e += cmp(quoi + " " + i + " cond2", x.closeBits(), o.get("closeBits").getAsInt());
            e += cmp(quoi + " " + i + " murs", x.walls().size(),
                    o.getAsJsonArray("walls").size());
        }
        return e;
    }

    private static int uneScene(String nom, Level data, List<Level.Liftable> portes,
                                List<Level.Liftable> asc, List<Integer> aretes, JsonObject c,
                                int images) {
        LevelSim lvl = LevelSim.of(data);
        Anims an = new Anims(lvl, data);
        an.sfx = null;                                 // le harnais est muet
        an.nav = null;
        an.ambientSfx = null;
        for (int e : aretes) {
            lvl.edgeFlags[e] = 0;
        }

        int arm = c.get("arm").getAsInt();
        int armWho = c.get("armWho").getAsInt();
        boolean armOnce = c.get("armOnce").getAsBoolean();
        int cible = c.get("target").getAsInt();
        int cibleA = c.get("targetLift").getAsInt();
        int locks = c.get("locks").getAsInt();

        JsonArray attendu = c.getAsJsonArray("images");
        int e = 0;
        for (int f = 0; f < images; f++) {
            if (!armOnce || f == 0) {
                armer(lvl, portes, asc, arm, armWho, cible, cibleA);
            }
            an.tempFrames = c.get("tempFrames").getAsInt();
            an.playerSpaceTap = c.get("tap").getAsInt() != 0;
            an.playerZone = c.get("playerZone").getAsInt();
            an.doorLocks = (locks >>> 16) & 0xFFFF;    // mot FORT
            an.liftLocks = locks & 0xFFFF;             // mot faible
            an.run();

            JsonObject r = attendu.get(f).getAsJsonObject();
            String q = nom + "@" + f;
            e += serie(q + " portePos", portes.size(), an::doorPosition,
                    r.getAsJsonArray("doorPos"));
            e += serie(q + " porteVit", portes.size(), an::doorSpeedOf,
                    r.getAsJsonArray("doorSpd"));
            e += serie(q + " porteTemps", portes.size(), an::doorTimerOf,
                    r.getAsJsonArray("doorTimer"));
            e += serie(q + " porteToit", portes.size(),
                    i -> lvl.zones[portes.get(i).zone()].roofH, r.getAsJsonArray("doorRoof"));
            e += serie(q + " ascPos", asc.size(), an::liftPosition,
                    r.getAsJsonArray("liftPos"));
            e += serie(q + " ascVit", asc.size(), an::liftSpeedOf, r.getAsJsonArray("liftSpd"));
            e += serie(q + " ascSol", asc.size(),
                    i -> lvl.zones[asc.get(i).zone()].floorH, r.getAsJsonArray("liftFloor"));
            e += cmp(q + " etatPortes", an.doorState, r.get("state").getAsInt() & 0xFFFF);
            e += cmp(q + " vitesseSol", (short) an.playerFloorSpd, r.get("floorSpd").getAsInt());
            e += cmp(q + " surAscenseur", an.playerStoodOnLift ? 255 : 0,
                    r.get("stood").getAsInt());
            JsonArray fl = r.getAsJsonArray("edgeFlags");
            for (int i = 0; i < aretes.size(); i++) {
                e += cmp(q + " drapeau " + aretes.get(i), (short) lvl.edgeFlags[aretes.get(i)],
                        fl.get(i).getAsInt());
            }
            if (e != 0) {
                return e;                              // une scene fausse l'est pour de bon
            }
        }
        return e;
    }

    private interface Lecture {
        int at(int i);
    }

    private static int serie(String quoi, int n, Lecture f, JsonArray a) {
        int e = cmp(quoi + " nombre", n, a.size());
        if (e != 0) {
            return e;
        }
        for (int i = 0; i < n; i++) {
            e += cmp(quoi + " " + i, f.at(i), a.get(i).getAsInt());
        }
        return e;
    }

    private static void armer(LevelSim lvl, List<Level.Liftable> portes,
                              List<Level.Liftable> asc, int arm, int armWho, int cible,
                              int cibleA) {
        if (armWho == 1 || armWho == 3) {
            for (Level.Liftable d : armWho == 3 ? portes
                    : (portes.isEmpty() ? List.<Level.Liftable>of()
                            : List.of(portes.get(cible)))) {
                for (Level.Liftable.Wall w : d.walls()) {
                    lvl.edgeFlags[w.edge()] = arm;
                }
            }
        }
        if (armWho == 2 || armWho == 3) {
            for (Level.Liftable d : armWho == 3 ? asc
                    : (asc.isEmpty() ? List.<Level.Liftable>of() : List.of(asc.get(cibleA)))) {
                for (Level.Liftable.Wall w : d.walls()) {
                    lvl.edgeFlags[w.edge()] = arm;
                }
            }
        }
    }

    private static int cmp(String quoi, int got, int want) {
        if (got == want) {
            return 0;
        }
        System.out.printf("  %-48s %d != %d%n", quoi, got, want);
        return 1;
    }
}
