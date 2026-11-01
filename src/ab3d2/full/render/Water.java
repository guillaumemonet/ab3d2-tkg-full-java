package ab3d2.full.render;

import ab3d2.full.level.WaterFrames;

/**
 * Ce qui fait bouger l'eau : trois compteurs, et rien d'autre.
 *
 * <p>Une surface d'eau n'a ni texture propre ni geometrie particuliere. Ce qu'on y voit est le
 * REFLET de ce qui est deja dessine a l'ecran, pris quelques lignes plus loin et repasse par
 * une rampe de miroitement. Trois compteurs suffisent a l'animer, et chacun agit sur une chose
 * differente :
 *
 * <ul>
 *   <li>l'IMAGE, qui change de depart dans {@link WaterFrames} a chaque battement et fait
 *       courir l'ondulation sur la surface ;</li>
 *   <li>l'ANGLE, qui avance de six cent quarante seiziemes de tour et decide de combien de
 *       lignes le reflet est decale — c'est lui qui fait monter et descendre l'image reflechie
 *       ;</li>
 *   <li>le DECALAGE, qui avance d'une unite et glisse la position de lecture dans l'image
 *       d'ondulation, de sorte que deux battements voisins ne montrent pas le meme motif.</li>
 * </ul>
 *
 * <p>Le decalage est masque comme la position de texture des sols, ses deux moities chacune de
 * son cote du mot double : il s'ajoute a elle et doit donc se comporter comme elle. Il faudrait
 * seize mille battements pour que ce masque serve, soit cinq minutes de jeu — aucun corpus ne
 * va si loin, et c'est pourquoi on le porte sur la foi du code et non d'une mesure.
 */
public final class Water {

    /** De combien l'angle avance par battement. */
    private static final int TURN = 640;

    /** Le masque qui ramene l'angle dans un tour, en octets. */
    private static final int ANGLE_MASK = 8191;

    /** Le masque qui tient les deux moities du decalage chacune de son cote. */
    private static final int BOTH = 0x3FFF3FFF;

    private int cursor;
    private int start;
    private int angle;
    private int offset;

    /** Ou commence l'image en cours dans le fichier d'ondulation. */
    public int start() {
        return start;
    }

    /** L'angle du reflet, en octets de table de sinus. */
    public int angle() {
        return angle;
    }

    /** Le glissement de la position de lecture dans l'image d'ondulation. */
    public int offset() {
        return offset;
    }

    /**
     * Un battement.
     *
     * <p>L'image est prise AVANT que le curseur n'avance : au premier battement c'est donc la
     * premiere qui sert, et le curseur ne revient a elle qu'au neuvieme.
     */
    public void tick() {
        start = WaterFrames.STARTS[cursor];
        cursor = (cursor + 1) % WaterFrames.STARTS.length;
        angle = (angle + TURN) & ANGLE_MASK;
        offset = (offset + 1) & BOTH;
    }
}
