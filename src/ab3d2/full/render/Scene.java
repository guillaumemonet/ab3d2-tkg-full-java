package ab3d2.full.render;

import ab3d2.full.level.Level;

import static ab3d2.full.M68k.s16;

/**
 * La boucle maitresse : ce qu'il faut dessiner, dans quel ordre, et entre quelles bornes.
 *
 * <p>Toutes les autres classes de ce paquet savent dessiner UNE chose — un pan, une surface
 * horizontale, un sprite, le ciel. Aucune ne sait ce qu'il y a a dessiner. C'est ici que ca se
 * decide, et c'est la seule piece du rendu qui parle de tout le reste.
 *
 * <h2>Le parcours</h2>
 *
 * <p>Le jeu tient une liste de secteurs visibles depuis celui ou se trouve le joueur, triee du
 * plus pres au plus loin ({@link ZoneOrder}). Faute de tampon de profondeur, il la parcourt a
 * L'ENVERS : le fond d'abord, et ce qui est devant recouvre.
 *
 * <p>Un secteur peut se voir par PLUSIEURS ouvertures, et il figure alors plusieurs fois dans
 * la liste de visibilite, chaque fois avec sa propre decoupe. Chaque occurrence est un passage
 * complet : on resserre la fenetre, et si elle n'est pas vide on redessine le secteur entier au
 * travers. Le meme mur peut donc etre peint deux fois dans la meme image, par deux fentes
 * differentes.
 *
 * <h2>Les deux etages</h2>
 *
 * <p>Un secteur peut avoir deux etages, et l'ordre entre eux depend d'ou se tient le joueur :
 * sous le plafond du bas, on commence par le bas ; au-dessus, par le haut. Les deux moities ont
 * leur propre habillage, leur propre luminosite, et leur propre rangee dans la table des
 * luminosites de sommets — un mot double plus loin pour l'etage haut.
 *
 * <h2>Une bizarrerie fidelement gardee</h2>
 *
 * <p>Les quatre bornes qui encadrent le plan d'eau ne se posent QUE sur l'etage du bas, parce
 * que l'etage du haut n'a pas d'eau. Mais quand le haut se dessine EN PREMIER, il les trouve
 * telles que le secteur precedent les a laissees. Une commande d'objets « avant l'eau » ou
 * « apres l'eau » a l'etage haut se range donc d'apres un plan d'eau qui n'est pas le sien.
 * Ce n'est pas une faute du portage : l'original fait exactement cela, et {@link Pass} porte
 * ces bornes telles qu'elles sont au moment du passage. Supprimer cette bizarrerie se voit :
 * c'est l'une des mutations du corpus, et elle mord.
 *
 * <h2>Ce que le corpus ne separe pas</h2>
 *
 * <p>Neuf cent soixante poses, quarante et un mille cinq cent quatre commandes, aucun ecart.
 * Sur vingt mutations, dix-sept mordent. Les trois autres se mesurent plutot qu'elles ne
 * s'excusent :
 *
 * <ul>
 *   <li>prendre le secteur du JOUEUR pour racine, au lieu du premier de la liste de
 *       visibilite. Sur les deux mille trois cent quatre-vingt-cinq secteurs des seize
 *       niveaux, la premiere entree de cette liste est TOUJOURS le secteur lui-meme, et
 *       aucune liste n'est vide. Les deux formes coincident donc partout ; on garde celle de
 *       l'original, qui ne suppose rien ;</li>
 *   <li>donner a l'etage haut la luminosite de l'etage bas. Les deux sont egales pour les
 *       deux mille trois cent quatre-vingt-cinq secteurs — mais seulement parce que le releve
 *       est pris sur un niveau qui vient d'etre charge, avant qu'aucune lumiere n'ait bouge.
 *       Celle-la n'est pas demontree inobservable : elle est hors de portee d'un oracle
 *       statique, et le dira des que l'eclairage animé sera du voyage ;</li>
 *   <li>traiter une commande inconnue comme un pan. Les seize niveaux comptent treize mille
 *       neuf cent trente-deux commandes et pas une seule inconnue. La tolerance est fidele a
 *       l'original, et ne sert jamais.</li>
 * </ul>
 */
