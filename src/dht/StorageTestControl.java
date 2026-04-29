package dht;

import peersim.config.Configuration;
import peersim.core.CommonState;
import peersim.core.Control;
import peersim.core.Network;
import peersim.core.Node;

/**
 * Contrôleur de test pour storage : Put/Get.
 *
 * Fonctionnement en deux phases :
 *
 *  Phase PUT  (t < midTime) :
 *    À chaque step, sélectionne un nœud ONLINE aléatoire et lui fait émettre
 *    un PUT_REQ avec une clé et une valeur aléatoires.
 *    La clé est un long positif aléatoire ; la valeur est "val-<clé>".
 *    Les clés émises sont mémorisées pour la phase GET.
 *
 *  Phase GET  (t >= midTime) :
 *    À chaque step, sélectionne un nœud ONLINE aléatoire et lui fait émettre
 *    un GET_REQ pour l'une des clés préalablement stockées.
 *
 * Paramètres de configuration :
 *   protocol  – pid du protocole DHT
 *   num_items – nombre de paires (key, value) à insérer (défaut : 5)
 */
public class StorageTestControl implements Control {

    private static final String PAR_PROTOCOL  = "protocol";
    private static final String PAR_NUM_ITEMS = "num_items";

    private final int    pid;
    private final int    numItems;

    /** Clés insérées pendant la phase PUT, lues lors de la phase GET. */
    private final long[] keys;
    private int putCount = 0;
    private int getCount = 0;

    public StorageTestControl(String prefix) {
        pid      = Configuration.getPid(prefix + "." + PAR_PROTOCOL);
        numItems = Configuration.getInt(prefix + "." + PAR_NUM_ITEMS, 5);
        keys     = new long[numItems];
    }

    @Override
    public boolean execute() {
        if (putCount < numItems) {
            doPut();
        } else if (getCount < numItems) {
            doGet();
        }
        return false;
    }

    private void doPut() {
        Node src = randomOnlineNode();
        if (src == null) return;

        long key   = CommonState.r.nextLong() & ((1L << DHTProtocol.FINGER_BITS) - 1);
        String val = "val-" + key;
        keys[putCount] = key;
        putCount++;

        DHTProtocol proto = (DHTProtocol) src.getProtocol(pid);
        proto.put(src, pid, key, val);
    }

    private void doGet() {
        Node src = randomOnlineNode();
        if (src == null) return;

        long key = keys[getCount];
        getCount++;

        DHTProtocol proto = (DHTProtocol) src.getProtocol(pid);
        proto.get(src, pid, key);
    }

    private Node randomOnlineNode() {
        int size  = Network.size();
        int start = CommonState.r.nextInt(size);
        for (int i = 0; i < size; i++) {
            Node n = Network.get((start + i) % size);
            DHTProtocol p = (DHTProtocol) n.getProtocol(pid);
            if (p.state == DHTProtocol.State.ONLINE) return n;
        }
        return null;
    }
}
