package ab3d2.full.view;

import ab3d2.full.Data;
import ab3d2.full.level.Level;
import ab3d2.full.level.Palette;
import ab3d2.full.render.Screen;
import ab3d2.full.render.View;
import ab3d2.full.sim.PlayerSim;
import ab3d2.full.sim.World;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Une IMAGE, prise depuis un point du niveau.
 *
 * <p>Tout le travail est dans {@link Viewer} ; il ne reste ici que le choix du point de vue et
 * la conversion en PNG. L'ecran du jeu est un tableau d'octets : un pixel n'est pas une couleur
 * mais un RANG, et il ne devient une image qu'ici, par {@link Palette}.
 *
 * <p>Le point de vue se pose <b>avant</b> de faire tourner le monde. C'est la position du
 * joueur qui reveille les monstres — leur secteur doit figurer dans sa liste de visibilite — et
 * un point de vue pose apres coup les laisserait tous endormis, donc invisibles : leurs octets
 * de dessin sont a zero tant qu'ils n'ont pas pense.
 *
 * <pre>
 * gradle -p full shot                                    # niveau a, la ou le joueur commence
 * gradle -p full shot -Plevel=a -Pzone=55 -Pframes=10 -Pangle=7168
 * </pre>
 */
public final class Shot {

    private Shot() {
    }

    public static void main(String[] args) throws IOException {
        char lettre = System.getProperty("ab3d2.level", "a").toLowerCase().charAt(0);
        Level lvl = Data.level(lettre);
        int secteur = Integer.getInteger("ab3d2.zone", -1);
        int echelle = Integer.getInteger("ab3d2.scale", 3);
        int tours = Integer.getInteger("ab3d2.frames", 0);
        Path sortie = Path.of(System.getProperty("ab3d2.out", "shot.png"));

        // Le point de vue vient de la SIMULATION, et non d'une valeur inventee : c'est elle qui
        // sait ou le joueur commence et a quelle hauteur sont ses yeux — le sol du secteur
        // moins sa taille. Pose a zero, l'oeil serait au ras du sol et l'image s'arreterait net.
        World world = World.of(lettre);
        Viewer viewer = new Viewer(lettre, lvl);
        world.light = viewer.lights();
        world.aliens.dynLight = viewer.lights();
        world.shots.dynLight = viewer.lights();
        if (secteur >= 0) {
            Level.Zone depart = lvl.zones().get(secteur);
            int ou = depart.controlPoint();
            world.player.xOff = lvl.controlPoints().get(ou).x() << 16;
            world.player.zOff = lvl.controlPoints().get(ou).z() << 16;
            world.player.zone = secteur;
            world.player.yOff = depart.floor() - PlayerSim.PLR_STAND_HEIGHT;
        }
        for (int t = 0; t < tours; t++) {
            world.frame(new World.Input());
        }

        int x = world.player.xOff >> 16;
        int z = world.player.zOff >> 16;
        int zone = world.player.zone;
        int angle = Integer.getInteger("ab3d2.angle", world.player.angPos);
        int hauteur = Integer.getInteger("ab3d2.eye", world.player.yOff);
        if (secteur >= 0) {
            zone = secteur;
            Level.Zone zz = lvl.zones().get(zone);
            int cp = zz.controlPoint();
            x = lvl.controlPoints().get(cp).x();
            z = lvl.controlPoints().get(cp).z();
            hauteur = Integer.getInteger("ab3d2.eye", zz.floor() - PlayerSim.PLR_STAND_HEIGHT);
        }

        // Aucune image jouee : l'eclairage n'a jamais ete calcule, il faut le faire une fois.
        if (tours == 0) {
            viewer.lights().frame(zone);
        }
        viewer.draw(world, x, z, zone, angle, hauteur);

        System.out.printf("niveau %c, secteur %d (%d, %d), angle %d, oeil %d%n",
                lettre, zone, x, z, angle, hauteur);
        System.out.printf("  %d choses animees, %d monstres vivants (%d qui pensent,"
                + " %d graphiques accroches)%n",
                viewer.animees, viewer.vivants, viewer.pensants, viewer.accroches);
        System.out.println("  " + viewer.tally());

        ecrire(viewer.screen(), viewer.view(), echelle, sortie);
        System.out.println("  -> " + sortie.toAbsolutePath());
    }

    /** Du tampon d'octets a un PNG, chaque octet passant par la palette du jeu. */
    static void ecrire(Screen screen, View view, int echelle, Path sortie) throws IOException {
        Palette palette = Data.palette();
        int largeur = View.FULL_WIDTH;
        int haut = view.height();
        BufferedImage image = new BufferedImage(largeur * echelle, haut * echelle,
                BufferedImage.TYPE_INT_RGB);
        byte[] pixels = screen.pixels();
        for (int y = 0; y < haut; y++) {
            for (int k = 0; k < largeur; k++) {
                int couleur = palette.at(pixels[screen.line(y) + k] & 0xFF);
                for (int dy = 0; dy < echelle; dy++) {
                    for (int dx = 0; dx < echelle; dx++) {
                        image.setRGB(k * echelle + dx, y * echelle + dy, couleur);
                    }
                }
            }
        }
        Path parent = sortie.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ImageIO.write(image, "png", sortie.toFile());
    }
}
