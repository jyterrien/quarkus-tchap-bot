# Configuration

Préfixe : `quarkus.tchap`

| Propriété | Description |
|---|---|
|

## Sondes de santé

| Sonde | Chemin | Nom | Rôle |
|---|---|---|---|
| Readiness | `/q/health/ready` | `tchap-bot` | Le client est démarré (salons nommés listés pour information). |
| Liveness | `/q/health/live` | `tchap-bot-sync` | La boucle `/sync` progresse. |

`/q/health` agrège les deux.

| Propriété | Défaut | Description |
|---|---|---|
| `quarkus.tchap.health.sync-max-silence` | `2M` | Durée sans `/sync` réussi avant que la liveness passe à DOWN. Doit être nettement supérieure à `quarkus.tchap.sync.timeout`. |

La liveness passe à DOWN aussi quand le homeserver est injoignable depuis plus que `sync-max-silence` :
un orchestrateur qui redémarre le conteneur sur liveness DOWN le fera alors. Augmenter la valeur pour
tolérer de plus longues indisponibilités du homeserver. Synchronisation désactivée : la liveness reste UP.
