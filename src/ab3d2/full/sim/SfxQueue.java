package ab3d2.full.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * File des bruitages demandes par la SIMULATION pendant une image.
 *
 * <p>Le jeu n'a pas d'appel de fonction pour jouer un son : il pose six variables GLOBALES —
 * le numero d'echantillon, la position, le volume, l'identifiant, un drapeau — puis appelle
 * {@code MakeSomeNoise}. Cette file garde la meme forme, et ce n'est pas un caprice : plusieurs
 * sites ne posent PAS toutes les variables, et heritent donc de ce que le site precedent y
 * avait laisse. Les tenir ici, sous le meme nom, est la seule facon de garder cet heritage.
 *
 * <h2>Trois facons de placer un bruit</h2>
 *
 * <p>La position que le moteur audio attend n'est pas celle du monde mais celle de la VUE, et
 * les sites du jeu l'obtiennent de trois manieres differentes :
 *
 * <ul>
 *   <li>{@link #at} : la position tournee que le RENDU a deja calculee pour cette chose
 *       ({@code ObjRotated_vl}). On garde ici les coordonnees du monde, et la couche d'affichage
 *       refait la meme rotation — c'est exactement la meme arithmetique, sur les memes
 *       nombres ;</li>
 *   <li>{@link #door} : une rotation ECRITE SUR PLACE par l'animation des portes, qui n'est pas
 *       tout a fait celle du rendu — sa profondeur RETRANCHE la ou l'autre ajoute, et elle ne
 *       corrige pas le rapport d'aspect. On garde donc le monde et la couche d'affichage
 *       applique cette rotation-la ;</li>
 *   <li>{@link #here} : les sites qui posent une position en dur, deja dans le repere de la
 *       vue — le joueur lui-meme, dont les bruits sont « sur » lui.</li>
 * </ul>
 */
public final class SfxQueue {

    /** Comment la position du bruit s'obtient. */
    public enum Place {
        /** Deja dans le repere de la vue. */
        VIEW,
        /** Un point du monde, tourne comme le rendu tourne les choses. */
        WORLD,
        /** Un point du monde, tourne comme l'animation des portes le fait. */
        DOOR
    }

    /** Un bruitage demande, avec tout ce que le jeu avait pose avant d'appeler. */
    public static final class Event {
        public final int num;
        public final int vol;
        public final int id;
        public final boolean notIfPlaying;
        public final Place place;
        public final int x;
        public final int z;

        Event(int num, int vol, int id, boolean notIfPlaying, Place place, int x, int z) {
            this.num = num;
            this.vol = vol;
            this.id = id;
            this.notIfPlaying = notIfPlaying;
            this.place = place;
            this.x = x;
            this.z = z;
        }
    }

    private final List<Event> events = new ArrayList<>();

    /**
     * {@code IDNUM}, un MOT.
     *
     * <p>Il reste d'un bruit a l'autre : le tir du joueur n'en ecrit que l'octet de POIDS FORT
     * ({@code move.b #$fb,IDNUM}), et l'octet bas garde ce que le bruit precedent y avait mis.
     */
    public int id;

    /**
     * {@code notifplaying} : refuser le bruit s'il joue deja sous le meme identifiant.
     *
     * <p>Lui aussi reste : l'impact d'une balle et le teleporteur ne l'ecrivent pas, et jouent
     * donc avec ce que le site precedent avait laisse.
     */
    public boolean notIfPlaying;

    /**
     * {@code Aud_SampleNum_w}, un MOT — et il RESTE d'un bruit a l'autre, lui aussi.
     *
     * <p>Un site s'en sert sans le vouloir : le tir d'un alien ({@code FireAtPlayer1},
     * newaliencontrol.s:1155) pose la position, le volume, la voie et l'identifiant, puis
     * appelle {@code MakeSomeNoise} — mais ne pose PAS le numero d'echantillon, et son
     * appelant (ai.s:1077) ne le pose pas davantage. Un alien qui tire joue donc ce que le
     * bruit PRECEDENT a laisse : son propre cri, le fusil du joueur, une porte. Ce n'est pas
     * un choix, c'est un oubli — et il s'entend.
     */
    public int num;

    /** {@code move.b #$fb,IDNUM} : n'ecrit que l'octet de poids fort. */
    public void idHigh(int high) {
        id = ((high & 0xFF) << 8) | (id & 0xFF);
    }

    /** Un bruit a une place du MONDE, tourne comme le rendu tourne les choses. */
    public void at(int num, int vol, int x, int z) {
        add(num, vol, Place.WORLD, x, z);
    }

    /** Un bruit a une place du monde, tourne comme l'animation des portes le fait. */
    public void door(int num, int vol, int x, int z) {
        add(num, vol, Place.DOOR, x, z);
    }

    /** Un bruit dont la place est deja celle de la vue : ceux du joueur. */
    public void here(int num, int vol, int x, int z) {
        add(num, vol, Place.VIEW, x, z);
    }

    /**
     * Un bruit qui ne choisit PAS son echantillon : il joue celui que le site precedent a
     * laisse dans {@link #num}. Un seul endroit du jeu fait cela — le tir d'un alien.
     */
    public void again(int vol, int x, int z) {
        add(num, vol, Place.WORLD, x, z);
    }

    private void add(int num, int vol, Place place, int x, int z) {
        this.num = num;                                // move.w d0,Aud_SampleNum_w : il reste
        if (num >= 0) {
            events.add(new Event(num, vol, id, notIfPlaying, place, x, z));
        }
    }

    /** Recupere et vide la file, une fois par image. */
    public List<Event> drain() {
        if (events.isEmpty()) {
            return List.of();
        }
        List<Event> out = new ArrayList<>(events);
        events.clear();
        return out;
    }
}
