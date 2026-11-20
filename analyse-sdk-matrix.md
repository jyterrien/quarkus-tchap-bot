# SDK Matrix Tchap — Analyse des fonctionnalités manquantes et priorisation

_Mise à jour : 12 novembre 2026. Légende : ✅ fait · ⚠️ partiel · ❌ à faire._

---

## Ce que le SDK couvre aujourd'hui

**SDK (`matrix-*`)**

- Authentification **par token fourni** ou **par identifiant + mot de passe** (`/login`), avec **renouvellement automatique** du token (`/refresh` à l'avance et sur 401, reconnexion en repli) et persistance des identifiants (mémoire, fichier, ou implémentation fournie par l'application)
- Erreurs serveur typées (`MatrixServerException` : statut, `errcode`, `soft_logout`)
- Sync long polling avec token persisté, dédup, filtrage par salons, auto-join ; arrêt de la boucle sur identifiants refusés ; santé de la boucle exposée (`SyncHealth`)
- Envoi `m.text` / `m.notice`, **dans un fil** (`m.thread`) ou hors fil ; réception avec la racine du fil (`MatrixEvent.threadRootId`)
- Join (id ou alias), leave, whoami
- Façade `MatrixClient` / `MatrixRoom`
- Transport HTTP Vert.x : timeouts de connexion, keep-alive, fermeture idempotente, gestion de la propriété Vert.x, 429 (`retry_after_ms` puis `Retry-After`), en-tête `Authorization` optionnel

**Extension Quarkus (`quarkus-tchap-bot`)**

- Événements CDI `TchapMessage` (avec `threadRootId`, `isInThread()`, `replyThreadId()`), observers par salon nommé
- Salons nommés injectables (`@TchapRoomName`), envoi dans un fil (`TchapRoom`, `TchapClient`)
- Deux modes d'authentification (`token` / `password`), stockage des identifiants `memory` / `file`, points d'extension `SyncTokenStore` et `CredentialStore` (`@DefaultBean`)
- Sondes de santé séparées : readiness (`tchap-bot`) et liveness (`tchap-bot-sync`, d'après la progression de `/sync`)

C'est un socle correct pour un bot **simple** (texte, un seul homeserver, salons non chiffrés), avec une authentification qui tient dans la durée. Les manques restants sont surtout : réponses ciblées et formatage, médias, filtres de sync, chiffrement.

**Observé sur le homeserver Tchap** : `/login` avec `refresh_token: true` renvoie un refresh token et un `expires_in_ms` de 6 h (jetons `mct_`/`mcr_`). Le login et la création du fichier d'identifiants sont validés en réel ; **un renouvellement réel (6 h ou session révoquée) reste à observer**.

---

## Ce que le transport apporte (et corrige)

| Point | Statut |
|---|---|
| **Transport absent** | ✅ `VertxHttpTransport` : timeouts (connect 30 s), keep-alive, fermeture idempotente, propriété Vert.x. |
| **Rate limit** | ✅ `RateLimitException` (sous-classe de `MatrixServerException`) alimentée par `retry_after_ms` puis `Retry-After`, consommée par `SyncEngine.retryDelayMs` ; le 429 n'est pas un refus définitif (`isClientError()` l'exclut). |
| **Backoff typé** | ✅ `SyncEngine` s'arrête sur identifiants refusés (`AuthenticationException`) ; erreurs transitoires (réseau, 5xx, 429) réessayées. Un 403 isolé sur `/sync` hors problème de token reste réessayé. |
| **Erreurs structurées** | ✅ `VertxHttpTransport` lève `MatrixServerException` (statut, `errcode`, `soft_logout`) ; le message « HTTP code: corps » est conservé. |
| **Observabilité** | ⚠️ `SyncHealth` + sondes de santé Quarkus. Pas de hooks (`onSyncError`, `onTokenInvalid`) ni de métriques. |

---

## Constats sur le transport

