package ab3d2.full.sound;

import ab3d2.full.M68k;

/**
 * LE MOTEUR AUDIO : huit voix melangees a la main dans les quatre canaux de la puce.
 *
 * <p>L'Amiga n'a que QUATRE voix materielles, et deux d'entre elles sortent a gauche, deux a
 * droite. Le jeu en veut huit, et en stereo : il les melange donc lui-meme, deux par deux, dans
 * chaque canal. Tout ce fichier est ce melange et ce qui le precede.
 *
 * <h2>Choisir une voix : {@code MakeSomeNoise} (hires.s:7517)</h2>
 *
 * <p>Un bruit arrive avec un numero d'echantillon, une position <b>deja tournee dans le repere
 * de la vue</b>, un volume et un identifiant. Trois choses se decident :
 *
 * <ol>
 *   <li>son IMPORTANCE : le volume multiplie par soixante-quatre, divise par la distance. La
 *       distance est une RACINE CARREE entiere, calculee par deux pas de Newton a partir de la
 *       plus haute puissance de deux — le jeu n'avait pas de racine, il en a fait une ;</li>
 *   <li>ses deux VOLUMES, gauche et droit : l'importance, plafonnee a soixante-quatre, dont on
 *       retranche d'un cote ce que l'abscisse donne a l'autre. Le cote le plus fort voit en
 *       outre son echantillon demarre QUATRE OCTETS plus loin, ce qui suffit a decaler les deux
 *       oreilles ;</li>
 *   <li>sa VOIX : la moins importante de celles qui jouent, et seulement si le nouveau bruit
 *       est plus important qu'elle.</li>
 * </ol>
 *
 * <h2>Melanger : {@code newsampbitl} (hires.s:6861)</h2>
 *
 * <p>Deux voix par canal. La plus FORTE passe telle quelle et donne son volume au canal
 * materiel ; la plus faible est mise a l'echelle par une table de soixante-cinq rampes de deux
 * cent cinquante-six octets, puis ajoutee. L'addition se fait QUATRE OCTETS A LA FOIS, par une
 * seule addition de trente-deux bits : les retenues passent donc d'un echantillon au suivant.
 * Ce n'est pas un effet voulu, c'est le prix du procede — et il s'entend, donc on le garde.
 *
 * <h2>Ce qui n'est pas porte, et pourquoi</h2>
 *
 * <ul>
 *   <li>{@code LEFTPLAYEDTAB}, {@code RIGHTPLAYEDTAB}, {@code Aud_NeedLeft_b} et
 *       {@code Aud_NeedRight_b} sont ECRITS et jamais lus, dans tout le jeu ;</li>
 *   <li>les drapeaux {@code NoiseMade*} ne sont lus que par la voie QUATRE CANAUX et par la
 *       relance du DMA, qui l'est aussi — or le reglage par defaut est HUIT canaux ;</li>
 *   <li>le double tampon de sortie : la routine donne au DMA le tampon qu'elle va reecrire
 *       aussitot, course que seul le materiel arbitre. L'hote joue les deux cents octets qui
 *       viennent d'etre melanges, ce qui est le resultat voulu.</li>
 * </ul>
 *
 * <h2>Ce que le corpus ne separe pas</h2>
 *
 * <p>Cinq mutations survivent, et les cinq se demontrent :
 *
 * <ul>
 *   <li>l'importance de depart, trente-deux mille sept cent soixante-sept, est toujours
 *       REECRITE avant d'etre lue. C'est une ecriture morte, et le jeu la fait quand meme ;</li>
 *   <li>plafonner a soixante-quatre avec {@code >} ou {@code >=} donne la meme chose, puisque
 *       la valeur posee est justement soixante-quatre ;</li>
 *   <li>le garde-fou de Newton, « jamais moins de un », ne peut pas distinguer zero de negatif :
 *       le pas vaut {@code x - (x*x-a)/2x}, et pour qu'il tombe a zero il faudrait que
 *       {@code a} vaille moins {@code x*x}. Or {@code a} est une somme de deux carres ;</li>
 *   <li>le partage gauche/droite ne fait rien quand la part est nulle, de quelque cote qu'on
 *       la verse ;</li>
 *   <li>echanger les deux voix d'un canal ne s'entend pas, et c'est plus surprenant : si leurs
 *       volumes different, la routine les RETRIE elle-meme ; s'ils sont egaux, la rampe choisie
 *       est la soixante-quatrieme, qui est l'identite, et une addition est commutative.</li>
 * </ul>
 */
public final class Paula {

    /** Combien d'octets un melange produit par canal : {@code move.w #100,$dff0a4}, en mots. */
    public static final int FRAME_BYTES = 200;

    /** La periode ecrite dans les canaux : {@code move.w #443,$dff0a6}. */
    public static final int PERIOD = 443;

