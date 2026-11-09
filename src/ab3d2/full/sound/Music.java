package ab3d2.full.sound;

import ab3d2.full.M68k;

/**
 * LA MUSIQUE : un lecteur Protracker, porte de {@code modules/music.s}.
 *
 * <p>C'est le seul morceau de ce jeu qui ne lui appartient pas : la routine {@code mt_} tourne
 * dans la moitie des productions Amiga de l'epoque, et Team17 l'a prise telle quelle. Elle lit
 * un module — quatre voix, trente et un echantillons, des motifs de soixante-quatre lignes — et
 * ecrit directement dans les registres de la puce.
 *
 * <h2>Une seule voix</h2>
 *
 * <p>En jeu, {@code UseAllChannels} est NUL : seule la premiere voix joue, sur le canal ZERO.
 * Les trois autres sont laissees aux bruitages. Et le lecteur ne se contente pas de les ignorer :
 * au moment d'armer le DMA il MASQUE les trois bits correspondants, pour que la ligne de motif
 * d'une voix muette ne reveille pas un canal qui ne lui appartient plus.
 *
 * <p>C'est pourquoi le melangeur saute le canal zero : le jeu declare ses deux cases de gauche
 * prises des le chargement du niveau, et le canal qu'elles forment revient a la musique.
 *
 * <h2>Ce qui se passe a chaque image</h2>
 *
 * <p>Un compteur monte jusqu'a la VITESSE — six par defaut, soit une ligne de motif tous les six
 * cinquantiemes de seconde. Entre deux lignes, seuls les EFFETS continus avancent : l'arpege,
 * les glissandos, le vibrato, le fondu de volume. Sur la ligne elle-meme, le lecteur charge
 * l'echantillon, arrete le canal, pose l'adresse, la longueur et la periode, puis rearme le
 * DMA — et ECRIT AUSSITOT APRES le point de boucle dans les memes registres. La puce ayant
 * pris sa copie au demarrage, la note commence sur l'echantillon entier et reboucle ensuite sur
 * sa queue. Tout le lecteur tient dans cette ruse.
 *
 * <h2>Deux bizarreries gardees</h2>
 *
 * <ul>
 *   <li>le volume d'un echantillon est lu sur un MOT la ou la donnee en occupe un octet : le
 *       volume y arrive donc avec le reglage fin dans ses bits hauts. Les modules du jeu ont un
 *       reglage fin nul, et cela ne se voit pas ;</li>
 *   <li>la recherche du plus grand numero de motif decompte son index de boucle une fois de
 *       plus a chaque valeur plus grande trouvee. Elle lit donc moins de cent vingt-huit cases,
 *       et le resultat reste juste tant que les grandes valeurs arrivent tot.</li>
 * </ul>
 *
 * <h2>Ce que le corpus ne separe pas</h2>
 *
 * <p>Sept mutations survivent, et toutes s'expliquent :
 *
 * <ul>
 *   <li>la vitesse declaree a six dans les donnees est toujours REECRITE par l'amorce : elle
 *       ne vaut que le temps d'un chargement ;</li>
 *   <li>le masque qui n'eveille que le canal zero est MORT tant qu'une seule voix joue :
 *       aucune autre ne pose jamais son bit ;</li>
 *   <li>trois plafonds — celui du fondu de volume, et les deux butees des glissandos — se
 *       comparent avec ou sans l'egalite : a la valeur limite exacte, les deux formes posent
 *       la meme chose ;</li>
 *   <li>la puce ne reprend sa copie qu'au passage de l'arret a la marche, et le lecteur ARRETE
 *       toujours un canal avant d'y poser une note : le cas d'un reveil sur un canal deja en
 *       marche ne se presente jamais ;</li>
 *   <li>l'extinction ne nomme jamais qu'un canal a la fois, donc le masque qui la borne ne
 *       sert pas.</li>
 * </ul>
 */
public final class Music {

    /** Combien d'echantillons un module porte. */
    private static final int SAMPLES = 31;

