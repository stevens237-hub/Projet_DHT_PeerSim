package dht;

import peersim.config.Configuration;
import peersim.core.CommonState;
import peersim.core.Control;
import peersim.core.Network;
import peersim.core.Node;
import peersim.transport.Transport;

/**
 * Contrôleur périodique qui pilote les arrivées et départs de nœuds.
 *
 * À chaque pas :
 *  1. Un nœud OFFLINE est sélectionné aléatoirement et initie un JOIN
 *     vers un nœud ONLINE tiré au hasard.
 *  2. Avec probabilité leave_rate, un nœud ONLINE (non-unique) quitte l'anneau.
 *
 * Paramètres de configuration :
 *   protocol   – pid du protocole DHT
 *   leave_rate – probabilité de départ à chaque pas (défaut 0.0)
 */
public class JoinLeaveControl implements Control {

    private static final String PAR_PROTOCOL   = "protocol";
    private static final String PAR_LEAVE_RATE = "leave_rate";

    private final int    pid;
    private final double leaveRate;

    public JoinLeaveControl(String prefix) {
        pid       = Configuration.getPid(prefix + "." + PAR_PROTOCOL);
        leaveRate = Configuration.getDouble(prefix + "." + PAR_LEAVE_RATE, 0.0);
    }

    @Override
    public boolean execute() {
        triggerOneJoin();
        if (leaveRate > 0.0 && CommonState.r.nextDouble() < leaveRate) {
            triggerOneLeave();
        }
        return false;
    }

    // ----------------------------------------------------------------- join

    private void triggerOneJoin() {
        // Sélection d'un nœud OFFLINE au hasard
        Node joiner = randomOfflineNode();
        if (joiner == null) return; // Tous les nœuds sont déjà en ligne

        Node contact = randomOnlineNode(joiner);
        if (contact == null) return; // Aucun nœud en ligne (ne devrait pas arriver)

        DHTProtocol joinerProto = (DHTProtocol) joiner.getProtocol(pid);
        int tid = joinerProto.getTransportId();
        Transport t = (Transport) joiner.getProtocol(tid);

        DHTMessage joinReq = new DHTMessage(
                DHTMessage.Type.JOIN_REQ,
                joiner, joinerProto.nodeId,
                null, 0L);
        t.send(joiner, contact, joinReq, pid);
    }

    // ----------------------------------------------------------------- leave

    private void triggerOneLeave() {
        // Sélection d'un nœud ONLINE au hasard (pas le seul nœud de l'anneau)
        int size  = Network.size();
        int start = CommonState.r.nextInt(size);
        for (int i = 0; i < size; i++) {
            Node node = Network.get((start + i) % size);
            DHTProtocol proto = (DHTProtocol) node.getProtocol(pid);
            if (proto.state == DHTProtocol.State.ONLINE
                    && proto.leftNeighbor != node) { // pas l'unique nœud
                proto.leave(node, pid);
                return;
            }
        }
    }

    // --------------------------------------------------------------- utilitaires

    private Node randomOfflineNode() {
        int size  = Network.size();
        int start = CommonState.r.nextInt(size);
        for (int i = 0; i < size; i++) {
            Node n = Network.get((start + i) % size);
            DHTProtocol p = (DHTProtocol) n.getProtocol(pid);
            if (p.state == DHTProtocol.State.OFFLINE) return n;
        }
        return null;
    }

    private Node randomOnlineNode(Node exclude) {
        int size  = Network.size();
        int start = CommonState.r.nextInt(size);
        for (int i = 0; i < size; i++) {
            Node n = Network.get((start + i) % size);
            DHTProtocol p = (DHTProtocol) n.getProtocol(pid);
            if (n != exclude && p.state == DHTProtocol.State.ONLINE) return n;
        }
        return null;
    }
}