    /** L'horloge de la puce, en PAL. */
    public static final int PAL_CLOCK = 3546895;

    /** Le volume maximal d'un canal. */
    public static final int MAX_VOLUME = 64;

    /** Combien de voix logicielles de chaque cote. */
    public static final int VOICES = 4;

    /**
     * Le silence : {@code Aud_EmptyBuffer_vl: ds.l 100}, quatre cents octets nuls.
     *
     * <p>Une voix qui n'a rien a dire ne se tait pas : elle LIT ce tampon. C'est pourquoi le
     * melange n'a jamais de cas particulier.
     */
    private static final byte[] SILENCE = new byte[400];

    /**
     * Une VOIX logicielle : l'echantillon qu'elle lit, ou elle en est, ou elle s'arrete, et son
     * volume.
     *
     * <p>Dans le jeu ce sont quatre adresses — {@code pos0LEFT}, {@code Samp0endLEFT},
     * {@code vol0left} — et la fin est un POINTEUR, pas une longueur.
     */
    private static final class Voice {
        byte[] sample = SILENCE;
        int pos;
        int end = SILENCE.length;
        int vol;

        void silence() {
            sample = SILENCE;
            pos = 0;
            end = SILENCE.length;
            vol = 0;
        }
    }

    /**
     * Une case de {@code CHANNELDATA} : quatre octets, dont le premier dit si la MUSIQUE la
     * tient, et le mot a deux l'importance de ce qui y joue. L'identifiant vit trente-deux
     * octets plus loin, dans la table qui suit les huit cases.
     */
    private static final class Slot {
        boolean busy;
        int importance;
        int id;
    }

    /** Les huit cases, gauche d'abord : c'est l'ordre ou le jeu les parcourt. */
    private final Slot[] slots = new Slot[2 * VOICES];
    private final Voice[] left = new Voice[VOICES];
    private final Voice[] right = new Voice[VOICES];

    /** La table des rampes : soixante-cinq volumes de deux cent cinquante-six valeurs. */
    private final byte[] tab = new byte[256 * 65];

    /** Les quatre canaux materiels : ce qu'ils jouent, et a quel volume. */
    private final byte[][] out = new byte[4][FRAME_BYTES];
    private final int[] paulaVol = new int[4];

    private final Samples bank;

    /** {@code Aud_Stereo_b} : vrai au demarrage. */
    public boolean stereo = true;

    /** {@code noiseloud} : l'importance du bruit en cours de placement. */
    private int noiseloud;

    /** Le decalage de quatre octets donne au cote le plus fort. */
    private int leftOffset;
    private int rightOffset;

    public Paula(Samples bank) {
        this.bank = bank;
        for (int i = 0; i < slots.length; i++) {
            slots[i] = new Slot();
        }
        for (int i = 0; i < VOICES; i++) {
            left[i] = new Voice();
            right[i] = new Voice();
        }
        for (int i = 0; i < 4; i++) {
            hw[i] = new Hardware();
        }
        // Les deux dernieres cases de chaque cote sont INDISPONIBLES au depart
        // (dc.l $FF000000), et le reglage huit canaux les libere (seq CHANNELDATA+8...).
        buildTable();
    }

    /**
     * La table des rampes (hires.s:571, {@code outerlop}).
     *
     * <p>Pour chaque volume de zero a soixante-quatre, les deux cent cinquante-six valeurs d'un
     * octet SIGNE multipliees par ce volume et ramenees de six bits. L'index de la table est
     * l'octet non signe, donc la seconde moitie y est negative — c'est tout ce que
     * {@code pretab} dit.
     */
    private void buildTable() {
        int at = 0;
        for (int d6 = 0; d6 <= MAX_VOLUME; d6++) {     // move.w #64,d7 ; dbra -> 65 tours
            for (int b = 0; b < 256; b++) {
                int d0 = (byte) (b < 128 ? b : b - 256);   // pretab : 0..127 puis -128..-1
                d0 = M68k.muls(d0, d6);                // muls d6,d0
                d0 >>= 6;                              // asr.l #6,d0
                tab[at++] = (byte) d0;                 // move.b d0,(a1)+
            }
        }
    }

    // ------------------------------------------------------------------------------
    // LES REGISTRES DE LA PUCE, tels que la MUSIQUE les ecrit.
    //
    // Le melangeur, lui, n'ecrit jamais de periode : il produit toujours ses deux cents
    // octets a la periode quatre cent quarante-trois, celle que le jeu pose au demarrage. La
    // musique, elle, change de periode a chaque note — c'est ainsi qu'un seul echantillon
    // donne toutes les notes. Un canal tenu par la musique est donc RELU a une autre cadence
    // que celle de la sortie, et c'est le seul endroit de ce portage ou un echantillon est
    // reechantillonne.
    // ------------------------------------------------------------------------------

