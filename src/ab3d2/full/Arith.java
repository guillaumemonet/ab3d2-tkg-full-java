package ab3d2.full;

/**
 * Les trois opérations arithmétiques dont les algorithmes d'origine <b>dépendent</b>.
 *
 * <p>Ce n'est pas de l'émulation 68000, et c'est la seule concession du dépôt. Le jeu de 1996
 * calcule en virgule fixe sur des mots de 16 bits, et ses résultats reposent sur deux
 * comportements précis :
 *
 * <ul>
 *   <li>la <b>troncature à 16 bits signés</b>. Une coordonnée qui déborde ne sature pas, elle
 *       repasse par l'autre bord — et le jeu s'en sert, par exemple pour faire boucler un
 *       angle sans test ;</li>
 *   <li>la <b>division signée tronquée vers zéro</b>. Java fait déjà ainsi pour {@code /}, mais
 *       on le nomme pour que l'intention soit lisible, et parce qu'une division dont le
 *       quotient déborde d'un mot ne doit pas être écrite comme un calcul exact.</li>
 * </ul>
 *
 * <p>Changer l'un ou l'autre ne change pas un arrondi : ça change une trajectoire. Un monstre
 * ne vise plus au même endroit, un projectile ne rebondit plus pareil. D'où ces trois fonctions,
 * nommées et isolées ici plutôt que dispersées en masques dans tout le code.
 */
public final class Arith {

    private Arith() {
    }

    /** Le mot bas, lu comme un entier signé de 16 bits. */
    public static int word(int v) {
        return (short) v;
    }

    /**
     * Produit de deux mots signés de 16 bits, sur 32 bits.
     *
     * <p>Les facteurs sont tronqués AVANT la multiplication : c'est ce qui donne son résultat au
     * jeu quand une coordonnée a débordé.
     */
    public static int mul(int a, int b) {
        return (short) a * (short) b;
    }

    /**
     * Quotient signé, tronqué vers zéro, d'un dividende de 32 bits par un diviseur de 16.
     *
     * @throws ArithmeticException si le diviseur est nul — le matériel levait une exception, et
     *     la masquer ici cacherait un vrai défaut de portage
     */
    public static int div(int dividend, int divisor) {
        int d = (short) divisor;
        if (d == 0) {
            throw new ArithmeticException("division par zéro");
        }
        return dividend / d;
    }

    /** Reste de {@link #div}, du signe du dividende. */
    public static int rem(int dividend, int divisor) {
        int d = (short) divisor;
        if (d == 0) {
            throw new ArithmeticException("division par zéro");
        }
        return dividend % d;
    }
}
