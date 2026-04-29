# DHT Anneau — PeerSim

Simulation d'une table de hachage distribuée (DHT) en anneau de type Chord, implémentée sur PeerSim 1.0.5 en mode événementiel.

## Fonctionnalités

- **Étape 1 — Join/Leave** : entrée et sortie dynamique des nœuds, maintien de l'anneau ordonné
- **Étape 2 — Routage** : acheminement hop-by-hop des messages vers le nœud cible
- **Étape 3 — Stockage** : PUT/GET distribués avec réplication sur 3 nœuds et migration des données
- **Étape 4 — Finger table** : routage O(log n) via table de sauts reconstruite périodiquement

## Prérequis

- JDK 17 ou supérieur

## Compilation et exécution

**Windows**
```powershell
# Compiler
javac -classpath "src;jep-2.3.0.jar;djep-1.0.0.jar" src/dht/*.java

# Lancer
java -classpath "src;jep-2.3.0.jar;djep-1.0.0.jar" peersim.Simulator dht_config.cfg
```

**Linux / Mac**
```bash
# Compiler
javac -classpath "src:jep-2.3.0.jar:djep-1.0.0.jar" src/dht/*.java

# Lancer
java -classpath "src:jep-2.3.0.jar:djep-1.0.0.jar" peersim.Simulator dht_config.cfg

# Ou via Makefile
make
```

## Structure

```
src/dht/
├── DHTProtocol.java        # Protocole principal (join, route, put/get, finger table)
├── DHTMessage.java         # Types de messages
├── DHTInitializer.java     # Initialisation de l'anneau
├── JoinLeaveControl.java   # Contrôleur d'arrivées/départs
├── MessageSenderControl.java  # Envoi de messages de test
├── StorageTestControl.java    # Tests PUT/GET
├── FingerTableControl.java    # Reconstruction des finger tables
└── DHTObserver.java        # Vérification de l'intégrité de l'anneau
dht_config.cfg              # Paramètres de simulation
```

## Configuration clé (`dht_config.cfg`)

| Paramètre | Valeur | Description |
|-----------|--------|-------------|
| `network.size` | 20 | Nombre de nœuds |
| `simulation.endtime` | 60000 | Durée de la simulation |
| `control.joinleave.step` | 2000 | Fréquence des join/leave |
| `control.fingers.step` | 1000 | Fréquence de reconstruction des finger tables |