    /** Ce qu'un canal joue quand la musique le tient. */
    private static final class Hardware {
        byte[] data;
        int start;
        int lengthWords;
        int period;
        /** Le segment en cours de lecture : la puce le LATCHE au demarrage du canal. */
        byte[] segData;
        int segStart;
        int segWords;
        double segPos;
        boolean active;
    }

    private final Hardware[] hw = new Hardware[4];

    /** {@code move.l d,(a5)} : ou le canal lit. */
    public void audLC(int ch, byte[] data, int start) {
        hw[ch].data = data;
        hw[ch].start = start;
    }

    /** {@code move.w d,4(a5)} : combien de MOTS il lit. */
    public void audLEN(int ch, int words) {
        hw[ch].lengthWords = words & 0xFFFF;
    }

    /** {@code move.w d,6(a5)} : sa periode, donc sa hauteur. */
    public void audPER(int ch, int period) {
        hw[ch].period = period & 0xFFFF;
    }

    /** {@code move.w d,8(a5)} : son volume. C'est le MEME registre que celui du melangeur. */
    public void audVOL(int ch, int vol) {
        paulaVol[ch] = vol & 0xFFFF;
    }

    /**
     * {@code move.w d,$dff096} : demarre ou arrete des canaux.
     *
     * <p>Le bit de poids fort dit s'il faut ARMER ou ETEINDRE ; les quatre bits bas disent
     * lesquels. Au demarrage, la puce prend une COPIE de l'adresse et de la longueur : c'est
     * ce qui permet au lecteur de musique d'y ecrire aussitot apres le point de BOUCLE, sans
     * couper la note qui commence.
     */
    public void dmacon(int value) {
        value &= 0xFFFF;
        if ((value & 0x8000) == 0) {
            dmaconBits &= ~value;                      // on ETEINT les canaux nommes
            for (int ch = 0; ch < 4; ch++) {
                if ((value >> ch & 1) != 0) {
                    hw[ch].active = false;
                }
            }
            return;
        }
        int bits = value & 0x7FFF;
        for (int ch = 0; ch < 4; ch++) {
            int bit = 1 << ch;
            // La copie ne se prend qu'au PASSAGE de l'arret a la marche : rearmer un canal qui
            // tourne deja ne le fait pas repartir, et c'est sur cela que repose la ruse du
            // point de boucle.
            if ((bits & bit) != 0 && (dmaconBits & bit) == 0) {
                hw[ch].active = true;
                hw[ch].segData = hw[ch].data;
                hw[ch].segStart = hw[ch].start;
                hw[ch].segWords = hw[ch].lengthWords;
                hw[ch].segPos = 0;
            }
        }
        dmaconBits |= bits;
    }

    /** Quels canaux la puce a en marche. */
    private int dmaconBits;

    /** Le registre de marche, pour qui veut l'eprouver. */
    public int dmaconBits() {
        return dmaconBits;
    }

    /** Ou en est la lecture d'un canal tenu par la musique, en octets. */
    public int hwPos(int ch) {
        return (int) hw[ch].segPos;
    }

    /** Le segment que la puce a copie au demarrage de ce canal : son depart. */
    public int hwSegStart(int ch, int nul) {
        if (hw[ch].segData == null) {
            return -1;
        }
        return hw[ch].segStart == nul ? -2 : hw[ch].segStart;
    }

    /** Et sa longueur, en mots. */
    public int hwSegWords(int ch) {
        return hw[ch].segWords;
    }

    /**
     * L'adresse que les registres portent MAINTENANT, ou moins un si aucune.
     *
     * <p>{@code nul} est le depart de l'echantillon NUL, que l'appelant connait : il ne vit pas
     * dans le module, et c'est le seul moyen de le nommer d'un cote comme de l'autre.
     */
    public int hwStart(int ch, int nul) {
        if (hw[ch].data == null) {
            return -1;
        }
        return hw[ch].start == nul ? -2 : hw[ch].start;
    }

    /** La longueur qu'ils portent, en mots. */
    public int hwWords(int ch) {
        return hw[ch].lengthWords;
    }

    /** La periode qu'ils portent. */
    public int hwPeriod(int ch) {
        return hw[ch].period;
    }

    /** Vrai si ce canal est tenu par la musique plutot que par le melangeur. */
    public boolean driven(int ch) {
        return hw[ch].active;
    }

    /**
     * Les deux voies de la trame qui vient d'etre melangee.
     *
     * <p>Sur Amiga, les canaux ZERO et TROIS sortent a gauche, UN et DEUX a droite. Ce n'est
     * pas un choix, c'est le cablage.
     */
    public void render(int[] gauche, int[] droite) {
        for (int i = 0; i < FRAME_BYTES; i++) {
            gauche[i] = sample(0, i) + sample(3, i);
            droite[i] = sample(1, i) + sample(2, i);
        }
    }