    /** La taille d'un en-tete d'echantillon. */
    private static final int SAMPLE_HEADER = 0x1E;

    /** Ou commence la table d'ordre. */
    private static final int ORDER = 0x3B8;

    /** Ou commencent les motifs. */
    private static final int PATTERNS = 0x43C;

    /** Ou commencent les en-tetes d'echantillons, moins le pas : {@code mt_data + $c}. */
    private static final int HEADERS = 0xC;

    /** La table de sinus du vibrato. */
    private static final int[] SIN = {
        0x00, 0x18, 0x31, 0x4A, 0x61, 0x78, 0x8D, 0xA1, 0xB4, 0xC5, 0xD4, 0xE0,
        0xEB, 0xF4, 0xFA, 0xFD, 0xFF, 0xFD, 0xFA, 0xF4, 0xEB, 0xE0, 0xD4, 0xC5,
        0xB4, 0xA1, 0x8D, 0x78, 0x61, 0x4A, 0x31, 0x18,
    };

    /** Les periodes des trente-six notes, suivies de deux zeros. */
    private static final int[] PERIODS = {
        0x358, 0x328, 0x2FA, 0x2D0, 0x2A6, 0x280, 0x25C, 0x23A, 0x21A, 0x1FC, 0x1E0,
        0x1C5, 0x1AC, 0x194, 0x17D, 0x168, 0x153, 0x140, 0x12E, 0x11D, 0x10D, 0x0FE,
        0x0F0, 0x0E2, 0x0D6, 0x0CA, 0x0BE, 0x0B4, 0x0AA, 0x0A0, 0x097, 0x08F, 0x087,
        0x07F, 0x078, 0x071, 0x000, 0x000,
    };

    /** Une voix du lecteur : {@code mt_voice1} et ses vingt-huit octets. */
    private static final class Voice {
        int note;                                      // (a6)
        int cmd;                                       // $2(a6)
        int start = -1;                                // $4(a6) : nul tant qu'aucune note
        int length;                                    // $8(a6), en MOTS
        int loopStart = -1;                            // $a(a6)
        int loopLength;                                // $e(a6), en MOTS
        int period;                                    // $10(a6)
        int volume;                                    // $12(a6)
        final int dmaBit;                              // $14(a6)
        int portDir;                                   // $16(a6)
        int portSpeed;                                 // $17(a6)
        int portTarget;                                // $18(a6)
        int vibParam;                                  // $1a(a6)
        int vibPos;                                    // $1b(a6), SIGNE

        Voice(int bit) {
            this.dmaBit = bit;
        }
    }

    private final byte[] data;
    private final int[] sampleStarts = new int[SAMPLES];
    private final Voice[] voices = {new Voice(1), new Voice(2), new Voice(4), new Voice(8)};

    /** {@code UseAllChannels} : NUL en jeu, donc une seule voix. */
    public boolean useAllChannels;

    private int speed = 6;                             // mt_speed
    private int songpos;                               // mt_songpos
    private int pattpos;                               // mt_pattpos
    private int counter;                               // mt_counter
    private boolean brk;                               // mt_break
    private boolean reachedEnd;                        // reachedend
    private int dmacon;                                // mt_dmacon

    /** {@code nullsample} : un long nul, pour les echantillons vides. */
    private final int nullSample;

    /**
     * @param module les octets du module, tels que le fichier les donne
     */
    public Music(byte[] module) {
        // On travaille sur une COPIE : {@code mt_init} ecrit dans les donnees, et le fichier
        // n'a pas a en souffrir. La queue nulle tient lieu de {@code nullsample}.
        this.data = new byte[module.length + 8];
        System.arraycopy(module, 0, data, 0, module.length);
        this.nullSample = module.length;
    }

    /** Les donnees du module, telles que le lecteur les a vues et modifiees. */
    public byte[] data() {
        return data;
    }

    /** Ou vit l'echantillon NUL, celui des echantillons vides. */
    public int nullSample() {
        return nullSample;
    }

