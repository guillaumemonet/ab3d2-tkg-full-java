package ab3d2.full.android;

import ab3d2.full.sound.Sink;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/**
 * LE HAUT-PARLEUR D'UN TELEPHONE : un {@code AudioTrack}, et rien d'autre.
 *
 * <p>Le pendant exact de {@code view.Speaker} pour l'ordinateur. Tout le travail est dans
 * {@link ab3d2.full.sound.Mixer} ; il ne reste ici que le dernier geste.
 *
 * <h2>Deux differences avec une ligne de bureau</h2>
 *
 * <ul>
 *   <li>Android veut du <b>petit-boutiste</b>, la ou la puce rend du gros-boutiste. On retourne
 *       donc chaque paire d'octets en passant. C'est le seul endroit du portage ou l'ordre des
 *       octets change, et il ne change que parce que le materiel moderne l'impose ;</li>
 *   <li>il n'y a pas de {@code available()} : un {@code AudioTrack} ne dit pas ce qu'il lui
 *       reste de place. On tient donc le compte nous-meme — ce qu'on a ecrit, moins ce que la
 *       tete de lecture a consomme — et c'est exactement ce que la file de bureau repondrait.</li>
 * </ul>
 *
 * <p>Le mode est {@code STREAM} et non {@code STATIC} : le son du jeu n'est pas un echantillon
 * qu'on rejoue, c'est un flux calcule a mesure.
 */
public final class Track implements Sink {

    /** Combien de trames la file garde d'avance, plus la marge. */
    private static final int TAMPON = 6;

    private AudioTrack track;
    private int capacite;
    private long ecrits;

    @Override
    public boolean open(int rate, int frameBytes) {
        int minimum = AudioTrack.getMinBufferSize(rate,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) {
            return false;
        }
        capacite = Math.max(minimum, frameBytes * TAMPON);
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(capacite)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.play();
            return true;
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            track = null;
            return false;
        }
    }

    /**
     * La place restante, deduite de ce qu'on a ecrit et de ce que la tete a lu.
     *
     * <p>{@code getPlaybackHeadPosition} compte en TRAMES d'echantillon, pas en octets : quatre
     * octets par trame en seize bits stereo. Confondre les deux donnerait une file quatre fois
     * trop grande, et le son partirait avec un quart de seconde de retard.
     */
    @Override
    public int available() {
        if (track == null) {
            return 0;
        }
        long lus = (long) track.getPlaybackHeadPosition() * 4L;
        int enAttente = (int) Math.max(0, ecrits - lus);
        return Math.max(0, capacite - enAttente);
    }

    @Override
    public void write(byte[] pcm, int offset, int length) {
        if (track == null) {
            return;
        }
        // La puce rend du gros-boutiste ; Android veut du petit-boutiste.
        byte[] retourne = new byte[length];
        for (int i = 0; i < length; i += 2) {
            retourne[i] = pcm[offset + i + 1];
            retourne[i + 1] = pcm[offset + i];
        }
        int n = track.write(retourne, 0, length);
        if (n > 0) {
            ecrits += n;
        }
    }

    @Override
    public void close() {
        if (track != null) {
            track.stop();
            track.release();
            track = null;
        }
    }
}