    /**
     * Un echantillon de sortie d'un canal, a l'echelle de son volume.
     *
     * <p>Un canal melange se lit tel quel : il a ete produit a la periode de sortie. Un canal
     * tenu par la musique avance d'autant plus vite que sa periode est COURTE, et reboucle
     * sur ce que le lecteur a pose dans ses registres une fois la note lancee.
     */
    private int sample(int ch, int i) {
        Hardware h = hw[ch];
        if (!h.active) {
            return out[ch][i] * paulaVol[ch] / MAX_VOLUME;
        }
        if (h.period <= 0 || paulaVol[ch] == 0) {
            return 0;
        }
        int octets = h.segWords * 2;
        int at = (int) h.segPos;
        if (h.segData == null || octets < 2 || at >= octets) {
            // Le segment est fini : la puce reprend ce que les registres disent MAINTENANT,
            // c'est-a-dire le point de boucle que le lecteur y a ecrit.
            h.segData = h.data;
            h.segStart = h.start;
            h.segWords = h.lengthWords;
            h.segPos = 0;
            at = 0;
            octets = h.segWords * 2;
            if (h.segData == null || octets < 2) {
                return 0;
            }
        }
        int k = h.segStart + at;
        int v = k >= 0 && k < h.segData.length ? h.segData[k] : 0;
        h.segPos += (double) PERIOD / h.period;
        return v * paulaVol[ch] / MAX_VOLUME;
    }

    /** La table des rampes, pour qui veut l'eprouver. */
    public byte[] table() {
        return tab;
    }

    /** La frequence de sortie, en hertz : l'horloge divisee par la periode. */
    public static int sampleRate() {
        return PAL_CLOCK / PERIOD;
    }

    /** Les deux cents octets que le canal vient de recevoir. */
    public byte[] channel(int paulaChannel) {
        return out[paulaChannel];
    }

    /** Le volume du canal, de zero a soixante-quatre. */
    public int volume(int paulaChannel) {
        return paulaVol[paulaChannel];
    }

    /** Vrai si ce canal materiel sort a GAUCHE : les canaux zero et trois, sur Amiga. */
    public static boolean isLeft(int paulaChannel) {
        return paulaChannel == 0 || paulaChannel == 3;
    }

    /**
     * La MUSIQUE prend une case, ou la rend ({@code st CHANNELDATA}).
     *
     * @param slot de zero a trois pour la gauche, de quatre a sept pour la droite
     */
    public void claim(int slot, boolean busy) {
        slots[slot].busy = busy;
    }

    /** Vrai si la case est tenue par la musique. */
    public boolean claimed(int slot) {
        return slots[slot].busy;
    }

    /** Oublie ce qui jouait dans cette case : importance et identifiant a zero. */
    public void forget(int slot) {
        slots[slot].importance = 0;
        slots[slot].id = 0;
    }

    /** L'importance du dernier bruit place : {@code noiseloud}. */
    public int noiseloud() {
        return noiseloud;
    }

    /** Le decalage de quatre octets donne au cote gauche, ou zero. */
    public int leftOffset() {
        return leftOffset;
    }

    /** Celui du cote droit. */
    public int rightOffset() {
        return rightOffset;
    }

    /** L'importance de ce qui joue dans cette case. */
    public int importance(int slot) {
        return slots[slot].importance;
    }

    /** L'identifiant de ce qui joue dans cette case. */
    public int id(int slot) {
        return slots[slot].id;
    }

    /** Le volume d'une voix logicielle. */
    public int voiceVolume(boolean gauche, int voice) {
        return (gauche ? left : right)[voice].vol;
    }

    /** Ou en est une voix logicielle dans son echantillon. */
    public int voicePos(boolean gauche, int voice) {
        return (gauche ? left : right)[voice].pos;
    }

    /** Ou une voix logicielle s'arretera. */
    public int voiceEnd(boolean gauche, int voice) {
        return (gauche ? left : right)[voice].end;
    }

    /** Le rang de l'echantillon qu'une voix lit, ou moins un si elle se tait. */
    public int voiceSample(boolean gauche, int voice) {
        Voice v = (gauche ? left : right)[voice];
        return v.sample == SILENCE ? -1 : bank.indexOf(v.sample);
    }

