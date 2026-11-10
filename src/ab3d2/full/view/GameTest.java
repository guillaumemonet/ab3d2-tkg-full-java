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
 *   <li>combien d'images sortent MAIGRES (moins de la moitie des pixels poses) et combien
 *       sortent tout a fait NOIRES. Ce sont ces deux nombres qui ont debusque le pan de mur
 *       jete au lieu d'etre peint : mille quarante-six images maigres sur seize mille, et
 *       l'ecran noir des qu'on se collait a un mur. Il en reste deux cent vingt-huit maigres
 *       et UNE noire, et celle-la est FIDELE — voir ci-dessous ;</li>
 *   <li>les monstres finissent par arriver a l'image — sans quoi la quatrieme boucle de sprite
 *       serait branchee pour rien.</li>
 * </ul>
 *
 * <p>Le joueur marche droit devant, tourne lentement et tire : de quoi reveiller du monde,
 * franchir des ouvertures et faire voler des balles, sans scenario a maintenir.
 *
 * <h2>L'IMAGE NOIRE, et pourquoi il n'y a rien a corriger</h2>
 *
 * <p>Il en reste une : <b>niveau n, image 904</b>, secteur 11, en {@code -1122,1284}, angle
 * 232, oeil {@code -75978}. Le secteur 11 est une EMBRASURE de quarante-huit unites de cote, et
 * le joueur s'y tient a quelques dizaines d'unites du PLAN de ses deux pans. La mise en scene
 * n'y retient que ce seul secteur : cinq commandes, deux pans, le sol, le plafond, les objets.
 *
 * <p>Les deux pans tombent alors tout entiers HORS de l'ecran. Leurs points, une fois tournes,
 * ont des profondeurs de 52, 14, -6 et 32 pour des ecarts lateraux de dix-neuf et vingt mille :
 * le premier pan se projette sur les colonnes 526 a 1624, le second sur 0 a 0, et l'ecran va de
 * zero a trois cent vingt. Sans colonne de pan, pas de silhouette, donc ni sol ni plafond. A
 * l'image suivante le second pan va meme de la colonne zero a la colonne moins trente-trois —
 * sa borne droite est a gauche de sa borne gauche.
 *
 * <p><b>Le jeu d'origine fait exactement la meme chose.</b> Les deux maillons qui auraient pu
 * diverger ont ete eprouves a CETTE camera-la, par deux poses nommees ajoutees aux oracles :
 * la mise en scene ({@code zoneGraphCheck}) donne les memes cinq commandes et les memes
 * marques, et la projection ({@code xformCheck}) donne les memes abscisses d'ecran — 0, 0,
 * 1624 et 526. L'image est noire chez Team17 aussi. Il n'y a rien a corriger ; il fallait le
 * prouver, parce qu'une image noire est exactement ce qu'un rendu fautif produit.
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
        // Les lumieres dynamiques se posent PENDANT l'image de simulation, avant le dessin :
        // leurs hauteurs de secteur doivent donc venir de la simulation, pas du fichier.
        viewer.lights().heights(Viewer.hauteurs(world));

        boolean dump = Boolean.getBoolean("ab3d2.dump");
        if (Boolean.getBoolean("ab3d2.wav")) {
            viewer.recordSound(java.nio.file.Path.of("build/son_" + lettre + ".wav"));
        }
        World.Input entree = new World.Input();
        Screen screen = viewer.screen();
        int pixels = Screen.WIDTH * viewer.view().height();

        int dessinees = 0;
        int vides = 0;
        int noirs = 0;
        int monstres = 0;
        int accroches = 0;
        int degrades = 0;
        int armes = 0;
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
                degrades += viewer.gouraudWalls();

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

                // L'habillage se pose APRES le compte : le bandeau remplit toujours ses
                // vingt-quatre lignes et gonflerait le remplissage sans rien dire du rendu. On
                // l'eprouve quand meme — une exception ici fait echouer le niveau.
                viewer.sound(world, world.player.xOff >> 16,
                        world.player.zOff >> 16, world.player.angPos);
                viewer.overlay(world, i * 20L);
                armes = Math.max(armes, viewer.armeVue ? 1 : 0);
                if (mis == 0) {
                    noirs++;
                }
                if (mis < pixels * PLEIN) {
                    if (vides < 40) {
                        if (dump) {
                            try {
                                Shot.ecrire(screen, Screen.HEIGHT, 3, java.nio.file.Path.of(
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
        viewer.closeSound();                           // ecrit le fichier d'onde
        boolean ok = dessinees > IMAGES / 2;
        System.out.printf("=== GameTest %c : %d images dessinees, %.1f%% de pixels poses"
                + " (au pire %.1f%%), %d monstres au plus, %d accroches, %d pans en degrade,"
                + " arme %s, %d bruit(s), %d maigre(s) dont %d noire(s) -> %s%n",
                lettre, dessinees,
                dessinees == 0 ? 0.0 : 100.0 * poses / ((double) dessinees * pixels),
                pire == Integer.MAX_VALUE ? 0.0 : 100.0 * pire / pixels,
                monstres, accroches, degrades, armes != 0 ? "vue" : "ABSENTE",
                viewer.bruits, vides, noirs,
                ok ? "ok" : "ECHEC");
        return ok;
    }
}
