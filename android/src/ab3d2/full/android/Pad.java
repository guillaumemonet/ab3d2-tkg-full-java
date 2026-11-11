package ab3d2.full.android;

import ab3d2.full.sim.World;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;

/**
 * LES COMMANDES TACTILES : un stick au pouce gauche, la visee au pouce droit.
 *
 * <p>Le jeu de 1996 se joue au clavier, et transposer un clavier sur une vitre ne marche pas :
 * une croix directionnelle dessinee ne se sent pas sous le doigt, et l'on passe son temps a
 * regarder ses pouces au lieu du couloir. Le schema retenu est celui des jeux de tir modernes,
 * et il tient en une phrase : <b>le pouce gauche deplace, le pouce droit tourne</b>.
 *
 * <ul>
 *   <li><b>a gauche</b>, un stick virtuel. Il n'est pas dessine a un endroit fixe : il NAIT ou
 *       le pouce se pose. C'est ce qui permet de jouer sans regarder — on pose le pouce
 *       n'importe ou dans la moitie gauche et le centre est la ou on l'a pose. Vers le haut on
 *       avance, vers le bas on recule, sur les cotes on fait des pas chasses ;</li>
 *   <li><b>a droite</b>, un glissement fait tourner. Pas de stick : un deplacement RELATIF,
 *       comme une souris. Le doigt qui glisse de dix pixels tourne d'autant, et s'arreter
 *       arrete la rotation — un stick, lui, continuerait de tourner tant qu'on le tient ;</li>
 *   <li>deux BOUTONS en bas a droite : tirer, et actionner. Le tir se tient ;</li>
 *   <li>une rangee de CHIFFRES en haut : les armes.</li>
 * </ul>
 *
 * <p>Plusieurs doigts a la fois, donc : on suit chaque pointeur par son identifiant, et non par
 * son rang dans l'evenement — le rang change quand un doigt se leve, l'identifiant non. C'est
 * la faute classique du tactile, et elle se manifeste par un personnage qui part tout seul
 * quand on leve le pouce de tir.
 */
public final class Pad {

    /** La taille du stick, en fraction de la hauteur de l'ecran. */
    private static final float STICK = 0.18f;

    /** En deca de cette fraction du rayon, on considere que le pouce n'a pas bouge. */
    private static final float MORT = 0.25f;

    /** De combien un pixel de glissement fait tourner. */
    private static final float TOURNE = 2.2f;

    /** La pente de visee, bornee : au-dela on tirerait dans ses pieds. */
    private static final int VISEE_MAX = 140;

    private int largeur;
    private int hauteur;

    /** Le doigt du stick, et ou il s'est pose. */
    private int doigtStick = -1;
    private float stickX;
    private float stickY;
    private float versX;
    private float versY;

    /** Le doigt de la visee, et ou il etait a la derniere image. */
    private int doigtVue = -1;
    private float vueX;
    private float vueY;

    private boolean feu;
    private boolean action;
    private int arme = -1;

    /** Ce que les doigts ont demande depuis la derniere image. */
    private float tourne;
    private float visee;

    private final Paint peinture = new Paint(Paint.ANTI_ALIAS_FLAG);

    public void taille(int largeur, int hauteur) {
        this.largeur = largeur;
        this.hauteur = hauteur;
    }

    private float rayon() {
        return hauteur * STICK;
    }

    private float bouton() {
        return hauteur * 0.11f;
    }

    /** Vrai si ce point tombe sur le bouton de tir. */
    private boolean surFeu(float x, float y) {
        return dist(x, y, largeur - bouton() * 1.4f, hauteur - bouton() * 1.4f) < bouton();
    }

    /** Vrai si ce point tombe sur le bouton d'action. */
    private boolean surAction(float x, float y) {
        return dist(x, y, largeur - bouton() * 3.4f, hauteur - bouton() * 1.1f) < bouton() * 0.8f;
    }

    /** Le numero d'arme sous ce point, ou -1. */
    private int surArme(float x, float y) {
        float h = hauteur * 0.09f;
        if (y > h) {
            return -1;
        }
        int n = (int) (x / (largeur / 9f));
        return n >= 0 && n < 9 ? n : -1;
    }

    private static float dist(float ax, float ay, float bx, float by) {
        float dx = ax - bx;
        float dy = ay - by;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** Un evenement tactile : on suit chaque doigt par son IDENTIFIANT, pas par son rang. */
    public void touch(MotionEvent e) {
        int action1 = e.getActionMasked();
        switch (action1) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                poser(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    bouger(e.getPointerId(i), e.getX(i), e.getY(i));
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                int i = e.getActionIndex();
                lever(e.getPointerId(i));
                break;
            }
            default:
                break;
        }
    }