    /**
     * Tout remettre a zero : les huit voix, les huit cases, et les volumes des canaux.
     *
     * <p>Le jeu ne fait jamais cela d'un seul geste — son etat de depart vient de la
     * declaration des donnees et du reglage du menu. C'est le harnais qui en a besoin, pour
     * partir d'un etat connu.
     */
    public void reset(int busyMask, boolean stereo) {
        silence();
        for (int i = 0; i < slots.length; i++) {
            slots[i].busy = (busyMask >> i & 1) != 0;
            slots[i].importance = 0;
            slots[i].id = 0;
        }
        java.util.Arrays.fill(paulaVol, 0);
        leftOffset = 0;
        rightOffset = 0;
        this.stereo = stereo;
    }

    /** Remet les huit voix au silence. */
    public void silence() {
        for (int i = 0; i < VOICES; i++) {
            left[i].silence();
            right[i].silence();
        }
    }

    /**
     * {@code MakeSomeNoise} : place un bruit, s'il merite une voix.
     *
     * @param sampleNum    le rang de l'echantillon
     * @param noiseX       son abscisse DANS LE REPERE DE LA VUE
     * @param noiseZ       sa profondeur, dans le meme repere
     * @param noiseVol     le volume demande par l'appelant
     * @param id           l'identifiant de la source, ou moins un si elle n'en a pas
     * @param notIfPlaying vrai si le bruit ne doit pas se superposer a lui-meme
     */
    public void play(int sampleNum, int noiseX, int noiseZ, int noiseVol, int id,
            boolean notIfPlaying) {
        if (notIfPlaying) {                            // tst.b notifplaying ; beq.s dontworry
            int d0 = M68k.setw(0, id);                 // move.w IDNUM,d0
            if ((d0 & 0xFFFF) != 0xFFFF) {             // cmp.w #$ffff,d0 ; beq.s dontworry
                for (int i = 0; i < slots.length; i++) {   // move.w #7,d1 ; findsameasme
                    if (!slots[i].busy                 // tst.b (a3) ; bne.s notavail
                            && (short) slots[i].id == (short) d0) {
                        return;                        // SameAsMe : il joue deja
                    }
                }
            }
        }

        // dontworry : l'importance, puis les deux volumes.
        int d1 = M68k.muls(noiseX, noiseX);            // move.w Aud_NoiseX_w,d1 ; muls d1,d1
        int d2 = M68k.muls(noiseZ, noiseZ);            // move.w Aud_NoiseZ_w,d2 ; muls d2,d2
        int d3 = M68k.setw(0, noiseVol);               // move.w Aud_NoiseVol_w,d3
        noiseloud = 32767;                             // move.w #32767,noiseloud
        int d0 = 1;                                    // moveq #1,d0
        d2 = d1 + d2;                                  // add.l d1,d2
        if (d2 != 0) {                                 // beq pastcalc
            // La RACINE CARREE : partir de la plus haute puissance de deux, puis deux pas de
            // Newton. Le depart est 2^(h/2), ou h est le rang du bit le plus haut.
            d0 = 31;                                   // move.w #31,d0
            while (d0 >= 0 && (d2 & (1 << (d0 & 31))) == 0) {  // btst d0,d2 ; dbra
                d0--;
            }
            d0 = M68k.asrw(d0, 1);                     // asr.w #1,d0
            d0 = 1 << (d0 & 31);                       // clr.l d3 ; bset d0,d3 ; move.l d3,d0

            d0 = newton(d0, d2);
            d0 = newton(d0, d2);

            d3 = (short) noiseVol;                     // move.w Aud_NoiseVol_w,d3 ; ext.l d3
            d3 <<= 6;                                  // asl.l #6,d3
            if (d3 > 32767) {                          // cmp.l #32767,d3 ; ble.s .nnnn
                d3 = 32767;
            }
            d0 = M68k.asrw(d0, 2);                     // asr.w #2,d0
            d0 = M68k.setw(d0, d0 + 1);                // addq #1,d0
            d3 = M68k.divs(d3, d0);                    // divs d0,d3
        }
        // pastcalc
        noiseloud = (short) d3;                        // move.w d3,noiseloud
        if ((short) d3 > MAX_VOLUME) {                 // cmp.w #64,d3 ; ble.s notooloud
            d3 = M68k.setw(d3, MAX_VOLUME);
        }
        int d4 = M68k.setw(0, d3);                     // move.w d3,d4

        if (!stereo) {                                 // tst.b Aud_Stereo_b ; beq NOSTEREO
            mono(sampleNum, id, d3, d4);
            return;
        }

        // Le partage gauche/droite : l'abscisse, ramenee a la distance.
        int d2b = M68k.setw(0, d3);                    // move.w d3,d2
        d2b = M68k.muls(d2b, noiseX);                  // muls Aud_NoiseX_w,d2
        d0 = M68k.setw(d0, d0 << 2);                   // asl.w #2,d0
        d2b = M68k.divs(d2b, d0);                      // divs d0,d2
        if ((short) d2b > 0) {                         // bgt.s quietleft
            d3 = M68k.setw(d3, d3 - d2b);              // sub.w d2,d3
            if ((short) d3 < 0) {                      // bge.s donequiet
                d3 = M68k.setw(d3, 0);
            }
        } else {
            d4 = M68k.setw(d4, d4 + d2b);              // add.w d2,d4
            if ((short) d4 < 0) {                      // bge.s donequiet
                d4 = M68k.setw(d4, 0);
            }
        }

        // Le cote le plus fort demarre QUATRE OCTETS plus loin.
        rightOffset = 0;
        leftOffset = 0;
        if ((byte) d4 > (byte) d3) {                   // cmp.b d3,d4 ; bgt.s RightLouder
            rightOffset = 4;
        } else if ((byte) d4 < (byte) d3) {            // beq.s NoLouder
            leftOffset = 4;
        }

        // La gauche d'abord. Si elle n'a AUCUNE voix libre, la routine sort et la droite n'est
        // meme pas tentee ; si elle en a une mais trop importante, la droite l'est.
        if (assign(true, sampleNum, id, d3, d4)) {
            assign(false, sampleNum, id, d3, d4);
        }
    }

