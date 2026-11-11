package ab3d2.full.boot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * D'OU VIENNENT LES DONNEES, et comment elles arrivent la.
 *
 * <p>Jusqu'ici la conversion lisait un dossier deja depaque, produit ailleurs. Une application
 * qu'on installe ne peut pas supposer cela : elle doit savoir partir des disquettes elle-meme.
 *
 * <p>Trois etapes, et chacune peut etre deja faite :
 *
 * <ol>
 *   <li>les images {@code .adf} sont-elles la ? Sinon, {@link DiskFetch} propose de les
 *       chercher chez Dream17 — et ne fait rien sans un oui ;</li>
 *   <li>le dossier depaque est-il la ? Sinon, {@link AdfAssets} monte les disquettes, depacke
 *       ce qui est compresse et ecrit l'arborescence ;</li>
 *   <li>un temoin ({@code .adf-extract}) evite de tout recommencer au lancement suivant. Il
 *       porte la signature des images : changer de disquettes refait l'extraction.</li>
 * </ol>
 *
 * <p>Rien de tout cela n'est du jeu. C'est l'amorcage, et il vaut mieux qu'il soit ici, en un
 * seul endroit, que dispersé dans la fenetre d'un cote et dans une activite Android de l'autre.
 */
public final class Boot {

    private Boot() {
    }

    /**
     * S'assure que les donnees sont lisibles, et rend la racine a donner a {@code ab3d2.media}.
     *
     * @param adfDir ou vivent (ou vivront) les images de disquettes
     * @param media  ou le jeu lira les donnees depaquees
     * @param ask    comment demander a l'utilisateur, et lui rendre compte
     * @return la racine des donnees, ou {@code null} si elles ne sont pas la et ne le seront pas
     */
    public static Path ensure(Path adfDir, Path media, DiskFetch.Ask ask) {
        if (deja(media)) {
            return media;
        }
        if (!DiskFetch.ensureDisks(adfDir, ask)) {
            return null;
        }
        try {
            return AdfAssets.ensureExtracted(adfDir, media);
        } catch (IOException e) {
            ask.error("L'extraction des disquettes a echoue :\n" + e.getMessage());
            return null;
        }
    }

    /**
     * L'amorcage seul, sans jeu : {@code gradle -p full unpack}.
     *
     * <p>Il sert a deux choses — eprouver la chaine complete sans ouvrir de fenetre, et
     * preparer les donnees d'avance quand on ne veut pas qu'elles arrivent au premier
     * lancement.
     */
    public static void main(String[] args) {
        AppDirs.setup();
        Path adf = Path.of(System.getProperty("ab3d2.adf", "adf"));
        Path media = Path.of(System.getProperty("ab3d2.media", "medias/original"));
        boolean refaire = System.getProperty("ab3d2.force") != null;
        if (refaire && Files.isDirectory(media)) {
            System.out.println("[amorcage] on repart de zero : " + media.toAbsolutePath());
        }
        Path pret = refaire ? depuisLesDisquettes(adf, media) : ensure(adf, media, CONSOLE);
        if (pret == null) {
            System.out.println("[amorcage] donnees absentes ; rien n'a ete fait");
            System.exit(1);
        }
        System.out.println("[amorcage] donnees pretes dans " + pret.toAbsolutePath());
    }

    private static Path depuisLesDisquettes(Path adf, Path media) {
        if (!DiskFetch.hasDisks(adf)) {
            return null;
        }
        try {
            return AdfAssets.ensureExtracted(adf, media);
        } catch (IOException e) {
            System.err.println("[amorcage] " + e.getMessage());
            return null;
        }
    }

    /** Comment un terminal pose la question. */
    private static final DiskFetch.Ask CONSOLE = new DiskFetch.Ask() {
        @Override
        public boolean confirm(String message) {
            System.out.println(message);
            System.out.print("Telecharger ? [o/N] ");
            try {
                int c = System.in.read();
                return c == 'o' || c == 'O' || c == 'y' || c == 'Y';
            } catch (IOException e) {
                return false;
            }
        }

        @Override
        public void info(String message) {
            System.out.println("[amorcage] " + message);
        }

        @Override
        public void error(String message) {
            System.err.println("[amorcage] " + message);
        }
    };

    /**
     * Le dossier est-il deja utilisable ?
     *
     * <p>On ne se fie pas au temoin seul : un dossier peut avoir ete copie a la main, sans lui.
     * Le signe sur : le premier niveau est la.
     */
    public static boolean deja(Path media) {
        return Files.isDirectory(media)
                && (Files.isDirectory(media.resolve("levels"))
                    || Files.isDirectory(media.resolve("LEVELS")));
    }
}
