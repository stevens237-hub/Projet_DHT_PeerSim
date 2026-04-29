package dht;

import peersim.config.Configuration;
import peersim.core.Node;
import peersim.edsim.EDProtocol;
import peersim.transport.Transport;

import java.util.HashMap;
import java.util.Map;

/**
 * Protocole DHT anneau .
 *
 * Etape 4 (routage avancé – méthode triche) :
 *   Chaque nœud maintient une finger table de FINGER_BITS entrées.
 *   finger[i] = premier nœud ONLINE dont l'ID >= (nodeId + 2^i) mod 2^63.
 *   La table est construite par FingerTableControl qui accède directement à
 *   Network.get(i) — vue globale du simulateur = la "triche".
 *   Le routage utilise bestNextHop() : plus grand saut sans dépasser la cible,
 *   ce qui réduit la complexité de O(n) à O(log n) sauts.
 */
public class DHTProtocol implements EDProtocol {

    // ------------------------------------------------------------------ config
    private static final String PAR_TRANSPORT = "transport";

    /** Nombre de bits dans l'espace d'IDs (IDs sur 16 bits, [0, 65535]). */
    public static final int FINGER_BITS = 16;

    // ------------------------------------------------------------------ état
    public enum State { OFFLINE, ONLINE }

    public long  nodeId;
    public Node  leftNeighbor;
    public Node  rightNeighbor;
    public State state;

    /** Finger table : finger[i] = successeur de (nodeId + 2^i) mod 2^63. */
    public Node[] fingers;

    /** Stockage local des données dont ce nœud est responsable ou réplica. */
    public Map<Long, String> storage;

    private final int tid;

    // ---------------------------------------------------------------- création
    public DHTProtocol(String prefix) {
        tid     = Configuration.getPid(prefix + "." + PAR_TRANSPORT);
        state   = State.OFFLINE;
        storage = new HashMap<>();
        fingers = new Node[FINGER_BITS];
    }