public final class Scene {

    /**
     * Ce qu'un passage de secteur fixe, et que chaque commande retrouve.
     *
     * <p>Toutes ces valeurs sont des variables globales dans l'original — elles y sont posees
     * avant d'appeler le dessin et relues par lui. Les reunir en un objet immuable dit la meme
     * chose sans la memoire plate, et rend visible ce dont chaque commande depend vraiment.
     *
     * @param zone              le secteur en cours
     * @param upper             son etage haut, plutot que son etage bas
     * @param bright            la luminosite de l'etage
     * @param leftClip          la premiere colonne ou peindre
     * @param rightClip         la premiere colonne ou ne plus peindre
     * @param topOfRoom         le plafond de l'etage, en hauteur du monde
     * @param bottomOfRoom      son sol
     * @param beforeWaterTop    les quatre bornes du plan d'eau, telles qu'elles sont AU MOMENT
     *                          du passage — voir la bizarrerie decrite plus haut
     * @param beforeWaterBottom idem
     * @param afterWaterTop     idem
     * @param afterWaterBottom  idem
     * @param rootZone          le secteur est celui ou se tient le joueur
     */
    public record Pass(int zone, boolean upper, int bright, int leftClip, int rightClip,
            int topOfRoom, int bottomOfRoom, int beforeWaterTop, int beforeWaterBottom,
            int afterWaterTop, int afterWaterBottom, boolean rootZone) {

        /**
         * Le decalage de l'etage dans la table des luminosites de sommets.
         *
         * <p>Chaque point de bord y a quatre mots : sol et plafond de l'etage bas, puis sol et
         * plafond de l'etage haut. L'original ne change pas d'index mais de POINTEUR, en
         * ajoutant quatre octets pour l'etage haut ; c'est le meme deux.
         */
        public int brightSlot() {
            return upper ? 2 : 0;
        }

        /** Le haut et le bas que prend une commande d'objets, selon son moment. */
        public int objectsTop(Level.Objects.Moment moment) {
            return switch (moment) {
                case BEFORE_WATER -> beforeWaterTop;
                case AFTER_WATER -> afterWaterTop;
                case WHOLE_ROOM -> topOfRoom;
            };
        }

        /** Le bas que prend une commande d'objets, selon son moment. */
        public int objectsBottom(Level.Objects.Moment moment) {
            return switch (moment) {
                case BEFORE_WATER -> beforeWaterBottom;
                case AFTER_WATER -> afterWaterBottom;
                case WHOLE_ROOM -> bottomOfRoom;
            };
        }
    }

    /**
     * Qui dessine, quand la boucle a decide quoi.
     *
     * <p>La separation n'est pas une commodite : elle dit exactement ou passe la frontiere du
     * portage. La boucle est fidele de bout en bout ; ce qu'elle appelle l'est aussi, mais
     * toutes les voies ne sont pas encore ouvertes — les modeles vectoriels, les sprites
     * eclaires et le miroitement manquent encore. Un peintre qui se contente de NOTER ce qu'on
     * lui demande eprouve donc la boucle seule, sans attendre le reste.
     */
    public interface Painter {

        /** Un pan de mur. */
        void wall(Pass pass, Level.Wall wall);

        /** Une surface horizontale : sol, plafond ou plan d'eau. */
        void flat(Pass pass, Level.Flat flat);

        /** Les choses du secteur, a ce moment de la traversee. */
        void objects(Pass pass, Level.Objects objects);

        /** Le ciel. */
        void sky(Pass pass, Level.Sky sky);
    }

    private final ZoneClip clip = new ZoneClip();

