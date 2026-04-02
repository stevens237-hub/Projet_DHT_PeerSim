package dht;

import peersim.core.Node;

/**
 * Messages échangés dans la DHT anneau.
 *
 * Types :
 *   JOIN_REQ      – un nœud offline demande à rejoindre l'anneau
 *   JOIN_ACK      – réponse : "insère-toi entre moi (sender) et target (mon ancien right)"
 *   UPDATE_RIGHT  – "mets à jour ton voisin de droite avec target"
 *   UPDATE_LEFT   – "mets à jour ton voisin de gauche avec target"
 *   LEAVE_NOTIFY  – "je pars, ton nouveau voisin est target"
 */
public class DHTMessage {

    public enum Type {
        JOIN_REQ,
        JOIN_ACK,
        UPDATE_RIGHT,
        UPDATE_LEFT,
        LEAVE_NOTIFY
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