    /** La vitesse : combien d'images par ligne de motif. */
    public int speed() {
        return speed;
    }

    /** Ou l'on en est dans le motif, en octets. */
    public int patternPosition() {
        return pattpos;
    }

    /** Le compteur d'images depuis la derniere ligne. */
    public int counter() {
        return counter;
    }

    /** Vrai si une commande de rupture est en attente. */
    public boolean broken() {
        return brk;
    }

    /** L'etat d'une voix, pour qui veut l'eprouver. */
    public int note(int v) {
        return voices[v].note;
    }

    public int cmd(int v) {
        return voices[v].cmd;
    }

    /** Le depart de l'echantillon, ou moins deux si c'est l'echantillon NUL. */
    public int start(int v) {
        return voices[v].start == nullSample ? -2 : voices[v].start;
    }

    public int length(int v) {
        return voices[v].length;
    }

    public int loopStart(int v) {
        return voices[v].loopStart == nullSample ? -2 : voices[v].loopStart;
    }

    public int loopLength(int v) {
        return voices[v].loopLength;
    }

    public int period(int v) {
        return voices[v].period;
    }

    public int volume(int v) {
        return voices[v].volume;
    }

    public int portDir(int v) {
        return voices[v].portDir;
    }

    public int portSpeed(int v) {
        return voices[v].portSpeed;
    }

    public int portTarget(int v) {
        return voices[v].portTarget;
    }

    public int vibParam(int v) {
        return voices[v].vibParam;
    }

    public int vibPos(int v) {
        return voices[v].vibPos;
    }

    /** Vrai si le morceau a passe une commande de saut de position. */
    public boolean reachedEnd() {
        return reachedEnd;
    }

    /** La ligne de motif en cours, pour qui veut regarder. */
    public int songPosition() {
        return songpos;
    }

    private int u8(int at) {
        return at >= 0 && at < data.length ? data[at] & 0xFF : 0;
    }

    private int u16(int at) {
        return (u8(at) << 8) | u8(at + 1);
    }

    private void w16(int at, int v) {
        if (at >= 0 && at + 1 < data.length) {
            data[at] = (byte) (v >> 8);
            data[at + 1] = (byte) v;
        }
    }

    /**
     * {@code mt_init} : trouve ou commencent les echantillons, et remet le lecteur au debut.
     *
     * <p>Le nombre de MOTIFS n'est ecrit nulle part : il se deduit du plus grand numero present
     * dans la table d'ordre. Les donnees d'echantillons commencent juste apres.
     */
    public void init(Paula paula) {
        int a1 = ORDER;
        int d0 = 0x7F;                                 // moveq #$7f,d0
        int d1 = 0;
        int d2 = 0;
        while (true) {                                 // mt_loop
            d2 = d1;                                   // move.l d1,d2
            d0 = M68k.setw(d0, d0 - 1);                // subq.w #1,d0
            boolean encore = true;
            while (encore) {                           // mt_lop2
                d1 = u8(a1++);                         // move.b (a1)+,d1
                if ((byte) d1 > (byte) d2) {           // cmp.b d2,d1 ; bgt.s mt_loop
                    break;
                }
                d0 = M68k.setw(d0, d0 - 1);            // dbf d0,mt_lop2
                if ((short) d0 == -1) {
                    encore = false;
                }
            }
            if (!encore) {
                break;
            }
        }
        d2 = (d2 + 1) & 0xFF;                          // addq.b #1,d2 : le nombre de motifs

        int a2 = PATTERNS + (d2 << 10);                // asl.l #8 ; asl.l #2 ; add #$43c
        int a0 = 0;
        for (int i = 0; i < SAMPLES; i++) {            // mt_lop3
            int len = u16(a0 + 42);                    // move.w 42(a0),d1 : la longueur en mots
            if (len == 0) {
                sampleStarts[i] = nullSample;          // move.l #nullsample,(a1)+
            } else {
                w16(a2, 0);                            // clr.l (a2) : les deux premiers mots
                w16(a2 + 2, 0);                        //   sont mis a ZERO — c'est eux que la
                sampleStarts[i] = a2;                  //   boucle d'un echantillon sans reprise
            }                                          //   rejouera, et ils doivent se taire
            a2 += len * 2;                             // asl.l #1,d1 ; add.l d1,a2
            a0 += SAMPLE_HEADER;                       // add.l #$1e,a0
        }

        speed = 6;                                     // move.b #$6,mt_speed
        for (int ch = 0; ch < 4; ch++) {               // clr.w $dff0a8 ... $dff0d8
            paula.audVOL(ch, 0);
        }
        songpos = 0;
        counter = 0;
        pattpos = 0;
        // Ni la rupture ni le drapeau de fin ne sont remis a zero : mt_init n'y touche pas, et
        // ils gardent ce que le morceau precedent y avait laisse.
        // Les enregistrements de voix ne sont PAS remis a zero : {@code mt_init} n'y touche
        // pas, et ils gardent ce que le morceau precedent y avait laisse.
    }

