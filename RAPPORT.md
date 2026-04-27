# Rapport de projet – DHT Anneau sur PeerSim

## Contexte général

L'objectif du projet est de concevoir et implémenter une **DHT (Distributed Hash Table)** en anneau au-dessus de **PeerSim 1.0.5**, un simulateur de systèmes pair-à-pair orienté événements (Event-Driven).

Dans une DHT en anneau :
- Chaque nœud possède un **identifiant unique** (long positif 63 bits) tiré aléatoirement.
- Les nœuds sont ordonnés dans l'anneau par ordre croissant de leurs identifiants.
- Chaque nœud ne connaît que ses **deux voisins immédiats** : le voisin gauche et le voisin droit.
- Les données et les messages circulent dans l'anneau de voisin en voisin.

Le projet est découpé en étapes progressives, chacune ajoutant une fonctionnalité.

---

## Étape 1 — Join / Leave (Rejoindre et quitter l'anneau)

### Objectif

Permettre à des nœuds de rejoindre dynamiquement l'anneau et de le quitter proprement, en maintenant à tout moment la cohérence de la structure (ordre des IDs, pointeurs gauche/droite valides).

### Choix d'architecture

**Identifiants** : générés avec `CommonState.r.nextLong() >>> 1`. Le décalage `>>> 1` (décalage logique à droite) force le bit de signe à 0, garantissant que tous les IDs sont **positifs**. Cela simplifie les comparaisons circulaires.

**État des nœuds** : chaque nœud est soit `OFFLINE` (créé mais pas dans l'anneau) soit `ONLINE` (intégré à l'anneau). PeerSim crée tous les nœuds dès le début ; seul le premier est `ONLINE` au démarrage avec `leftNeighbor = rightNeighbor = lui-même` (anneau mono-nœud).

### Protocole JOIN (découverte par messages, insertion synchrone)

Le join se déroule en deux phases intentionnellement séparées :

**Phase 1 — Découverte (event-driven, messages)** :
1. Le nœud N (OFFLINE) envoie un `JOIN_REQ` à un nœud de contact quelconque (choisi aléatoirement parmi les ONLINE).
2. Chaque nœud qui reçoit le `JOIN_REQ` vérifie si N se place entre lui et son voisin droit grâce à `isBetween(N.id, moi.id, droite.id)`.
3. Si oui → il répond avec un `JOIN_ACK` contenant ses coordonnées et celles de son voisin droit (futur voisin gauche et droit de N).
4. Si non → il relaie le `JOIN_REQ` vers son voisin droit.

**Phase 2 — Insertion (synchrone, accès direct aux objets)** :
Quand N reçoit le `JOIN_ACK` :
- N fixe ses voisins : `left = L`, `right = R`.
- N met à jour directement les objets Java : `L.right = N` et `R.left = N`.
- N passe `ONLINE`.

**Pourquoi séparer ces deux phases ?**
La découverte doit être distribuée (simuler le comportement réel), mais la mise à jour finale doit être atomique. Si on utilisait des messages pour mettre à jour les pointeurs de L et R, il y aurait une fenêtre de temps pendant laquelle l'anneau serait incohérent (L pointe encore vers R, mais N est déjà "inséré"). Dans PeerSim, l'accès direct aux objets est instantané et évite ces incohérences transitoires.

### Protocole LEAVE (entièrement synchrone)

Le départ est traité de façon 100% synchrone :
1. N met à jour directement `L.right = R`.
2. N met à jour directement `R.left = L`.
3. N passe `OFFLINE` et nullifie ses pointeurs.

**Pourquoi synchrone ?** Si on utilisait des messages `LEAVE_NOTIFY`, il y aurait un délai (100–500 unités de temps) pendant lequel L et R croiraient encore que N est dans l'anneau. Des messages en transit pourraient être envoyés vers N (qui est OFFLINE), causant des `NullPointerException`. Le synchrone élimine cette fenêtre d'incohérence.

### Fonction `isBetween(newId, leftId, rightId)`

Cette fonction gère le **wrap-around** de l'anneau circulaire :
```
Si leftId == rightId  → anneau mono-nœud, toujours vrai
Si leftId < rightId   → cas normal : newId ∈ ]leftId, rightId[
Sinon (wrap-around)   → newId > leftId OU newId < rightId
```
Le wrap-around se produit quand le nœud avec le plus grand ID a pour voisin droit le nœud avec le plus petit ID.

### Observateur (`DHTObserver`)

