package ab3d2.full.view;

import ab3d2.full.sound.Paula;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * LE HAUT-PARLEUR : les quatre canaux de la puce, mis en stereo et envoyes a la machine.
 *
 * <p>C'est la seule piece de tout ce portage qui ne vient pas du jeu : l'Amiga n'avait pas de
 * couche audio, il avait un registre d'adresse et un registre de volume par canal, et la puce
 * lisait la memoire toute seule. Ici il faut pousser les octets.
 *
 * <p>Deux choses viennent du materiel et sont gardees telles quelles :
 *
 * <ul>
 *   <li>la FREQUENCE : l'horloge de la puce divisee par la periode que le jeu ecrit, soit
 *       trois millions cinq cent quarante-six mille huit cent quatre-vingt-quinze sur quatre
 *       cent quarante-trois, un peu plus de huit mille hertz ;</li>
 *   <li>le PLACEMENT : sur Amiga les canaux zero et trois sortent a GAUCHE, un et deux a
 *       DROITE. Ce n'est pas un choix, c'est le cablage.</li>
 * </ul>
 *
 * <p>Le melangeur produit deux cents echantillons d'un coup, soit vingt-cinq millisecondes,
 * quand une image du jeu en dure vingt. Ce n'est pas un defaut d'accord : sur l'Amiga le
 * melange est declenche par la puce quand elle a fini de lire, pas par l'image. Ici c'est la
 * file de la machine qui le declenche — tant qu'elle se vide, on melange.
 */
public final class Speaker {

    /** Combien de trames on garde d'avance dans la file. */
    private static final int AHEAD = 4;

    private final Paula paula;
    private SourceDataLine line;
    private final byte[] pcm = new byte[Paula.FRAME_BYTES * 4];
    private boolean tried;

    public Speaker(Paula paula) {
        this.paula = paula;
    }

    /** Vrai si la machine a bien voulu d'une sortie audio. */
    public boolean open() {
        if (tried) {
            return line != null;
        }
        tried = true;
        AudioFormat format = new AudioFormat(Paula.sampleRate(), 16, 2, true, true);
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            SourceDataLine l = (SourceDataLine) AudioSystem.getLine(info);
            l.open(format, Paula.FRAME_BYTES * 4 * (AHEAD + 2));
            l.start();
            line = l;
        } catch (LineUnavailableException | IllegalArgumentException e) {
            System.out.println("pas de sortie audio (" + e + ") — le jeu reste muet");
            line = null;
        }
        return line != null;
    }

    /**
     * Melange autant de trames que la file en reclame.
     *
     * <p>La borne n'est pas une precaution de style : sans elle, une file vide au demarrage
     * ferait melanger des dizaines de trames d'affilee et le son partirait en avance.
     */
    public void pump() {
        if (line == null) {
            // Muet : on melange quand meme une trame. Le moteur continue d'avancer dans ses
            // echantillons et de rendre ses voix, ce que le harnais eprouve.
            paula.mix();
            if (recorded != null) {
                convertir();
                recorded.write(pcm, 0, pcm.length);
            }
            return;
        }
        int garde = 0;
        while (line.available() >= pcm.length && garde++ < AHEAD * 2) {
            paula.mix();
            convertir();
            line.write(pcm, 0, pcm.length);
            if (recorded != null) {
                recorded.write(pcm, 0, pcm.length);
            }
        }
    }

    /** Les quatre canaux, a leur volume, sommes en deux voies de seize bits. */
    private void convertir() {
        byte[] c0 = paula.channel(0);
        byte[] c1 = paula.channel(1);
        byte[] c2 = paula.channel(2);
        byte[] c3 = paula.channel(3);
        int v0 = paula.volume(0);
        int v1 = paula.volume(1);
        int v2 = paula.volume(2);
        int v3 = paula.volume(3);
        for (int i = 0; i < Paula.FRAME_BYTES; i++) {
            // Canaux zero et trois a gauche, un et deux a droite : le cablage de la puce.
            int g = c0[i] * v0 + c3[i] * v3;
            int d = c1[i] * v1 + c2[i] * v2;
            // Chaque canal vaut au plus cent vingt-huit fois soixante-quatre ; deux canaux
            // sommes tiennent donc sur quinze bits, et un seul doublement les porte a l'echelle
            // de seize. Rien ne sature, et c'est voulu : le jeu, lui, saturait.
            g <<= 1;
            d <<= 1;
            int at = i * 4;
            pcm[at] = (byte) (g >> 8);
            pcm[at + 1] = (byte) g;
            pcm[at + 2] = (byte) (d >> 8);
            pcm[at + 3] = (byte) d;
        }
    }

    /**
     * Enregistre AUSSI ce qui sort, dans un fichier d'onde.
     *
     * <p>C'est le pendant sonore du vidage d'images : on ne peut pas regarder le son, mais on
     * peut l'ecouter. Le fichier s'ecrit a la fermeture.
     */
    public void record(Path f) {
        this.recordTo = f;
        this.recorded = new ByteArrayOutputStream();
    }

    private Path recordTo;
    private ByteArrayOutputStream recorded;

    /** L'en-tete d'un fichier d'onde, puis les octets. */
    private void ecrire() {
        if (recordTo == null || recorded == null || recorded.size() == 0) {
            return;
        }
        byte[] data = recorded.toByteArray();
        int rate = Paula.sampleRate();
        int octetsParSeconde = rate * 4;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write("RIFF".getBytes(StandardCharsets.US_ASCII));
            le32(out, 36 + data.length);
            out.write("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
            le32(out, 16);
            le16(out, 1);                              // PCM
            le16(out, 2);                              // deux voies
            le32(out, rate);
            le32(out, octetsParSeconde);
            le16(out, 4);                              // un echantillon des deux voies
            le16(out, 16);
            out.write("data".getBytes(StandardCharsets.US_ASCII));
            le32(out, data.length);
            // Le melangeur rend du gros-boutiste, le fichier d'onde veut du petit-boutiste.
            for (int i = 0; i < data.length; i += 2) {
                out.write(data[i + 1]);
                out.write(data[i]);
            }
            Path parent = recordTo.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(recordTo, out.toByteArray());
            System.out.println("  son -> " + recordTo.toAbsolutePath());
        } catch (IOException e) {
            System.out.println("  (pas de fichier d'onde : " + e + ")");
        }
    }

    private static void le16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write(v >> 8 & 0xFF);
    }

    private static void le32(ByteArrayOutputStream o, int v) {
        le16(o, v & 0xFFFF);
        le16(o, v >>> 16);
    }

    /** Rend la sortie audio. */
    public void close() {
        ecrire();
        if (line != null) {
            line.flush();
            line.close();
            line = null;
        }
    }
}
