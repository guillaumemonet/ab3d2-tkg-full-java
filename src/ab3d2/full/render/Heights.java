package ab3d2.full.render;

import ab3d2.full.level.Level;

/**
 * Les quatre hauteurs d'un secteur — et son plan d'eau — A CETTE IMAGE.
 *
 * <p>Le fichier de niveau en donne des valeurs de depart, mais trois machines les bougent en
 * cours de partie : une PORTE descend le toit de sa zone, un ASCENSEUR monte son sol, une EAU
 * fait aller et venir son plan. Le dessin ne peut donc pas lire l'enregistrement immuable du
 * fichier — il lirait le niveau tel qu'il etait au chargement, et une porte grande ouverte
 * resterait peinte fermee.
 *
 * <p>C'est exactement ce que faisait le portage : {@code Scene} prenait ses hauteurs dans
 * {@link Level.Zone}, et la simulation les posait de son cote sans que personne les relise.
 * Les deux moities du jeu bougeaient le meme decor chacune pour soi.
 *
 * <p>Un harnais qui ne fait pas tourner la simulation prend {@link #of(Level)} : les hauteurs
 * du fichier, figees, ce qui est precisement ce que le jeu montre a la premiere image.
 */
public interface Heights {

    /** Le sol de l'etage bas. */
    int floor(int zone);

    /** Le plafond de l'etage bas — c'est lui qu'une porte descend. */
    int ceiling(int zone);

    /** Le sol de l'etage haut. */
    int upperFloor(int zone);

    /** Le plafond de l'etage haut. */
    int upperCeiling(int zone);

    /** Le plan d'eau. */
    int water(int zone);

    /** Les hauteurs du FICHIER, figees. */
    static Heights of(Level lvl) {
        int max = 0;
        for (Level.Zone z : lvl.zones()) {
            max = Math.max(max, z.id());
        }
        int[] sol = new int[max + 1];
        int[] toit = new int[max + 1];
        int[] solHaut = new int[max + 1];
        int[] toitHaut = new int[max + 1];
        int[] eau = new int[max + 1];
        for (Level.Zone z : lvl.zones()) {
            sol[z.id()] = z.floor();
            toit[z.id()] = z.ceiling();
            solHaut[z.id()] = z.upperFloor();
            toitHaut[z.id()] = z.upperCeiling();
            eau[z.id()] = z.water();
        }
        return new Heights() {
            @Override
            public int floor(int zone) {
                return sol[zone];
            }

            @Override
            public int ceiling(int zone) {
                return toit[zone];
            }

            @Override
            public int upperFloor(int zone) {
                return solHaut[zone];
            }

            @Override
            public int upperCeiling(int zone) {
                return toitHaut[zone];
            }

            @Override
            public int water(int zone) {
                return eau[zone];
            }
        };
    }
}