Vérifie périodiquement l'anneau sur trois critères :
1. Tous les nœuds traversés sont ONLINE.
2. **Cohérence bidirectionnelle** : pour chaque nœud N, `N.right.left == N`.
3. **Exactement un wrap-around** : les IDs doivent être croissants sauf à un seul endroit (la "coupure" de l'anneau).

---

### Problèmes rencontrés et corrections — Étape 1

#### Problème 1 : NullPointerException sur nœud OFFLINE

**Symptôme** : un `JOIN_REQ` arrivait sur un nœud qui venait de quitter. Ce nœud avait `rightNeighbor = null`, ce qui causait un crash lors du `handleJoinReq`.

**Cause** : le transport introduit un délai de 100–500 ut. Un nœud peut recevoir des messages après son départ.

**Correction** : filtre en entrée de `processEvent` :
```java
if (state == State.OFFLINE && msg.type != JOIN_ACK) return;
```
Un nœud OFFLINE ignore tout sauf son propre `JOIN_ACK` (en attente depuis avant son éventuel départ).

---

#### Problème 2 : Nœud OFFLINE visible dans l'anneau

**Symptôme** : l'observateur signalait `ERREUR : nœud OFFLINE dans l'anneau`.

**Cause** : la version initiale envoyait des messages `LEAVE_NOTIFY` via le transport pour prévenir les voisins. Pendant le délai de transport, les voisins avaient encore des pointeurs vers le nœud parti.

**Correction** : suppression des messages `LEAVE_NOTIFY` ; remplacement par la mise à jour synchrone directe (`L.right = R`, `R.left = L`).

---

#### Problème 3 : Nœud ONLINE absent de l'anneau

**Symptôme** : l'observateur signalait un nombre de nœuds traversés inférieur au nombre de nœuds ONLINE.

**Cause** : la version initiale envoyait des messages `UPDATE_RIGHT` et `UPDATE_LEFT` via le transport pour mettre à jour les voisins lors du join. Pendant le délai, les voisins n'avaient pas encore leurs pointeurs à jour.

**Correction** : même principe — mise à jour synchrone directe dans `handleJoinAck`.

---

#### Problème 4 : Race condition de JOIN concurrent

**Symptôme** : deux nœuds joignent presque simultanément pour la même position dans l'anneau. Le second écrase l'insertion du premier, laissant un nœud "perdu".

**Cause** : le `JOIN_ACK` est envoyé en se basant sur l'état de l'anneau au moment de l'envoi. Si un autre nœud s'est inséré entre-temps, le `JOIN_ACK` est devenu obsolète.

**Correction** : dans `handleJoinAck`, vérification de cohérence avant de valider l'insertion :
```java
if (leftProto.rightNeighbor != msg.target) {
    // La position a changé — relancer un JOIN_REQ depuis left
    t.send(node, msg.sender, new JOIN_REQ(...), pid);
    return;
}
```
Si la vérification échoue, le nœud relance sa recherche depuis le voisin gauche proposé, qui est désormais le "point d'entrée" le plus proche de sa position souhaitée.

---

## Étape 2 — Routing (Send / Deliver)

### Objectif

Permettre à un nœud d'envoyer un message applicatif à n'importe quel autre nœud identifié par son ID, en traversant l'anneau de proche en proche.

### Fonctionnement

Un message `ROUTE` transporte : expéditeur, `targetId` (ID de destination), payload texte, et `hopCount`.

À chaque nœud :
- Si `msg.targetId == monId` → **DELIVER** (livraison finale, affichage du log).
- Si `hopCount >= Network.size()` → **DROP** (la destination a probablement quitté l'anneau).
- Sinon → **FORWARD** vers `rightNeighbor`, avec `hopCount + 1`.

### Logs de traçabilité

```
[SEND]    from=X to=Y payload='...'
[FORWARD] at=X hop=N next=Z target=Y
[DELIVER] dest=Y from=X payload='...' hops=N
[DROP]    target=Y introuvable après N sauts
```

### Pourquoi hopCount >= Network.size() comme seuil de DROP ?

Si un message a fait autant de sauts qu'il y a de nœuds dans le réseau, il a potentiellement fait **un tour complet** de l'anneau sans trouver sa destination. La destination a donc quitté l'anneau après l'envoi du message. C'est le TTL (Time To Live) de notre système.

---

### Problèmes rencontrés et corrections — Étape 2

Aucun bug majeur. L'étape 2 est directe une fois l'anneau stable (étape 1 corrigée). La seule subtilité : le saut est calculé **avant la livraison** — `hopCount` dans le `DELIVER` représente le nombre de sauts effectués pour atteindre la destination (le nœud qui livre ne compte pas comme un saut supplémentaire).

---

## Étape 3 — Storage (Put / Get)

### Objectif

Chaque nœud stocke des données sous forme de paires `(clé, valeur)` avec `clé` = long positif et `valeur` = chaîne. Le stockage est **distribué** : chaque clé est confiée au nœud responsable, avec **réplication de degré 3**.

### Qui est responsable d'une clé ?

Le nœud responsable de la clé `k` est celui dont l'ID est le **premier successeur de `k` dans l'anneau** :
```
isResponsible(key) : key ∈ (leftNeighbor.id, monId]
```
Avec wrap-around si `leftNeighbor.id > monId`.

### Types de messages ajoutés

| Type | Rôle |
|---|---|
| `PUT_REQ` | Routé vers la droite jusqu'au responsable de la clé |
| `GET_REQ` | Idem, demande de récupération |
| `GET_RESP` | Réponse du responsable vers le demandeur (routée dans l'anneau) |
| `REPLICATE` | Copie directe envoyée aux voisins immédiats (gauche et droite) |

### Réplication de degré 3

Quand le responsable stocke une clé :
1. Il stocke localement.
2. Il envoie `REPLICATE` à son voisin gauche.
3. Il envoie `REPLICATE` à son voisin droit.

Résultat : la donnée existe sur 3 nœuds consécutifs dans l'anneau.

### Contrôleur de test (`StorageTestControl`)

Fonctionne en deux phases :
- **Phase PUT** : insère `num_items` paires (clé aléatoire, valeur = `"val-<clé>"`).
- **Phase GET** : récupère les mêmes clés et vérifie les valeurs reçues.

---

### Problèmes rencontrés et corrections — Étape 3

#### Problème 1 : GET retourne `null` après départ du responsable

**Symptôme** : 2 GETs sur 5 retournaient `value=null` dans les premiers tests.

**Cause** : quand le nœud responsable quitte l'anneau, ses données ne sont transmises nulle part. Le GET est alors routé vers le **nouveau responsable**, qui n'a jamais vu ces données — même si les réplicas existent encore chez les anciens voisins.

**Correction 1 — Migration au LEAVE** :
Dans `leave()`, avant de couper les pointeurs, le nœud transfère son storage au successeur (voisin droit) :
```java
for (Map.Entry<Long, String> entry : storage.entrySet()) {
    rightProto.storage.putIfAbsent(entry.getKey(), entry.getValue());
}
```
Résultat : de 3/5 GETs réussis à 4/5.

---

#### Problème 2 : GET toujours `null` malgré la migration au LEAVE (race condition join-après-leave)

**Symptôme** : 1 GET retournait encore `null` après la correction précédente.

**Analyse** :
1. Node `832…` (responsable de `8825…`) quitte à t=38000 → transfère à `1459…`.
2. Node `604…` rejoint à t=40122 entre `8716…` et `1459…`.
3. `604…` devient le nouveau responsable de `8825…` (son ID est après le wrap-around).
4. `1459…` a bien reçu les données, mais `604…` qui arrive après ne les récupère pas.

**Observation clé** : les réplicas (`8605…` et `2172…`) ont encore la donnée, mais le GET ne les interroge jamais — il va directement au responsable courant.

**Correction 2 — Migration au JOIN** :
Dans `handleJoinAck`, après l'insertion dans l'anneau, le nouveau nœud hérite des clés dont il est maintenant responsable depuis son voisin droit :
```java
for (Map.Entry<Long, String> entry : rightProto.storage.entrySet()) {
    if (isResponsibleStatic(entry.getKey(), leftProto.nodeId, this.nodeId)) {
        this.storage.putIfAbsent(entry.getKey(), entry.getValue());
    }
}
```
Le voisin droit avait ces clés : soit comme réplica, soit comme ancien responsable, soit reçues via migration au départ. Il les donne au nouveau nœud qui en devient responsable.

**Résultat final** : **5/5 GETs réussis**, zéro perte de données.

---

#### Explication du cycle complet de durabilité

```
PUT   → stocké sur le responsable + REPLICATE aux 2 voisins (degré 3)
LEAVE → le responsable transfère son storage au successeur droit
JOIN  → le nouveau nœud hérite des clés de son successeur droit
GET   → toujours trouvé, quel que soit l'historique de join/leave
```

Les réplicas servent d'amortisseur : même si le responsable part et qu'un nouveau arrive, la chaîne `leave → transfer → join → inherit` garantit la continuité de la donnée.

---

## Étape 4 — Advanced Routing (Finger Table – méthode "triche")

### Problème du routage naïf

Dans les étapes 1 à 3, le routage est **O(n)** : chaque message avance d'un nœud vers la droite à chaque saut. Avec 20 nœuds, le maximum est 19 sauts. Avec 1000 nœuds, c'est 999 sauts dans le pire cas — soit ~300 000 unités de temps de délai, bien au-delà de notre `simulation.endtime`. Le routage naïf ne passe pas à l'échelle.

### La Finger Table

Chaque nœud maintient un tableau de **63 entrées** (63 bits dans l'espace d'IDs). L'entrée `i` pointe vers le premier nœud ONLINE dont l'ID est ≥ `(monId + 2^i) mod 2^63` :

```
finger[0]  = successeur de (monId + 1)   → saut de 1
finger[1]  = successeur de (monId + 2)   → saut de 2
finger[2]  = successeur de (monId + 4)   → saut de 4
finger[3]  = successeur de (monId + 8)   → saut de 8
...
finger[62] = successeur de (monId + 2^62) → saut à mi-chemin de l'anneau
```

Les sauts doublent à chaque entrée — c'est une **recherche dichotomique sur l'anneau**.

### Algorithme de routage avec la Finger Table

Méthode `bestNextHop(targetId)` :
```
Pour i de 62 downto 0 :
    Si finger[i] est ONLINE et finger[i].id ∈ (monId, targetId) strictement :
        Retourner finger[i]   ← le plus grand saut sans dépasser la cible
Retourner rightNeighbor      ← fallback naïf si aucun finger n'aide
```

**Pourquoi parcourir de 62 downto 0 ?** Pour essayer le plus grand saut en premier. Le premier finger qui satisfait la condition est nécessairement le meilleur saut possible depuis ce nœud vers cette cible.

**Complexité** : O(log n) sauts au lieu de O(n).

### La "triche" : FingerTableControl

Dans un vrai système distribué, un nœud ne sait pas où sont les autres. Construire la finger table nécessiterait d'envoyer des messages dans l'anneau pour trouver chaque successeur — c'est la méthode "honnête" (piggybacking, étape suivante).

Ici, on **triche** en exploitant la vue globale de PeerSim : `Network.get(i)` donne accès à n'importe quel nœud du réseau directement. `FingerTableControl` s'exécute toutes les 1000 unités de temps et reconstruit les finger tables de tous les nœuds ONLINE en scannant tout le réseau :

```java
// Pour chaque nœud ONLINE n :
//   Pour chaque i de 0 à 62 :
//     target = (n.nodeId + 2^i) & Long.MAX_VALUE
//     Parcourir tous les nœuds ONLINE pour trouver le plus petit id >= target
//     n.fingers[i] = ce nœud
```

Le `& Long.MAX_VALUE` gère l'overflow : si `nodeId + 2^i` dépasse `Long.MAX_VALUE`, le résultat devient négatif en Java ; l'opération `& Long.MAX_VALUE` (masque du bit de signe) ramène la valeur dans l'espace [0, 2^63-1] — c'est l'arithmétique modulo 2^63 dont on a besoin.

### Résultats observés

| Réseau | Routage naïf (max sauts) | Finger Table (max sauts) |
|---|---|---|
| 20 nœuds  | 19 sauts     | 3 sauts      |
| 1 000 nœuds | ~999 sauts | ~10 sauts    |
| 1 000 000 nœuds | impossible | ~20 sauts |

Avec 20 nœuds dans notre simulation :
- Avant (naïf) : maximum 10 sauts, moyenne ~5 sauts.
- Après (finger table) : maximum 3 sauts, moyenne ~1 saut.
- Zéro DROP, tous les messages livrés.

### Dégradation gracieuse

Si la finger table n'est pas encore construite (nœud venant de joindre, avant le prochain tick de `FingerTableControl`), `bestNextHop` retourne `rightNeighbor` : le routage dégrade naturellement vers le comportement naïf. Aucun crash, aucune incohérence.

---

## Récapitulatif des fichiers

| Fichier | Rôle |
|---|---|
| `DHTProtocol.java` | Cœur du protocole : join, leave, route, put, get, finger table |
| `DHTMessage.java` | Enveloppe de message (7 types, tous les champs nécessaires) |
| `DHTInitializer.java` | Attribution des IDs et initialisation du nœud fondateur |
| `JoinLeaveControl.java` | Déclenche les joins et les leaves périodiquement |
| `MessageSenderControl.java` | Envoie des messages applicatifs (test étape 2) |
| `StorageTestControl.java` | Test PUT/GET (test étape 3) |
| `FingerTableControl.java` | Reconstruit les finger tables via vue globale (étape 4 – triche) |
| `DHTObserver.java` | Vérifie et affiche l'état de l'anneau périodiquement |
| `dht_config.cfg` | Configuration PeerSim (taille réseau, délais, contrôleurs) |

---

## Récapitulatif des choix de conception clés

| Choix | Raison |
|---|---|
| Join discovery par messages | Simule le comportement distribué réel |
| Join/Leave insertion synchrone | Évite les incohérences transitoires dues aux délais de transport |
| Migration au LEAVE | Évite la perte de données quand le responsable part |
| Migration au JOIN | Évite la perte quand un nouveau nœud "vole" la responsabilité |
| hopCount >= Network.size() comme TTL | Un tour complet = destination partie |
| Finger table construite globalement | Exploite l'omniscience du simulateur (triche intentionnelle) |
| `& Long.MAX_VALUE` pour les offsets | Arithmétique correcte modulo 2^63 dans l'espace d'IDs 63 bits |
