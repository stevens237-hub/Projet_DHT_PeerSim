package dht;

import peersim.core.Node;

/**
 * Messages échangés dans la DHT anneau.
 *
 * Types :
 *   JOIN_REQ – nœud offline cherche sa place dans l'anneau (relayé vers la droite)
 *   JOIN_ACK – réponse : "insère-toi entre moi (sender) et target (mon ancien right)"
 *   ROUTE    – message applicatif d'un nœud source vers un nœud destination (par ID)
 *              relayé de droite en droite jusqu'à trouver le bon destinataire
 */
public class DHTMessage {

    public enum Type {
        JOIN_REQ,
        JOIN_ACK,
        ROUTE
    }

    public final Type   type;
    public final Node   sender;
    public final long   senderId;

    /**
     * Pour JOIN_ACK : l'ancien voisin de droite du nœud qui répond.
     * Pour ROUTE    : null (on ne connaît pas la référence Java du destinataire,
     *                 seulement son ID).
     */
    public final Node   target;
    public final long   targetId;

    /** Contenu du message applicatif (utilisé uniquement pour ROUTE). */
    public final String payload;

    /** Nombre de sauts effectués depuis la source (utilisé pour ROUTE). */
    public final int    hopCount;

    /** Constructeur pour JOIN_REQ et JOIN_ACK (pas de payload). */
    public DHTMessage(Type type, Node sender, long senderId, Node target, long targetId) {
        this(type, sender, senderId, target, targetId, null, 0);
    }

    /** Constructeur complet (utilisé pour ROUTE). */
    public DHTMessage(Type type, Node sender, long senderId, Node target, long targetId,
                      String payload, int hopCount) {
        this.type     = type;
        this.sender   = sender;
        this.senderId = senderId;
        this.target   = target;
        this.targetId = targetId;
        this.payload  = payload;
        this.hopCount = hopCount;
    }

    @Override
    public String toString() {
        return "DHTMessage{" + type
                + ", from=" + senderId
                + ", to=" + targetId
                + (payload != null ? ", payload='" + payload + "'" : "")
                + (hopCount > 0 ? ", hops=" + hopCount : "")
                + "}";
    }
}