    /**
     * Un pas de Newton sur la racine : {@code x <- x - (x*x - a) / 2x}, et jamais moins de un.
     */
    private static int newton(int d0, int d2) {
        int d3 = M68k.setw(0, d0);                     // move.w d0,d3
        d3 = M68k.muls(d3, d3);                        // muls d3,d3
        d3 -= d2;                                      // sub.l d2,d3
        d3 >>= 1;                                      // asr.l #1,d3
        d3 = M68k.divs(d3, d0);                        // divs d0,d3
        d0 = M68k.setw(d0, d0 - d3);                   // sub.w d3,d0
        if ((short) d0 <= 0) {                         // bgt .stillnot0
            d0 = M68k.setw(d0, 1);                     // move.w #1,d0
        }
        return d0;
    }

    /**
     * Donne le bruit a une voix d'un cote ({@code FindLeftChannel} / {@code FindRightChannel}).
     *
     * <p>Les deux cotes ne sont PAS symetriques, et ce n'est pas une coquetterie de style :
     *
     * <ul>
     *   <li>a gauche on part d'une importance de trente-deux mille sept cent soixante-sept, a
     *       droite de dix mille — une voix de droite qui porte un bruit plus important que dix
     *       mille n'est donc jamais prise, meme libre de ce point de vue ;</li>
     *   <li>a gauche on cede la place quand l'ancien bruit est AUSSI important que le nouveau
     *       ({@code bge}), a droite seulement quand il l'est PLUS ({@code bgt}) ;</li>
     *   <li>et quand la droite renonce, elle renonce a TOUT : la routine sort.</li>
     * </ul>
     */
    private boolean assign(boolean gauche, int sampleNum, int id, int d3, int d4) {
        int base = gauche ? 0 : VOICES;
        int d0 = M68k.setw(0, id);                     // move.w IDNUM,d0
        int d2 = gauche ? 32767 : 10000;               // move.w #32767,d2 / #10000,d2
        int choisie = -1;
        int trouvee = -1;
        for (int i = 0; i < VOICES; i++) {
            Slot s = slots[base + i];
            if (s.busy) {                              // tst.b (a3) ; bne.s .notactive
                continue;
            }
            if ((short) s.id == (short) d0) {          // cmp.w 32(a3),d0 ; beq FOUNDLEFT
                trouvee = i;                           // d2 n'est PAS repris de la case
                break;
            }
            if ((short) s.importance <= (short) d2) {  // cmp.w 2(a3),d2 ; blt.s .notactive
                d2 = M68k.setw(d2, s.importance);
                choisie = i;
            }
        }
        int voie = trouvee >= 0 ? trouvee : choisie;
        if (voie < 0) {                                // tst.l d5 ; bne FOUNDALEFT
            return false;                              // NONOISE : la routine ENTIERE s'arrete
        }
        if (gauche) {
            if ((short) d2 >= (short) noiseloud) {     // cmp.w noiseloud,d2 ; bge dorightchan
                return true;                           // ... mais la DROITE est quand meme tentee
            }
        } else {
            if ((short) d2 > (short) noiseloud) {      // bgt.s tototot
                return false;
            }
        }

        if ((d0 & 0xFFFF) == 0xFFFF) {                 // cmp.w #$ffff,d0 ; bne.s .noche
            d0 = M68k.setw(d0, 0xFFFE);                // move.w #$fffe,d0
        }
        Slot s = slots[base + voie];
        s.id = d0 & 0xFFFF;                            // move.w d0,32(a3)
        s.importance = noiseloud & 0xFFFF;             // move.w noiseloud,2(a3)

        int decalage = gauche ? leftOffset : rightOffset;
        Voice v = (gauche ? left : right)[voie];
        v.sample = bank.data(sampleNum);               // move.l (a3,d5.w*8),a1
        v.pos = decalage;                              // add.l LEFTOFFSET,a1
        v.end = bank.length(sampleNum) + decalage;     // move.l 4(a3,d5.w*8),a2 ; add.l ...
        // La GAUCHE donne toujours son volume a sa voix ; la DROITE le fait pour ses voix zero
        // et trois seulement — ses voix un et deux recoivent le volume de GAUCHE. C'est dans le
        // jeu, et rien n'indique que ce soit voulu.
        v.vol = gauche || voie == 0 || voie == 3 ? (gauche ? d3 & 0xFF : d4 & 0xFF) : d3 & 0xFF;
        return true;
    }

