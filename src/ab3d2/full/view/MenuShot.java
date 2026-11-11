package ab3d2.full.view;

import ab3d2.full.menu.MenuShell;
import ab3d2.full.sim.Campaign;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * UNE IMAGE DU MENU, prise apres un nombre d'images donne.
 *
 * <p>Le menu ne se regarde pas en une image : son feu met une seconde a s'etablir, son texte
 * n'arrive qu'a la trente-cinquieme et son fondu en prend seize. Une capture a l'image zero ne
 * montre rien du tout, ce qui ne veut pas dire que le menu est casse.
 *
 * <pre>
 * gradle -p full menuShot                      # soixante images
 * gradle -p full menuShot -Pframes=200 -Pdown=3  # apres trois crans vers le bas
 * </pre>
 */
public final class MenuShot {

    private MenuShot() {
    }

    public static void main(String[] args) throws Exception {
        int images = Integer.getInteger("ab3d2.frames", 60);
        int bas = Integer.getInteger("ab3d2.down", 0);
        int entrees = Integer.getInteger("ab3d2.enter", 0);
        int echelle = Integer.getInteger("ab3d2.scale", 2);
        Path sortie = Path.of(System.getProperty("ab3d2.out", "menu.png"));

        MenuShell menu = new MenuShell(Campaign.start());
        for (int i = 0; i < images; i++) {
            // Les commandes sont des fronts : on les pose une image, on les retire la suivante.
            boolean descendre = bas > 0 && i > 40 && (i % 4) == 0;
            if (descendre) {
                bas--;
            }
            boolean valider = entrees > 0 && bas == 0 && i > 60 && (i % 8) == 0;
            if (valider) {
                entrees--;
            }
            menu.frame(false, descendre, valider, false, 0);
        }

        byte[] pixels = menu.pixels();
        int largeur = ab3d2.full.menu.MenuFire.WIDTH;
        int haut = ab3d2.full.menu.MenuFire.HEIGHT;
        BufferedImage img = new BufferedImage(largeur * echelle, haut * echelle,
                BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < haut; y++) {
            for (int x = 0; x < largeur; x++) {
                int argb = 0xFF000000
                        | menu.palette().at(pixels[y * largeur + x] & 0xFF, menu.fade());
                for (int dy = 0; dy < echelle; dy++) {
                    for (int dx = 0; dx < echelle; dx++) {
                        img.setRGB(x * echelle + dx, y * echelle + dy, argb);
                    }
                }
            }
        }
        Path parent = sortie.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ImageIO.write(img, "png", sortie.toFile());
        System.out.printf("menu apres %d images -> %s%n", images, sortie.toAbsolutePath());
    }
}
