package ab3d2.full.android;

import ab3d2.full.boot.Boot;
import ab3d2.full.boot.DiskFetch;
import ab3d2.full.defs.Database;
import ab3d2.full.sim.World;
import ab3d2.full.view.Shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

import java.nio.file.Path;

/**
 * LE JEU SUR UN TELEPHONE : une surface, des doigts, et {@link Shell} entre les deux.
 *
 * <p>Cette classe est le pendant exact de {@code view.Game}, la fenetre de bureau. Elle fait
 * les memes trois choses — ouvrir une surface, lire les commandes, montrer cinquante fois par
 * seconde le tampon d'octets que la coquille vient d'ecrire — et elle ne contient pas une
 * ligne de jeu. Tout le jeu est dans le code commun, celui-la meme qui tourne sur l'ordinateur
 * et qui a ete verifie contre le portage litteral.
 *
 * <p>C'est le but du decoupage, et sa preuve : un moteur de 1996 ecrit pour un 68000 tourne ici
 * sans qu'une seule regle du jeu ait ete reecrite pour l'occasion.
 *
 * <h2>Trois choses que le telephone impose</h2>
 *
 * <ul>
 *   <li><b>ou ecrire.</b> Il n'y a pas de dossier courant : les donnees vont dans le dossier
 *       prive de l'application, que le systeme efface avec elle ;</li>
 *   <li><b>l'image.</b> L'ecran du jeu fait trois cent vingt sur deux cent cinquante-six, et le
 *       telephone est bien plus grand. On l'etire en gardant ses PROPORTIONS et sans
 *       adoucissement : un pixel de 1996 doit rester un carre, pas devenir une tache ;</li>
 *   <li><b>le fil.</b> La boucle de jeu ne peut pas tourner sur le fil de l'interface, qui doit
 *       rester libre de repondre aux doigts. Elle a donc le sien, et c'est lui qui bat a
 *       cinquante.</li>
 * </ul>
 */
public final class GameActivity extends Activity implements SurfaceHolder.Callback {

    /** La periode d'une image, en millisecondes : le jeu compte en images de balayage. */
    private static final long PERIODE_MS = 20;

    private SurfaceView surface;
    private Pad pad;
    private Shell shell;
    private Track son;

    private Thread boucle;
    private volatile boolean tourne;
    private volatile boolean pret;

    private Bitmap image;
    private int[] raster;
    private final Rect source = new Rect();
    private final Rect cible = new Rect();
    private final Paint peinture = new Paint();
    private final World.Input entree = new World.Input();

    private volatile String message = "Preparation...";

    @Override
    protected void onCreate(Bundle etat) {
        super.onCreate(etat);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        plongee();

        // Les trois dossiers, dans l'espace prive de l'application.
        Path racine = getFilesDir().toPath();
        System.setProperty("ab3d2.media",
                racine.resolve("medias").resolve("original").toString());
        System.setProperty("ab3d2.adf", racine.resolve("adf").toString());
        System.setProperty("ab3d2.save", racine.resolve("run").toString());

        pad = new Pad();
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        surface.setOnTouchListener((v, e) -> {
            pad.touch(e);
            return true;
        });
        setContentView(surface);

        // Un pixel de 1996 reste un carre : pas d'adoucissement.
        peinture.setFilterBitmap(false);
        peinture.setAntiAlias(false);
        peinture.setDither(false);

        image = Bitmap.createBitmap(320, 256, Bitmap.Config.ARGB_8888);
        raster = new int[320 * 256];
        source.set(0, 0, 320, 256);

        new Thread(this::amorcer, "amorcage").start();
    }

    /**
     * PLEIN ECRAN, pour de bon.
     *
     * <p>Un theme « sans titre, plein ecran » ne suffit plus : il retire la barre d'etat et
     * laisse celle de NAVIGATION, qui mange un bon centimetre sur le cote — precisement la ou
     * le pouce droit cherche le bouton de tir, et qu'il touche donc par megarde.
     *
     * <p>Deux facons de la cacher selon l'age du systeme, et la meme intention : les barres
     * reviennent d'un glissement depuis le bord, puis repartent d'elles-memes.
     */
    private void plongee() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(android.view.WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(android.view.WindowInsetsController
                        .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    /** Les barres reviennent des qu'on quitte et revient : on replonge. */
    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) {
            plongee();
        }
    }

    /**
     * Les donnees, avant le jeu.
     *
     * <p>Le telechargement et le depackage prennent plusieurs secondes : ils ne peuvent pas
     * avoir lieu sur le fil de l'interface, que le systeme tuerait. D'ou ce fil a part, et la
     * question posee en revenant sur celui de l'interface — une boite de dialogue n'existe que
     * la.
     */
    private void amorcer() {
        Path media = Path.of(System.getProperty("ab3d2.media"));
        Path adf = Path.of(System.getProperty("ab3d2.adf"));
        message = "Donnees du jeu...";
        Path pretes = Boot.ensure(adf, media, new Demande());
        if (pretes == null) {
            message = "Sans les cinq disquettes (.adf), il n'y a rien a jouer.";
            return;
        }
        message = "Chargement...";
        son = new Track();
        Shell s = new Shell(son);
        s.music(true);
        s.openSound();
        shell = s;
        pret = true;
        demarrer();
    }

