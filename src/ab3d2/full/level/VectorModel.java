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
 * ou qu'une porte s'ouvre sans changer de geometrie.
 *
 * <h2>Les parties</h2>
 *
 * <p>Un modele n'est pas une liste de faces : c'est une liste de PARTIES, chacune portant ses
 * faces. Deux mots par partie — ou sont ses faces, et quel point la represente — et une valeur
 * negative ferme la liste. Le point choisi sert a mesurer la distance de la partie a l'oeil,
 * et donc a decider dans quel ordre les parties se dessinent : c'est le tri en profondeur, que
 * le premier mot du fichier reclame ou non. Suivent un octet de NORMALE par point —
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
        List<Part> parts) {

    /** Une pose : ses drapeaux de parties, ses normales, ses points, ses angles de faces. */
    public record Frame(int onOff, int[] normals, int[] x, int[] y, int[] z, int[] angles) { }

    /**
     * Une partie du modele.
     *
     * @param at    ou sont ses faces, compte depuis le deuxieme mot du fichier
     * @param point le DEPLACEMENT de son point representatif dans la table des points tournes,
     *              dix octets par point — ce n'est pas un rang
     * @param faces ses faces, dans l'ordre ou elles se dessinent
     */
    public record Part(int at, int point, List<Face> faces) {

        /** Le rang du point representatif. */
        public int pointIndex() {
            return point / 10;
        }
    }

    /**
     * Une FACE : un contour ferme, une texture, et de quoi decider sa teinte.
     *
     * <h2>Le contour</h2>
     *
     * <p>Le premier mot du dossier compte les aretes MOINS UNE, et le contour porte une entree
     * de plus que d'aretes : la derniere REPETE la premiere, mot pour mot. C'est ce doublon qui
     * ferme le polygone — le trace prend les entrees deux par deux et n'a donc rien de special
     * a faire au dernier tour. Mesure : sur les 1167 faces des vingt modeles, la derniere
     * entree est l'exacte copie de la premiere, sans exception.
     *
     * <p>Chaque entree tient en quatre octets : un mot de rang de point, puis deux octets de
     * coordonnee dans la texture, l'un et l'autre entre zero et soixante-quatre inclus.
     *
     * <h2>La teinte</h2>
     *
     * <p>Trois choses closent le dossier. Le mot de TEXTURE, negatif quand elle est rangee dans
     * la seconde banque — 466 faces sur 1167 le sont. Un octet de CLARTE, qui se lit comme un
     * pourcentage : cent pour une face normale, et 30, 50 ou 70 pour les dix-huit faces que le
     * modeleur a voulues plus sombres. Un octet de RANG, enfin, qui n'est pas une normale mais
     * le numero de la face dans le modele entier : il sert a lire son orientation dans la table
     * d'angles de la pose, et chaque pose a donc ses propres orientations de faces.
     *
     * <h2>Les deux mots de drapeaux</h2>
     *
     * <p>Le deuxieme mot du dossier et le dernier portent chacun DEUX drapeaux, un par octet,
     * parce que le jeu les ecrit d'un seul mot sur deux octets voisins. Du premier, seul
     * l'octet bas sert : les TROUS, c'est-a-dire une texture qui laisse voir au travers — sept
     * faces dans tout le jeu. Du second viennent le DEGRADE (octet bas) et le MIROITEMENT
     * (octet haut), et aucune face n'a les deux.
     *
     * @param at         ou commence le dossier, compte depuis le deuxieme mot du fichier
     * @param point      les rangs de points du contour, le dernier repetant le premier
     * @param u          l'abscisse dans la texture, une par entree
     * @param v          l'ordonnee dans la texture, une par entree
     * @param texture    ou commence la texture dans sa banque
     * @param secondBank si c'est la seconde banque de textures
     * @param light      la clarte propre de la face, en pourcentage
     * @param normal     le rang de la face, qui lit son orientation dans la table de la pose
     * @param holes      si la texture laisse voir au travers
     * @param gouraud    si la face se degrade d'un sommet a l'autre
     * @param glare      si la face miroite
     */
    public record Face(int at, int[] point, int[] u, int[] v,
            int texture, boolean secondBank, int light, int normal,
            boolean holes, boolean gouraud, boolean glare) {

        /** Combien d'aretes le contour a. */
        public int lines() {
            return point.length - 1;
        }
    }

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

        // La table des parties ouvre le bloc des faces, et se ferme sur une valeur negative.
        List<Part> parts = new ArrayList<>();
        for (int at = linesAt; at + 3 < firstPts; at += 4) {
            int off = word(raw, at);
            if (off < 0) {
                break;
            }
            parts.add(new Part(off, uword(raw, at + 2), faces(raw, start, off, name)));
        }
        return new VectorModel(name, sort, pointCount, List.copyOf(frames), List.copyOf(parts));
    }

    /**
     * Les faces d'une partie, a la file jusqu'au premier mot de tete NEGATIF.
     *
     * <p>Chaque dossier occupe {@code 18 + 4n} octets, ou {@code n} est son mot de tete : deux
     * mots d'en-tete, {@code n+2} entrees de contour de quatre octets, puis la texture, la
     * clarte, le rang et le mot de drapeaux de fin.
     */
    private static List<Face> faces(byte[] raw, int start, int partAt, String name)
            throws IOException {
        List<Face> out = new ArrayList<>();
        int at = start + partAt;
        while (true) {
            if (at + 2 > raw.length) {
                throw new IOException("faces de " + name + " : dossier tronque");
            }
            int n = word(raw, at);
            if (n < 0) {
                return List.copyOf(out);
            }
            int size = 18 + 4 * n;
            if (at + size > raw.length) {
                throw new IOException("faces de " + name + " : dossier hors du fichier");
            }
            int count = n + 2;
            int[] point = new int[count];
            int[] u = new int[count];
            int[] v = new int[count];
            for (int i = 0; i < count; i++) {
                point[i] = uword(raw, at + 4 + i * 4);
                u[i] = raw[at + 4 + i * 4 + 2] & 0xFF;
                v[i] = raw[at + 4 + i * 4 + 3] & 0xFF;
            }
            int tex = word(raw, at + 12 + 4 * n);
            int tail = uword(raw, at + 16 + 4 * n);
            out.add(new Face(at - start, point, u, v,
                    tex & 0x7FFF, tex < 0,
                    raw[at + 14 + 4 * n] & 0xFF,
                    raw[at + 15 + 4 * n] & 0xFF,
                    (uword(raw, at + 2) & 0xFF) != 0,
                    (tail & 0xFF) != 0,
                    (tail >> 8) != 0));
            at += size;
        }
    }

    private static int word(byte[] b, int at) {
        return (short) (((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF));
    }

    private static int uword(byte[] b, int at) {
        return ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
    }
}
