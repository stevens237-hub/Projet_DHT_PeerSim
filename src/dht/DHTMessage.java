package dht;

import peersim.core.Node;

/**
 * Messages échangés dans la DHT anneau.
 *
 * Types :
 *   JOIN_REQ – un nœud offline demande à rejoindre l'anneau ; relayé de droite
 *              en droite jusqu'à trouver la bonne position
 *   JOIN_ACK – réponse : "insère-toi entre moi (sender) et target (mon ancien right)"
 */
public class DHTMessage {

    public enum Type {
        JOIN_REQ,
        JOIN_ACK
    }

    public final Type type;

    /** Nœud expéditeur (celui qui a initié ou relayé le message). */
    public final Node sender;
    public final long senderId;

    /**
     * Nœud cible (sens selon le type) :
     *   JOIN_ACK     → l'ancien voisin de droite du nœud qui répond
     *   UPDATE_*     → le nouveau voisin à enregistrer
     *   LEAVE_NOTIFY → le nœud avec lequel le destinataire doit se connecter
     */
    public final Node target;
    public final long targetId;

    public DHTMessage(Type type, Node sender, long senderId, Node target, long targetId) {
        this.type     = type;
        this.sender   = sender;
        this.senderId = senderId;
        this.target   = target;
        this.targetId = targetId;
    }

    @Override
    public String toString() {
        return "DHTMessage{" + type
                + ", senderId=" + senderId
                + ", targetId=" + targetId + "}";
    }
}
