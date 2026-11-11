package ab3d2.full.boot;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * OU SONT LES DOSSIERS, quand le jeu n'est plus lance depuis ses sources.
 *
 * <p>Depuis Gradle, tout est relatif au dossier courant et cela va de soi. Une application
 * installee, elle, peut etre lancee de n'importe ou — depuis un raccourci, depuis une autre
 * partition — et le dossier courant ne veut alors plus rien dire. Pire : il peut n'etre pas
 * inscriptible.
 *
 * <p>Trois regles, dans cet ordre :
 *
 * <ol>
 *   <li>ce que l'on a explicitement demande ({@code -Dab3d2.media}, {@code -Dab3d2.adf},
 *       {@code -Dab3d2.save}) l'emporte toujours ;</li>
 *   <li>si le code tourne depuis une application empaquetee, tout est relatif au dossier de
 *       l'application, qui reste donc DEPLACABLE ;</li>
 *   <li>sinon, les defauts relatifs au dossier courant — et les harnais ne changent pas de
 *       comportement d'un iota.</li>
 * </ol>
 *
 * <h2>Reconnaitre une application empaquetee</h2>
 *
 * <p>Il n'y a pas de drapeau pour cela. Le signe est la FORME du chemin d'ou le code est
 * charge : {@code jpackage} range les archives dans un sous-dossier {@code app}, de sorte
 * qu'une archive en {@code .../MonJeu/app/truc.jar} designe {@code .../MonJeu}. Depuis Gradle,
 * le code vient d'un dossier de classes et ce test echoue, ce qui est exactement ce qu'on veut.
 *
 * <h2>Le journal</h2>
 *
 * <p>Une application a fenetre n'a pas de console : tout ce qu'elle ecrit disparait. On
 * redirige donc la sortie vers un fichier des qu'on est empaquete — sans quoi la premiere
 * panne serait muette.
 */
public final class AppDirs {

    private AppDirs() {
    }

    /** Le dossier de l'application, ou {@code null} si l'on tourne depuis les sources. */
    public static Path appRoot() {
        try {
            Path source = Path.of(AppDirs.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
            if (!Files.isRegularFile(source)) {
                return null;                           // un dossier de classes : on est en dev
            }
            Path app = source.getParent();             // .../MonJeu/app
            if (app == null || !"app".equals(String.valueOf(app.getFileName()))) {
                return null;
            }
            return app.getParent();                    // .../MonJeu
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Pose les trois chemins, et le journal si l'on est empaquete.
     *
     * <p>A appeler AVANT tout acces aux donnees : les chemins sont lus a la premiere lecture et
     * les changer apres coup ne servirait a rien.
     */
    public static void setup() {
        Path racine = appRoot();
        if (racine == null) {
            return;                                    // depuis les sources : rien a faire
        }
        defautSi("ab3d2.media", racine.resolve("medias").resolve("original"));
        defautSi("ab3d2.adf", racine.resolve("adf"));
        defautSi("ab3d2.save", racine.resolve("run"));
        journal(racine.resolve("run"));
    }

    private static void defautSi(String cle, Path valeur) {
        if (System.getProperty(cle) == null) {
            System.setProperty(cle, valeur.toString());
        }
    }

    /** Redirige la sortie vers un fichier : une application a fenetre n'a pas de console. */
    private static void journal(Path run) {
        String demande = System.getProperty("ab3d2.log");
        if ("off".equals(demande)) {
            return;
        }
        Path f = demande != null ? Path.of(demande) : run.resolve("ab3d2.log");
        try {
            Path parent = f.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            PrintStream out = new PrintStream(Files.newOutputStream(f), true, "UTF-8");
            System.setOut(out);
            System.setErr(out);
            System.out.println("=== Alien Breed 3D II : The Killing Grounds — conversion Java");
            System.out.println("    donnees   " + System.getProperty("ab3d2.media"));
            System.out.println("    disquettes " + System.getProperty("ab3d2.adf"));
            System.out.println("    reglages  " + System.getProperty("ab3d2.save"));
        } catch (IOException e) {
            // Pas de journal : on continue sans. Mieux vaut un jeu muet qu'un jeu qui refuse
            // de demarrer parce qu'il ne sait pas ou se plaindre.
        }
    }
}