    /** {@code mt_end} : plus un son. */
    public void stop(Paula paula) {
        for (int ch = 0; ch < 4; ch++) {
            paula.audVOL(ch, 0);
        }
        paula.dmacon(0xF);                             // move.w #$f,$dff096 : on ETEINT
    }

    /** Combien de voix jouent : une en jeu, quatre pour la musique de fin. */
    private int count() {
        return useAllChannels ? 4 : 1;
    }

    /** {@code mt_music} : une image. */
    public void tick(Paula paula) {
        counter = (counter + 1) & 0xFF;                // addq.b #$1,mt_counter
        if ((byte) counter < (byte) speed) {           // cmp.b mt_speed,D0 ; blt.s mt_nonew
            for (int ch = 0; ch < count(); ch++) {     // mt_nonew
                checkCom(paula, voices[ch], ch);
            }
        } else {
            counter = 0;                               // clr.b mt_counter
            getNew(paula);
        }
        // mt_endr : une commande de rupture rejoue la fin de ligne jusqu'a ce qu'elle passe.
        while (brk) {
            next();
        }
    }

    /** {@code mt_nex} : la ligne suivante du morceau. */
    private void next() {
        pattpos = 0;                                   // clr.w mt_pattpos
        brk = false;                                   // clr.b mt_break
        songpos = (songpos + 1) & 0x7F;                // addq.b #1 ; and.b #$7f
    }

    /** {@code mt_getnew} : une nouvelle ligne de motif. */
    private void getNew(Paula paula) {
        int a3 = HEADERS;
        int a2 = ORDER;
        int a0 = PATTERNS;
        int d1 = u8(a2 + songpos) << 10;               // asl.l #8 ; asl.l #2
        d1 = (d1 + pattpos) & 0xFFFFFFFF;              // add.w mt_pattpos,d1
        dmacon = 0;                                    // clr.w mt_dmacon

        for (int ch = 0; ch < count(); ch++) {         // mt_playvoice, une voix a la fois
            d1 = playVoice(paula, voices[ch], ch, a0, a3, d1);
        }

        // mt_setdma : deux attentes de deux cent cinquante tours, le temps que la puce prenne
        // ses copies. Elles n'ont pas de sens ici.
        int d0 = dmacon | 0x8000;
        if (!useAllChannels) {
            d0 &= 0b1111111111110001;                  // seul le canal ZERO est reveille
        }
        paula.dmacon(d0);

        // Et AUSSITOT le point de boucle, que la puce prendra a la fin de la note.
        for (int ch = count() - 1; ch >= 0; ch--) {
            Voice v = voices[ch];
            paula.audLC(ch, data, v.loopStart);        // move.l $a(a6),$a0(a5)
            paula.audLEN(ch, v.loopLength);            // move.w $e(a6),$a4(a5)
        }

        pattpos += 0x10;                               // add.w #$10,mt_pattpos
        if (pattpos == 0x400) {                        // cmp.w #$400 ; bne.s mt_endr
            next();
        }
    }

