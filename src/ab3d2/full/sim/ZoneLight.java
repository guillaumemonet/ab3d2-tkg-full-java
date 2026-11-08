package ab3d2.full.sim;

/**
 * L'eclairage vu par la BOUCLE : quand le refaire, et quand le faire clignoter.
 *
 * <p>{@link DynLight} dit comment une source ECRIT dans la table ; celle-ci dit quand la table
 * se refait. Les deux moments ne se devinent pas, et les intervertir efface tout :
 *
 * <ul>
 *   <li>{@link #frame} ({@code computeZoneBrightness}) refait les luminosites du secteur
 *       regarde et de son entourage DEPUIS LA TABLE DE DEPART. L'original l'appelle juste
 *       apres le deplacement du joueur et <b>avant</b> l'IA — c'est ce qui laisse les torches
 *       et les tirs ecrire par-dessus sans etre effaces. Appele apres l'IA, il les perdrait
 *       tous, et aucune lumiere mobile ne se verrait jamais ;</li>
 *   <li>{@link #tick} ({@code brightanim}) fait avancer les sept sequences de clignotement.
 *       Il ne touche pas la table de l'image en cours mais celle de DEPART, de sorte que son
 *       effet n'arrive qu'a l'image suivante. L'original l'appelle apres les portes et les
 *       ascenseurs, <b>une image sur cinq</b> ({@code Anim_Timer_w}, newanims.s:801).</li>
 * </ul>
 *
 * <p>Un niveau peut se jouer sans : laisser le champ a {@code null} et rien ne clignote.
 */
public interface ZoneLight extends DynLight {

    /** computeZoneBrightness : refait les luminosites vues depuis ce secteur. */
    void frame(int zone);

    /** brightanim : avance les sequences de clignotement. */
    void tick();
}
