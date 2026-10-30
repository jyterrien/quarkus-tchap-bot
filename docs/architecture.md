# Architecture

Voir `discusion-initiale.txt` pour le document de référence.

Modules :
- `matrix-client-api` : contrats publics (aucune dépendance Quarkus)
- `matrix-client-core` : implémentation protocolaire Matrix
- `matrix-transport-http` : transport HTTP (Vert.x)
- `quarkus-tchap-bot/runtime` : intégration CDI, config, lifecycle
- `quarkus-tchap-bot/deployment` : build-time processing Quarkus
