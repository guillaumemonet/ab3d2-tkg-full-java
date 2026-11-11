package ab3d2.full.boot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fabrique le dossier d'assets à partir des DISQUETTES.
 *
 * <p>Rien du jeu n'est versionné : la seule source est le jeu d'images {@code .adf}. Au premier
 * lancement on les monte, on dépacke ce qui l'est ({@link SbDepack}) et on écrit l'arborescence
 * sur disque ; ensuite tout le reste du moteur lit ce dossier comme avant.
 *
 * <p>Les cinq disquettes ne servent pas à la même chose :
 * <pre>
 *   1  TKG1  boot 2 Mo   includes, vectobj, wallinc, sounds
 *   2  TKG2  niveaux     levels/LEVEL_A..P, music, wallinc
 *   3  TKG1  boot 4 Mo   Includes, WALLINC, vectobj, hqn      ← prime sur la 1 (variante 4 Mo)
 *   4  Edit  éditeur     (inutile au jeu)
 *   5  SFX   sons        samples, plus un arbre EDIT/
 * </pre>
 * L'ordre de montage compte : une disquette montée plus tard écrase les fichiers de même nom.
 * On monte donc 1, puis 3 (qui impose ses variantes 4 Mo), puis 2 et 5. Vérifié : le
 * {@code vectobj/blaster} de la disquette 4 Mo est celui des assets de référence, celui de la
 * 2 Mo est un autre modèle.
 */
public final class AdfAssets {

    /**
     * Ordre de montage. Le numéro est celui du nom de fichier « (Disk N of 5) ».
     *
     * <p>On cible la version <b>4 Mo</b> : la disquette 3 EST le boot 4 Mo, elle rend la 1
     * (boot 2 Mo) inutile. La 4 est l'éditeur. Restent donc le boot, les niveaux et les sons.
     * Une disquette montée plus tard écrase les fichiers de même chemin.
     */
    private static final int[] MOUNT_ORDER = { 3, 2, 5 };

    /** Témoin écrit à la fin d'une extraction réussie : évite de tout refaire à chaque lancement. */
    private static final String STAMP = ".adf-extract";

    private AdfAssets() {
    }

    /** Les images de disquettes d'un dossier, dans l'ordre de montage. */
    public static List<Path> disks(Path adfDir) throws IOException {
        List<Path> all = new ArrayList<>();
        try (var s = Files.list(adfDir)) {
            s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".adf"))
             .forEach(all::add);
        }
        List<Path> ordered = new ArrayList<>();
        for (int n : MOUNT_ORDER) {
            for (Path p : all) {
                if (diskNumber(p) == n) {
                    ordered.add(p);
                }
            }
        }
        for (Path p : all) {                           // images non nommées « Disk N » : à la fin
            if (diskNumber(p) < 0 && !ordered.contains(p)) {
                ordered.add(p);
            }
        }
        return ordered;
    }

    /** Le N de « (Disk N of 5) » dans le nom de fichier, ou -1. */
    private static int diskNumber(Path p) {
        var m = java.util.regex.Pattern.compile("\\(Disk (\\d+) of")
                .matcher(p.getFileName().toString());
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** Signature des images : si elle change, on ré-extrait. */
    private static String fingerprint(List<Path> disks) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Path p : disks) {
            sb.append(p.getFileName()).append(':').append(Files.size(p)).append('\n');
        }
        return sb.toString();
    }

    /**
     * Garantit que {@code dest} contient les assets, en les extrayant des disquettes si besoin.
     * Ne fait rien si l'extraction précédente correspond aux mêmes images.
     *
     * @return {@code dest}
     */
    public static Path ensureExtracted(Path adfDir, Path dest) throws IOException {
        List<Path> disks = disks(adfDir);
        if (disks.isEmpty()) {
            throw new IOException("aucune image .adf dans " + adfDir);
        }
        String want = fingerprint(disks);
        Path stamp = dest.resolve(STAMP);
        if (Files.isRegularFile(stamp)
                && want.equals(Files.readString(stamp, StandardCharsets.UTF_8))) {
            return dest;                               // déjà fait, mêmes disquettes
        }
        System.out.println("[ADF] extraction des assets depuis " + disks.size()
                + " disquettes vers " + dest);
        int files = 0;
        int packed = 0;
        // AmigaDOS ignore la casse : /includes/ (disquette 1) et /Includes/ (disquette 3) sont
        // le MEME repertoire. On fige la premiere orthographe vue, sinon la variante 4 Mo
        // n'ecrase pas la 2 Mo et on se retrouve avec deux arbres cote a cote.
        Map<String, String> dirCase = new java.util.HashMap<>();
        for (Path disk : disks) {
            Adf adf = new Adf(disk);
            int n = 0;
            for (Adf.Entry e : adf.list()) {
                if (e.dir()) {
                    continue;
                }
                byte[] raw = adf.read(e);
                boolean wasPacked = SbDepack.isPacked(raw);
                byte[] data = wasPacked ? SbDepack.unpack(raw) : raw;
                Path out = dest.resolve(canonical(e.path(), dirCase));
                Files.createDirectories(out.getParent());
                Files.write(out, data);
                n++;
                if (wasPacked) {
                    packed++;
                }
            }
            files += n;
            System.out.printf("[ADF]   %-8s %3d fichiers (%s)%n",
                    adf.volume(), n, disk.getFileName());
        }
        Files.writeString(stamp, want, StandardCharsets.UTF_8);
        System.out.println("[ADF] " + files + " fichiers ecrits, dont " + packed + " depackes");
        return dest;
    }

    /** Chemin relatif avec l'orthographe de répertoire déjà retenue pour ce niveau. */
    private static String canonical(String adfPath, Map<String, String> dirCase) {
        String[] parts = adfPath.substring(1).split("/");
        StringBuilder key = new StringBuilder();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i == parts.length - 1) {
                out.append(parts[i]);                  // le fichier garde son nom
                break;
            }
            key.append('/').append(parts[i].toLowerCase(Locale.ROOT));
            String seen = dirCase.putIfAbsent(key.toString(), parts[i]);
            out.append(seen != null ? seen : parts[i]).append('/');
        }
        return out.toString();
    }

    /** Variante sans exception vérifiée, pour les appels depuis un initialiseur statique. */
    public static Path ensureExtractedUnchecked(Path adfDir, Path dest) {
        try {
            return ensureExtracted(adfDir, dest);
        } catch (IOException e) {
            throw new UncheckedIOException("extraction des ADF depuis " + adfDir, e);
        }
    }
}
