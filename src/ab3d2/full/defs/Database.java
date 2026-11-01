package ab3d2.full.defs;

import java.util.List;

/**
 * La base de liaison du jeu : ce que sont les monstres, les objets, les armes et les projectiles.
 *
 * <p>Elle tient dans un seul fichier, {@code test.lnk}, que le jeu charge sans en transformer un
 * octet. Sa disposition est entierement FIXE — aucun offset n'y est ecrit, les tables se suivent
 * a des positions connues d'avance et leurs tailles sont des constantes du code. C'est pour cela
 * qu'on peut la lire directement, comme les niveaux.
 *
 * <p>Un niveau ne contient aucune de ces definitions : il n'y place que des INDEX. Un monstre
 * pose dans une salle est le numero d'une entree d'{@link #aliens()}, un mur le numero d'une
 * entree de {@link #wallTextures()}. C'est ici que tout prend son sens.
 */
public record Database(
        List<LevelInfo> levels,
        List<Resource> wallTextures,
        String floorFile,
        String textureFile,
        List<Resource> spriteSheets,
        List<Resource> vectorModels,
        List<ObjectDef> objects,
        List<AlienDef> aliens,
        List<Gun> guns,
        List<Shoot> alienShots,
        List<BulletDef> bullets,
        List<FloorType> floors,
        List<Integer> ambientSfx,
        List<String> soundFiles,
        int player1Graphic,
        int player2Graphic) {

    /**
     * Les plafonds de l'inventaire, case par case.
     *
     * <p>Ils ne viennent PAS du fichier : {@code game_LoadModProperties} les ecrit en dur au
     * demarrage, et sans cet appel la table resterait a zero -- aucun ramassage ne donnerait
     * quoi que ce soit. D'ou leur place ici, en constantes.
     *
     * <p>Tout est plafonne a dix mille, sauf le carburant du jetpack.
     */
    public static final List<Integer> MAX_INVENTORY = List.of(
            10000, 250, 10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000,
            10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000);

    /** La definition d'objet de rang {@code i}, ou {@code null} s'il n'y en a pas. */
    public ObjectDef object(int i) {
        return i >= 0 && i < objects.size() ? objects.get(i) : null;
    }

    /** La definition de monstre de rang {@code i}, ou {@code null}. */
    public AlienDef alien(int i) {
        return i >= 0 && i < aliens.size() ? aliens.get(i) : null;
    }

    /** L'arme de rang {@code i}, ou {@code null}. */
    public Gun gun(int i) {
        return i >= 0 && i < guns.size() ? guns.get(i) : null;
    }

    /** Le projectile de rang {@code i}, ou {@code null}. */
    public BulletDef bullet(int i) {
        return i >= 0 && i < bullets.size() ? bullets.get(i) : null;
    }

    /** La facon de tirer du monstre de rang {@code i}, ou {@code null}. */
    public Shoot alienShot(int i) {
        return i >= 0 && i < alienShots.size() ? alienShots.get(i) : null;
    }

    /**
     * Le bloc des textures de sol, et le bloc des textures de modeles.
     *
     * <p>Ces deux-la ne sont pas des tables indexees comme {@link #wallTextures()} : ce sont
     * deux fichiers uniques, nommes une seule fois pour tout le jeu. Le second se double d'un
     * troisieme, de meme nom suivi de {@code .pal} : la table d'ombrage, que les sols et les
     * modeles en relief se partagent.
     */
    public String texturePaletteFile() {
        return textureFile + ".pal";
    }

    /** Un niveau : son nom affiche et la musique qui l'accompagne. */
    public record LevelInfo(int index, char letter, String name, String music) { }

    /**
     * Un fichier de graphismes designe par son rang.
     *
     * <p>Textures de mur, feuilles de sprites et modeles vectoriels sont ranges en trois tables
     * separees, et un objet y renvoie par son INDEX, pas par sa position dans les definitions.
     */
    public record Resource(int index, String file) {

        /** Le nom sans son chemin ni son extension. */
        public String name() {
            int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
            String base = slash < 0 ? file : file.substring(slash + 1);
            int dot = base.lastIndexOf('.');
            return dot < 0 ? base : base.substring(0, dot);
        }
    }

    /**
     * Un pas d'un script d'animation, de six octets.
     *
     * <p>La duree d'un pas n'est pas un compteur : elle s'encode par REPETITION, chaque pas
     * portant le rang du suivant. Un pas qui se designe lui-meme fige l'animation.
     *
     * @param gfx   la feuille de sprites, ou le modele vectoriel
     * @param word2 donnee de dessin pour un sprite, increment d'angle pour un modele
     * @param delta ajoute, double, a la hauteur de l'entite
     * @param next  le pas suivant
     */
    public record AnimStep(int gfx, int frame, int word2, int delta, int next) { }

    /**
     * Ce qu'un objet pose dans un niveau est et fait.
     *
     * <p>Il a DEUX scripts d'animation : celui du repos et celui de l'activation. Le jeu bascule
     * de l'un a l'autre en repartant du premier pas.
     *
     * @param ammoGive ce que son ramassage ajoute aux consommables, case par case
     * @param gunGive  ce que son ramassage ajoute aux objets d'inventaire — armes, jetpack
     */
    public record ObjectDef(
            int index,
            String name,
            int behaviour,
            int gfxType,
            int activeTimeout,
            int hitPoints,
            int collideRadius,
            int collideHeight,
            int floorCeiling,
            int lockToWall,
            int sfx,
            List<Integer> ammoGive,
            List<Integer> gunGive,
            List<AnimStep> idleAnim,
            List<AnimStep> activeAnim) {

        /** Vrai si l'objet se dessine comme un modele en trois dimensions. */
        public boolean isVector() {
            return gfxType == 1;
        }

        /** Vrai si l'objet pend au plafond au lieu de reposer sur le sol. */
        public boolean onCeiling() {
            return floorCeiling != 0;
        }
    }

    /**
     * Une image d'une animation de monstre, de onze octets.
     *
     * @param gfx     la feuille de sprites ; NEGATIF, c'est le terminateur, et c'est lui qui dit
     *                ou l'animation reboucle
     * @param frame   le numero d'image, augmente de un ; negatif, l'image est retournee
     * @param sfx     le bruit a jouer, augmente de un
     * @param action  mordre ou tirer sur cette image
     * @param special saut ou attente, codes
     * @param aux     un objet auxiliaire accroche a l'image, comme la flamme d'un lance-flammes
     */
    public record AlienFrame(
            int gfx,
            int frame,
            int word2,
            int sfx,
            int action,
            int special,
            int aux,
            int auxX,
            int auxY) {

        /** Vrai si cette image ferme l'animation. */
        public boolean isEnd() {
            return gfx < 0;
        }
    }

    /**
     * Ce qu'un monstre est : sa resistance, sa taille, et surtout sa CONDUITE.
     *
     * <p>Celle-ci se decrit en quatre etats — par defaut, en reponse, en poursuite, en retraite —
     * chacun avec sa facon de se comporter, sa vitesse et sa duree. Le monstre passe de l'un a
     * l'autre selon les degats qu'il encaisse ({@code damageToRetreat}, {@code damageToFollowup}).
     *
     * @param anims onze options d'animation — marcher de face, de dos, de profil, attaquer, etre
     *              touche, mourir — de vingt images au plus chacune
     */
    public record AlienDef(
            int index,
            String name,
            int gfxType,
            int bright,
            int hitPoints,
            int height,
            int girth,
            int bulletType,
            int splatType,
            int auxiliary,
            int reactionTime,
            int defaultBehaviour,
            int defaultSpeed,
            int responseBehaviour,
            int responseSpeed,
            int responseTimeout,
            int followupBehaviour,
            int followupSpeed,
            int followupTimeout,
            int retreatBehaviour,
            int retreatSpeed,
            int retreatTimeout,
            int damageToRetreat,
            int damageToFollowup,
            List<List<AlienFrame>> anims) {

        /** Vrai si le monstre se dessine comme un modele en trois dimensions. */
        public boolean isVector() {
            return gfxType == 1;
        }
    }

    /**
     * Comment une chose tire : quel projectile, a quelle cadence, par combien.
     *
     * <p>Les monstres ont la meme description que les armes du joueur. Elle leur sert en plus,
     * par un heritage de registre de la routine de collision d'origine, de table d'etendues
     * verticales pour les chocs entre entites.
     */
    public record Shoot(int bulletType, int delay, int bulletCount, int sfx) { }

    /** Une arme du joueur : sa facon de tirer, et l'objet qui la represente en main. */
    public record Gun(int index, String name, Shoot shoot, int gunObject) { }

    /**
     * Un projectile : comment il vole et ce qu'il fait en arrivant.
     *
     * <p>{@code hitScan} distingue les deux grandes familles : le trait instantane, qui touche
     * des qu'il part, et le projectile qui voyage et subit la pesanteur.
     *
     * @param animData l'animation du vol
     * @param popData  l'animation de l'impact
     */
    public record BulletDef(
            int index,
            String name,
            int hitScan,
            int gravity,
            int lifetime,
            int ammoInClip,
            int bounceHoriz,
            int bounceVert,
            int hitDamage,
            int explosiveForce,
            int speed,
            int animFrames,
            int popFrames,
            int bounceSfx,
            int impactSfx,
            int graphicType,
            int impactGraphicType,
            List<AnimStep> animData,
            List<AnimStep> popData) {

        /** Vrai si le tir touche instantanement, sans projectile a faire voyager. */
        public boolean isHitScan() {
            return hitScan != 0;
        }
    }

    /**
     * Un type de sol : ce qu'il coute de s'y tenir, et le bruit qu'on y fait en marchant.
     *
     * <p>Les degats servent aux sols toxiques ; le secteur designe son type par un index.
     */
    public record FloorType(int index, int damage, int sfx) { }
}