    /** Comment un telephone pose la question. */
    private final class Demande implements DiskFetch.Ask {

        @Override
        public boolean confirm(String texte) {
            final boolean[] reponse = {false};
            final Object verrou = new Object();
            runOnUiThread(() -> new AlertDialog.Builder(GameActivity.this)
                    .setTitle("Donnees du jeu")
                    .setMessage(texte)
                    .setCancelable(false)
                    .setPositiveButton("Telecharger", (d, w) -> rendre(verrou, reponse, true))
                    .setNegativeButton("Non", (d, w) -> rendre(verrou, reponse, false))
                    .show());
            synchronized (verrou) {
                while (!attendu) {
                    try {
                        verrou.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
            return reponse[0];
        }

        @Override
        public void info(String texte) {
            message = texte;
        }

        @Override
        public void error(String texte) {
            message = texte;
        }
    }

    private volatile boolean attendu;

    private void rendre(Object verrou, boolean[] reponse, boolean valeur) {
        synchronized (verrou) {
            reponse[0] = valeur;
            attendu = true;
            verrou.notifyAll();
        }
    }

    private void demarrer() {
        if (boucle != null || !pret) {
            return;
        }
        tourne = true;
        boucle = new Thread(this::jouer, "jeu");
        boucle.start();
    }

    /** La boucle : une image toutes les vingt millisecondes, et l'on dort ce qui reste. */
    private void jouer() {
        long prochaine = System.nanoTime();
        while (tourne) {
            pad.lire(entree);
            int arme = pad.armeDemandee();
            if (arme >= 0 && shell.session() != null) {
                shell.session().world().gun = arme;
            }
            // Dans le menu, le stick choisit et le bouton de tir valide.
            boolean haut = entree.forward && !precedentHaut;
            boolean bas = entree.backward && !precedentBas;
            boolean ok = entree.fire && !precedentFeu;
            precedentHaut = entree.forward;
            precedentBas = entree.backward;
            precedentFeu = entree.fire;

            shell.frame(entree, haut, bas, ok, false, 0);
            dessiner();

            prochaine += PERIODE_MS * 1_000_000L;
            long reste = prochaine - System.nanoTime();
            if (reste > 0) {
                try {
                    Thread.sleep(reste / 1_000_000L, (int) (reste % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else {
                prochaine = System.nanoTime();         // on a pris du retard : on repart d'ici
            }
        }
    }

    private boolean precedentHaut;
    private boolean precedentBas;
    private boolean precedentFeu;

    private void dessiner() {
        SurfaceHolder holder = surface.getHolder();
        Canvas c = holder.lockCanvas();
        if (c == null) {
            return;
        }
        try {
            byte[] pixels = shell.pixels();
            for (int i = 0; i < raster.length; i++) {
                raster[i] = shell.argb(pixels[i] & 0xFF);
            }
            image.setPixels(raster, 0, 320, 0, 0, 320, 256);
            c.drawColor(Color.BLACK);
            c.drawBitmap(image, source, cible, peinture);
            pad.draw(c);
        } finally {
            holder.unlockCanvasAndPost(c);
        }
    }

    /** L'image garde ses proportions : on la centre, on ne la deforme pas. */
    private void cadrer(int largeur, int hauteur) {
        float echelle = Math.min(largeur / 320f, hauteur / 256f);
        int w = Math.round(320 * echelle);
        int h = Math.round(256 * echelle);
        int x = (largeur - w) / 2;
        int y = (hauteur - h) / 2;
        cible.set(x, y, x + w, y + h);
        // Les commandes ont besoin de savoir ou l'image s'arrete : elles vivent a cote, pas
        // dessus.
        pad.taille(largeur, hauteur, x, x + w);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        demarrer();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int largeur, int hauteur) {
        cadrer(largeur, hauteur);
        if (!pret) {
            attendre(holder);
        }
    }

    /** Tant que les donnees ne sont pas la, on ecrit ou l'on en est. */
    private void attendre(SurfaceHolder holder) {
        Canvas c = holder.lockCanvas();
        if (c == null) {
            return;
        }
        try {
            c.drawColor(Color.BLACK);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.rgb(140, 220, 160));
            p.setTextSize(c.getHeight() * 0.04f);
            c.drawText(message, c.getWidth() * 0.06f, c.getHeight() * 0.5f, p);
        } finally {
            holder.unlockCanvasAndPost(c);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        tourne = false;
        Thread t = boucle;
        boucle = null;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    protected void onDestroy() {
        tourne = false;
        if (son != null) {
            son.close();
        }
        super.onDestroy();
    }

    /** Le bouton RETOUR recule dans les menus, comme la touche d'echappement. */
    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK && shell != null
                && shell.mode() == Shell.Mode.GAME) {
            return true;                               // en jeu, on ne quitte pas par megarde
        }
        return super.onKeyDown(code, e);
    }
}