    /** {@code mt_playvoice} : la ligne de cette voix. */
    private int playVoice(Paula paula, Voice v, int ch, int a0, int a3, int d1) {
        v.note = u16(a0 + d1);                         // move.l (a0,d1.l),(a6)
        v.cmd = u16(a0 + d1 + 2);
        d1 += 4;                                       // addq.l #4,d1

        int d2 = (v.cmd >> 8 & 0xF0) >> 4;             // move.b $2(a6) ; and #$f0 ; lsr #4
        d2 |= (v.note >> 8) & 0xF0;                    // move.b (a6) ; and #$f0 ; or
        if (d2 != 0) {
            int d4 = d2 * SAMPLE_HEADER;               // mulu #$1e,d4
            v.start = sampleStarts[(d2 - 1) & 0x1F];   // move.l (a1,d2.l),$4(a6)
            v.length = u16(a3 + d4);                   // move.w (a3,d4.l),$8(a6)
            // Le volume est lu sur un MOT : son octet haut est le reglage fin.
            v.volume = u16(a3 + d4 + 2);               // move.w $2(a3,d4.l),$12(a6)
            int d3 = u16(a3 + d4 + 4);                 // move.w $4(a3,d4.l),d3 : la reprise
            if (d3 != 0) {
                v.loopStart = v.start + d3 * 2;        // asl.w #1,d3 ; add.l d3,d2
                v.length = d3 + u16(a3 + d4 + 6);      // la note s'arrete au BOUT de la boucle
                v.loopLength = u16(a3 + d4 + 6);
            } else {                                   // mt_noloop
                v.loopStart = v.start;                 // add.l d3,d2 avec d3 nul
                v.loopLength = u16(a3 + d4 + 6);
            }
            paula.audVOL(ch, v.volume);                // move.w $12(a6),$8(a5)
        }

        // mt_setregs
        if ((v.note & 0xFFF) == 0) {                   // and.w #$fff,d0 ; beq mt_checkcom2
            checkCom2(paula, v, ch);
            return d1;
        }
        if (((v.cmd >> 8) & 0xF) == 3) {               // l'effet TROIS garde la note en cours
            setMyPort(v);
            checkCom2(paula, v, ch);
            return d1;
        }
        // mt_setperiod
        v.period = v.note & 0xFFF;                     // move.w (a6),$10(a6) ; and.w #$fff
        paula.dmacon(v.dmaBit);                        // move.w $14(a6),$dff096 : on ARRETE
        v.vibPos = 0;                                  // clr.b $1b(a6)
        paula.audLC(ch, data, v.start);                // move.l $4(a6),(a5)
        paula.audLEN(ch, v.length);                    // move.w $8(a6),$4(a5)
        paula.audPER(ch, v.period & 0xFFF);            // move.w $10(a6),$6(a5)
        dmacon |= v.dmaBit;                            // or.w d0,mt_dmacon
        checkCom2(paula, v, ch);
        return d1;
    }

    /** {@code mt_setmyport} : vise la note demandee sans la declencher. */
    private void setMyPort(Voice v) {
        int d2 = v.note & 0xFFF;
        v.portTarget = d2;                             // move.w d2,$18(a6)
        v.portDir = 0;                                 // clr.b $16(a6)
        if (d2 == v.period) {                          // beq.s mt_clrport
            v.portTarget = 0;
            return;
        }
        if (d2 < v.period) {                           // bge.s mt_rt : on MONTE
            v.portDir = 1;                             // move.b #$1,$16(a6)
        }
    }

