package dht;

import peersim.config.Configuration;
import peersim.core.Node;
import peersim.edsim.EDProtocol;
import peersim.transport.Transport;

/**
 * Protocole DHT anneau – Etapes 1 (join/leave) et 2 (routing).
 *
 * Chaque nœud connait uniquement ses deux voisins immédiats (gauche et droite).
 * Les identifiants sont des longs positifs aléatoires ; les nœuds sont ordonnés
 * par identifiant dans l'anneau.
 *
 * Protocole de JOIN (event-driven) :
 *  1. Le nœud N (OFFLINE) envoie JOIN_REQ à un nœud contact quelconque.
 *  2. Chaque nœud reçoit JOIN_REQ et vérifie si N se place entre lui et son
 *     voisin de droite. Si oui → JOIN_ACK. Sinon → retransmet vers la droite.
 *  3. N reçoit JOIN_ACK(left=L, right=R) :
 *       - fixe ses voisins : left=L, right=R
 *       - met à jour L.right = N et R.left = N (synchrone)
 *       - passe ONLINE
 *
 * Protocole de LEAVE (synchrone) :
 *  1. N met à jour directement leftNeighbor.right = rightNeighbor
 *  2. N met à jour directement rightNeighbor.left = leftNeighbor
 *  3. N passe OFFLINE
 */
public class DHTProtocol implements EDProtocol {

    // ------------------------------------------------------------------ config
    private static final String PAR_TRANSPORT = "transport";

    // ------------------------------------------------------------------ état
    public enum State { OFFLINE, ONLINE }

    /** Identifiant unique du nœud dans l'anneau (long positif). */
    public long  nodeId;
    public Node  leftNeighbor;
    public Node  rightNeighbor;
    public State state;

    /** PID du protocole de transport (nécessaire pour envoyer des messages). */
    private final int tid;

    // ---------------------------------------------------------------- création
    public DHTProtocol(String prefix) {
        tid   = Configuration.getPid(prefix + "." + PAR_TRANSPORT);
        state = State.OFFLINE;
    }