    private void poser(int id, float x, float y) {
        int n = surArme(x, y);
        if (n >= 0) {
            arme = n;
            return;
        }
        if (surFeu(x, y)) {
            feu = true;
            return;
        }
        if (surAction(x, y)) {
            action = true;
            return;
        }
        if (x < largeur / 2f) {
            if (doigtStick < 0) {
                doigtStick = id;                       // le stick NAIT la ou le pouce se pose
                stickX = x;
                stickY = y;
                versX = x;
                versY = y;
            }
        } else if (doigtVue < 0) {
            doigtVue = id;
            vueX = x;
            vueY = y;
        }
    }

    private void bouger(int id, float x, float y) {
        if (id == doigtStick) {
            versX = x;
            versY = y;
        } else if (id == doigtVue) {
            tourne += (x - vueX) * TOURNE;
            visee += (y - vueY) * TOURNE;
            vueX = x;
            vueY = y;
        }
    }

    private void lever(int id) {
        if (id == doigtStick) {
            doigtStick = -1;
        } else if (id == doigtVue) {
            doigtVue = -1;
        } else {
            // Un doigt leve ailleurs : c'etait un bouton. On les relache tous les deux plutot
            // que de retenir lequel, ce qui ne se remarque pas et evite un etat de plus.
            feu = false;
            action = false;
        }
    }

    /**
     * Verse dans l'intention du jeu ce que les doigts ont demande, et remet les compteurs a
     * zero : un glissement vaut pour UNE image, pas pour toutes les suivantes.
     */
    public void lire(World.Input in) {
        in.forward = false;
        in.backward = false;
        in.stepLeft = false;
        in.stepRight = false;
        in.run = false;
        if (doigtStick >= 0) {
            float dx = versX - stickX;
            float dy = versY - stickY;
            float r = rayon();
            if (Math.abs(dy) > r * MORT) {
                in.forward = dy < 0;
                in.backward = dy > 0;
                in.run = Math.abs(dy) > r * 0.8f;      // pousser a fond, c'est courir
            }
            if (Math.abs(dx) > r * MORT) {
                in.stepLeft = dx < 0;
                in.stepRight = dx > 0;
            }
        }
        in.turn = Math.round(tourne);
        tourne = 0;
        in.aim = Math.max(-VISEE_MAX, Math.min(VISEE_MAX, Math.round(visee)));
        in.fire = feu;
        in.action = action;
        in.jump = false;
        in.duck = false;
    }

    /** L'arme demandee depuis la derniere lecture, ou -1. */
    public int armeDemandee() {
        int n = arme;
        arme = -1;
        return n;
    }

    /** Dessine les commandes par-dessus le jeu, discretement. */
    public void draw(Canvas c) {
        float r = rayon();
        peinture.setStyle(Paint.Style.STROKE);
        peinture.setStrokeWidth(Math.max(2f, hauteur * 0.004f));

        if (doigtStick >= 0) {
            peinture.setColor(Color.argb(90, 220, 230, 240));
            c.drawCircle(stickX, stickY, r, peinture);
            peinture.setStyle(Paint.Style.FILL);
            peinture.setColor(Color.argb(120, 220, 230, 240));
            float dx = versX - stickX;
            float dy = versY - stickY;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d > r) {
                dx *= r / d;
                dy *= r / d;
            }
            c.drawCircle(stickX + dx, stickY + dy, r * 0.35f, peinture);
            peinture.setStyle(Paint.Style.STROKE);
        }

        float b = bouton();
        peinture.setColor(Color.argb(feu ? 150 : 70, 255, 120, 90));
        c.drawCircle(largeur - b * 1.4f, hauteur - b * 1.4f, b, peinture);
        peinture.setColor(Color.argb(action ? 150 : 70, 140, 220, 160));
        c.drawCircle(largeur - b * 3.4f, hauteur - b * 1.1f, b * 0.8f, peinture);

        peinture.setStyle(Paint.Style.FILL);
        peinture.setColor(Color.argb(90, 220, 230, 240));
        peinture.setTextSize(hauteur * 0.05f);
        float pas = largeur / 9f;
        for (int i = 0; i < 9; i++) {
            c.drawText(String.valueOf(i + 1), i * pas + pas * 0.4f, hauteur * 0.07f, peinture);
        }
    }
}
