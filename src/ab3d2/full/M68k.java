package ab3d2.full;

/**
 * Les operations dont les algorithmes d'origine <b>dependent</b>.
 *
 * <p>Ce n'est pas de l'emulation 68000, et c'est la seule concession du depot. Le jeu de 1996
 * calcule en virgule fixe sur des mots de 16 bits, et ses resultats reposent sur des
 * comportements precis du processeur :
 *
 * <ul>
 *   <li>la <b>troncature a 16 bits signes</b>. Une coordonnee qui deborde ne sature pas, elle
 *       repasse par l'autre bord — et le jeu s'en sert, par exemple pour faire boucler un angle
 *       sans test ;</li>
 *   <li>l'ecriture d'un <b>mot dans un registre de 32 bits</b>, qui laisse le mot fort
 *       intact. Le code d'origine s'en sert pour transporter deux valeurs dans un registre ;</li>
 *   <li>le sort reserve a une <b>division impossible</b>. Le 68000 ne touche pas au registre si
 *       le diviseur est nul ou si le quotient deborde d'un mot : il lance une exception que le
 *       jeu n'intercepte pas, donc la valeur precedente reste. C'est un comportement observable,
 *       pas un detail ;</li>
 *   <li>le <b>reste</b>, que {@code divs.w} laisse dans le mot fort et que l'original recupere
 *       par un {@code swap}.</li>
 * </ul>
 *
 * <p>Changer l'un ou l'autre ne change pas un arrondi : ca change une trajectoire. Un monstre ne
 * vise plus au meme endroit, un projectile ne rebondit plus pareil. D'ou ces fonctions, nommees
 * et isolees ici plutot que dispersees en masques dans tout le code.
 */
public final class M68k {

    private M68k() {
    }

    /** Le mot bas, lu comme un entier signe de 16 bits ({@code ext.w}). */
    public static int s16(int v) {
        return (short) v;
    }

    /** {@code move.w} vers un registre : remplace le mot bas, garde le mot fort. */
    public static int setw(int reg, int v) {
        return (reg & 0xFFFF0000) | (v & 0xFFFF);
    }

    /**
     * {@code muls.w} : produit signe de deux mots, sur 32 bits.
     *
     * <p>Les facteurs sont tronques AVANT la multiplication : c'est ce qui donne son resultat au
     * jeu quand une coordonnee a deborde.
     */
    public static int muls(int a, int b) {
        return (short) a * (short) b;
    }

    /**
     * {@code divs.w} : quotient signe, tronque vers zero, d'un dividende de 32 bits par un
     * diviseur de 16.
     *
     * <p>Rend {@code dn} inchange si le diviseur est nul ou si le quotient ne tient pas dans un
     * mot, parce que c'est ce que fait le processeur.
     *
     * <p>ATTENTION : ne rend que le QUOTIENT. Pour le reste, voir {@link #divsRem}.
     */
    public static int divs(int dn, int src) {
        int dv = (short) src;
        if (dv == 0) {
            return dn;
        }
        int q = Math.abs(dn) / Math.abs(dv);
        if ((dn < 0) != (dv < 0)) {
            q = -q;
        }
        if (q < -32768 || q > 32767) {
            return dn;
        }
        return q;
    }

    /**
     * {@code muls.w} sans signe : produit de deux mots lus comme des entiers positifs.
     *
     * <p>Comme {@link #muls}, elle ne voit que le mot bas de ses facteurs. Le jeu s'en sert
     * pour ses tables d'inverses, dont les entrees ne tiendraient pas dans un mot signe.
     */
    public static int mulu(int a, int b) {
        return (a & 0xFFFF) * (b & 0xFFFF);
    }

    /**
     * {@code divs.w} dans son entier : le registre tel que le processeur le laisse, quotient
     * dans le mot bas et reste dans le mot fort.
     *
     * <p>Le code d'origine recupere les deux d'un seul geste — un {@code swap} apres la
     * division — et c'est ainsi qu'il interpole : le quotient donne le pas entier, le reste
     * amorce l'accumulateur. {@link #divs} et {@link #divsRem} n'en rendent qu'une moitie
     * chacune, et leur reunion ne vaut PAS ceci : quand la division est impossible, le
     * processeur ne pose ni l'un ni l'autre et laisse le registre intact.
     */
    public static int divsReg(int dn, int src) {
        int dv = (short) src;
        if (dv == 0) {
            return dn;
        }
        int q = Math.abs(dn) / Math.abs(dv);
        if ((dn < 0) != (dv < 0)) {
            q = -q;
        }
        if (q < -32768 || q > 32767) {
            return dn;
        }
        return ((dn % dv) << 16) | (q & 0xFFFF);
    }

    /**
     * Le reste de {@link #divs}, celui que le processeur laisse dans le mot fort.
     *
     * <p>Il prend le signe du DIVIDENDE, comme sur 68k et comme {@code %} en Java.
     */
    public static int divsRem(int dn, int src) {
        int dv = (short) src;
        if (dv == 0) {
            return 0;
        }
        return dn % dv;
    }

    /** {@code asr.w} : decalage arithmetique a droite du mot bas. */
    public static int asrw(int reg, int n) {
        return setw(reg, ((short) reg) >> n);
    }

    /** {@code neg.w} : oppose du mot bas. */
    public static int negw(int reg) {
        return setw(reg, -(short) reg);
    }
}
