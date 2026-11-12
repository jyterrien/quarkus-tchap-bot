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
| **Rate limit** | ⚠️ `RateLimitException` alimentée par `retry_after_ms` puis `Retry-After`, consommée par `SyncEngine.retryDelayMs`. Reste la hiérarchie d'exceptions (voir 5ter). |
| **Backoff typé** | ✅ `SyncEngine` s'arrête sur identifiants refusés (`AuthenticationException`) ; erreurs transitoires (réseau, 5xx, 429) réessayées. Un 403 isolé sur `/sync` hors problème de token reste réessayé. |
| **Erreurs structurées** | ✅ `VertxHttpTransport` lève `MatrixServerException` (statut, `errcode`, `soft_logout`) ; le message « HTTP code: corps » est conservé. |
| **Observabilité** | ⚠️ `SyncHealth` + sondes de santé Quarkus. Pas de hooks (`onSyncError`, `onTokenInvalid`) ni de métriques. |

---

## Constats sur le transport

1. ❌ **Aucun timeout de lecture/écriture applicatif** : `setConnectTimeout(30_000)` ne couvre que la connexion TCP. Un `sendMessage` peut bloquer indéfiniment si le serveur ne répond pas. Il faut `setIdleTimeout` ou un timeout par requête. _(La liveness détecte désormais une boucle de sync bloquée, mais pas un envoi bloqué.)_
2. ⚠️ **`MatrixTransportException` n'est jamais levée** par `VertxHttpTransport`. La hiérarchie reste incohérente : `RateLimitException extends MatrixTransportException extends RuntimeException`, alors que `MatrixServerException extends MatrixException`. Le contrat `HttpTransport` annonce `MatrixException` : **un `catch (MatrixException)` rate toujours les 429.**
3. ❌ **Pas de retry transport** : un 502/503 transitoire remonte tel quel ; `SyncEngine` retente à 5 s, mais `MessageService` échoue immédiatement (correct pour l'idempotence via txnId, mais un retry opt-in manque).
4. ❌ **Aucun `DELETE`** dans `HttpTransport` : nécessaire pour redaction, forget, kick, `delete_devices`, pushers.
5. ❌ **Pas de `PATCH`** ; `PUT` uniquement pour l'envoi. Le `DELETE` est le manque réel.
6. ❌ **Pas d'upload binaire** (`/media/v3/upload`) : `Content-Type: application/json` uniquement. Bloque les médias.
7. ⚠️ **Headers non extensibles** : l'en-tête `Authorization` est maintenant optionnel, mais pas de `Content-Type` autre que JSON ni de header custom (`User-Agent`, `X-Request-ID`). Prérequis E2EE et médias : un `send(method, path, headers, body)` générique.
8. ✅ **`Authorization: Bearer` toujours envoyé** : résolu. Token `null` ou vide → pas d'en-tête (`/login`, `/refresh`, et à l'avenir `/versions`, `/.well-known`).
9. ❌ **`close()` non bloquant sur thread Vert.x** : fermeture « fire-and-forget » sans signal. Acceptable, à documenter.
10. ✅ **Pas de gestion typée du `401`** : résolu. `MatrixServerException.isTokenRejected()` permet à `MatrixHttpApi` de renouveler le token et de rejouer la requête une fois.

---

## Manques classés par priorité

### P0 — Bloquants pour écrire un bot utilisable