    /**
     * La voie MONO ({@code NOSTEREO}) : huit cases parcourues d'un trait, gauche puis droite.
     *
     * <p>Elle garde les memes bizarreries de volume que la voie stereo, et en ajoute une : son
     * compteur de voix part de moins un et se corrige d'un cran, si bien que la case retenue
     * n'est jamais la derniere parcourue mais la suivante.
     */
    private void mono(int sampleNum, int id, int d3, int d4) {
        int d0 = M68k.setw(0, id);                     // move.w IDNUM,d0
        int d2 = 32767;                                // move.w #32767,d2
        int d6 = -1;                                   // moveq #-1,d6
        int d5 = -1;                                   // move.l #-1,d5
        int choisie = -1;
        boolean trouvee = false;
        for (int i = 0; i < slots.length; i++) {       // move.w #7,d1 ; FindChannel
            Slot s = slots[i];
            if (!s.busy) {                             // tst.b (a3) ; bne.s .notactive
                if ((short) s.id == (short) d0) {      // cmp.w 32(a3),d0 ; beq.s FOUNDMYCHAN
                    d2 = M68k.setw(d2, s.importance);  // FOUNDMYCHAN : move.w 2(a3),d2
                    d6 = M68k.setw(d6, d5 + 1);        // FOUNDCHAN : move.w d5,d6 ; add.w #1,d6
                    choisie = i;
                    trouvee = true;
                    break;
                }
                if ((short) s.importance <= (short) d2) {  // cmp.w 2(a3),d2 ; blt.s .notactive
                    d2 = M68k.setw(d2, s.importance);
                    choisie = i;
                    d6 = M68k.setw(d6, d5 + 1);        // move.w d5,d6 ; add.w #1,d6
                }
            }
            d5 = M68k.setw(d5, d5 + 1);                // add.w #1,d5
        }
        if (!trouvee && choisie < 0) {                 // move.l a2,a3 ; gopastchan
            return;
        }
        if ((short) d6 < 0) {                          // tst.w d6 ; bge.s FOUNDACHAN
            return;                                    // tooquiet
        }
        if ((short) d2 > (short) noiseloud) {          // cmp.w noiseloud,d2 ; bgt.s tooquiet
            return;
        }
        if ((d0 & 0xFFFF) == 0xFFFF) {
            d0 = M68k.setw(d0, 0xFFFE);
        }
        Slot s = slots[choisie];
        s.id = d0 & 0xFFFF;
        s.importance = noiseloud & 0xFFFF;

        // En mono le decalage de quatre octets n'existe pas : les deux pointeurs sont nus.
        int canal = (short) d6;
        boolean gauche = canal < VOICES;
        int voie = gauche ? canal : canal - VOICES;
        Voice v = (gauche ? left : right)[voie];
        v.sample = bank.data(sampleNum);
        v.pos = 0;
        v.end = bank.length(sampleNum);
        // Memes bizarreries qu'en stereo : a droite, les voix un et deux prennent d3.
        v.vol = gauche || voie == 1 || voie == 2 ? d3 & 0xFF : d4 & 0xFF;
    }

    /**
     * {@code newsampbitl} : une trame de deux cents octets pour chacun des quatre canaux.
     *
     * <p>Les canaux zero et un sont sautes quand la MUSIQUE tient la premiere case de leur
     * cote : la puce y joue alors le module, et le melange n'a rien a y faire. Les deux autres
     * ne sont jamais sautes — le jeu ne pose la question que pour les deux premiers.
     */
    public void mix() {
        if (!slots[0].busy) {                          // tst.b CHANNELDATA ; bne nochannel0
            mixChannel(0, left[0], left[2], 0, 2, true);
        }
        if (!slots[VOICES].busy) {                     // tst.b CHANNELDATA+16 ; bne nochannel1
            mixChannel(1, right[0], right[2], 0, 2, false);
        }
        mixChannel(3, left[1], left[3], 1, 3, true);   // $dff0d0 : gauche
        mixChannel(2, right[1], right[3], 1, 3, false);  // $dff0c0 : droite
    }

