package ab3d2.full.menu;

import ab3d2.full.sim.Inventory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * LES SAUVEGARDES : six emplacements de soixante-dix octets, dans un seul fichier.
 *
 * <p>Le jeu n'ecrit pas une partie, il ecrit un INVENTAIRE et un numero de niveau. Il n'y a ni
 * position, ni portes ouvertes, ni monstres tues : recharger, c'est recommencer le niveau avec
 * ce qu'on avait en y entrant. C'est exactement ce que fait aussi la mort, et ce n'est pas un
 * hasard — les deux passent par le meme inventaire de partie.
 *
 * <h2>Soixante-dix octets, et pas un de plus</h2>
 *
 * <pre>
 *   0   mot    le niveau
 *   2   11 longs  Plr_Health_w et les vingt-et-une munitions   (22 mots)
 *   46  6 longs   Plr_Shield_w et les onze items               (12 mots)
 * </pre>
 *
 * <p>Les longs ne veulent rien dire : l'assembleur recopie par longs parce que c'est deux fois
 * plus rapide, et les champs sont des mots. Vingt-deux consommables et douze items, ce sont
 * exactement les tailles de {@link Inventory} — ce qui est la preuve que les deux decrivent la
 * meme chose.
 *
 * <p>L'original ouvre {@code ab3:boot.dat} SANS passer par sa file d'entrees-sorties, et c'est
 * delibere : la file affiche « insert disk » et redemande quand le fichier manque, ce qui n'a
 * aucun sens pour une sauvegarde absente.
 */
public final class SaveSlots {

    /** Six emplacements : le premier est « nouvelle partie », les cinq autres s'ecrivent. */
    public static final int COUNT = 6;

    /** La taille d'un emplacement, en octets. */
    public static final int SIZE = 2 + Inventory.NUM_CONSUMABLES * 2 + Inventory.NUM_ITEMS * 2;

    private final Path file;
    private final byte[] data = new byte[COUNT * SIZE];

    public SaveSlots(Path file) {
        this.file = file;
        if (Files.isRegularFile(file)) {
            try {
                byte[] brut = Files.readAllBytes(file);
                System.arraycopy(brut, 0, data, 0, Math.min(brut.length, data.length));
            } catch (IOException e) {
                // Une sauvegarde illisible vaut une sauvegarde absente : on repart a vide.
                java.util.Arrays.fill(data, (byte) 0);
            }
        }
    }

    /** Vrai si l'emplacement porte quelque chose : une sante non nulle. */
    public boolean used(int slot) {
        return slot >= 0 && slot < COUNT && consumable(slot, 0) != 0;
    }

    /** Le niveau d'un emplacement, de zero a quinze. */
    public int level(int slot) {
        return word(slot * SIZE) & 0xFFFF;
    }

    /** Ce que l'emplacement affiche dans le menu. */
    public String label(int slot) {
        if (!used(slot)) {
            return "      EMPTY";
        }
        return "   " + (char) ('A' + (level(slot) & 15)) + "   ENERGY " + consumable(slot, 0);
    }

    /** Recopie un emplacement dans un inventaire. */
    public void read(int slot, Inventory inv) {
        for (int i = 0; i < Inventory.NUM_CONSUMABLES; i++) {
            inv.consumables[i] = consumable(slot, i);
        }
        for (int i = 0; i < Inventory.NUM_ITEMS; i++) {
            inv.items[i] = word(slot * SIZE + 2 + Inventory.NUM_CONSUMABLES * 2 + i * 2);
        }
    }

    /** Ecrit un inventaire dans un emplacement, et enregistre le fichier. */
    public void write(int slot, int niveau, Inventory inv) {
        int at = slot * SIZE;
        putWord(at, niveau);
        for (int i = 0; i < Inventory.NUM_CONSUMABLES; i++) {
            putWord(at + 2 + i * 2, inv.consumables[i]);
        }
        for (int i = 0; i < Inventory.NUM_ITEMS; i++) {
            putWord(at + 2 + Inventory.NUM_CONSUMABLES * 2 + i * 2, inv.items[i]);
        }
        flush();
    }

    private void flush() {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(file, data);
        } catch (IOException e) {
            throw new UncheckedIOException("sauvegarde impossible : " + file, e);
        }
    }

    private int consumable(int slot, int i) {
        return word(slot * SIZE + 2 + i * 2);
    }

    private int word(int at) {
        if (at < 0 || at + 1 >= data.length) {
            return 0;
        }
        return ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
    }

    private void putWord(int at, int v) {
        if (at < 0 || at + 1 >= data.length) {
            return;
        }
        data[at] = (byte) (v >> 8);
        data[at + 1] = (byte) v;
    }
}