    /** {@code mt_checkcom} : les effets qui avancent ENTRE deux lignes. */
    private void checkCom(Paula paula, Voice v, int ch) {
        if ((v.cmd & 0xFFF) == 0) {                    // and.w #$fff,d0 ; beq.s mt_nop
            paula.audPER(ch, v.period);                // mt_nop
            return;
        }
        int effet = (v.cmd >> 8) & 0xF;
        switch (effet) {
            case 0 -> arpeggio(paula, v, ch);
            case 1 -> portUp(paula, v, ch);
            case 2 -> portDown(paula, v, ch);
            case 3 -> myPort(paula, v, ch);
            case 4 -> vibrato(paula, v, ch);
            default -> {
                paula.audPER(ch, v.period);            // move.w $10(a6),$6(a5)
                if (effet == 0xA) {
                    volSlide(paula, v, ch);
                }
            }
        }
    }

    /** {@code mt_arpeggio} : la note, puis deux autres, trois fois par ligne. */
    private void arpeggio(Paula paula, Voice v, int ch) {
        int d0 = (short) M68k.divsRem(M68k.setw(0, counter), 3);  // divs #$3,d0 ; swap d0
        if (d0 == 0) {                                 // mt_arp2 : la note elle-meme
            paula.audPER(ch, v.period);
            return;
        }
        int param = v.cmd & 0xFF;
        // Un tour sur trois la note, un tour le demi-ton du quartet HAUT, un tour celui du BAS.
        int demi = d0 == 2 ? param & 0xF : (param >> 4) & 0xF;   // mt_arp1 / le cas trois
        int d1 = v.period;
        for (int at = 0; at <= 0x24; at++) {           // mt_arploop : moveq #$24,d7
            if (d1 >= PERIODS[at]) {                   // cmp.w (a0),d1 ; bge.s mt_arp4
                int k = at + demi;                     // (a0,d0.w) : d0 est un pas d'OCTETS
                paula.audPER(ch, k < PERIODS.length ? PERIODS[k] : 0);
                return;
            }
        }
    }

    /** {@code mt_myport} : le glissando qui rejoint la note visee. */
    private void myPort(Paula paula, Voice v, int ch) {
        int param = v.cmd & 0xFF;
        if (param != 0) {                              // move.b $3(a6),d0 ; beq.s mt_myslide
            v.portSpeed = param;                       // move.b d0,$17(a6)
            v.cmd &= 0xFF00;                           // clr.b $3(a6)
        }
        if (v.portTarget == 0) {                       // tst.w $18(a6) ; beq.s mt_rt
            return;
        }
        int d0 = v.portSpeed & 0xFF;
        if (v.portDir != 0) {                          // mt_mysub : on monte, donc la periode BAISSE
            v.period = M68k.setw(v.period, v.period - d0);
            if ((short) v.portTarget >= (short) v.period) {  // blt.s mt_myok
                v.period = v.portTarget;
                v.portTarget = 0;
            }
        } else {
            v.period = M68k.setw(v.period, v.period + d0);
            if ((short) v.portTarget <= (short) v.period) {  // bgt.s mt_myok
                v.period = v.portTarget;
                v.portTarget = 0;
            }
        }
        paula.audPER(ch, v.period);                    // mt_myok
    }

    /** {@code mt_vib} : le vibrato, une sinusoide de trente-deux pas. */
    private void vibrato(Paula paula, Voice v, int ch) {
        int param = v.cmd & 0xFF;
        if (param != 0) {                              // move.b $3(a6),d0 ; beq.s mt_vi
            v.vibParam = param;                        // move.b d0,$1a(a6)
        }
        int d0 = v.vibPos & 0xFF;                      // move.b $1b(a6),d0
        int d2 = SIN[(d0 >> 2) & 0x1F];                // lsr.w #$2 ; and.w #$1f
        d2 = (d2 * (v.vibParam & 0xF)) & 0xFFFF;       // mulu d0,d2
        d2 >>= 6;                                      // lsr.w #$6,d2
        int p = v.period;
        if ((byte) v.vibPos < 0) {                     // tst.b $1b(a6) ; bmi.s mt_vibmin
            p = M68k.setw(p, p - d2);
        } else {
            p = M68k.setw(p, p + d2);
        }
        paula.audPER(ch, p & 0xFFFF);                  // mt_vib2 : la periode JOUEE seulement
        int pas = ((v.vibParam & 0xFF) >> 2) & 0x3C;   // lsr.w #$2 ; and.w #$3c
        v.vibPos = (v.vibPos + pas) & 0xFF;            // add.b d0,$1b(a6)
    }

