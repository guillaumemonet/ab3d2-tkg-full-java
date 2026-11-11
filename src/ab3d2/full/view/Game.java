package ab3d2.full.view;

import ab3d2.full.boot.AppDirs;
import ab3d2.full.boot.Boot;
import ab3d2.full.boot.DiskFetch;
import ab3d2.full.defs.Database;
import ab3d2.full.sim.World;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * LE JEU : une fenetre, un clavier, et {@link Shell} entre les deux.
 *
 * <p>Tout ce qui est le jeu vit ailleurs. Ici il ne reste que ce qui tient a la plateforme :
 * ouvrir une fenetre, lire les touches, et montrer cinquante fois par seconde le tampon
 * d'octets que la coquille vient d'ecrire. Ce decoupage n'est pas une elegance : c'est ce qui
 * permettra de poser le meme jeu sur une surface Android sans y toucher.
 *
 * <p>La boucle bat a <b>cinquante fois par seconde</b> parce que tous les compteurs du jeu sont
 * en images de balayage : une porte met tant d'images a s'ouvrir, un monstre tant d'images a
 * reagir. Accelerer la boucle accelererait le jeu.
 *
 * <pre>
 * gradle -p full game              # le menu, puis le jeu
 * gradle -p full game -Plevel=c    # tout de suite le niveau C, sans passer par le menu
 * </pre>
 */
public final class Game {

    private Game() {
    }

    public static void main(String[] args) {
        // AVANT tout : savoir ou sont les dossiers. Lancee depuis un raccourci, une application
        // installee n'a pas de dossier courant qui veuille dire quelque chose.
        AppDirs.setup();
        if (!amorcer()) {
            return;
        }
        int echelle = Integer.getInteger("ab3d2.scale", 3);
        String niveau = System.getProperty("ab3d2.level", args.length > 0 ? args[0] : null);

        Shell shell = new Shell(new Speaker());
        shell.simpleWalls(Boolean.getBoolean("ab3d2.simple"));
        shell.gouraudFlats(!Boolean.getBoolean("ab3d2.flatshade"));
        shell.music(!Boolean.getBoolean("ab3d2.nomusic"));
        if (!Boolean.getBoolean("ab3d2.mute")) {
            shell.openSound();
        }
        if (niveau != null && !niveau.isBlank()) {
            shell.playNow(Character.toLowerCase(niveau.charAt(0)) - 'a');
        }

        Vue vue = new Vue(shell, echelle);
        JFrame fenetre = new JFrame("Alien Breed 3D II — The Killing Grounds");
        fenetre.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        fenetre.add(vue);
        fenetre.pack();
        fenetre.setLocationRelativeTo(null);
        fenetre.setVisible(true);
        vue.requestFocusInWindow();

        new Timer(20, e -> vue.pas()).start();
    }

    /**
     * Les donnees du jeu, avant tout le reste.
     *
     * <p>Une application qu'on installe ne peut pas supposer qu'un dossier depaque l'attend
     * quelque part. Si les donnees manquent, {@link Boot} propose d'aller chercher les
     * disquettes — et ne fait rien sans un oui.
     *
     * @return vrai si l'on peut jouer
     */
    private static boolean amorcer() {
        Path media = Path.of(System.getProperty("ab3d2.media", "medias/original"));
        Path adf = Path.of(System.getProperty("ab3d2.adf", "adf"));
        Path pret = Boot.ensure(adf, media, SWING);
        if (pret == null) {
            SWING.error("Sans les donnees du jeu, il n'y a rien a jouer."
                    + System.lineSeparator() + System.lineSeparator()
                    + "Placez les cinq fichiers .adf dans :" + System.lineSeparator()
                    + adf.toAbsolutePath());
            return false;
        }
        System.setProperty("ab3d2.media", pret.toString());
        return true;
    }

    /** Un message de plusieurs lignes, sur une seule, pour le journal. */
    private static String aplatir(String message) {
        return message.replace("\r", " ").replace("\n", " ");
    }

    /** Comment cette plateforme-ci pose la question : une boite de dialogue, ou le terminal. */
    private static final DiskFetch.Ask SWING = new DiskFetch.Ask() {
        @Override
        public boolean confirm(String message) {
            if (GraphicsEnvironment.isHeadless()) {
                System.out.println(message);
                System.out.print("Telecharger ? [o/N] ");
                try {
                    int c = System.in.read();
                    return c == 'o' || c == 'O' || c == 'y' || c == 'Y';
                } catch (java.io.IOException e) {
                    return false;
                }
            }
            return JOptionPane.showConfirmDialog(null, message,
                    "Alien Breed 3D II — donnees du jeu",
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE)
                    == JOptionPane.YES_OPTION;
        }

        @Override
        public void info(String message) {
            System.out.println("[disquettes] " + aplatir(message));
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(null, message, "Alien Breed 3D II",
                        JOptionPane.INFORMATION_MESSAGE);
            }
        }