| # | Manque | Statut | Détail |
|---|--------|--------|--------|
| 1 | **Login / logout / refresh** | ⚠️ | ✅ `/login` (mot de passe) et `/refresh` ; renouvellement à l'avance et sur 401 ; repli sur nouveau login ; identifiants persistés (`CredentialStore`). ❌ **`/logout` absent** : le bot ne ferme pas sa session à l'arrêt, des sessions s'accumulent sur le compte (voir 29). |
| 2 | **Messages enrichis** : `m.emote`, `m.image`, `m.file`, `m.audio`, `m.video`, `m.location` + upload média | ❌ | `MessageService` ne sait faire que `m.text`/`m.notice`. Le transport ne permet pas l'upload binaire. |
| 3 | **Réponses / threads / relations** : `m.in_reply_to`, `m.thread`, `m.replace`, `m.reaction` | ⚠️ | ✅ `m.thread` en émission (`sendTextInThread`, `sendNoticeInThread`) et réception (`threadRootId`), jusqu'à l'extension Quarkus. ❌ Répondre à un `event_id` précis (`m.in_reply_to`), édition (`m.replace`), réaction (`m.reaction`). |
| 4 | **Formatage** : `format: org.matrix.custom.html`, `formatted_body` | ❌ | Notifications, alertes, markdown/HTML. |
| 5bis | **Erreurs Matrix typées** | ✅ | `MatrixServerException(statut, errcode, soft_logout)` ; `SyncEngine` arrête la boucle sur identifiants refusés ; client en `FAILED` (sondes DOWN). |
| 5ter | **Cohérence de la hiérarchie d'exceptions** | ❌ | `MatrixException` racine ; `MatrixTransportException` et `RateLimitException` à y rattacher (`RateLimitException extends MatrixServerException`). **Attention : voir « Points de vigilance » (429 et `isClientError`).** |
| 5quater | **`HttpTransport.delete(...)`** + `send(method, path, headers, body)` | ❌ | Leave/kick/redact/forget/media. |
| 6 | **Backoff typé** | ✅ | Identifiants refusés → arrêt ; réseau/5xx/429 → retry. |
| 7 | **Typing / presence / read receipts** | ❌ | `sendTyping`, `sendReceipt` : triviaux, rendent un bot « vivant ». |
| 29 | **Logout à l'arrêt** _(nouveau)_ | ❌ | Fermer proprement la session créée par le mode `password` (`POST /logout`), et supprimer le fichier d'identifiants ou le marquer invalide. |

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
| 17bis | **Timeout d'inactivité HTTP** + timeout par requête | ❌ | Voir constat 1. |
| 17ter | **Header Authorization optionnel** | ✅ | Fait. |
| 17quater | **Headers custom + Content-Type paramétrable** | ❌ | Prérequis E2EE, médias, `User-Agent`. |

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
- ✅ Sondes de santé readiness / liveness, `SyncHealth` (#24 partiel)

### Sprint 1 (bot utilisable)

1. **Messages riches** : `m.in_reply_to` (répondre au bon message), `formatted_body` HTML, `m.emote` ; helpers `sendReply`, `sendHtml` ; `MessageService.sendEvent(roomId, type, content)` (#3, #4, #12).
2. **Logout à l'arrêt** (#1, #29).
3. **Refonte des exceptions** : `MatrixTransportException` et `RateLimitException` sous `MatrixException`/`MatrixServerException` (#5ter) — **en préservant la distinction 429 / refus définitif** (voir vigilance).
4. **`HttpTransport.delete()`** + `send(method, path, headers, body)` générique ; `setIdleTimeout` (#5quater, #17bis, #17quater).
5. `MediaService` (upload + `m.image`/`m.file`) — dépend de 4.

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

- **429 et `isClientError()`** : aujourd'hui `RateLimitException` n'est pas une `MatrixServerException`, donc `PasswordTokenProvider` traite un 429 comme erreur transitoire (pas de reconnexion). **Quand `RateLimitException` sera rattachée à `MatrixServerException` (5ter), `isClientError()` renverra vrai pour un 429** et le fournisseur le prendrait pour un refus définitif (« login refused »). À corriger dans la même modification : exclure le statut 429 de `isClientError()`, avec un test.
- **Mode `token` : un token refusé met le client en `FAILED`** (liveness DOWN) au lieu de réessayer indéfiniment. Comportement voulu, mais changement par rapport à avant.
- **Mode `password`, stockage `memory`** : nouvelle connexion à chaque démarrage, donc un appareil de plus sur le compte (si le serveur n'en réutilise pas) tant que le logout (29) n'existe pas. Utiliser `file` en attendant.
- **`initial-limit` par défaut à 20** (extension) : au premier sync d'un démarrage sans token de reprise, les 20 derniers messages de chaque salon sont rejoués et le bot y répond à nouveau. Pour un bot qui répond, préférer `initial-limit=0` et/ou `sync.store=file`.
- **Sonde de liveness et indisponibilité du homeserver** : `quarkus.tchap.health.sync-max-silence` (2 min par défaut) fait passer la liveness à DOWN quand le serveur est injoignable trop longtemps ; un orchestrateur qui redémarre sur liveness DOWN le fera sans régler la panne. À dimensionner selon les coupures tolérées.
- **`DefaultMatrixClient.start()`** : passe `RUNNING` avant la fin du premier sync (sans écraser un `FAILED` survenu entre-temps). Une panne transitoire du premier sync laisse `RUNNING` : la progression réelle se lit dans `syncHealth()` / la liveness.
- **`Session.isValid()`** ne reflète pas la validité réelle (« token non vide »). En mode `password`, la session est invalide jusqu'à `verifySession()`, qui n'est pas appelé par l'extension : l'identifiant du bot pour ignorer ses propres messages vient du fournisseur de token (identifiant serveur, qui peut différer de l'identifiant de connexion).
- **`SyncEventParser`** jette une `RuntimeException` générique sur JSON invalide → perte possible du token de reprise ; à typer.
- **`DefaultMatrixRoom`** n'expose pas l'état du salon (nom, membres) alors que `Room.encrypted` existe : abstraction `RoomState` manquante.
- **Secrets** : le mot de passe se fournit par variable d'environnement ou secret, jamais dans un fichier versionné ; `.env` et `.tchap/` sont dans `.gitignore`. Les tokens et le mot de passe ne sont jamais journalisés (`Credentials.toString()` masque les tokens).

---

## Tickets à ouvrir tout de suite

1. `MessageService` : `sendReply` (`m.in_reply_to`), `sendHtml` (`formatted_body`), `m.emote`.
2. `AuthService.logout()` + appel à l'arrêt de l'extension en mode `password`.
3. Aligner la hiérarchie d'exceptions sur le contrat `HttpTransport` (`RateLimitException`, `MatrixTransportException`) **et** exclure le 429 de `MatrixServerException.isClientError()`.
4. `HttpTransport#delete(String, String)` + `send(method, path, headers, body)`.
5. Headers custom + `Content-Type` paramétrable.
6. `setIdleTimeout` + timeout par requête.
7. Filtres `/sync` par type d'événement.
8. Décision de cadrage E2EE : limite documentée ou chantier.
9. Observer un renouvellement réel du token sur Tchap (6 h ou session révoquée) et consigner le résultat.