    /** {@code mt_volslide} : le fondu de volume, dans un sens ou dans l'autre. */
    private void volSlide(Paula paula, Voice v, int ch) {
        int param = v.cmd & 0xFF;
        int haut = (param >> 4) & 0xF;
        if (haut != 0) {
            v.volume = M68k.setw(v.volume, v.volume + haut);
            if ((short) v.volume >= 0x40) {            // cmp.w #$40 ; bmi.s mt_vol2
                v.volume = M68k.setw(v.volume, 0x40);
            }
        } else {                                       // mt_voldown
            v.volume = M68k.setw(v.volume, v.volume - (param & 0xF));
            if ((short) v.volume < 0) {                // bpl.s mt_vol3
                v.volume = M68k.setw(v.volume, 0);
            }
        }
        paula.audVOL(ch, v.volume);
    }

    /** {@code mt_portup} : la periode baisse, donc la note monte. Plancher a $71. */
    private void portUp(Paula paula, Voice v, int ch) {
        v.period = M68k.setw(v.period, v.period - (v.cmd & 0xFF));
        if ((short) (v.period & 0xFFF) < 0x71) {       // cmp.w #$71,d0 ; bpl.s mt_por2
            v.period = (v.period & 0xF000) | 0x71;
        }
        paula.audPER(ch, v.period & 0xFFF);
    }

    /** {@code mt_portdown} : la periode monte, donc la note descend. Plafond a $358. */
    private void portDown(Paula paula, Voice v, int ch) {
        v.period = M68k.setw(v.period, v.period + (v.cmd & 0xFF));
        if ((short) (v.period & 0xFFF) >= 0x358) {     // cmp.w #$358,d0 ; bmi.s mt_por3
            v.period = (v.period & 0xF000) | 0x358;
        }
        paula.audPER(ch, v.period & 0xFFF);
    }

    /** {@code mt_checkcom2} : les effets qui ne valent qu'au moment de la ligne. */
    private void checkCom2(Paula paula, Voice v, int ch) {
        int effet = (v.cmd >> 8) & 0xF;
        int param = v.cmd & 0xFF;
        switch (effet) {
            case 0xE -> {                              // mt_setfilt : le filtre de la machine
                // and.b #$fd,$bfe001 ; or.b d0,$bfe001 — une broche du port parallele.
            }
            case 0xD -> brk = !brk;                    // mt_pattbreak : not.b mt_break
            case 0xB -> {                              // mt_posjmp
                reachedEnd = true;                     // st reachedend
                songpos = (param - 1) & 0xFF;
                brk = !brk;
            }
            case 0xC -> {                              // mt_setvol
                // La comparaison est SIGNEE : un parametre au-dela de cent vingt-sept passe
                // pour negatif et echappe au plafond. Le jeu le fait, on le garde.
                if ((byte) param > 0x40) {             // cmp.b #$40 ; ble.s mt_vol4
                    param = 0x40;
                    v.cmd = (v.cmd & 0xFF00) | 0x40;   // move.b #$40,$3(a6) : la donnee est ECRASEE
                }
                paula.audVOL(ch, param);
            }
            case 0xF -> {                              // mt_setspeed
                if ((byte) param > 0x1F) {             // cmp.b #$1f ; ble.s mt_sets
                    param = 0x1F;
                    v.cmd = (v.cmd & 0xFF00) | 0x1F;
                }
                if (param != 0) {                      // beq.s mt_rts2
                    speed = param;
                    counter = 0;
                }
            }
            default -> {
            }
        }
    }
}
