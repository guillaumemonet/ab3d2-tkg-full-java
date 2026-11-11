package ab3d2.full.sound;

/**
 * OU LE SON SORT : la seule chose que le jeu ne peut pas faire tout seul.
 *
 * <p>Tout le reste de l'audio est porte — la puce, ses quatre voies, le melange, le lecteur de
 * module. Il ne manque que le dernier geste : pousser les octets vers un haut-parleur. Et
 * celui-la n'a rien a voir d'une machine a l'autre : une ligne {@code javax.sound} sur un
 * ordinateur, un {@code AudioTrack} sur un telephone.
 *
 * <p>D'ou cette interface, qui tient en quatre verbes. Elle ne sait rien du jeu, et le jeu ne
 * sait rien d'elle.
 *
 * <p>Le format est toujours le meme, parce qu'il vient du materiel : <b>seize bits signes, deux
 * voies entrelacees, gros-boutiste</b>, a la frequence que {@link Paula#sampleRate()} donne —
 * l'horloge de la puce divisee par la periode que le jeu ecrit.
 */
public interface Sink {

    /**
     * Ouvre la sortie.
     *
     * @param rate       la frequence, en hertz
     * @param frameBytes combien d'octets une trame de melange produit
     * @return vrai si la machine a bien voulu d'une sortie audio
     */
    boolean open(int rate, int frameBytes);

    /**
     * Combien d'octets la file accepte encore.
     *
     * <p>C'est elle qui mene la cadence : tant qu'elle se vide, on melange. Rendre zero arrete
     * le melange sans rien casser.
     */
    int available();

    /** Pousse des octets deja melanges. */
    void write(byte[] pcm, int offset, int length);

    /** Rend la sortie. */
    void close();

    /** Une sortie qui n'existe pas : le jeu tourne, et reste muet. */
    Sink SILENT = new Sink() {
        @Override
        public boolean open(int rate, int frameBytes) {
            return false;
        }

        @Override
        public int available() {
            return 0;
        }

        @Override
        public void write(byte[] pcm, int offset, int length) {
        }

        @Override
        public void close() {
        }
    };
}