    @Override
    public Object clone() {
        try {
            DHTProtocol clone = (DHTProtocol) super.clone();
            clone.state         = State.OFFLINE;
            clone.nodeId        = 0;
            clone.leftNeighbor  = null;
            clone.rightNeighbor = null;
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------------------------------------------------- accesseur util
    public int getTransportId() { return tid; }

    // ---------------------------------------------------- point d'entrée ED
    @Override
    public void processEvent(Node node, int pid, Object event) {
        DHTMessage msg = (DHTMessage) event;
        // Un nœud OFFLINE n'accepte que JOIN_ACK (sa réponse de join en attente).
        // Tout autre message en transit vers un nœud parti est simplement ignoré.
        if (state == State.OFFLINE && msg.type != DHTMessage.Type.JOIN_ACK) return;
        switch (msg.type) {
            case JOIN_REQ: handleJoinReq(node, pid, msg); break;
            case JOIN_ACK: handleJoinAck(node, pid, msg); break;
            case ROUTE:    handleRoute(node, pid, msg);   break;
        }
    }

    // -------------------------------------------------- handlers individuels

    /**
     * Ce nœud reçoit une demande de JOIN (ou un relai de JOIN).
     * Il vérifie si le nœud demandeur se place entre lui et son right.
     * Si oui → il répond. Sinon → il relaie vers son right.
     */
    private void handleJoinReq(Node node, int pid, DHTMessage msg) {
        DHTProtocol rightProto = (DHTProtocol) rightNeighbor.getProtocol(pid);

        if (isBetween(msg.senderId, this.nodeId, rightProto.nodeId)) {
            // Le nœud se place entre this et rightNeighbor → on répond
            Transport t = (Transport) node.getProtocol(tid);
            DHTMessage ack = new DHTMessage(
                    DHTMessage.Type.JOIN_ACK,
                    node,          this.nodeId,       // left = moi
                    rightNeighbor, rightProto.nodeId  // right = mon ancien right
            );
            t.send(node, msg.sender, ack, pid);
        } else {
            // On relaie vers la droite
            Transport t = (Transport) node.getProtocol(tid);
            t.send(node, rightNeighbor, msg, pid);
        }
    }

    /**
     * Ce nœud (en attente de JOIN) reçoit la réponse.
     * msg.sender  = futur voisin gauche
     * msg.target  = futur voisin droite
     *
     * La découverte de la position est message-based (JOIN_REQ relayé dans l'anneau).
     * La mise à jour finale des pointeurs est synchrone afin que l'anneau soit
     * immédiatement cohérent après insertion.
     */
    private void handleJoinAck(Node node, int pid, DHTMessage msg) {
        DHTProtocol leftProto  = (DHTProtocol) msg.sender.getProtocol(pid);
        DHTProtocol rightProto = (DHTProtocol) msg.target.getProtocol(pid);

        // Vérification de cohérence : si un autre nœud s'est inséré entre left et right
        // depuis l'envoi du JOIN_ACK, on relance la recherche depuis left.
        if (leftProto.rightNeighbor != msg.target) {
            Transport t = (Transport) node.getProtocol(tid);
            DHTMessage retry = new DHTMessage(DHTMessage.Type.JOIN_REQ, node, nodeId, null, 0);
            t.send(node, msg.sender, retry, pid);
            return;
        }

        this.leftNeighbor  = msg.sender;
        this.rightNeighbor = msg.target;
        this.state         = State.ONLINE;

        leftProto.rightNeighbor = node;
        rightProto.leftNeighbor = node;

        System.out.println("[t=" + peersim.core.CommonState.getTime()
                + "] Node " + nodeId + " joined. left=" + getIdOf(leftNeighbor, pid)
                + " right=" + getIdOf(rightNeighbor, pid));
    }

    /**
     * Routage d'un message applicatif (étape 2).
     *
     * Algorithme : on relaie vers le voisin de droite jusqu'à tomber sur le
     * nœud dont l'ID correspond exactement à targetId (O(n) dans l'anneau).
     * Si hopCount dépasse la taille du réseau, le message est abandonné
     * (la destination a probablement quitté l'anneau entre-temps).
     */
    private void handleRoute(Node node, int pid, DHTMessage msg) {
        if (msg.targetId == this.nodeId) {
            // Ce nœud est la destination : livraison finale
            System.out.println("[t=" + peersim.core.CommonState.getTime()
                    + "] [DELIVER] dest=" + nodeId
                    + " from=" + msg.senderId
                    + " payload='" + msg.payload + "'"
                    + " hops=" + msg.hopCount);
            return;
        }

        // Sécurité : si le message a déjà fait plus de tours que de nœuds dans le
        // réseau, la destination est introuvable (nœud parti depuis l'envoi)
        if (msg.hopCount >= peersim.core.Network.size()) {
            System.out.println("[t=" + peersim.core.CommonState.getTime()
                    + "] [DROP] target=" + msg.targetId
                    + " introuvable après " + msg.hopCount + " sauts"
                    + " (dernier nœud=" + nodeId + ")");
            return;
        }

        // Relayage vers le voisin de droite
        DHTProtocol rightProto = (DHTProtocol) rightNeighbor.getProtocol(pid);
        System.out.println("[t=" + peersim.core.CommonState.getTime()
                + "] [FORWARD] at=" + nodeId
                + " hop=" + (msg.hopCount + 1)
                + " next=" + rightProto.nodeId
                + " target=" + msg.targetId);

        DHTMessage fwd = new DHTMessage(
                DHTMessage.Type.ROUTE,
                msg.sender, msg.senderId,
                null, msg.targetId,
                msg.payload, msg.hopCount + 1);
        Transport t = (Transport) node.getProtocol(tid);
        t.send(node, rightNeighbor, fwd, pid);
    }

    // ------------------------------------------------------------ API publique

    /**
     * Envoie un message applicatif vers le nœud ayant l'identifiant targetId.
     * Appelé depuis MessageSenderControl.
     */
    public void sendMessage(Node node, int pid, long targetId, String payload) {
        System.out.println("[t=" + peersim.core.CommonState.getTime()
                + "] [SEND] from=" + nodeId
                + " to=" + targetId
                + " payload='" + payload + "'");

        if (targetId == this.nodeId) {
            // Livraison locale (la source est aussi la destination)
            System.out.println("[t=" + peersim.core.CommonState.getTime()
                    + "] [DELIVER] dest=" + nodeId + " (local) hops=0");
            return;
        }

        DHTMessage msg = new DHTMessage(
                DHTMessage.Type.ROUTE,
                node, nodeId,
                null, targetId,
                payload, 0);
        Transport t = (Transport) node.getProtocol(tid);
        t.send(node, rightNeighbor, msg, pid);
    }

    /**
     * Déclenche la procédure de départ de ce nœud.
     * Appelé depuis JoinLeaveControl.
     *
     * Note de conception : le départ est traité de façon synchrone (mise à jour
     * directe des voisins) afin d'éviter les incohérences transitoires dues aux
     * délais de transport. Dans un vrai système distribué, des messages LEAVE_NOTIFY
     * seraient envoyés (le handler handleLeaveNotify est conservé à cet effet).
     */
    public void leave(Node node, int pid) {
        if (state != State.ONLINE) return;
        // On ne retire pas le dernier nœud de l'anneau
        if (leftNeighbor == node && rightNeighbor == node) return;

        DHTProtocol leftProto  = (DHTProtocol) leftNeighbor.getProtocol(pid);
        DHTProtocol rightProto = (DHTProtocol) rightNeighbor.getProtocol(pid);

        // Mise à jour synchrone : pont direct entre les deux voisins
        leftProto.rightNeighbor  = this.rightNeighbor;
        rightProto.leftNeighbor  = this.leftNeighbor;

        System.out.println("[t=" + peersim.core.CommonState.getTime()
                + "] Node " + nodeId + " leaving. "
                + leftProto.nodeId + " <-> " + rightProto.nodeId);

        state         = State.OFFLINE;
        leftNeighbor  = null;
        rightNeighbor = null;
    }

    // ------------------------------------------------------- méthode utilitaire

    /**
     * Retourne vrai si newId se place strictement entre leftId et rightId
     * dans un espace d'identifiants circulaire.
     *
     *  - Si leftId == rightId : anneau à un seul nœud → toujours vrai.
     *  - Si leftId < rightId  : cas normal, newId ∈ ]leftId, rightId[.
     *  - Sinon (wrap-around)  : newId > leftId OU newId < rightId.
     */
    public static boolean isBetween(long newId, long leftId, long rightId) {
        if (leftId == rightId) return true;        // anneau mono-nœud
        if (leftId < rightId)  return newId > leftId && newId < rightId;
        return newId > leftId || newId < rightId;  // wrap-around
    }

    private long getIdOf(Node n, int pid) {
        return ((DHTProtocol) n.getProtocol(pid)).nodeId;
    }
}