    /**
     * Dessine tout ce qui se voit.
     *
     * @param level   le niveau
     * @param from    le secteur ou se tient le joueur : c'est SA liste de visibilite qu'on suit
     * @param order   l'ordre des secteurs, deja trie
     * @param edges   les bornes que les ouvertures imposent a chaque secteur
     * @param lights  les luminosites du moment : ce sont elles, et non celles du fichier
     * @param view    la vue, deja tournee
     * @param playerY la hauteur de l'oeil, en virgule fixe comme le jeu la tient
     * @param painter qui dessine
     */
    public void draw(Level level, Heights hauteurs, Level.Zone from, ZoneOrder order,
            VisibleEdges edges, Lights lights, View view, int playerY, Painter painter) {
        if (from.visible().isEmpty()) {
            return;
        }
        // Le secteur RACINE est celui de la premiere entree de la liste, et non celui du
        // joueur : c'est ce que lit l'original, et les deux ne se confondent pas toujours.
        int root = from.visible().get(0).zone();

        int[] ordre = order.order();
        for (int i = ordre.length - 1; i >= 0; i--) {   // .subroomloop, a rebours
            int zoneId = ordre[i];
            if (!edges.draws(zoneId)) {
                continue;                              // ZoneT_Unused_w nul : rien de visible
            }
            int startLeft = edges.startLeft(zoneId);
            int startRight = edges.startRight(zoneId, view);
            Level.Zone zone = level.zones().get(zoneId);

            // .finditit : toutes les entrees de la liste qui parlent de ce secteur, et pas
            // seulement la premiere — un secteur vu par deux ouvertures se dessine deux fois.
            for (Level.Visible v : from.visible()) {
                if (v.zone() != zoneId) {
                    continue;
                }
                clip.narrow(level, view, v.clip(), startLeft, startRight);
                if (!clip.visible()) {
                    continue;                          // .skip_not_visible
                }
                boolean rootZone = v.zone() == root;   // seq Draw_InRootZone_b
                if (playerY < hauteurs.ceiling(zoneId)) { // cmp.l Zone_SplitHeight_l,d0 ; blt
                    lowerFirst(zone, hauteurs, lights, playerY, rootZone, painter);
                } else {
                    upperFirst(zone, hauteurs, lights, playerY, rootZone, painter);
                }
            }
        }
    }

    /** .lower_zone_first — l'etage bas d'abord : le joueur est sous le plafond du bas. */
    private void lowerFirst(Level.Zone zone, Heights hauteurs, Lights lights, int playerY,
            boolean rootZone, Painter painter) {
        lower(zone, hauteurs, lights, playerY, rootZone, painter);
        if (!zone.hasUpperGraphics()) {
            return;                                    // .noupperroom2
        }
        upper(zone, hauteurs, lights, rootZone, painter);
    }

    /** .ready_upper — l'etage haut d'abord, s'il existe, puis le bas de toute facon. */
    private void upperFirst(Level.Zone zone, Heights hauteurs, Lights lights, int playerY,
            boolean rootZone, Painter painter) {
        if (zone.hasUpperGraphics()) {                 // .lower_zone_only si non
            upper(zone, hauteurs, lights, rootZone, painter);
        }
        lower(zone, hauteurs, lights, playerY, rootZone, painter);
    }

    /**
     * L'etage HAUT.
     *
     * <p>Il ne pose pas les bornes du plan d'eau : il n'a pas d'eau. Elles restent donc celles
     * du dernier etage bas traite, et une commande d'objets qui s'y refere les prend telles
     * quelles — voir l'avertissement en tete de classe.
     */
    private void upper(Level.Zone zone, Heights hauteurs, Lights lights, boolean rootZone,
            Painter painter) {
        render(zone.upper(), new Pass(zone.id(), true, lights.zoneBright(zone.id(), true),
                clip.left(), clip.right(), hauteurs.upperCeiling(zone.id()),
                hauteurs.upperFloor(zone.id()),
                beforeWaterTop, beforeWaterBottom, afterWaterTop, afterWaterBottom,
                rootZone), painter);
    }

