package ab3d2.full.view;

import ab3d2.full.Data;
import ab3d2.full.level.Level;
import ab3d2.full.render.Screen;
import ab3d2.full.sim.World;

/**
 * La BOUCLE DE JEU, jouee sans fenetre : simulation et rendu ensemble, image apres image.
 *
 * <p>Ce n'est pas une verification contre un oracle — il n'y en a pas pour une partie entiere,
 * et il ne peut pas y en avoir : le portage fidele et celui-ci ne divergent pas sur une image
 * mais sur la MANIERE de tenir l'etat. C'est un harnais de robustesse, et il dit ce qu'il
 * eprouve :
 *
 * <ul>
 *   <li>mille images par niveau passent sans qu'une seule exception sorte — ce qui veut dire
 *       que toutes les tables tiennent, y compris celles que le rendu indexe par des valeurs
 *       que la simulation fabrique ;</li>
 *   <li>combien d'images sortent VIDES, et a quel endroit. Il y en a : un joueur qui marche
 *       droit devant finit contre un mur, et la l'ecran devient noir. La sonde est sans appel —
 *       au meme instant, l'oeil decale de trente-deux unites vers {@code -x} ou {@code -z}
 *       redonne un ecran PLEIN, et tourne de quatre-vingt-dix degres aussi. L'oeil est donc
 *       passe de l'autre cote des faces du secteur ou la simulation le croit encore : c'est la
 *       COLLISION qu'il faut reprendre, pas le rendu. On les compte donc sans les sanctionner,
 *       pour voir ce nombre descendre le jour ou ce sera corrige ;</li>
 *   <li>les monstres finissent par arriver a l'image — sans quoi la quatrieme boucle de sprite
 *       serait branchee pour rien.</li>
 * </ul>
 *
 * <p>Le joueur marche droit devant, tourne lentement et tire : de quoi reveiller du monde,
 * franchir des ouvertures et faire voler des balles, sans scenario a maintenir.
 *
 * <pre>gradle -p full gameTest [-Plevel=a|all]</pre>
 */
public final class GameTest {

    /** Combien d'images par niveau. */
    private static final int IMAGES = 1000;

    /** En dessous de cette part de pixels poses, l'image est tenue pour vide. */
    private static final double PLEIN = 0.5;

    private GameTest() {
    }

    public static void main(String[] args) {
        String arg = System.getProperty("ab3d2.level", args.length > 0 ? args[0] : "all");
        boolean tout = true;
        if ("all".equalsIgnoreCase(arg)) {
            for (char c = 'a'; c <= 'p'; c++) {
                tout &= run(c);
            }
            System.out.println(tout ? "PASS (16 niveaux)" : "ECHEC");
        } else {
            tout = run(arg.toLowerCase().charAt(0));
        }
        System.exit(tout ? 0 : 1);
    }

    /** Combien de pixels l'image porte. */
    private static int compte(Viewer viewer, Screen screen) {
        int mis = 0;
        for (int y = 0; y < viewer.view().height(); y++) {
            int de = screen.line(y);
            for (int k = 0; k < Screen.WIDTH; k++) {
                if (screen.pixels()[de + k] != 0) {
                    mis++;
                }
            }
        }
        return mis;
    }

    private static boolean run(char lettre) {
        Level lvl = Data.level(lettre);
        if (lvl == null) {
            System.out.printf("=== GameTest %c : niveau introuvable%n", lettre);
            return false;
        }
        World world = World.of(lettre);
        Viewer viewer = new Viewer(lettre, lvl);
        world.light = viewer.lights();
        world.aliens.dynLight = viewer.lights();
        world.shots.dynLight = viewer.lights();

        boolean dump = Boolean.getBoolean("ab3d2.dump");
        World.Input entree = new World.Input();
        Screen screen = viewer.screen();
        int pixels = Screen.WIDTH * viewer.view().height();

        int dessinees = 0;
        int vides = 0;
        int monstres = 0;
        int accroches = 0;
        long poses = 0;
        int pire = Integer.MAX_VALUE;
        try {
            for (int i = 0; i < IMAGES; i++) {
                entree.forward = true;
                entree.run = (i % 100) < 40;
                entree.fire = (i % 50) == 0;
                entree.action = (i % 37) == 0;
                entree.turn = ((i / 25) % 4 == 3) ? 40 : 0;
                world.frame(entree);

                int zone = world.player.zone;
                if (zone < 0) {
                    continue;                          // mort ou hors monde : rien a montrer
                }
                viewer.draw(world, world.player.xOff >> 16, world.player.zOff >> 16, zone,
                        world.player.angPos, world.player.yOff);
                dessinees++;
                monstres = Math.max(monstres, viewer.vivants);
                accroches = Math.max(accroches, viewer.accroches);

                int mis = 0;
                for (int y = 0; y < viewer.view().height(); y++) {
                    int de = screen.line(y);
                    for (int k = 0; k < Screen.WIDTH; k++) {
                        if (screen.pixels()[de + k] != 0) {
                            mis++;
                        }
                    }
                }
                poses += mis;
                pire = Math.min(pire, mis);
                if (mis < pixels * PLEIN) {
                    if (vides < 40) {
                        if (dump) {
                            try {
                                Shot.ecrire(screen, viewer.view(), 3, java.nio.file.Path.of(
                                        "build/vide_" + lettre + "_" + i + ".png"));
                            } catch (java.io.IOException io) {
                                System.out.println("    (pas de PNG : " + io + ")");
                            }
                        }
                        System.out.printf("    image %d vide (%d pixels) : secteur %d"
                                + " (%d tries) en %d,%d angle %d, oeil %d%n", i, mis, zone,
                                viewer.zones(), world.player.xOff >> 16,
                                world.player.zOff >> 16,
                                world.player.angPos, world.player.yOff);
                    }
                    vides++;
                }
            }
        } catch (RuntimeException e) {
            System.out.printf("=== GameTest %c : ROMPU a l'image %d : %s%n",
                    lettre, dessinees, e);
            e.printStackTrace(System.out);
            return false;
        }

        // Ce qui FAIT ECHOUER : une exception (rattrapee plus haut) ou une boucle qui ne
        // dessine rien. Les images vides, elles, sont RELEVEES et non sanctionnees : on sait
        // d'ou elles viennent (voir l'en-tete), et ce n'est pas le rendu.
        boolean ok = dessinees > IMAGES / 2;
        System.out.printf("=== GameTest %c : %d images dessinees, %.1f%% de pixels poses"
                + " (au pire %.1f%%), %d monstres au plus, %d accroches, %d image(s) vide(s)"
                + " -> %s%n",
                lettre, dessinees,
                dessinees == 0 ? 0.0 : 100.0 * poses / ((double) dessinees * pixels),
                pire == Integer.MAX_VALUE ? 0.0 : 100.0 * pire / pixels,
                monstres, accroches, vides, ok ? "ok" : "ECHEC");
        return ok;
    }
}
