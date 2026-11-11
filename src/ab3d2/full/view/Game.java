package ab3d2.full.view;

import ab3d2.full.Data;
import ab3d2.full.defs.Database;
import ab3d2.full.level.Palette;
import ab3d2.full.sim.Campaign;
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

/**
 * LE JEU : une fenetre, un clavier, et {@link Session} entre les deux.
 *
 * <p>Tout ce qui est le jeu vit ailleurs. Ici il ne reste que ce qui tient a la plateforme :
 * ouvrir une fenetre, lire les touches, et montrer cinquante fois par seconde le tampon
 * d'octets que la session vient d'ecrire. Ce decoupage n'est pas une elegance : c'est ce qui
 * permettra de poser la meme partie sur une surface Android sans toucher au jeu.
 *
 * <p>La boucle bat a <b>cinquante fois par seconde</b> parce que tous les compteurs du jeu sont
 * en images de balayage : une porte met tant d'images a s'ouvrir, un monstre tant d'images a
 * reagir. Accelerer la boucle accelererait le jeu.
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

        Session session = new Session(lettre - 'a');
        session.simpleWalls(Boolean.getBoolean("ab3d2.simple"));
        session.gouraudFlats(!Boolean.getBoolean("ab3d2.flatshade"));
        session.showWeapon(!Boolean.getBoolean("ab3d2.nogun"));
        session.playMusic(!Boolean.getBoolean("ab3d2.nomusic"));
        if (!Boolean.getBoolean("ab3d2.mute") && !session.openSound()) {
            System.out.println("son indisponible : le jeu sera muet");
        }

        Vue vue = new Vue(session, echelle);
        JFrame fenetre = new JFrame("Alien Breed 3D II — The Killing Grounds");
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

        private final Session session;
        private final int echelle;
        private final int largeur;
        private final int haut;
        private final BufferedImage image;
        private final int[] raster;
        private final Palette palette = Data.palette();

        private final boolean[] touches = new boolean[256];
        private final World.Input entree = new World.Input();

        private int images;
        private long dernierTop = System.nanoTime();
        private double parSeconde;
        private String titre = "";

        Vue(Session session, int echelle) {
            this.session = session;
            this.echelle = echelle;
            this.largeur = session.width();
            // L'image montree fait les DEUX CENT CINQUANTE-SIX lignes de l'ecran : les deux
            // cent trente-deux premieres sont la vue, le reste est le tableau de bord.
            this.haut = session.height();
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
            session.viewer().board().push(
                    "fleches ou ZQSD : marcher, A/E : tourner, MAJ : pas de cote",
                    MessageBoard.OPTIONS);
            session.viewer().board().push(
                    "ESPACE : tirer, F : actionner, 1-9 : arme, C : s'accroupir",
                    MessageBoard.OPTIONS);
        }

        private void touche(int code, boolean enfoncee) {
            if (code >= 0 && code < touches.length) {
                touches[code] = enfoncee;
            }
            if (enfoncee && code >= KeyEvent.VK_1 && code <= KeyEvent.VK_9) {
                World monde = session.world();
                monde.gun = code - KeyEvent.VK_1;
                Database.Gun g = monde.glf.gun(monde.gun);
                session.viewer().board().push("arme " + (monde.gun + 1) + " : "
                        + (g == null ? "?" : g.name()), MessageBoard.OPTIONS);
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

        /** Une image : la session avance, puis la fenetre montre ce qu'elle a ecrit. */
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

            int santeAvant = session.world().health();
            session.frame(entree);
            if (session.phase() == Session.Phase.PLAYING
                    && session.world().health() < santeAvant) {
                session.viewer().board().push(
                        "touche : -" + (santeAvant - session.world().health()),
                        MessageBoard.OTHER);
            }
            convertir();

            images++;
            long maintenant = System.nanoTime();
            if (maintenant - dernierTop >= 1_000_000_000L) {
                parSeconde = images * 1e9 / (maintenant - dernierTop);
                images = 0;
                dernierTop = maintenant;
            }
            titre();
            repaint();
        }

        private void titre() {
            Campaign c = session.campaign();
            String etat = session.phase() == Session.Phase.ENDING
                    ? (session.outcome() == Campaign.Outcome.WON ? " — TERMINE" : " — MORT")
                    : "";
            String neuf = "niveau " + Character.toUpperCase(c.letter()) + " — "
                    + c.name().trim() + etat;
            if (!neuf.equals(titre)) {
                titre = neuf;
                java.awt.Window w = javax.swing.SwingUtilities.getWindowAncestor(this);
                if (w instanceof JFrame f) {
                    f.setTitle("Alien Breed 3D II — The Killing Grounds — " + neuf);
                }
            }
        }

        /** Du tampon d'octets a des couleurs : un octet du jeu est un RANG, pas une teinte. */
        private void convertir() {
            byte[] pixels = session.pixels();
            for (int i = 0; i < largeur * haut; i++) {
                raster[i] = palette.at(pixels[i] & 0xFF);
            }
            image.setRGB(0, 0, largeur, haut, raster, 0, largeur);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            g.drawImage(image, 0, 0, largeur * echelle, haut * echelle, null);

            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            g.setColor(new Color(220, 230, 240));
            g.drawString(String.format("secteur %d   sante %d   %d monstres   %.0f img/s",
                    session.world().player.zone, session.world().health(),
                    session.viewer().vivants, parSeconde), 8, 16);
        }
    }
}
