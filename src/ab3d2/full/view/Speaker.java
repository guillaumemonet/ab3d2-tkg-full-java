package ab3d2.full.view;

import ab3d2.full.sound.Sink;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * LE HAUT-PARLEUR D'UN ORDINATEUR : une ligne {@code javax.sound}, et rien d'autre.
 *
 * <p>Tout le travail est dans {@link ab3d2.full.sound.Mixer} ; il ne reste ici que le dernier
 * geste, celui qui n'a rien a voir d'une machine a l'autre. C'est la seule classe de la sortie
 * audio qui ne peut pas tourner sur un telephone, et c'est pour cela qu'elle est seule.
 *
 * <p>La file est dimensionnee pour six trames : quatre d'avance et deux de marge. Trop courte,
 * elle se vide entre deux images et le son hoquette ; trop longue, le bruit d'un tir arrive
 * apres le coup.
 */
public final class Speaker implements Sink {

    /** Combien de trames la file garde d'avance, plus la marge. */
    private static final int TAMPON = 6;

    private SourceDataLine line;

    @Override
    public boolean open(int rate, int frameBytes) {
        // Gros-boutiste, seize bits signes, deux canaux : le format que la puce rend.
        AudioFormat format = new AudioFormat(rate, 16, 2, true, true);
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            SourceDataLine l = (SourceDataLine) AudioSystem.getLine(info);
            l.open(format, frameBytes * TAMPON);
            l.start();
            line = l;
            return true;
        } catch (LineUnavailableException | IllegalArgumentException e) {
            System.out.println("pas de sortie audio (" + e + ")");
            line = null;
            return false;
        }
    }

    @Override
    public int available() {
        return line == null ? 0 : line.available();
    }

    @Override
    public void write(byte[] pcm, int offset, int length) {
        if (line != null) {
            line.write(pcm, offset, length);
        }
    }

    @Override
    public void close() {
        if (line != null) {
            line.flush();
            line.close();
            line = null;
        }
    }
}