        @Override
        public void error(String message) {
            System.err.println("[disquettes] " + aplatir(message));
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(null, message, "Alien Breed 3D II",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
    };

    /** La fenetre : une image par top, et le clavier qui la conduit. */
    private static final class Vue extends JPanel {

        private final Shell shell;
        private final int echelle;
        private final int largeur;
        private final int haut;
        private final BufferedImage image;
        private final int[] raster;

        private final boolean[] touches = new boolean[256];
        /** Les touches deja vues enfoncees : le menu ne veut que les FRONTS. */
        private final boolean[] vues = new boolean[256];
        private final World.Input entree = new World.Input();

        /** Le dernier caractere frappe, pour l'affectation des commandes. */
        private int frappe;

        private int images;
        private long dernierTop = System.nanoTime();
        private double parSeconde;
        private String titre = "";

        Vue(Shell shell, int echelle) {
            this.shell = shell;
            this.echelle = echelle;
            this.largeur = shell.width();
            // L'image montree fait les DEUX CENT CINQUANTE-SIX lignes de l'ecran : les deux
            // cent trente-deux premieres sont la vue, le reste est le tableau de bord.
            this.haut = shell.height();
            this.image = new BufferedImage(largeur, haut, BufferedImage.TYPE_INT_RGB);
            this.raster = new int[largeur * haut];
            setPreferredSize(new Dimension(largeur * echelle, haut * echelle));
            setBackground(Color.BLACK);
            setFocusable(true);
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    touche(e, true);
                }

                @Override
                public void keyReleased(KeyEvent e) {
                    touche(e, false);
                }
            });
        }

        private void touche(KeyEvent e, boolean enfoncee) {
            int code = e.getKeyCode();
            if (code >= 0 && code < touches.length) {
                touches[code] = enfoncee;
                if (!enfoncee) {
                    vues[code] = false;
                }
            }
            if (enfoncee) {
                char c = e.getKeyChar();
                if (c >= 32 && c < 127) {
                    frappe = Character.toUpperCase(c);
                }
            }
            if (enfoncee && shell.mode() == Shell.Mode.GAME && shell.session() != null
                    && code >= KeyEvent.VK_1 && code <= KeyEvent.VK_9) {
                World monde = shell.session().world();
                monde.gun = code - KeyEvent.VK_1;
                Database.Gun g = monde.glf.gun(monde.gun);
                shell.session().viewer().board().push("arme " + (monde.gun + 1) + " : "
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

        /** Vrai une seule fois par appui : c'est ce que le menu lit. */
        private boolean front(int... codes) {
            for (int c : codes) {
                if (c >= 0 && c < touches.length && touches[c] && !vues[c]) {
                    vues[c] = true;
                    return true;
                }
            }
            return false;
        }

        /** Une image : la coquille avance, puis la fenetre montre ce qu'elle a ecrit. */
        void pas() {
            if (shell.mode() == Shell.Mode.QUIT) {
                System.exit(0);
            }
            lireClavier();

            int santeAvant = sante();
            int lu = frappe;
            frappe = 0;
            shell.frame(entree,
                    front(KeyEvent.VK_UP, KeyEvent.VK_Z, KeyEvent.VK_W),
                    front(KeyEvent.VK_DOWN, KeyEvent.VK_S),
                    front(KeyEvent.VK_ENTER, KeyEvent.VK_SPACE, KeyEvent.VK_CONTROL),
                    front(KeyEvent.VK_ESCAPE),
                    lu);
            if (shell.mode() == Shell.Mode.GAME && sante() < santeAvant
                    && shell.session() != null) {
                shell.session().viewer().board().push("touche : -" + (santeAvant - sante()),
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

        private void lireClavier() {
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
        }

        private int sante() {
            return shell.session() == null ? 0 : shell.session().world().health();
        }

        private void titre() {
            String neuf = shell.mode() == Shell.Mode.MENU
                    ? "menu"
                    : "niveau " + Character.toUpperCase(shell.campaign().letter()) + " — "
                            + shell.campaign().name().trim();
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
            byte[] pixels = shell.pixels();
            for (int i = 0; i < largeur * haut; i++) {
                raster[i] = shell.argb(pixels[i] & 0xFF);
            }
            image.setRGB(0, 0, largeur, haut, raster, 0, largeur);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            g.drawImage(image, 0, 0, largeur * echelle, haut * echelle, null);

            if (shell.mode() == Shell.Mode.GAME && shell.session() != null) {
                g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                g.setColor(new Color(220, 230, 240));
                g.drawString(String.format("secteur %d   sante %d   %d monstres   %.0f img/s",
                        shell.session().world().player.zone, sante(),
                        shell.session().viewer().vivants, parSeconde), 8, 16);
            }
        }
    }
}
