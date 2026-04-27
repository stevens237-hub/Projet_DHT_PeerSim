package dht;

import peersim.core.Node;

/**
 * Messages échangés dans la DHT anneau.
 *
 * Types :
 *   JOIN_REQ  – nœud offline cherche sa place (relayé vers la droite)
 *   JOIN_ACK  – réponse : "insère-toi entre moi et target"
 *   ROUTE     – message applicatif source → destination (par ID de nœud)
 *   PUT_REQ   – stocker une donnée (key, value) ; routé vers le responsable
 *   GET_REQ   – récupérer une donnée (key) ; routé vers le responsable
 *   GET_RESP  – réponse au GET ; routé vers la source (par targetId)
 *   REPLICATE – copie d'une donnée envoyée directement aux voisins immédiats
 */
public class DHTMessage {

    public enum Type {
        JOIN_REQ, JOIN_ACK,
        ROUTE,
        PUT_REQ, GET_REQ, GET_RESP, REPLICATE
    }

    public final Type   type;
    public final Node   sender;
    public final long   senderId;

    /** Nœud précédent dans le routage (pour piggybacking). */
    public Node         previousHop;
    public long         previousHopId;

    /** Nœud cible : JOIN_ACK (ancien right), GET_RESP (source à rembourser). */
    public final Node   target;
    /** ID cible  : JOIN_ACK, ROUTE, GET_RESP (ID de la source pour le retour). */
    public final long   targetId;

    /** Payload texte (ROUTE uniquement). */
    public final String payload;
    /** Compteur de sauts (ROUTE, PUT_REQ, GET_REQ, GET_RESP). */
    public final int    hopCount;

    /** Clé de la donnée DHT (PUT_REQ, GET_REQ, GET_RESP, REPLICATE). */
    public final long   key;
    /** Valeur associée à la clé (PUT_REQ, GET_RESP, REPLICATE ; null pour GET_REQ). */
    public final String value;

    // ---------------------------------------------------------------- constructeurs

    /** JOIN_REQ / JOIN_ACK */
    public DHTMessage(Type type, Node sender, long senderId, Node target, long targetId) {
        this(type, sender, senderId, target, targetId, null, 0, 0L, null);
    }

    /** ROUTE */
    public DHTMessage(Type type, Node sender, long senderId, Node target, long targetId,
                      String payload, int hopCount) {
        this(type, sender, senderId, target, targetId, payload, hopCount, 0L, null);
    }

    /** PUT_REQ, GET_REQ, REPLICATE (pas de targetId de nœud) */
    public DHTMessage(Type type, Node sender, long senderId,
                      long key, String value, int hopCount) {
        this(type, sender, senderId, null, 0L, null, hopCount, key, value);
    }

    /** GET_RESP (targetId = ID de la source ; key + value du résultat) */
    public DHTMessage(Type type, Node sender, long senderId,
                      Node target, long targetId, long key, String value, int hopCount) {
        this(type, sender, senderId, target, targetId, null, hopCount, key, value);
    }

    /** Constructeur complet (privé). */
    private DHTMessage(Type type, Node sender, long senderId,
                       Node target, long targetId, String payload, int hopCount,
                       long key, String value) {
        this.type     = type;
        this.sender   = sender;
        this.senderId = senderId;
        this.target   = target;
        this.targetId = targetId;
        this.payload  = payload;
        this.hopCount = hopCount;
        this.key      = key;
        this.value    = value;
    }

    @Override
    public String toString() {
        return "DHTMessage{" + type
                + ", from=" + senderId
                + (targetId != 0 ? ", to=" + targetId : "")
                + (key != 0 ? ", key=" + key : "")
                + (value != null ? ", value='" + value + "'" : "")
                + (hopCount > 0 ? ", hops=" + hopCount : "")
                + "}";
    }
}