1. ✅ **Timeout de requête** : `HttpTransportOptions` (connexion 30 s, requête 60 s par défaut), `quarkus.tchap.http.connect-timeout` / `request-timeout`, et un délai propre à une requête (`TransportRequest.withTimeout`). Une requête sans réponse échoue en `MatrixTransportException` au lieu de bloquer. Le délai de requête est relevé automatiquement à `sync.timeout` + 30 s (le long polling garde la requête ouverte). **Aucun délai d'inactivité de connexion n'est posé exprès** : il couperait le long polling.
2. ✅ **Hiérarchie d'exceptions** : `MatrixException` est la racine de tout (`AuthenticationException`, `MatrixTransportException`, `MatrixServerException` et sa sous-classe `RateLimitException`). `VertxHttpTransport` lève `MatrixTransportException` pour les erreurs réseau et transport fermé ; un seul `catch (MatrixException)` attrape tout, le 429 compris.
3. ❌ **Pas de retry transport** : un 502/503 transitoire remonte tel quel ; `SyncEngine` retente à 5 s, mais `MessageService` échoue immédiatement (correct pour l'idempotence via txnId, mais un retry opt-in manque).
4. ✅ **`DELETE`** : `HttpTransport.delete(path, token)` (alias de `send`). Sert aux alias de salon, étiquettes, règles de notification, sauvegarde de clés. _(Attention : `/redact`, `/leave`, `/forget`, `/kick` ne sont pas des `DELETE` : `/redact` est un `PUT`, les autres des `POST`, déjà possibles.)_
5. ✅ **`PATCH`** pris en charge par `send` (peu utilisé par l'API client Matrix).
6. ✅ **Upload et réponses binaires** : `TransportRequest.withBody` envoie un corps binaire avec le `Content-Type` voulu, et `HttpTransport.execute` rend la réponse complète (`TransportResponse` : statut, en-têtes, octets), ce qui permet de télécharger un média. `send` en découle (corps lu comme texte).
7. ✅ **Headers extensibles** : `send(TransportRequest)` accepte des en-têtes supplémentaires (`User-Agent`, `X-Request-ID`...) et un `Content-Type` quelconque. `Authorization` et `Content-Type` ne se fixent que par `accessToken` et `contentType` (refusés dans les en-têtes).
8. ✅ **`Authorization: Bearer` toujours envoyé** : résolu. Token `null` ou vide → pas d'en-tête (`/login`, `/refresh`, et à l'avenir `/versions`, `/.well-known`).
9. ❌ **`close()` non bloquant sur thread Vert.x** : fermeture « fire-and-forget » sans signal. Acceptable, à documenter.
10. ✅ **Pas de gestion typée du `401`** : résolu. `MatrixServerException.isTokenRejected()` permet à `MatrixHttpApi` de renouveler le token et de rejouer la requête une fois.

---

## Manques classés par priorité

### P0 — Bloquants pour écrire un bot utilisable

| # | Manque | Statut | Détail |
|---|--------|--------|--------|
| 1 | **Login / logout / refresh** | ✅ | `/login` (mot de passe), `/refresh` et `/logout` ; renouvellement à l'avance et sur 401 ; repli sur nouveau login ; identifiants persistés et oubliés au logout (`CredentialStore.clear`). La session est fermée à l'arrêt selon `quarkus.tchap.auth.logout-on-shutdown` (voir 29). |
| 2 | **Messages enrichis** : `m.emote`, `m.image`, `m.file`, `m.audio`, `m.video`, `m.location` + upload média | ✅ | `m.emote`, images, fichiers, audio et vidéo : téléversement (`MediaService.upload`), envoi (`OutgoingMessage.image/file/audio/video`, `room.sendMedia`), réception (`TchapMessage.media()`) et téléchargement (`download`). Reste : `m.location`, miniatures, et les médias chiffrés (dépend de l'E2EE, point 8). |
| 3 | **Réponses / threads / relations** : `m.in_reply_to`, `m.thread`, `m.replace`, `m.reaction` | ✅ | `m.thread` et `m.in_reply_to` (`OutgoingMessage.inReplyTo` / `inThread`, `TchapMessage.reply`), éditions (`edit`) et réactions (`react`), en émission et en réception, jusqu'à l'extension Quarkus (`TchapMessageEdited`, `TchapReaction`). Une édition reçue n'est plus publiée comme un nouveau message. Reste : **retirer une réaction** et supprimer un message (`PUT /redact`, faisable avec le transport actuel). |
| 4 | **Formatage** : `format: org.matrix.custom.html`, `formatted_body` | ✅ | `OutgoingMessage.withHtml` + `escapeHtml`. Pas de conversion markdown → HTML ni de filtrage des balises : à la charge de l'appelant. |
| 5bis | **Erreurs Matrix typées** | ✅ | `MatrixServerException(statut, errcode, soft_logout)` ; `SyncEngine` arrête la boucle sur identifiants refusés ; client en `FAILED` (sondes DOWN). |
| 5ter | **Cohérence de la hiérarchie d'exceptions** | ✅ | `MatrixException` racine de tout ; `MatrixTransportException` (réseau) et `RateLimitException extends MatrixServerException` (429) rattachées ; `isClientError()` exclut le 429, `isRateLimited()` et `isServerError()` ajoutés. Les deux classes passent dans `matrix-client-api`, même paquet. |
| 5quater | **`HttpTransport.delete(...)`** + `send(...)` générique | ✅ | `TransportRequest` (méthode, en-têtes, corps binaire, délai), `delete()` par défaut sur `send`. Les transports existants restent valides (`send` non pris en charge par défaut). |
| 6 | **Backoff typé** | ✅ | Identifiants refusés → arrêt ; réseau/5xx/429 → retry. |
| 7 | **Typing / presence / read receipts** | ❌ | `sendTyping`, `sendReceipt` : triviaux, rendent un bot « vivant ». |
| 29 | **Logout à l'arrêt** _(nouveau)_ | ✅ | `MatrixClient.logout()` et `TchapLifecycle` : `POST /logout` à l'arrêt en mode `password`, par défaut seulement quand les identifiants ne sont pas persistés (`store=memory`). Opt-in sinon, car supprimer l'appareil est incompatible avec une session à reprendre (Kubernetes, chiffrement de bout en bout). Identifiants oubliés ensuite. Les sessions déjà accumulées se suppriment par la gestion des devices (#20). |

### P1 — Nécessaires pour un bot robuste en production

| # | Manque | Statut | Détail |
|---|--------|--------|--------|
| 8 | **Chiffrement (E2EE)** | ❌ | `EncryptionCapability` existe mais rien ne l'utilise. Tchap est **chiffré par défaut** : sans olm/megolm, un bot ne lit pas les salons chiffrés. Plus gros chantier (crypto, sauvegarde de clés, vérification d'appareil, `/keys/*`, to-device). **À cadrer avant de coder** : limite acceptable (à documenter) ou chantier à part entière. |
| 9 | **Filtres `/sync` plus fins** : `event_format=client`, `not_types`, `lazy_load_members`, `include_leave` | ❌ | Seuls `rooms` + `timeline.limit` sont construits. Un bot reçoit tout. |
| 10 | **Événements d'état** (`m.room.member`, `name`, `topic`, `create`) | ❌ | `SyncEventParser` ne lit que `timeline.events` ; pas de `state.events`, pas de `rooms.leave`. |
| 11 | **Invitations : accepter/refuser explicitement** | ❌ | `autoJoinInvites` fait un join aveugle ; pas d'accès à `invite_state`. |
| 12 | **Envoi d'événements arbitraires** (`/state/{type}/{key}`, `/send/{type}/{txn}`) | ❌ | `MatrixHttpApi` est fermé à `m.room.message`. Dépend de 5quater pour le state. |
| 13 | **Historique** (`/messages`, `/context/{eventId}`) | ❌ | Utile après redémarrage ou pour contextualiser une réponse ; aussi pour **lire l'historique d'un fil**. |
| 14 | **Création de salons**, alias, `/invite` | ❌ | Bot modérateur / onboarding. |
| 15 | **Rate limiting côté client** | ❌ | Rien n'empêche d'envoyer 100 messages/seconde (token bucket). |
| 16 | **Transaction ID fourni par l'appelant** | ❌ | `TransactionIdGenerator` est interne ; un bot qui rejoue après crash ne peut pas réutiliser le txnId. |
| 17bis | **Timeout d'inactivité HTTP** + timeout par requête | ✅ | Timeout par requête et de connexion, configurables. Pas de timeout d'inactivité de connexion, volontairement (long polling). Voir constat 1. |
| 17ter | **Header Authorization optionnel** | ✅ | Fait. |
| 17quater | **Headers custom + Content-Type paramétrable** | ✅ | Via `TransportRequest`. |

### P2 — Nécessaire pour devenir un vrai client (au-delà du bot)

| # | Manque | Statut |
|---|--------|--------|
| 17 | **Multi-comptes / multi-homeserver**, découverte `.well-known/matrix/client`. `DefaultMatrixClient` est figé sur un serveur et un fournisseur de token. | ❌ |
| 18 | **Recherche** (`/search`, `/publicRooms`) | ❌ |
| 19 | **Répertoire / profils** (`/profile`, `/user_directory/search`, avatar, displayname) | ❌ |
| 20 | **Gestion des devices** (`/devices`, `/delete_devices`), vérification croisée. _Lié à 29 : le nettoyage des sessions accumulées passe par là._ | ❌ |
| 21 | **Notifications push** (`/pushers`, règles) | ❌ |
| 22 | **Événements éphémères** (typing, receipts), `account_data` (`m.direct`, tags) absents du parser | ❌ |
| 23 | **Pagination / scrollback unifié** | ❌ |
| 24 | **Observabilité** : `SyncHealth` et sondes de santé ✅ ; hooks de cycle de vie (`onSyncError`, `onTokenInvalid`), métriques, listener multiple ❌ | ⚠️ |
| 25 | ~~Transport~~ — fermé, sauf les points 1/2/3/7/8/10 qui deviennent des tickets ciblés | ✅ |
| 26 | **Threads complets** : envoi et réception ✅ ; lecture de l'historique d'un fil dépend de 13 | ⚠️ |
| 27 | **Widgets / VoIP / calls** — hors périmètre, à exclure explicitement | — |
| 28 | **Tests d'intégration** : faux homeserver WireMock côté extension Quarkus ✅ ; **aucun test contre un vrai Synapse/Tchap** ; `HttpTransportMocks` n'est pas partagé entre modules | ⚠️ |
| 30 | **Secrets au repos** _(nouveau)_ : le fichier d'identifiants est en `rw-------` mais en clair ; intégration à un gestionnaire de secrets (Vault, Kubernetes) via un `CredentialStore` dédié | ❌ |

---

## Roadmap resserrée

### Terminé depuis la version précédente

- ✅ Login par mot de passe, refresh du token, reconnexion de repli, persistance des identifiants (#1 partiel)
- ✅ Erreurs serveur typées, arrêt de la sync sur identifiants refusés, état `FAILED` (#5bis, #6)
- ✅ En-tête `Authorization` optionnel (#17ter)
- ✅ Threads en émission et en réception, côté SDK et côté extension (#3 partiel, #26 partiel)
- ✅ Messages enrichis : réponse à un message précis (`m.in_reply_to`), HTML (`formatted_body`), `m.emote`, réponse dans un thread ; `OutgoingMessage` et `send(...)` (#3, #4, #2 partiel)
- ✅ Éditions (`m.replace`) et réactions (`m.reaction`) en émission et en réception ; une édition reçue n'est plus republiée comme un nouveau message (#3)
- ✅ Médias : téléversement, téléchargement (accès authentifié puis ancien accès), messages image/fichier/audio/vidéo, `room.sendMedia`, média des messages reçus (`TchapMessage.media()`), `MxcUri` validé, nom de fichier réduit à un nom simple (#2, #5)
- ✅ Transport : `delete()`, `send(TransportRequest)` générique (en-têtes, corps binaire, délai propre), timeouts de connexion et de requête configurables (#5quater, #17bis, #17quater)
- ✅ Hiérarchie d'exceptions unifiée sous `MatrixException` ; `RateLimitException` sous `MatrixServerException`, 429 exclu de `isClientError()` (#5ter)
- ✅ Logout à l'arrêt (`POST /logout`), avec `logout-on-shutdown` et oubli des identifiants ; arrêt propre de la sync et des magasins (#1, #29)
- ✅ Sondes de santé readiness / liveness, `SyncHealth` (#24 partiel)

### Sprint 1 (bot utilisable)

1. ~~**Messages riches** : `m.in_reply_to`, HTML, `m.emote`, éditions, réactions~~ — ✅ fait via `OutgoingMessage`, `send`, `edit`, `react` (#3, #4). Reste : `MessageService.sendEvent(roomId, type, content)` pour les événements arbitraires (#12), retrait d'une réaction et suppression de message (`/redact`).
2. ~~**Logout à l'arrêt** (#1, #29)~~ — ✅ fait.
3. ~~**Refonte des exceptions**~~ — ✅ fait (#5ter), avec la distinction 429 / refus définitif préservée.
4. ~~**`HttpTransport.delete()`** + `send(...)` générique, timeouts~~ — ✅ fait (#5quater, #17bis, #17quater).
5. ~~`MediaService` (upload, téléchargement, `m.image`/`m.file`/`m.audio`/`m.video`)~~ — ✅ fait. Reste : streaming et limite de taille des gros fichiers, `m.location`, miniatures, médias chiffrés.

### Sprint 2 (bot robuste)

6. **Filtres `/sync` par type** (#9) et parser `/sync` complet : `state`, `leave`, `invite_state`, `ephemeral`, `account_data` (#10, #11, #22).
7. Typing et accusés de lecture (#7).
8. State de salon, `createRoom`, `invite` (#12, #14).
9. Historique `/messages`, `/context` (#13), dont lecture d'un fil (#26).
10. Rate limiter client + txnId injectable (#15, #16).

### Sprint 3 (client Matrix)

11. **E2EE** (module séparé) — dépend des headers custom et du transport binaire (#8). Décision de cadrage à prendre avant.
12. Multi-sessions / `.well-known` (#17).
13. Profils, devices, répertoire, recherche, publicRooms, pushers (#18–#21).
14. Observabilité : hooks `onSyncError`, `onTokenInvalid`, métriques (#24). Secrets au repos (#30).

### Sprint 4

15. Tests d'intégration contre Synapse/Tchap + `MockHttpTransport` partagé (#28).

---

## Points de vigilance transverses

- **Mode `token` : un token refusé met le client en `FAILED`** (liveness DOWN) au lieu de réessayer indéfiniment. Comportement voulu, mais changement par rapport à avant.
- **Mode `password`, stockage `memory`** : nouvelle connexion à chaque démarrage, donc un appareil de plus sur le compte (si le serveur n'en réutilise pas) sauf si `logout-on-shutdown` (activé par défaut avec `store=memory`) ferme la session à l'arrêt. Un arrêt brutal (kill, crash) ne se déconnecte pas : la session reste jusqu'à son expiration ; utiliser `file` pour la reprendre.
- **`initial-limit` par défaut à 20** (extension) : au premier sync d'un démarrage sans token de reprise, les 20 derniers messages de chaque salon sont rejoués et le bot y répond à nouveau. Pour un bot qui répond, préférer `initial-limit=0` et/ou `sync.store=file`.
- **Sonde de liveness et indisponibilité du homeserver** : `quarkus.tchap.health.sync-max-silence` (2 min par défaut) fait passer la liveness à DOWN quand le serveur est injoignable trop longtemps ; un orchestrateur qui redémarre sur liveness DOWN le fera sans régler la panne. À dimensionner selon les coupures tolérées.
- **`DefaultMatrixClient.start()`** : passe `RUNNING` avant la fin du premier sync (sans écraser un `FAILED` survenu entre-temps). Une panne transitoire du premier sync laisse `RUNNING` : la progression réelle se lit dans `syncHealth()` / la liveness.
- **`Session.isValid()`** ne reflète pas la validité réelle (« token non vide »). En mode `password`, la session est invalide jusqu'à `verifySession()`, qui n'est pas appelé par l'extension : l'identifiant du bot pour ignorer ses propres messages vient du fournisseur de token (identifiant serveur, qui peut différer de l'identifiant de connexion).
- **`SyncEventParser`** jette une `RuntimeException` générique sur JSON invalide → perte possible du token de reprise ; à typer.
- **`DefaultMatrixRoom`** n'expose pas l'état du salon (nom, membres) alors que `Room.encrypted` existe : abstraction `RoomState` manquante.
- **Secrets** : le mot de passe se fournit par variable d'environnement ou secret, jamais dans un fichier versionné ; `.env` et `.tchap/` sont dans `.gitignore`. Les tokens et le mot de passe ne sont jamais journalisés (`Credentials.toString()` masque les tokens).

---

## Tickets à ouvrir tout de suite

1. ~~`MessageService` : réponses, HTML, `m.emote`, éditions, réactions~~ — fait. Reste : `/redact` (supprimer un message, retirer une réaction).
2. ~~`logout()` à l'arrêt de l'extension en mode `password`~~ — fait.
3. ~~Aligner la hiérarchie d'exceptions sur le contrat `HttpTransport` et exclure le 429 de `isClientError()`~~ — fait.
4. ~~`HttpTransport#delete` + `send(...)` générique~~ — fait.
5. ~~Headers custom + `Content-Type` paramétrable~~ — fait.
6. ~~Timeout par requête~~ — fait.
7. Filtres `/sync` par type d'événement.
8. Décision de cadrage E2EE : limite documentée ou chantier.
9. Observer un renouvellement réel du token sur Tchap (6 h ou session révoquée) et consigner le résultat.