    /** L'etage BAS, et avec lui les quatre bornes du plan d'eau. */
    private void lower(Level.Zone zone, Heights hauteurs, Lights lights, int playerY,
            boolean rootZone, Painter painter) {
        int top = hauteurs.ceiling(zone.id());
        int bottom = hauteurs.floor(zone.id());
        int water = hauteurs.water(zone.id());
        if (water >= playerY) {                        // cmp.l Plr_YOff_l,d2 ; blt
            // Le joueur est AU-DESSUS de l'eau : ce qui vient avant, c'est le volume immerge.
            beforeWaterTop = water;
            beforeWaterBottom = bottom;
            afterWaterBottom = water;
            afterWaterTop = top;
        } else {
            // Le joueur a la tete sous l'eau : l'ordre s'inverse.
            beforeWaterTop = top;
            beforeWaterBottom = water;
            afterWaterBottom = bottom;
            afterWaterTop = water;
        }
        render(zone.lower(), new Pass(zone.id(), false, lights.zoneBright(zone.id(), false),
                clip.left(), clip.right(), top, bottom,
                beforeWaterTop, beforeWaterBottom, afterWaterTop, afterWaterBottom,
                rootZone), painter);
    }

    private int beforeWaterTop;
    private int beforeWaterBottom;
    private int afterWaterTop;
    private int afterWaterBottom;

    /** draw_RenderCurrentZone — la suite de commandes d'un etage, dans l'ordre du fichier. */
    private void render(Level.Graphics graphics, Pass pass, Painter painter) {
        if (graphics == null) {
            return;
        }
        for (Level.Paint commande : graphics.program()) {
            if (commande instanceof Level.Wall wall) {
                painter.wall(pass, wall);
            } else if (commande instanceof Level.Flat flat) {
                painter.flat(pass, flat);
            } else if (commande instanceof Level.Objects objects) {
                painter.objects(pass, objects);
            } else if (commande instanceof Level.Sky sky) {
                painter.sky(pass, sky);
            }
            // Level.Unknown : l'original passe au mot suivant sans se plaindre.
        }
    }

    /**
     * La luminosite d'un bout de pan, telle que le dessin du mur la demande.
     *
     * <p>Elle ne vient pas du secteur en cours mais, quand le pan le demande, de CELUI D'A COTE
     * — un mur qui donne sur une autre piece prend la lumiere de cette piece-la. Le quatrieme
     * bit du champ dit lequel des deux, et les trois premiers disent quel emplacement.
     *
     * <p>La valeur lue est prise en VALEUR ABSOLUE avant qu'on y ajoute l'ecart propre au pan :
     * une luminosite negative est une lumiere animee, et le rendu n'en garde que l'amplitude.
     *
     * @param haut  vrai pour le haut du pan, qui a sa propre rangee de quatre bits
     * @param droit vrai pour le bout droit
     */
    public static int wallBright(Lights lights, Pass pass, Level.Wall wall, boolean haut,
            boolean droit) {
        int champ = haut ? (wall.brightRow() & 0xFF) >>> 4 : wall.brightRow() & 0xFF;
        int zone = (champ & 8) != 0 ? wall.otherZone() : pass.zone();
        int slot = (champ & 7) + pass.brightSlot();
        int point = droit ? wall.rightSlot() : wall.leftSlot();
        int v = s16(lights.pointBright(zone, point, slot));
        if (v < 0) {
            v = -v;                                    // neg.w d0
        }
        return s16(v + wall.brightOffset());
    }

    /**
     * Ou une surface horizontale lit les luminosites de ses sommets.
     *
     * <p>C'est l'arithmetique de pointeur de l'original, ramenee a un rang de mot : le secteur
     * d'abord, puis un mot pour un plafond, puis deux pour l'etage haut.
     */
    public static int flatBrightsAt(Pass pass, Level.Flat flat) {
        return pass.zone() * Lights.POINTS * Lights.SLOTS
                + (flat.kind() == Level.Surface.CEILING ? 1 : 0)
                + pass.brightSlot();
    }
}
