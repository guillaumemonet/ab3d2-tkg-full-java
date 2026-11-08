package ab3d2.full.view;

import ab3d2.full.Data;
import ab3d2.full.defs.Database;
import ab3d2.full.level.Level;
import ab3d2.full.level.Palette;
import ab3d2.full.render.Screen;
import ab3d2.full.render.View;
import ab3d2.full.sim.World;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * LE JEU : la simulation et le rendu, ensemble, cinquante fois par seconde.
 *
 * <p>Chaque piece avait ete eprouvee seule contre un releve du portage fidele, puis
 * {@link Shot} les avait fait tourner ensemble le temps d'UNE image. Ici elles tournent sans
 * s'arreter : on marche, on tourne, on ouvre les portes, on tire, et les monstres viennent.
 *
 * <p>La boucle bat a <b>cinquante fois par seconde</b> parce que tous les compteurs du jeu sont
 * en images de balayage : une porte met tant d'images a s'ouvrir, un monstre tant d'images a
 * reagir. Accelerer la boucle accelererait le jeu, et {@link World} compte en images, pas en
 * secondes.
 *
 * <p>L'ordre d'une image n'est pas libre. {@link World#frame} refait les luminosites APRES le
 * joueur et AVANT l'IA — sans quoi les torches et les tirs seraient effaces juste apres avoir
 * ecrit — puis {@link Viewer#draw} dessine ce que la simulation vient d'arreter.
 *
 * <pre>gradle -p full game [-Plevel=c] [-Pscale=3]</pre>
 */
public final class Game {

    private Game() {
    }

    public static void main(String[] args) {
        char lettre = System.getProperty("ab3d2.level", args.length > 0 ? args[0] : "a")
                .toLowerCase().charAt(0);
        int echelle = Integer.getInteger("ab3d2.scale", 3);
        Level lvl = Data.level(lettre);

        World world = World.of(lettre);
        Viewer viewer = new Viewer(lettre, lvl);
        // L'eclairage est le MEME objet des deux cotes : la simulation y ecrit (torches, tirs),
        // le peintre y lit. C'est le seul point ou les deux moities du jeu se touchent.
        world.light = viewer.lights();
        world.aliens.dynLight = viewer.lights();
        world.shots.dynLight = viewer.lights();

        Vue vue = new Vue(world, viewer, echelle);
        JFrame fenetre = new JFrame("Alien Breed 3D II — The Killing Grounds — niveau "
                + Character.toUpperCase(lettre));
        fenetre.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        fenetre.add(vue);
        fenetre.pack();
        fenetre.setLocationRelativeTo(null);
        fenetre.setVisible(true);
        vue.requestFocusInWindow();

        new Timer(20, e -> vue.pas()).start();
    }

    /** La fenetre : une image de jeu par top, et le clavier qui la conduit. */
    private static final class Vue extends JPanel {

        private final World world;
        private final Viewer viewer;
        private final int echelle;
        private final int largeur = View.FULL_WIDTH;
        private final int haut;
        private final BufferedImage image;
        private final int[] raster;
        private final Palette palette = Data.palette();

        private final boolean[] touches = new boolean[256];
        private final World.Input entree = new World.Input();
        private final Deque<String> journal = new ArrayDeque<>();

        private int images;
        private long dernierTop = System.nanoTime();
        private double parSeconde;

        Vue(World world, Viewer viewer, int echelle) {
            this.world = world;
            this.viewer = viewer;
            this.echelle = echelle;
            this.haut = viewer.view().height();
            this.image = new BufferedImage(largeur, haut, BufferedImage.TYPE_INT_RGB);
            this.raster = new int[largeur * haut];
            setPreferredSize(new Dimension(largeur * echelle, haut * echelle));
            setBackground(Color.BLACK);
            setFocusable(true);
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    touche(e.getKeyCode(), true);
                }

                @Override
                public void keyReleased(KeyEvent e) {
                    touche(e.getKeyCode(), false);
                }
            });
            dire("fleches ou ZQSD : marcher — A/E ou fleches : tourner — MAJ : pas de cote");
            dire("ESPACE : tirer — F : actionner — 1-9 : arme — C : s'accroupir");
        }

        private void touche(int code, boolean enfoncee) {
            if (code >= 0 && code < touches.length) {
                touches[code] = enfoncee;
            }
            if (enfoncee && code >= KeyEvent.VK_1 && code <= KeyEvent.VK_9) {
                world.gun = code - KeyEvent.VK_1;
                Database.Gun g = world.glf.gun(world.gun);
                dire("arme " + (world.gun + 1) + " : " + (g == null ? "?" : g.name()));
            }
        }

        private boolean on(int... codes) {
            for (int c : codes) {
                if (c >= 0 && c < touches.length && touches[c]) {
                    return true;
                }
            }
            return false;
        }

        /** Une image : la simulation avance, puis le rendu la montre. */
        void pas() {
            entree.forward = on(KeyEvent.VK_UP, KeyEvent.VK_Z, KeyEvent.VK_W);
            entree.backward = on(KeyEvent.VK_DOWN, KeyEvent.VK_S);
            entree.stepLeft = on(KeyEvent.VK_Q, KeyEvent.VK_A) && on(KeyEvent.VK_SHIFT);
            entree.stepRight = on(KeyEvent.VK_D, KeyEvent.VK_E) && on(KeyEvent.VK_SHIFT);
            entree.run = on(KeyEvent.VK_CONTROL);
            entree.jump = on(KeyEvent.VK_SPACE) && on(KeyEvent.VK_ALT);
            entree.duck = on(KeyEvent.VK_C);
            entree.fire = on(KeyEvent.VK_SPACE) && !on(KeyEvent.VK_ALT);
            entree.action = on(KeyEvent.VK_F);
            entree.turn = 0;
            if (!on(KeyEvent.VK_SHIFT)) {
                if (on(KeyEvent.VK_LEFT, KeyEvent.VK_Q, KeyEvent.VK_A)) {
                    entree.turn -= 60;
                }
                if (on(KeyEvent.VK_RIGHT, KeyEvent.VK_D, KeyEvent.VK_E)) {
                    entree.turn += 60;
                }
            }

            int santeAvant = world.health();
            world.frame(entree);
            for (String m : world.messages.drain()) {
                dire(m);
            }
            if (world.health() < santeAvant) {
                dire("touche : -" + (santeAvant - world.health())
                        + " (sante " + world.health() + ")");
            }

            int zone = world.player.zone;
            if (zone >= 0) {
                viewer.draw(world, world.player.xOff >> 16, world.player.zOff >> 16, zone,
                        world.player.angPos, world.player.yOff);
                convertir();
            }

            images++;
            long maintenant = System.nanoTime();
            if (maintenant - dernierTop >= 1_000_000_000L) {
                parSeconde = images * 1e9 / (maintenant - dernierTop);
                images = 0;
                dernierTop = maintenant;
            }
            repaint();
        }

        /** Du tampon d'octets a des couleurs : un octet du jeu est un RANG, pas une teinte. */
        private void convertir() {
            Screen screen = viewer.screen();
            byte[] pixels = screen.pixels();
            for (int y = 0; y < haut; y++) {
                int de = screen.line(y);
                int vers = y * largeur;
                for (int k = 0; k < largeur; k++) {
                    raster[vers + k] = palette.at(pixels[de + k] & 0xFF);
                }
            }
            image.setRGB(0, 0, largeur, haut, raster, 0, largeur);
        }

        private void dire(String texte) {
            journal.addFirst(texte);
            while (journal.size() > 6) {
                journal.removeLast();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            g.drawImage(image, 0, 0, largeur * echelle, haut * echelle, null);

            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            g.setColor(new Color(220, 230, 240));
            g.drawString(String.format("secteur %d   sante %d   %d monstres   %.0f img/s",
                    world.player.zone, world.health(), viewer.vivants, parSeconde), 8, 16);
            int y = haut * echelle - 8;
            for (String ligne : journal) {
                g.drawString(ligne, 8, y);
                y -= 14;
            }
        }
    }
}
