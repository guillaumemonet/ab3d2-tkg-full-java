package ab3d2.full.render;

import ab3d2.full.level.Level;

/**
 * CE QU'UNE PORTE OU UN ASCENSEUR FAIT AUX MURS QU'IL EMPORTE.
 *
 * <p>Une porte du jeu n'est pas un objet qui glisse devant un trou : c'est un ensemble de PANS
 * DE MUR dont le moteur REECRIT les enregistrements, image apres image, pendant qu'elle
 * s'ouvre. Deux valeurs, pas une :
 *
 * <ul>
 *   <li>le <b>bas</b> du pan ({@code move.l d3,24(a1)}, newanims.s) — c'est le battant qui
 *       remonte, et c'est ce qui ouvre le passage ;</li>
 *   <li>le <b>decalage vertical de sa texture</b> ({@code move.w a2,12(a1)}) — base propre au
 *       pan moins la position courante, pour que le dessin de la porte MONTE AVEC ELLE au lieu
 *       de glisser dessus.</li>
 * </ul>
 *
 * <p>Le portage litteral le fait en ecrivant dans la memoire du niveau. Cette conversion tient
 * le niveau pour IMMUABLE — c'est une de ses regles — et doit donc refaire l'enregistrement a
 * la volee. D'ou cette interface : le dessin demande, pour chaque pan, la version que la
 * simulation vient d'arreter.
 *
 * <p>Sans elle, la simulation ouvre la porte — on passe au travers — mais le battant reste
 * peint en place. C'est ce qui donnait des portes qui « ne marchent pas » alors que
 * {@code animsCheck} passait : la simulation etait juste, le dessin ne la lisait pas.
 */
public interface Deform {

    /**
     * Le pan tel qu'il faut le dessiner MAINTENANT.
     *
     * @return l'enregistrement du fichier si rien ne le deforme, une copie modifiee sinon
     */
    Level.Wall apply(Level.Wall wall);

    /** Rien ne bouge : c'est ce que voient les harnais et la prise d'image statique. */
    Deform NONE = wall -> wall;
}