    /**
     * Un canal materiel : deux voix, la plus forte nue, la plus faible mise a l'echelle.
     *
     * @param paulaChannel le canal materiel
     * @param a            la premiere voix du couple
     * @param b            la seconde
     * @param slotA        le rang de la case de la premiere
     * @param slotB        celui de la seconde
     * @param gauche       de quel cote sont ces voix
     */
    private void mixChannel(int paulaChannel, Voice a, Voice b, int slotA, int slotB,
            boolean gauche) {
        int d0 = a.vol & 0xFF;                         // move.b vol0left,d0
        int d1 = b.vol & 0xFF;                         // move.b vol2left,d1
        boolean swapped = (byte) d0 < (byte) d1;       // cmp.b d1,d0 ; slt swappedem
        Voice forte = a;
        Voice faible = b;
        int rampe = 0;
        if (swapped) {
            // La seconde est la plus forte : on echange, et c'est ELLE qui donne son volume.
            forte = b;
            faible = a;
            d0 = M68k.setw(d0, d0 << 6);               // asl.w #6,d0
            d0 = M68k.divs(d0, d1);                    // divs d1,d0
            rampe = (d0 & 0xFFFF) << 8;                // lsl.w #8,d0 ; adda.w d0,a2
            paulaVol[paulaChannel] = d1;               // move.w d1,$dff0a8
        } else if ((short) d0 != 0) {                  // fbig0 : tst.w d0 ; beq.s donechan0
            d1 = M68k.setw(d1, d1 << 6);               // asl.w #6,d1
            d1 = M68k.divs(d1, d0);                    // divs d0,d1
            rampe = (d1 & 0xFFFF) << 8;                // lsl.w #8,d1 ; adda.w d1,a2
            paulaVol[paulaChannel] = d0;               // move.w d0,$dff0a8
        } else {
            // Les deux voix sont muettes : le volume du canal ne bouge pas, et la table reste
            // a sa premiere rampe — celle qui annule tout.
            rampe = 0;
        }
        // Le decalage n'est qu'un mot : il reboucle au-dela de soixante-cinq mille cinq cent
        // trente-cinq, comme le ferait l'adresse.
        rampe &= 0xFFFF;

        byte[] dst = out[paulaChannel];
        byte[] sa = forte.sample;
        byte[] sb = faible.sample;
        int pa = forte.pos;
        int pb = faible.pos;
        int at = 0;
        for (int n = 0; n < FRAME_BYTES / 4; n++) {    // move.w #49,d7 ; dbra
            // move.l (a0)+,d0 : QUATRE echantillons lus d'un coup.
            int big = ((sa[pa] & 0xFF) << 24) | ((sa[pa + 1] & 0xFF) << 16)
                    | ((sa[pa + 2] & 0xFF) << 8) | (sa[pa + 3] & 0xFF);
            pa += 4;
            int t1 = tab[(rampe + (sb[pb] & 0xFF)) & 0xFFFF] & 0xFF;
            int t2 = tab[(rampe + (sb[pb + 1] & 0xFF)) & 0xFFFF] & 0xFF;
            int t3 = tab[(rampe + (sb[pb + 2] & 0xFF)) & 0xFFFF] & 0xFF;
            int t4 = tab[(rampe + (sb[pb + 3] & 0xFF)) & 0xFFFF] & 0xFF;
            pb += 4;
            int small = (t1 << 24) | (t2 << 16) | (t3 << 8) | t4;
            // add.l d5,d0 : UNE addition pour quatre echantillons. Les retenues passent d'un
            // octet au suivant, et c'est le grain de ce moteur.
            int somme = big + small;
            dst[at] = (byte) (somme >>> 24);
            dst[at + 1] = (byte) (somme >>> 16);
            dst[at + 2] = (byte) (somme >>> 8);
            dst[at + 3] = (byte) somme;
            at += 4;
        }

        // Les pointeurs sont rendus a leur voix : l'echange d'avant la boucle est defait.
        forte.pos = pa;
        faible.pos = pb;

        finish(a, slots[(gauche ? 0 : VOICES) + slotA]);
        finish(b, slots[(gauche ? 0 : VOICES) + slotB]);
    }

    /** Une voix arrivee au bout de son echantillon retombe au silence et rend sa case. */
    private static void finish(Voice v, Slot s) {
        if (v.pos < v.end) {                           // cmp.l Samp0endLEFT,a0 ; blt.s
            return;
        }
        v.silence();                                   // move.l #Aud_EmptyBuffer_vl,a0 ...
        s.id = 0;                                      // clr.w LEFTCHANDATA+32
        s.importance = 0;                              // move.w #0,LEFTCHANDATA+2
    }
}
