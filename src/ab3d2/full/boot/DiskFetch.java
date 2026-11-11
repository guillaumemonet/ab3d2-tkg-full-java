package ab3d2.full.boot;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * LES DISQUETTES, au premier lancement.
 *
 * <p>Ce depot ne redistribue aucune donnee du jeu. Il lui faut les cinq images {@code .adf}, et
 * quand elles manquent il propose de les prendre chez <b>Dream17</b>, le site de preservation
 * du catalogue Amiga de Team17, qui les met a disposition dans une archive d'environ trois
 * megaoctets et demi.
 *
 * <p><b>Rien ne part sans un oui explicite.</b> C'est pourquoi la question n'est pas posee ici :
 * cette classe ne sait pas ce qu'est une fenetre. Elle recoit un {@link Ask}, et c'est la
 * plateforme — une boite de dialogue Swing, un terminal, un ecran Android — qui decide comment
 * demander. Sans reponse positive, il ne se passe rien.
 *
 * <p>Le reglage {@code ab3d2.adfUrl} change la source ; {@code ab3d2.noDownload} supprime la
 * proposition, ce qui sert aux harnais.
 */
public final class DiskFetch {

    /** La page de telechargement de Dream17 ; elle redirige vers l'archive. */
    public static final String DEFAULT_URL = "https://dream17.abime.net/download.php?id=50";

    /**
     * Le nom que le client donne de lui-meme.
     *
     * <p>Generique a dessein : il ne porte ni le nom de l'utilisateur, ni son adresse, ni rien
     * qui le designe. Un serveur n'a pas besoin de savoir qui joue.
     */
    private static final String UA = "AlienBreed3D2-TKG-Java (portage non commercial)";

    private static final int TIMEOUT_MS = 30_000;

    /** Comment la plateforme pose la question, et rend compte. */
    public interface Ask {

        /** @return vrai si l'utilisateur accepte le telechargement */
        boolean confirm(String message);

        /** Dire que tout s'est bien passe. */
        void info(String message);

        /** Dire que cela a echoue. */
        void error(String message);
    }

    /** Une plateforme muette : elle refuse, et n'affiche rien. */
    public static final Ask SILENT = new Ask() {
        @Override
        public boolean confirm(String message) {
            return false;
        }

        @Override
        public void info(String message) {
        }

        @Override
        public void error(String message) {
        }
    };

    private static final String MESSAGE = """
            Les donnees du jeu sont absentes.

            Cette conversion ne contient aucune donnee d'Alien Breed 3D II : il lui faut
            les cinq disquettes du jeu (fichiers .adf).

            Les telecharger depuis Dream17, le site de preservation du catalogue Amiga
            de Team17 ? (environ 3,4 Mo)

            Elles seront installees dans :
            %s

            Si vous possedez deja les images, repondez NON et copiez-les vous-meme dans
            ce dossier.""";

    private DiskFetch() {
    }

    /** Y a-t-il au moins une image de disquette dans ce dossier ? */
    public static boolean hasDisks(Path adfDir) {
        if (!Files.isDirectory(adfDir)) {
            return false;
        }
        try (var s = Files.list(adfDir)) {
            return s.anyMatch(p -> p.getFileName().toString()
                    .toLowerCase(Locale.ROOT).endsWith(".adf"));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * S'assure que les disquettes sont la, en proposant de les telecharger si besoin.
     *
     * @return vrai si elles y sont a la sortie
     */
    public static boolean ensureDisks(Path adfDir, Ask ask) {
        if (hasDisks(adfDir)) {
            return true;
        }
        if (System.getProperty("ab3d2.noDownload") != null) {
            return false;
        }
        if (!ask.confirm(MESSAGE.formatted(adfDir.toAbsolutePath()))) {
            return false;
        }
        String url = System.getProperty("ab3d2.adfUrl", DEFAULT_URL);
        try {
            int n = download(url, adfDir);
            ask.info(n + " disquette(s) installee(s) dans\n" + adfDir.toAbsolutePath());
            return n > 0;
        } catch (IOException e) {
            ask.error("Le telechargement a echoue :\n" + e.getMessage()
                    + "\n\nPlacez les fichiers .adf vous-meme dans :\n"
                    + adfDir.toAbsolutePath());
            return false;
        }
    }

    /** Telecharge l'archive et en extrait les {@code .adf} ; rend le nombre d'images ecrites. */
    public static int download(String url, Path adfDir) throws IOException {
        Files.createDirectories(adfDir);
        System.out.println("[disquettes] telechargement depuis " + url);
        HttpURLConnection c = open(url);
        List<String> ecrites = new ArrayList<>();
        try (InputStream in = c.getInputStream();
             ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                // On aplatit : l'archive peut ranger les images dans un sous-dossier.
                String nom = e.getName().replace('\\', '/');
                nom = nom.substring(nom.lastIndexOf('/') + 1);
                if (!nom.toLowerCase(Locale.ROOT).endsWith(".adf")) {
                    continue;
                }
                Path out = adfDir.resolve(nom);
                Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
                ecrites.add(nom);
                System.out.printf("[disquettes]   %-60s %d octets%n", nom, Files.size(out));
            }
        }
        if (ecrites.isEmpty()) {
            throw new IOException("aucun fichier .adf dans l'archive telechargee");
        }
        return ecrites.size();
    }

    /** Ouvre la connexion en suivant les redirections, changement de protocole compris. */
    private static HttpURLConnection open(String url) throws IOException {
        String courant = url;
        for (int saut = 0; saut < 5; saut++) {
            URL u = URI.create(courant).toURL();
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestProperty("User-Agent", UA);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            if (code / 100 == 3) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) {
                    throw new IOException("redirection sans Location (HTTP " + code + ")");
                }
                courant = URI.create(courant).resolve(loc).toString();  // il peut etre relatif
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new IOException("HTTP " + code + " sur " + courant);
            }
            return c;
        }
        throw new IOException("trop de redirections depuis " + url);
    }
}
