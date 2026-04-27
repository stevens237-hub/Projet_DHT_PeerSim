package dht;

import peersim.config.Configuration;
import peersim.core.CommonState;
import peersim.core.Control;
import peersim.core.Network;
import peersim.core.Node;

/**
 * Contrôleur de construction de la finger table.
 *
 * À chaque exécution, parcourt tous les nœuds ONLINE du réseau et reconstruit
 * leur finger table en utilisant Network.get(i) — la vue globale du simulateur.
 *
 * C'est la "triche" : dans un vrai système distribué, un nœud ne peut pas
 * interroger directement tous ses pairs. Ici on exploite l'omniscience du
 * simulateur pour calculer finger[i] = successeur de (nodeId + 2^i) mod 2^63
 * sans échange de messages.
 *
 * Paramètres :
 *   protocol – pid du protocole DHT
 */
public class FingerTableControl implements Control {

    private static final String PAR_PROTOCOL = "protocol";
    private final int pid;

    public FingerTableControl(String prefix) {
        pid = Configuration.getPid(prefix + "." + PAR_PROTOCOL);
    }

    @Override
    public boolean execute() {
        int rebuilt = 0;
        for (int n = 0; n < Network.size(); n++) {
            Node node   = Network.get(n);
            DHTProtocol proto = (DHTProtocol) node.getProtocol(pid);
            if (proto.state != DHTProtocol.State.ONLINE) continue;
            buildFingers(node, proto);
            rebuilt++;
        }
        System.out.println("[t=" + CommonState.getTime()
                + "] [FINGERS] tables reconstruites pour " + rebuilt + " nœuds");
        return false;
    }

    /**
     * Reconstruit la finger table du nœud node.
     *
     * Pour chaque indice i ∈ [0, FINGER_BITS) :
     *   target = (proto.nodeId + 2^i) mod 2^63
     *   finger[i] = premier nœud ONLINE avec id >= target (circulaire)
     *
     * La triche : on scanne directement Network.get(j) pour trouver ce nœud.
     */
    private void buildFingers(Node node, DHTProtocol proto) {
        for (int i = 0; i < DHTProtocol.FINGER_BITS; i++) {
            // (nodeId + 2^i) mod 2^63 — & Long.MAX_VALUE maintient le résultat positif
            long target = (proto.nodeId + (1L << i)) & Long.MAX_VALUE;
            proto.fingers[i] = findOnlineSuccessor(target, node);
        }
    }

    /**
     * Trouve le premier nœud ONLINE dont l'ID est >= target dans l'espace circulaire.
     * Si aucun nœud n'a un ID >= target, retourne celui avec le plus petit ID (wrap-around).
     * Le nœud exclude (celui dont on construit la table) est ignoré.
     *
     * Accès direct à Network.get(i) = la "triche".
     */
    private Node findOnlineSuccessor(long target, Node exclude) {
        Node bestDirect = null;   // id >= target (successeur direct)
        long bestDirectId = Long.MAX_VALUE;
        Node bestWrap   = null;   // id <  target (candidat après wrap-around)
        long bestWrapId = Long.MAX_VALUE;

        for (int i = 0; i < Network.size(); i++) {
            Node n = Network.get(i);
            if (n == exclude) continue;
            DHTProtocol p = (DHTProtocol) n.getProtocol(pid);
            if (p.state != DHTProtocol.State.ONLINE) continue;

            if (p.nodeId >= target && p.nodeId < bestDirectId) {
                bestDirect   = n;
                bestDirectId = p.nodeId;
            } else if (p.nodeId < target && p.nodeId < bestWrapId) {
                bestWrap   = n;
                bestWrapId = p.nodeId;
            }
        }
        // Préférer le successeur direct ; sinon prendre le premier après wrap-around
        return bestDirect != null ? bestDirect : bestWrap;
    }
}
