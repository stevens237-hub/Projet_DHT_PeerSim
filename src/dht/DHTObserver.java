package dht;

import peersim.config.Configuration;
import peersim.core.CommonState;
import peersim.core.Control;
import peersim.core.Network;
import peersim.core.Node;

/**
 * Observateur périodique de l'anneau DHT.
 *
 * À chaque appel :
 *  - Affiche le nombre de nœuds ONLINE / OFFLINE.
 *  - Parcourt l'anneau depuis un nœud ONLINE et vérifie :
 *      * que tous les nœuds traversés sont bien ONLINE
 *      * que les identifiants sont dans l'ordre croissant (sauf le wrap-around)
 *      * que le parcours revient bien au point de départ
 *  - Affiche les identifiants dans l'ordre de l'anneau.
 */
public class DHTObserver implements Control {

    private static final String PAR_PROTOCOL = "protocol";

    private final int    pid;
    private final String name;

    public DHTObserver(String prefix) {
        pid  = Configuration.getPid(prefix + "." + PAR_PROTOCOL);
        name = prefix;
    }

    @Override
    public boolean execute() {
        long time   = CommonState.getTime();
        int  online = 0, offline = 0;

        for (int i = 0; i < Network.size(); i++) {
            DHTProtocol p = (DHTProtocol) Network.get(i).getProtocol(pid);
            if (p.state == DHTProtocol.State.ONLINE) online++; else offline++;
        }

        System.out.println("--- [" + name + " t=" + time + "] "
                + "online=" + online + " offline=" + offline + " ---");

        verifyRing(online);
        return false;
    }

    private void verifyRing(int expectedOnline) {
        Node start = null;
        for (int i = 0; i < Network.size(); i++) {
            Node n = Network.get(i);
            if (((DHTProtocol) n.getProtocol(pid)).state == DHTProtocol.State.ONLINE) {
                start = n;
                break;
            }
        }
        if (start == null) { System.out.println("  (aucun nœud en ligne)"); return; }

        StringBuilder sb        = new StringBuilder("  Anneau : ");
        Node          cur       = start;
        int           count     = 0;
        int           wrapCount = 0;   // nombre de descentes d'ID (doit être exactement 1)
        boolean       valid     = true;
        long          prevId    = -1;

        do {
            DHTProtocol proto = (DHTProtocol) cur.getProtocol(pid);

            // Vérification 1 : le nœud traversé doit être ONLINE
            if (proto.state != DHTProtocol.State.ONLINE) {
                System.out.println("  ERREUR : nœud OFFLINE dans l'anneau (id=" + proto.nodeId + ")");
                valid = false;
                break;
            }

            // Vérification 2 : cohérence bidirectionnelle (right.left == this)
            DHTProtocol rightProto = (DHTProtocol) proto.rightNeighbor.getProtocol(pid);
            if (rightProto.leftNeighbor != cur) {
                System.out.println("  ERREUR : incohérence bidirectionnelle au nœud id="
                        + proto.nodeId + " (right.left != this)");
                valid = false;
                break;
            }

            // Vérification 3 : comptage des wrap-arounds (descentes d'ID)
            if (prevId >= 0 && proto.nodeId < prevId) {
                wrapCount++;
            }
            prevId = proto.nodeId;

            sb.append(proto.nodeId).append(" → ");
            count++;

            if (count > Network.size() + 1) {
                System.out.println("  ERREUR : cycle infini détecté !");
                valid = false;
                break;
            }

            cur = proto.rightNeighbor;
        } while (cur != start);

        if (!valid) return;

        sb.append("(retour)");
        System.out.println(sb);

        // Un seul wrap-around attendu (la coupure de l'anneau circulaire)
        if (wrapCount > 1) {
            System.out.println("  ERREUR : ordre des IDs non respecté (" + wrapCount + " inversions)");
        } else if (count != expectedOnline) {
            System.out.println("  ERREUR : " + count + " nœuds dans l'anneau mais "
                    + expectedOnline + " marqués ONLINE.");
        } else {
            System.out.println("  Intégrité OK : " + count + " nœuds, ordre correct, "
                    + "pointeurs bidirectionnels cohérents.");
        }
    }
}