    @Override
    public Object clone() {
        try {
            DHTProtocol clone = (DHTProtocol) super.clone();
            clone.state         = State.OFFLINE;
            clone.nodeId        = 0;
            clone.leftNeighbor  = null;
            clone.rightNeighbor = null;
            clone.storage       = new HashMap<>();
            clone.fingers       = new Node[FINGER_BITS];
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    public int getTransportId() { return tid; }

    // ---------------------------------------------------- point d'entrée ED
    @Override
    public void processEvent(Node node, int pid, Object event) {
        DHTMessage msg = (DHTMessage) event;
        if (state == State.OFFLINE && msg.type != DHTMessage.Type.JOIN_ACK) return;
        switch (msg.type) {
            case JOIN_REQ:  handleJoinReq(node, pid, msg);   break;
            case JOIN_ACK:  handleJoinAck(node, pid, msg);   break;
            case ROUTE:     handleRoute(node, pid, msg);     break;
            case PUT_REQ:   handlePutReq(node, pid, msg);   break;
            case GET_REQ:   handleGetReq(node, pid, msg);   break;
            case GET_RESP:  handleGetResp(node, pid, msg);  break;
            case REPLICATE: handleReplicate(node, pid, msg); break;
        }
    }

    // -------------------------------------------------- handlers join/leave

    private void handleJoinReq(Node node, int pid, DHTMessage msg) {
        DHTProtocol rightProto = (DHTProtocol) rightNeighbor.getProtocol(pid);
        if (isBetween(msg.senderId, this.nodeId, rightProto.nodeId)) {
            Transport t = (Transport) node.getProtocol(tid);
            DHTMessage ack = new DHTMessage(
                    DHTMessage.Type.JOIN_ACK,
                    node, this.nodeId,
                    rightNeighbor, rightProto.nodeId);
            t.send(node, msg.sender, ack, pid);
        } else {
            Transport t = (Transport) node.getProtocol(tid);
            t.send(node, rightNeighbor, msg, pid);
        }
    }

    private void handleJoinAck(Node node, int pid, DHTMessage msg) {
        DHTProtocol leftProto  = (DHTProtocol) msg.sender.getProtocol(pid);
        DHTProtocol rightProto = (DHTProtocol) msg.target.getProtocol(pid);

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

        // Migration au join : le voisin droit cède les clés dont ce nœud est désormais responsable.
        int inherited = 0;
        for (Map.Entry<Long, String> entry : rightProto.storage.entrySet()) {
            if (isResponsibleStatic(entry.getKey(), leftProto.nodeId, this.nodeId)) {
                this.storage.putIfAbsent(entry.getKey(), entry.getValue());
                inherited++;
            }
        }

        System.out.println("[t=" + peersim.core.CommonState.getTime()
                + "] Node " + nodeId + " joined. left=" + getIdOf(leftNeighbor, pid)
                + " right=" + getIdOf(rightNeighbor, pid)
                + (inherited > 0 ? " inherited=" + inherited + " keys" : ""));
    }

    // -------------------------------------------------- handler routage (étape 2)

    private void handleRoute(Node node, int pid, DHTMessage msg) {
        if (msg.targetId == this.nodeId) {
            System.out.println("[t=" + peersim.core.CommonState.getTime()
                    + "] [DELIVER] dest=" + nodeId
                    + " from=" + msg.senderId
                    + " payload='" + msg.payload + "'"
                    + " hops=" + msg.hopCount);
            return;
        }

        if (msg.hopCount >= peersim.core.Network.size()) {
            System.out.println("[t=" + peersim.core.CommonState.getTime()
                    + "] [DROP] target=" + msg.targetId
                    + " introuvable après " + msg.hopCount + " sauts");
            return;
        }

        Node next   = bestNextHop(msg.targetId, pid);
        long nextId = ((DHTProtocol) next.getProtocol(pid)).nodeId;
        System.out.println("[t=" + peersim.core.CommonState.getTime()
                + "] [FORWARD] at=" + nodeId
                + " hop=" + (msg.hopCount + 1)
                + " next=" + nextId
                + " target=" + msg.targetId);

        DHTMessage fwd = new DHTMessage(
                DHTMessage.Type.ROUTE,
                msg.sender, msg.senderId,
                null, msg.targetId,
                msg.payload, msg.hopCount + 1);
        Transport t = (Transport) node.getProtocol(tid);
        t.send(node, next, fwd, pid);
    }

    // --------------------------------------------------- handlers stockage (étape 3)

    private void handlePutReq(Node node, int pid, DHTMessage msg) {
        if (msg.hopCount >= peersim.core.Network.size()) {
            System.out.println("[t=" + peersim.core.CommonState.getTime()
                    + "] [PUT-DROP] key=" + msg.key + " après " + msg.hopCount + " sauts");
            return;
        }

        if (isResponsible(pid, msg.key)) {
            storage.put(msg.key, msg.value);
            System.out.println("[t=" + peersim.core.CommonState.getTime()
                    + "] [PUT-STORE] node=" + nodeId
                    + " key=" + msg.key + " value='" + msg.value + "'"
                    + " hops=" + msg.hopCount);
            Transport t = (Transport) node.getProtocol(tid);
            DHTMessage rep = new DHTMessage(
                    DHTMessage.Type.REPLICATE, node, nodeId, msg.key, msg.value, 0);
            t.send(node, leftNeighbor,  rep, pid);
            t.send(node, rightNeighbor, rep, pid);
        } else {
            Node next = bestNextHop(msg.key, pid);
            DHTMessage fwd = new DHTMessage(
                    DHTMessage.Type.PUT_REQ,
                    msg.sender, msg.senderId,
                    msg.key, msg.value, msg.hopCount + 1);
            Transport t = (Transport) node.getProtocol(tid);
            t.send(node, next, fwd, pid);
        }
    }

    private void handleGetReq(Node node, int pid, DHTMessage msg) {
        if (msg.hopCount >= peersim.core.Network.size()) {
            System.out.println("[t=" + peersim.core.CommonState.getTime()
                    + "] [GET-DROP] key=" + msg.key + " après " + msg.hopCount + " sauts");
            return;
        }

        if (isResponsible(pid, msg.key)) {
            String found = storage.get(msg.key);
            System.out.println("[t=" + peersim.core.CommonState.getTime()
                    + "] [GET-HIT] node=" + nodeId
                    + " key=" + msg.key
                    + " value=" + (found != null ? "'" + found + "'" : "null")
                    + " hops=" + msg.hopCount);
            DHTMessage resp = new DHTMessage(
                    DHTMessage.Type.GET_RESP,
                    node, nodeId,
                    msg.sender, msg.senderId,
                    msg.key, found, 0);
            Transport t = (Transport) node.getProtocol(tid);
            t.send(node, msg.sender, resp, pid);
        } else {
            Node next = bestNextHop(msg.key, pid);
            DHTMessage fwd = new DHTMessage(
                    DHTMessage.Type.GET_REQ,
                    msg.sender, msg.senderId,
                    msg.key, null, msg.hopCount + 1);
            Transport t = (Transport) node.getProtocol(tid);
            t.send(node, next, fwd, pid);
        }
    }

    private void handleGetResp(Node node, int pid, DHTMessage msg) {
        if (msg.targetId == this.nodeId) {
            System.out.println("[t=" + peersim.core.CommonState.getTime()
                    + "] [GET-RESP] dest=" + nodeId
                    + " key=" + msg.key
                    + " value=" + (msg.value != null ? "'" + msg.value + "'" : "null")
                    + " hops=" + msg.hopCount);
            return;
        }

        if (msg.hopCount >= peersim.core.Network.size()) {
            System.out.println("[t=" + peersim.core.CommonState.getTime()
                    + "] [GET-RESP-DROP] targetId=" + msg.targetId
                    + " après " + msg.hopCount + " sauts");
            return;
        }

        Node next = bestNextHop(msg.targetId, pid);
        DHTMessage fwd = new DHTMessage(
                DHTMessage.Type.GET_RESP,
                msg.sender, msg.senderId,
                msg.target, msg.targetId,
                msg.key, msg.value, msg.hopCount + 1);
        Transport t = (Transport) node.getProtocol(tid);
        t.send(node, next, fwd, pid);
    }

    private void handleReplicate(Node node, int pid, DHTMessage msg) {
        storage.put(msg.key, msg.value);
        System.out.println("[t=" + peersim.core.CommonState.getTime()
                + "] [REPLICATE] node=" + nodeId
                + " key=" + msg.key + " value='" + msg.value + "'");
    }

    // ------------------------------------------------------------ API publique

    public void sendMessage(Node node, int pid, long targetId, String payload) {
        System.out.println("[t=" + peersim.core.CommonState.getTime()
                + "] [SEND] from=" + nodeId + " to=" + targetId
                + " payload='" + payload + "'");

        if (targetId == this.nodeId) {
            System.out.println("[t=" + peersim.core.CommonState.getTime()
                    + "] [DELIVER] dest=" + nodeId + " (local) hops=0");
            return;
        }

        Node next = bestNextHop(targetId, pid);
        DHTMessage msg = new DHTMessage(
                DHTMessage.Type.ROUTE, node, nodeId, null, targetId, payload, 0);
        Transport t = (Transport) node.getProtocol(tid);
        t.send(node, next, msg, pid);
    }

    public void put(Node node, int pid, long key, String value) {
        System.out.println("[t=" + peersim.core.CommonState.getTime()
                + "] [PUT] from=" + nodeId + " key=" + key + " value='" + value + "'");

        if (isResponsible(pid, key)) {
            storage.put(key, value);
            System.out.println("[t=" + peersim.core.CommonState.getTime()
                    + "] [PUT-STORE] node=" + nodeId
                    + " key=" + key + " value='" + value + "' hops=0");
            Transport t = (Transport) node.getProtocol(tid);
            DHTMessage rep = new DHTMessage(
                    DHTMessage.Type.REPLICATE, node, nodeId, key, value, 0);
            t.send(node, leftNeighbor,  rep, pid);
            t.send(node, rightNeighbor, rep, pid);
            return;
        }

        Node next = bestNextHop(key, pid);
        DHTMessage msg = new DHTMessage(DHTMessage.Type.PUT_REQ, node, nodeId, key, value, 0);
        Transport t = (Transport) node.getProtocol(tid);
        t.send(node, next, msg, pid);
    }

    public void get(Node node, int pid, long key) {
        System.out.println("[t=" + peersim.core.CommonState.getTime()
                + "] [GET] from=" + nodeId + " key=" + key);

        if (isResponsible(pid, key)) {
            String found = storage.get(key);
            System.out.println("[t=" + peersim.core.CommonState.getTime()
                    + "] [GET-RESP] dest=" + nodeId + " (local) key=" + key
                    + " value=" + (found != null ? "'" + found + "'" : "null"));
            return;
        }

        Node next = bestNextHop(key, pid);
        DHTMessage msg = new DHTMessage(DHTMessage.Type.GET_REQ, node, nodeId, key, null, 0);
        Transport t = (Transport) node.getProtocol(tid);
        t.send(node, next, msg, pid);
    }

    public void leave(Node node, int pid) {
        if (state != State.ONLINE) return;
        if (leftNeighbor == node && rightNeighbor == node) return;

        DHTProtocol leftProto  = (DHTProtocol) leftNeighbor.getProtocol(pid);
        DHTProtocol rightProto = (DHTProtocol) rightNeighbor.getProtocol(pid);

        int transferred = 0;
        for (Map.Entry<Long, String> entry : storage.entrySet()) {
            if (rightProto.storage.putIfAbsent(entry.getKey(), entry.getValue()) == null) {
                transferred++;
            }
        }

        leftProto.rightNeighbor  = this.rightNeighbor;
        rightProto.leftNeighbor  = this.leftNeighbor;

        System.out.println("[t=" + peersim.core.CommonState.getTime()
                + "] Node " + nodeId + " leaving. transferred=" + transferred
                + " entries to " + rightProto.nodeId
                + " | " + leftProto.nodeId + " <-> " + rightProto.nodeId);

        state         = State.OFFLINE;
        leftNeighbor  = null;
        rightNeighbor = null;
        storage       = new HashMap<>();
    }

    // ------------------------------------------------------- méthodes utilitaires

    /**
     * Sélectionne le meilleur prochain saut vers targetId.
     *
     * Parcourt fingers de i=62 downto 0 (du plus grand saut au plus petit).
     * Retourne le premier finger[i] dont l'ID est strictement entre (nodeId, targetId)
     * dans l'espace circulaire — ou égal à targetId (livraison directe possible).
     * Fallback : rightNeighbor (comportement naïf, O(n)).
     *
     * Complexité résultante : O(log n) sauts.
     */
    private Node bestNextHop(long targetId, int pid) {
        for (int i = FINGER_BITS - 1; i >= 0; i--) {
            if (fingers[i] == null) continue;
            DHTProtocol fp = (DHTProtocol) fingers[i].getProtocol(pid);
            if (fp.state != State.ONLINE) continue;
            if (fp.nodeId == targetId || isBetween(fp.nodeId, this.nodeId, targetId))
                return fingers[i];
        }
        return rightNeighbor;
    }

    private boolean isResponsible(int pid, long key) {
        DHTProtocol leftProto = (DHTProtocol) leftNeighbor.getProtocol(pid);
        long leftId = leftProto.nodeId;
        if (leftId == nodeId) return true;
        if (leftId < nodeId)  return key > leftId && key <= nodeId;
        return key > leftId || key <= nodeId;
    }

    private static boolean isResponsibleStatic(long key, long leftId, long myId) {
        if (leftId == myId) return true;
        if (leftId < myId)  return key > leftId && key <= myId;
        return key > leftId || key <= myId;
    }

    public static boolean isBetween(long newId, long leftId, long rightId) {
        if (leftId == rightId) return true;
        if (leftId < rightId)  return newId > leftId && newId < rightId;
        return newId > leftId || newId < rightId;
    }

    private long getIdOf(Node n, int pid) {
        return ((DHTProtocol) n.getProtocol(pid)).nodeId;
    }
}
