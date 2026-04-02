package dht;

import peersim.config.Configuration;
import peersim.core.CommonState;
import peersim.core.Control;
import peersim.core.Network;
import peersim.core.Node;

/**
 * Initialise la DHT au démarrage de la simulation :
 *  - Assigne un identifiant long positif aléatoire à chaque nœud.
 *  - Met tous les nœuds en état OFFLINE.
 *  - Active uniquement le premier nœud (Network.get(0)) comme unique membre
 *    de l'anneau : il est son propre voisin gauche et droit.
 */
public class DHTInitializer implements Control {

    private static final String PAR_PROTOCOL = "protocol";
    private final int pid;

    public DHTInitializer(String prefix) {
        pid = Configuration.getPid(prefix + "." + PAR_PROTOCOL);
    }

    @Override
    public boolean execute() {
        // Attribuer des IDs aléatoires à tous les nœuds
        for (int i = 0; i < Network.size(); i++) {
            Node node = Network.get(i);
            DHTProtocol proto = (DHTProtocol) node.getProtocol(pid);
            // Long positif (bit de signe mis à 0)
            proto.nodeId = CommonState.r.nextLong() >>> 1;
            proto.state  = DHTProtocol.State.OFFLINE;
        }

        // Seul le premier nœud est en ligne : anneau mono-nœud
        Node first = Network.get(0);
        DHTProtocol firstProto = (DHTProtocol) first.getProtocol(pid);
        firstProto.state         = DHTProtocol.State.ONLINE;
        firstProto.leftNeighbor  = first;
        firstProto.rightNeighbor = first;

        System.out.println("[init] DHT initialisée. Nœud fondateur : id="
                + firstProto.nodeId);
        return false;
    }
}
