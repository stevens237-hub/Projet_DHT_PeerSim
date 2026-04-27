package dht;

import peersim.config.Configuration;
import peersim.core.CommonState;
import peersim.core.Control;
import peersim.core.Network;
import peersim.core.Node;

/**
 * Contrôleur de test pour l'étape 2 (routing).
 *
 * À chaque pas, sélectionne aléatoirement deux nœuds ONLINE (source et destination)
 * et envoie un message applicatif de l'un vers l'autre.
 * Le message est relayé de voisin en voisin dans l'anneau jusqu'à atteindre
 * le nœud dont l'ID correspond à targetId.
 *
 * Paramètres de configuration :
 *   protocol – pid du protocole DHT
 */
public class MessageSenderControl implements Control {

    private static final String PAR_PROTOCOL = "protocol";
    private final int pid;

    public MessageSenderControl(String prefix) {
        pid = Configuration.getPid(prefix + "." + PAR_PROTOCOL);
    }

    @Override
    public boolean execute() {
        Node src = randomOnlineNode(null);
        Node dst = randomOnlineNode(src);
        if (src == null || dst == null) return false;

        DHTProtocol srcProto = (DHTProtocol) src.getProtocol(pid);
        DHTProtocol dstProto = (DHTProtocol) dst.getProtocol(pid);

        String payload = "msg-t" + CommonState.getTime()
                + "-from-" + srcProto.nodeId;

        srcProto.sendMessage(src, pid, dstProto.nodeId, payload);
        return false;
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
