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

## Authentification

Deux modes, `quarkus.tchap.auth.mode` :

| Mode | Fonctionnement |
|---|---|
| `token` (défaut) | Le token d'accès est fourni (`quarkus.tchap.access-token`) et n'est jamais renouvelé. S'il est refusé, le bot passe en `FAILED` (liveness DOWN) au lieu de réessayer indéfiniment. |
| `password` | Le bot se connecte avec `quarkus.tchap.user-id` et `quarkus.tchap.auth.password`, puis garde son token valide seul. |

```properties
quarkus.tchap.homeserver-url=https://matrix.agent.social.tchap.gouv.fr
quarkus.tchap.auth.mode=password
quarkus.tchap.user-id=@bot-user.fr:agent.social.tchap.gouv.fr
# Le mot de passe se fournit par variable d'environnement : QUARKUS_TCHAP_AUTH_PASSWORD
quarkus.tchap.auth.store=file
quarkus.tchap.auth.store-path=.tchap/credentials.json
```

En mode `password` :

- Le login demande un **refresh token**. Le token d'accès est renouvelé **à l'avance** (une minute avant son
  expiration) et **à la demande** quand le serveur le refuse (401 / `M_UNKNOWN_TOKEN`) ; la requête refusée est
  rejouée une fois.
- Le renouvellement utilise `/refresh`. Si le serveur refuse le refresh token, ou n'en a pas émis, le bot se
  reconnecte avec le mot de passe en réutilisant le même appareil (`device_id`).
- Une panne réseau, une erreur 5xx ou un 429 pendant le renouvellement ne déclenchent **pas** de reconnexion :
  l'erreur est transmise et le prochain appel réessaie.
- Des identifiants définitivement refusés (mauvais mot de passe) arrêtent la synchronisation : le client passe en
  `FAILED` et la liveness en DOWN. Le bot ne réessaie pas toutes les 5 secondes (risque de verrouiller le compte).
- Ni le mot de passe ni les tokens n'apparaissent dans les journaux.
- L'identifiant du serveur (`@prenom.nom:srv`) peut différer de l'identifiant de connexion
  (`@prenom.nom.fr:srv`) : le bot ignore ses propres messages d'après l'identifiant renvoyé par le serveur.

| Propriété | Défaut | Description |
|---|---|---|
| `quarkus.tchap.auth.mode` | `token` | `token` ou `password`. |
| `quarkus.tchap.auth.password` | | Mot de passe du bot (mode `password`). Variable d'environnement ou secret, jamais dans un fichier versionné. |
| `quarkus.tchap.auth.device-name` | `quarkus-tchap-bot` | Nom de l'appareil affiché pour la session du bot. |
| `quarkus.tchap.auth.store` | `memory` | Stockage des identifiants : `memory` ou `file`. |
| `quarkus.tchap.auth.store-path` | `.tchap/credentials.json` | Fichier des identifiants quand `store=file`. |

**Stockage des identifiants.** Le refresh token est à usage unique : chaque renouvellement en émet un nouveau, qui
doit être conservé. Avec `store=memory`, le bot se reconnecte avec le mot de passe à chaque démarrage (et crée un
nouvel appareil si le serveur n'en réutilise pas). Avec `store=file`, il reprend sa session au redémarrage. Le
fichier contient des secrets : il est créé lisible par son seul propriétaire (`rw-------`), doit rester hors du
dépôt et, en conteneur, sur un volume persistant. Pour un autre stockage (base de données, etc.), fournir son
propre bean `CredentialStore` : il remplace celui de l'extension, comme pour `SyncTokenStore`. `save` est appelé
depuis un thread Vert.x : y faire les accès bloquants sur un exécuteur dédié à un seul thread, pour garder l'ordre
des écritures.
