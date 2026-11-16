package ab3d2.full.view;

import ab3d2.full.render.Screen;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * UNE IMAGE DU RECIT qui ouvre un niveau.
 *
 * <p>Le fondu dure huit images : en dessous, la couleur n'est pas encore celle qu'on verra.
 * La prise se fait donc par defaut a la huitieme, la ou le texte a sa teinte definitive —
 * c'est aussi la que le portage prend la sienne, ce qui rend les deux comparables.
 *
 * <pre>
 * gradle -p full storyShot -Plevel=a
 * gradle -p full storyShot -Plevel=c -Pframes=3   # au milieu du fondu
 * </pre>
 */
public final class StoryShot {

    private StoryShot() {
    }

    public static void main(String[] args) throws Exception {
        char lettre = System.getProperty("ab3d2.level", "a").toLowerCase().charAt(0);
        int images = Integer.getInteger("ab3d2.frames", 8);
        int echelle = Integer.getInteger("ab3d2.scale", 1);
        Path sortie = Path.of(System.getProperty("ab3d2.out", "story.png"));

        Story story = new Story();
        if (!story.begin(lettre - 'a')) {
            System.out.println("pas de texte : le fichier TEXT_FILE des disquettes manque");
            return;
        }
        for (int i = 1; i < images; i++) {
            story.frame(false);
        }

        byte[] pixels = story.pixels();
        BufferedImage img = new BufferedImage(Screen.WIDTH * echelle, Screen.HEIGHT * echelle,
                BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < Screen.HEIGHT; y++) {
            for (int x = 0; x < Screen.WIDTH; x++) {
                int argb = story.argb(pixels[y * Screen.WIDTH + x]);
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
        System.out.printf("recit du niveau %c apres %d images -> %s%n",
                lettre, images, sortie.toAbsolutePath());
    }
}
