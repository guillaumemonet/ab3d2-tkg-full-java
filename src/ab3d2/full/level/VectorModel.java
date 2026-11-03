package ab3d2.full.level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Un modele en relief : des points, des faces, et autant de poses qu'il a d'images.
 *
 * <p>Vingt fichiers, nommes par la base de liaison, charges tels quels. Chacun est un objet
 * entier avec toutes ses images d'animation — ce n'est pas une feuille de sprites decoupee en
 * cases, c'est une geometrie refaite a chaque pas.
 *
 * <h2>Ce qu'il y a dans le fichier</h2>
 *
 * <p>Trois mots d'en-tete : s'il faut TRIER ses faces, combien de points, combien d'images.
 * Puis une table de deux mots par image — ou sont les points, ou sont les angles de faces —,
 * dont les deplacements comptent depuis le DEUXIEME mot du fichier et non depuis son debut.
 * Vient ensuite la liste des faces, commune a toutes les images, et enfin un bloc par image.
 *
 * <p>Un bloc d'image commence par un mot double de DRAPEAUX, un bit par partie : c'est lui qui
 * allume ou eteint les morceaux de l'objet, et c'est ainsi qu'un canon rentre dans sa tourelle
 * ou qu'une porte s'ouvre sans changer de geometrie. Suivent un octet de NORMALE par point —
 * nibble haut l'elevation, nibble bas l'azimut —, complete a un nombre pair, puis les points
 * eux-memes, trois mots chacun.
 *
 * <h2>La normale qui vaut deux cent cinquante-cinq</h2>
 *
 * <p>Beaucoup de points portent {@code $ff}. Son nibble haut designe le rang quinze de la
 * grille d'eclairage, et ce rang-la n'est JAMAIS ecrit : il vaut donc zero pour toujours, et
 * zero est la pleine lumiere. C'est la facon du jeu de dire « ce point ne s'ombre pas ».
 */
public record VectorModel(
        String name,
        boolean sort,
        int pointCount,
        List<Frame> frames,
        int[] lines) {

    /** Une pose : ses drapeaux de parties, ses normales, ses points, ses angles de faces. */
    public record Frame(int onOff, int[] normals, int[] x, int[] y, int[] z, int[] angles) { }

    /**
     * Lit un modele.
     *
     * @param file le fichier, tel que la base de liaison le nomme
     * @param name le nom court, pour les messages
     */
    public static VectorModel read(Path file, String name) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        if (raw.length < 6) {
            throw new IOException("modele trop court : " + file);
        }
        // Les deplacements de la table comptent depuis le DEUXIEME mot.
        final int start = 2;
        boolean sort = word(raw, 0) != 0;
        int pointCount = uword(raw, start);
        int frameCount = uword(raw, start + 2);
        int table = start + 4;
        int linesAt = table + frameCount * 4;

        List<Frame> frames = new ArrayList<>(frameCount);
        int firstPts = Integer.MAX_VALUE;
        for (int f = 0; f < frameCount; f++) {
            int ptsOff = uword(raw, table + f * 4);
            int angOff = uword(raw, table + f * 4 + 2);
            firstPts = Math.min(firstPts, start + ptsOff);
            int at = start + ptsOff;
            int onOff = (uword(raw, at) << 16) | uword(raw, at + 2);
            int[] normals = new int[pointCount];
            for (int i = 0; i < pointCount; i++) {
                normals[i] = raw[at + 4 + i] & 0xFF;
            }
            // Le bloc des normales est complete a un nombre PAIR d'octets.
            int pts = at + 4 + ((pointCount + 1) / 2) * 2;
            int[] x = new int[pointCount];
            int[] y = new int[pointCount];
            int[] z = new int[pointCount];
            for (int i = 0; i < pointCount; i++) {
                x[i] = word(raw, pts + i * 6);
                y[i] = word(raw, pts + i * 6 + 2);
                z[i] = word(raw, pts + i * 6 + 4);
            }
            // Les angles de faces vont de leur deplacement au bloc suivant, ou a la fin.
            int angEnd = raw.length;
            for (int g = 0; g < frameCount; g++) {
                int other = start + uword(raw, table + g * 4);
                if (other > start + angOff && other < angEnd) {
                    angEnd = other;
                }
            }
            int[] angles = new int[Math.max(0, angEnd - (start + angOff))];
            for (int i = 0; i < angles.length; i++) {
                angles[i] = raw[start + angOff + i] & 0xFF;
            }
            frames.add(new Frame(onOff, normals, x, y, z, angles));
        }

        int[] lines = new int[Math.max(0, (firstPts - linesAt) / 2)];
        for (int i = 0; i < lines.length; i++) {
            lines[i] = uword(raw, linesAt + i * 2);
        }
        return new VectorModel(name, sort, pointCount, List.copyOf(frames), lines);
    }

    private static int word(byte[] b, int at) {
        return (short) (((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF));
    }

    private static int uword(byte[] b, int at) {
        return ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
    }
}
