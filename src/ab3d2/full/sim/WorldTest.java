package ab3d2.full.sim;

/**
 * Fait tourner la boucle de jeu entiere, sans fenetre, sur les seize niveaux.
 *
 * <p>Les trois autres harnais eprouvent chacun sa piece — le deplacement, l'IA, le tir. Celui-ci
 * les fait jouer ENSEMBLE, dans l'ordre de la boucle d'origine, ce qu'aucun des trois ne fait.
 * C'est la que se verraient les fautes d'assemblage : un monstre qui riposte avant d'avoir ete
 * touche, une porte qui bouge au mauvais moment, un etat partage qu'une piece ecrase.
 *
 * <p>Le joueur y est conduit par un script simple mais remuant : il avance, tourne par a-coups,
 * tire, actionne ce qu'il croise. On ne verifie pas qu'il va quelque part — on verifie que mille
 * images de jeu complet se deroulent sans rien casser, et on releve ce qui a bouge.
 *
 * <pre>gradle worldTest [-Plevel=a]</pre>
 */
public final class WorldTest {

    private static final int IMAGES = 1000;

    private WorldTest() {
    }

    public static void main(String[] args) {
        String arg = System.getProperty("ab3d2.level", args.length > 0 ? args[0] : "all");
        boolean ok = true;
        if ("all".equalsIgnoreCase(arg)) {
            for (char c = 'a'; c <= 'p'; c++) {
                ok &= run(c);
            }
            System.out.println(ok ? "\nPASS (16 niveaux)" : "\nECHEC");
        } else {
            ok = run(Character.toLowerCase(arg.charAt(0)));
        }
        System.exit(ok ? 0 : 2);
    }

    private static boolean run(char letter) {
        World w;
        try {
            w = World.of(letter);
        } catch (RuntimeException e) {
            System.out.printf("  %c : montage impossible — %s%n", letter, e);
            return false;
        }

        int zoneDepart = w.player.zone;
        int santeDepart = w.health();
        int verrousDepart = w.anims.doorLocks;
        java.util.Set<Integer> secteursVus = new java.util.TreeSet<>();
        int tirs = 0;
        int ramasses = 0;
        int messages = 0;

        World.Input in = new World.Input();
        try {
            for (int f = 0; f < IMAGES; f++) {
                // Un trajet volontairement agite : on avance toujours, on tourne par a-coups
                // pour longer les murs, on tire regulierement et on actionne sans relache.
                in.forward = true;
                in.run = (f / 100) % 2 == 0;
                in.turn = ((f / 25) % 4 == 0) ? 40 : 0;
                in.fire = f % 30 < 3;
                in.action = f % 17 == 0;
                in.aim = 0;

                int avant = w.shots.alienShotsActive();
                w.frame(in);
                if (w.shots.alienShotsActive() > avant) {
                    tirs++;
                }
                secteursVus.add(w.player.zone);
                ramasses += w.objects.collected().size();
                messages += w.messages.drain().size();
                if (w.dead()) {
                    break;                             // mourir n'est pas un defaut
                }
            }
        } catch (RuntimeException e) {
            System.out.printf("  %c : image interrompue — %s%n", letter, e);
            e.printStackTrace(System.out);
            return false;
        }

        int vivants = 0;
        for (Aliens.Alien a : w.aliens.aliens()) {
            if (a.zone >= 0 && (a.hitPoints & 0xFF) != 0) {
                vivants++;
            }
        }
        System.out.printf("  %c : %4d images, secteur %3d -> %3d (%2d visites), sante %4d -> %4d,"
                + " %2d objets pris, %2d textes, %3d ripostes, %3d monstres debout,"
                + " verrous 0x%04X -> 0x%04X%n",
                letter, w.frame, zoneDepart, w.player.zone, secteursVus.size(),
                santeDepart, w.health(), ramasses, messages, tirs, vivants,
                verrousDepart, w.anims.doorLocks);
        return true;
    }
}
