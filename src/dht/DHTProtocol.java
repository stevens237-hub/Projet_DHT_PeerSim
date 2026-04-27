package dht;

import peersim.config.Configuration;
import peersim.core.Node;
import peersim.edsim.EDProtocol;
import peersim.transport.Transport;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * Protocole DHT anneau – Etapes 1 (join/leave), 2 (routing), 3 (storage).
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
 *
 * Protocole de stockage (étape 3) :
 *  - Chaque nœud possède une HashMap<Long, String> storage.
 *  - Le nœud responsable d'une clé k est celui dont l'ID est le successeur
 *    immédiat de k dans l'anneau (premier nœud avec nodeId >= k, mod anneau).
 *  - Degré de réplication = 3 : responsable + voisin gauche + voisin droit.
 *  - PUT_REQ est relayé vers la droite jusqu'au responsable, qui stocke puis
 *    envoie REPLICATE à ses deux voisins immédiats.
 *  - GET_REQ est relayé vers la droite jusqu'au responsable, qui répond avec
 *    GET_RESP routé en sens inverse vers le demandeur.
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

    /** Stockage local des données dont ce nœud est responsable ou réplica. */
    public Map<Long, String> storage;

    /** Table de routage pour l'étape 4 (liens longs). */
    public Set<Node> routingTable;

    /** PID du protocole de transport (nécessaire pour envoyer des messages). */
    private final int tid;

    // ---------------------------------------------------------------- création
    public DHTProtocol(String prefix) {
        tid     = Configuration.getPid(prefix + "." + PAR_TRANSPORT);
        state   = State.OFFLINE;
        storage = new HashMap<>();
        routingTable = new LinkedHashSet<>();
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
            clone.routingTable  = new LinkedHashSet<>();
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

        // --- Etape 4 : Piggybacking ---
        if (msg.sender != null && msg.sender != node) routingTable.add(msg.sender);
        if (msg.previousHop != null && msg.previousHop != node) routingTable.add(msg.previousHop);
        if (routingTable.size() > 20) {
            routingTable.remove(routingTable.iterator().next()); // LRU simple
        }
        // ------------------------------

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
            ack.previousHop = node;
            ack.previousHopId = nodeId;
            t.send(node, msg.sender, ack, pid);
        } else {
            // On relaie vers la droite ou via un lien long
            Transport t = (Transport) node.getProtocol(tid);
            msg.previousHop = node;
            msg.previousHopId = nodeId;
            t.send(node, getBestNextHop(msg.senderId, pid), msg, pid);
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

        // Etape 4 : "En trichant" - On ajoute quelques nœuds de l'anneau à notre table
        for (int i = 0; i < 5; i++) {
            Node randNode = peersim.core.Network.get(peersim.core.CommonState.r.nextInt(peersim.core.Network.size()));
            DHTProtocol randP = (DHTProtocol) randNode.getProtocol(pid);
            if (randP.state == State.ONLINE && randNode != node) {
                routingTable.add(randNode);
            }
        }

        // Migration au join : le voisin droit cède les clés dont ce nœud
        // est maintenant responsable (range (left.id, nodeId] retiré à rightNeighbor).
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

        // Relayage via le meilleur lien long (ou voisin de droite par défaut)
        Node nextHop = getBestNextHop(msg.targetId, pid);
        System.out.println("[t=" + peersim.core.CommonState.getTime()
                + "] [FORWARD] at=" + nodeId
                + " hop=" + (msg.hopCount + 1)
                + " next=" + getIdOf(nextHop, pid)
                + " target=" + msg.targetId);

        DHTMessage fwd = new DHTMessage(
                DHTMessage.Type.ROUTE,
                msg.sender, msg.senderId,
                null, msg.targetId,
                msg.payload, msg.hopCount + 1);
        fwd.previousHop = node;
        fwd.previousHopId = nodeId;
        Transport t = (Transport) node.getProtocol(tid);
        t.send(node, nextHop, fwd, pid);
    }

    // --------------------------------------------------- handlers étape 3

    /**
     * Reçoit une requête PUT_REQ.
     * Si ce nœud est responsable de la clé → stocke et réplique vers ses voisins.
     * Sinon → relaie vers la droite.
     */
    private void handlePutReq(Node node, int pid, DHTMessage msg) {
        if (msg.hopCount >= peersim.core.Network.size()) {
            System.out.println("[t=" + peersim.core.CommonState.getTime()
                    + "] [PUT-DROP] key=" + msg.key + " introuvable après " + msg.hopCount + " sauts");
            return;
        }

        if (isResponsible(pid, msg.key)) {
            storage.put(msg.key, msg.value);
            System.out.println("[t=" + peersim.core.CommonState.getTime()
                    + "] [PUT-STORE] node=" + nodeId
                    + " key=" + msg.key + " value='" + msg.value + "'"
                    + " hops=" + msg.hopCount);

            // Réplication vers le voisin gauche et le voisin droit
            Transport t = (Transport) node.getProtocol(tid);
            DHTMessage rep = new DHTMessage(
                    DHTMessage.Type.REPLICATE, node, nodeId, msg.key, msg.value, 0);
            t.send(node, leftNeighbor,  rep, pid);
            t.send(node, rightNeighbor, rep, pid);
        } else {
            // Relayer vers la droite (ou le meilleur prochain saut)
            DHTMessage fwd = new DHTMessage(
                    DHTMessage.Type.PUT_REQ,
                    msg.sender, msg.senderId,
                    msg.key, msg.value, msg.hopCount + 1);
            fwd.previousHop = node;
            fwd.previousHopId = nodeId;
            Transport t = (Transport) node.getProtocol(tid);
            t.send(node, getBestNextHop(msg.key, pid), fwd, pid);
        }
    }

    /**
     * Reçoit une requête GET_REQ.
     * Si ce nœud est responsable → répond GET_RESP vers le demandeur.
     * Sinon → relaie vers la droite.
     */
    private void handleGetReq(Node node, int pid, DHTMessage msg) {
        if (msg.hopCount >= peersim.core.Network.size()) {
            System.out.println("[t=" + peersim.core.CommonState.getTime()
                    + "] [GET-DROP] key=" + msg.key + " introuvable après " + msg.hopCount + " sauts");
            return;
        }

        if (isResponsible(pid, msg.key)) {
            String found = storage.get(msg.key);
            System.out.println("[t=" + peersim.core.CommonState.getTime()
                    + "] [GET-HIT] node=" + nodeId
                    + " key=" + msg.key
                    + " value=" + (found != null ? "'" + found + "'" : "null")
                    + " hops=" + msg.hopCount);

            // Répondre au demandeur (msg.sender) avec GET_RESP
            DHTMessage resp = new DHTMessage(
                    DHTMessage.Type.GET_RESP,
                    node, nodeId,
                    msg.sender, msg.senderId,
                    msg.key, found, 0);
            Transport t = (Transport) node.getProtocol(tid);
            t.send(node, msg.sender, resp, pid);
        } else {
            DHTMessage fwd = new DHTMessage(
                    DHTMessage.Type.GET_REQ,
                    msg.sender, msg.senderId,
                    msg.key, null, msg.hopCount + 1);
            fwd.previousHop = node;
            fwd.previousHopId = nodeId;
            Transport t = (Transport) node.getProtocol(tid);
            t.send(node, getBestNextHop(msg.key, pid), fwd, pid);
        }
    }

    /**
     * Reçoit la réponse GET_RESP.
     * Si ce nœud est le destinataire (targetId == nodeId) → livraison.
     * Sinon → relaie vers la droite (le demandeur est quelque part dans l'anneau).
     */
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
                    + " introuvable après " + msg.hopCount + " sauts");
            return;
        }

        DHTMessage fwd = new DHTMessage(
                DHTMessage.Type.GET_RESP,
                msg.sender, msg.senderId,
                msg.target, msg.targetId,
                msg.key, msg.value, msg.hopCount + 1);
        fwd.previousHop = node;
        fwd.previousHopId = nodeId;
        Transport t = (Transport) node.getProtocol(tid);
        t.send(node, getBestNextHop(msg.targetId, pid), fwd, pid);
    }

    /**
     * Reçoit un message de réplication directe.
     * Stocke simplement la paire (key, value) localement.
     */
    private void handleReplicate(Node node, int pid, DHTMessage msg) {
        storage.put(msg.key, msg.value);
        System.out.println("[t=" + peersim.core.CommonState.getTime()
                + "] [REPLICATE] node=" + nodeId
                + " key=" + msg.key + " value='" + msg.value + "'");
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
        msg.previousHop = node;
        msg.previousHopId = nodeId;
        Transport t = (Transport) node.getProtocol(tid);
        t.send(node, getBestNextHop(targetId, pid), msg, pid);
    }

    /**
     * Initie un PUT vers la DHT : le message est relayé jusqu'au nœud responsable.
     * Appelé depuis StorageTestControl.
     */
    public void put(Node node, int pid, long key, String value) {
        System.out.println("[t=" + peersim.core.CommonState.getTime()
                + "] [PUT] from=" + nodeId
                + " key=" + key + " value='" + value + "'");

        if (isResponsible(pid, key)) {
            // Ce nœud est directement responsable
            storage.put(key, value);
            System.out.println("[t=" + peersim.core.CommonState.getTime()
                    + "] [PUT-STORE] node=" + nodeId
                    + " key=" + key + " value='" + value + "' hops=0");
            Transport t = (Transport) node.getProtocol(tid);
            DHTMessage rep = new DHTMessage(DHTMessage.Type.REPLICATE, node, nodeId, key, value, 0);
            t.send(node, leftNeighbor,  rep, pid);
            t.send(node, rightNeighbor, rep, pid);
            return;
        }

        DHTMessage msg = new DHTMessage(DHTMessage.Type.PUT_REQ, node, nodeId, key, value, 0);
        msg.previousHop = node;
        msg.previousHopId = nodeId;
        Transport t = (Transport) node.getProtocol(tid);
        t.send(node, getBestNextHop(key, pid), msg, pid);
    }

    /**
     * Initie un GET vers la DHT : le message est relayé jusqu'au nœud responsable.
     * Appelé depuis StorageTestControl.
     */
    public void get(Node node, int pid, long key) {
        System.out.println("[t=" + peersim.core.CommonState.getTime()
                + "] [GET] from=" + nodeId + " key=" + key);

        if (isResponsible(pid, key)) {
            String found = storage.get(key);
            System.out.println("[t=" + peersim.core.CommonState.getTime()
                    + "] [GET-RESP] dest=" + nodeId + " (local)"
                    + " key=" + key
                    + " value=" + (found != null ? "'" + found + "'" : "null"));
            return;
        }

        DHTMessage msg = new DHTMessage(DHTMessage.Type.GET_REQ, node, nodeId, key, null, 0);
        msg.previousHop = node;
        msg.previousHopId = nodeId;
        Transport t = (Transport) node.getProtocol(tid);
        t.send(node, getBestNextHop(key, pid), msg, pid);
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

        // Transfert du storage au successeur (voisin droit) avant de partir.
        // Le voisin droit devient responsable de toutes les clés de ce nœud ;
        // on lui donne les données qu'il ne possède pas encore.
        int transferred = 0;
        for (Map.Entry<Long, String> entry : storage.entrySet()) {
            if (rightProto.storage.putIfAbsent(entry.getKey(), entry.getValue()) == null) {
                transferred++;
            }
        }

        // Mise à jour synchrone : pont direct entre les deux voisins
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
     * Etape 4 : Sélectionne le meilleur prochain saut (le plus proche de targetId)
     * parmi le voisin de droite et la table de routage.
     */
    private Node getBestNextHop(long targetId, int pid) {
        Node bestNode = rightNeighbor;
        long minDist = distance(getIdOf(rightNeighbor, pid), targetId);

        for (Node n : routingTable) {
            DHTProtocol p = (DHTProtocol) n.getProtocol(pid);
            if (p.nodeId == this.nodeId || p.state != State.ONLINE) continue;

            // Le nœud doit être strictement entre moi et la cible
            if (isBetween(p.nodeId, this.nodeId, targetId)) {
                long d = distance(p.nodeId, targetId);
                if (d < minDist) {
                    minDist = d;
                    bestNode = n;
                }
            }
        }
        return bestNode;
    }

    /**
     * Calcule la distance logique entre a et b dans le sens horaire.
     */
    private long distance(long a, long b) {
        if (b >= a) return b - a;
        return Long.MAX_VALUE - a + b + 1;
    }

    /**
     * Retourne vrai si ce nœud est le responsable de la clé donnée.
     *
     * Dans un anneau ordonné par ID croissant, le responsable d'une clé k est le
     * premier nœud rencontré dans le sens horaire dont l'ID est >= k.
     * En termes de voisinage : this est responsable si k ∈ (leftNeighbor.id, nodeId].
     *
     * Cas particulier : si leftNeighbor.id == nodeId (anneau mono-nœud), on est
     * toujours responsable.
     */
    private boolean isResponsible(int pid, long key) {
        DHTProtocol leftProto = (DHTProtocol) leftNeighbor.getProtocol(pid);
        long leftId = leftProto.nodeId;
        if (leftId == nodeId) return true;           // anneau mono-nœud
        if (leftId < nodeId)  return key > leftId && key <= nodeId;
        return key > leftId || key <= nodeId;        // wrap-around
    }

    /** Version statique de isResponsible, utilisée lors du join. */
    private static boolean isResponsibleStatic(long key, long leftId, long myId) {
        if (leftId == myId) return true;
        if (leftId < myId)  return key > leftId && key <= myId;
        return key > leftId || key <= myId;
    }

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
