package ab3d2.full.menu;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * LES REGLAGES du joueur : huit bascules et dix-sept touches.
 *
 * <p>L'original serialise une cinquantaine de reglages dans un fichier TEXTE
 * ({@code cfg_options}, {@code ab3:prefs.cfg}). Le portage litteral a remplace cela par
 * l'ecriture du bloc binaire contigu des preferences, ce qui revient au meme et evite un
 * analyseur de texte.
 *
 * <p>Ici on garde le principe — un petit bloc binaire, un nombre magique en tete — en se
 * limitant a ce que la conversion sait faire : les huit bascules du menu {@code CUSTOM
 * OPTIONS}, et les dix-sept touches de {@code CONTROL OPTIONS}. Un fichier absent ou d'un
 * autre format laisse les valeurs par defaut, sans un mot : c'est le comportement d'origine.
 */
public final class Options {

    /** Les huit bascules, dans l'ordre du menu. */
    public static final int ORIGINAL_MOUSE = 0;
    public static final int ALWAYS_RUN = 1;
    public static final int SHOW_MESSAGES = 2;
    public static final int NO_AUTO_AIM = 3;
    public static final int SHOW_FPS = 4;
    public static final int HIDE_WEAPON = 5;
    public static final int COUNT = 8;

    /** Les dix-sept touches reglables, dans l'ordre des deux pages. */
    public static final int KEYS = 17;

    /** « k8nx » : le meme nombre magique que le portage litteral. */
    private static final int MAGIC = 0x6B386E78;

    private final boolean[] bascules = new boolean[COUNT];
    private final int[] touches = new int[KEYS];
    private final Path file;

    public Options(Path file) {
        this.file = file;
        defaults();
        load();
    }

    /** Les valeurs de depart : les messages s'affichent, l'arme se voit, rien d'autre. */
    private void defaults() {
        java.util.Arrays.fill(bascules, false);
        bascules[SHOW_MESSAGES] = true;
        java.util.Arrays.fill(touches, 0);
    }

    public boolean on(int i) {
        return i >= 0 && i < COUNT && bascules[i];
    }

    /** {@code not.b} : le menu ne pose pas une valeur, il retourne celle qui est la. */
    public void toggle(int i) {
        if (i >= 0 && i < COUNT) {
            bascules[i] = !bascules[i];
            save();
        }
    }

    /** Le code de la touche affectee a une action, ou zero si aucune. */
    public int key(int i) {
        return i >= 0 && i < KEYS ? touches[i] : 0;
    }

    public void key(int i, int code) {
        if (i >= 0 && i < KEYS) {
            touches[i] = code;
            save();
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            byte[] b = Files.readAllBytes(file);
            if (b.length < 4 + COUNT + KEYS * 4 || lire(b, 0) != MAGIC) {
                return;                                // absent ou invalide : les defauts restent
            }
            for (int i = 0; i < COUNT; i++) {
                bascules[i] = b[4 + i] != 0;
            }
            for (int i = 0; i < KEYS; i++) {
                touches[i] = lire(b, 4 + COUNT + i * 4);
            }
        } catch (IOException e) {
            // Illisible : on garde les defauts, comme le jeu.
        }
    }

    private void save() {
        byte[] b = new byte[4 + COUNT + KEYS * 4];
        ecrire(b, 0, MAGIC);
        for (int i = 0; i < COUNT; i++) {
            b[4 + i] = (byte) (bascules[i] ? 1 : 0);
        }
        for (int i = 0; i < KEYS; i++) {
            ecrire(b, 4 + COUNT + i * 4, touches[i]);
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(file, b);
        } catch (IOException e) {
            throw new UncheckedIOException("reglages non enregistres : " + file, e);
        }
    }

    private static int lire(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16)
                | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    private static void ecrire(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }
}
