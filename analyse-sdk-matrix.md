# SDK Matrix Tchap — Analyse des fonctionnalités manquantes et priorisation

---

## Ce que le SDK couvre aujourd'hui

- Authentification **par token déjà obtenu** (pas de `/login`)
- Sync long polling avec token persisté, dédup, filtrage par salons, auto-join
- Envoi `m.text` / `m.notice` par salon
- Join (id ou alias), leave, whoami
- Façade `MatrixClient` / `MatrixRoom`
- Transport HTTP Vert.x avec timeouts de connexion, keep-alive, fermeture idempotente, gestion de la propriété Vert.x, gestion du 429 (`retry_after_ms` puis `Retry-After`)

C'est un socle correct pour un bot **très simple** (login fait ailleurs, texte uniquement, un seul homeserver, pas de chiffrement).

---

## Ce que le transport apporte (et corrige)

| Point précédent | Statut |
|---|---|
| **#25 Transport absent** | ✅ Résolu : `VertxHttpTransport` avec timeouts (connect 30 s), keep-alive, fermeture idempotente, gestion de la propriété Vert.x. |
| **#5 Rate limit** | ✅ Partiellement : `RateLimitException` alimentée par `retry_after_ms` (spec) puis `Retry-After`, et `SyncEngine.retryDelayMs` la consomme déjà. |
| **#6 Backoff** | ⚠️ Toujours incomplet côté `SyncEngine` : il ne distingue toujours **pas** 401/403 (`M_UNKNOWN_TOKEN`, `M_FORBIDDEN`) d'une erreur transitoire — le transport ne fait que propager `MatrixException("HTTP 4xx: ...")`. |
| **#5 Erreurs structurées** | ⚠️ Toujours ouvert : `VertxHttpTransport.handle()` aplatit le corps d'erreur dans le message. Le `errcode` Matrix (`M_FORBIDDEN`, `M_UNKNOWN_TOKEN`, `M_NOT_FOUND`, `M_LIMIT_EXCEEDED`…) n'est **pas** parsé ni exposé. |
| **#23 Observabilité** | ⚠️ Toujours ouvert (pas de hook `onTransportError`, pas de métriques). |

---

## Nouveaux constats issus du transport

1. **Aucun timeout de lecture/écriture applicatif** : `setConnectTimeout(30_000)` ne couvre que la connexion TCP. Le `syncTimeoutMs` (30 s par défaut) protège le long polling côté Matrix, mais un `sendMessage` peut bloquer indéfiniment si le serveur ne répond pas. Il faut `setIdleTimeout`/`setReadTimeout` ou un timeout par requête.
2. **`MatrixTransportException` n'est jamais levée** par `VertxHttpTransport` : toutes les erreurs (réseau, 4xx, 5xx) sortent en `MatrixException` brute. La hiérarchie `MatrixTransportException extends RuntimeException` (non-Matrix) vs `MatrixException extends RuntimeException` (Matrix) est incohérente : le contrat `HttpTransport` annonce `MatrixException`/`RateLimitException`, mais `RateLimitException extends MatrixTransportException`, pas `MatrixException`. **Un `catch (MatrixException)` rate les 429.**
3. **Pas de retry transport** : un 502/503 transitoire remonte tel quel ; `SyncEngine` retente à 5 s, mais `MessageService` échoue immédiatement (pas de retry côté envoi, ce qui est correct pour l'idempotence via txnId — mais il faudrait un retry opt-in).
4. **Aucun `DELETE`** dans `HttpTransport` : nécessaire pour `leave` par événement, `delete_devices`, suppression de pushers, oublier un salon, redaction.
5. **Pas de `PATCH`** : `PUT` uniquement pour l'envoi. Certaines routes Matrix utilisent `PUT` (send, state) et `DELETE` (redact, forget, kick…), `POST` (join, login, createRoom), `GET`. Le `DELETE` manque vraiment.
6. **Pas d'upload binaire** (`/media/v3/upload`) : `sendBuffer` avec `Content-Type: application/json` uniquement. Bloque le P0 #2 (médias).
7. **Headers non extensibles** : pas de moyen de passer `Content-Type` autre que JSON, ni de header custom (ex. `User-Agent`, `X-Request-ID`, `Authorization` alternative). Pour E2EE et media, il faudra un `send(method, path, headers, body)` générique.
8. **`Authorization: Bearer ` toujours envoyé**, même quand `accessToken` est `null`/vide (cas `/login`, `/_matrix/client/versions`, `/.well-known`). Il faut rendre le header optionnel.
9. **`close()` non bloquant sur thread Vert.x** : correct, mais le `CompletionStage` retourné par `close()` (via `AutoCloseable`) n'existe pas → l'appelant ne peut pas attendre la fermeture asynchrone. `AutoCloseable.close()` synchrone + `Context.isOnVertxThread()` = fermeture « fire-and-forget » sur event loop, sans signal. Acceptable, mais à documenter explicitement.
10. **Pas de gestion de `401` avec refresh** : le transport ne tente pas de rafraîchir le token (c'est le rôle du client, ok), mais il ne lève pas non plus une exception *typée* qui permettrait au client de le faire. À coupler avec #5.

---

## Manques classés par priorité

### P0 — Bloquants pour écrire un bot utilisable

| # | Manque | Pourquoi c'est bloquant |
|---|--------|-------------------------|
| 1 | **Login / logout / refresh** (`/login`, `/logout`, `/refresh`) | Le SDK exige un `accessToken` fourni à la main. Un bot doit pouvoir s'authentifier (mot de passe ou token de device), gérer l'expiration, et se déconnecter proprement. `AuthenticationException` existe mais n'est jamais levée. |
| 2 | **Envoi de messages enrichis** : `m.emote`, `m.image`, `m.file`, `m.audio`, `m.video`, `m.location` + upload média (`/media/v3/upload`, `mxc://`) | Un bot Tchap répond souvent avec des pièces jointes ou du formatage. Aujourd'hui `MessageService` ne sait faire que `m.text`/`m.notice`. **Aggravé** : le transport actuel ne permet pas l'upload binaire. |
| 3 | **Réponses / threads / relations** : `m.relates_to` (`m.in_reply_to`, `m.thread`), `m.replace` (édition), `m.reaction` | Indispensable pour qu'un bot réponde *au bon message*, dans un fil, ou réagisse. Aucune API ne permet de répondre à un `event_id`. |
| 4 | **Formatage** : `format: org.matrix.custom.html`, `formatted_body` | Les bots doivent souvent renvoyer du markdown/HTML (notifications, alertes). |
| **5bis** | **Erreurs Matrix typées** : parser `{errcode, error, retry_after_ms}` dans `VertxHttpTransport.handle()` et exposer `MatrixServerException(errcode, …)` ; faire hériter `RateLimitException` de `MatrixServerException` (et non `MatrixTransportException`) ; `SyncEngine` arrête le sync sur `M_UNKNOWN_TOKEN`/`M_FORBIDDEN` au lieu de retenter | **Nouveau bloquant** : sans ça, un bot en boucle infinie sur token expiré. |
| **5ter** | **Cohérence hiérarchie d'exceptions** : `MatrixTransportException` non-Matrix vs `MatrixException` Matrix — clarifier (`MatrixException` racine, `MatrixTransportException extends MatrixException`, `MatrixServerException extends MatrixException`, `RateLimitException extends MatrixServerException`) | **Nouveau** : contrat `HttpTransport` dit `MatrixException`, réalité dit `RateLimitException extends RuntimeException` → piège. |
| **5quater** | **`HttpTransport.delete(...)`** + variante `send(method, path, headers, body)` | **Nouveau** : nécessaire pour leave/kick/redact/forget/media. |
| 6 | **Backoff typé** (401/403 → stop, 5xx → retry) | Dépend de #5bis. |
| 7 | **Typing / presence / read receipts** | `sendTyping`, `sendReceipt` sont triviaux et rendent un bot « vivant ». |

### P1 — Nécessaires pour un bot robuste en production

| # | Manque | Détail |
|---|--------|--------|
| 8 | **Chiffrement (E2EE)** | `EncryptionCapability` existe mais rien ne l'utilise ; `Room.encrypted` est un record isolé. Tchap est **chiffré par défaut** : sans olm/megolm, un bot ne peut pas lire les messages des salons chiffrés. C'est le plus gros chantier (crypto, key backup, device verification, `/keys/*`, `/sync` to-device). Dépend de #5ter (transport binaire pour to-device). |
| 9 | **Filtres `/sync` plus fins** : `event_format=client`, `not_types`, `lazy_load_members`, `include_leave` | Aujourd'hui seul `rooms` + `timeline.limit` est construit. Pas de filtre par type d'événement → un bot reçoit tout. |
| 10 | **Événements d'état** (`m.room.member`, `m.room.name`, `m.room.topic`, `m.room.create`) | `SyncEventParser` ne lit que `timeline.events` ; pas de `state.events`, pas de `rooms.leave`. Impossible de savoir qui est dans un salon, son nom, etc. |
| 11 | **Invitations : accepter/refuser explicitement** | `autoJoinInvites` fait un join aveugle. Pas de `leave` sur invitation, pas d'accès au contenu de l'invitation (`rooms.invite[].invite_state`). |
| 12 | **Envoi d'événements arbitraires** (`/rooms/{id}/state/{type}/{key}`, `/rooms/{id}/send/{type}/{txn}`) | Un bot doit pouvoir poser un state (topic, sujet), envoyer des events custom. `MatrixHttpApi` est fermé à `m.room.message`. **Dépend de #5quater** (DELETE pour le state). |
| 13 | **Récupération d'historique** (`/rooms/{id}/messages`, `/rooms/{id}/context/{eventId}`) | Un bot qui redémarre ou veut contextualiser une réponse en a besoin. |
| 14 | **Création de salons** (`/createRoom`), alias, invitations (`/invite`) | Un bot modérateur/onboarding doit créer des salons et inviter. |
| 15 | **Rate limiting côté client** | `RateLimitException` est traitée en retry, mais rien n'empêche d'envoyer 100 messages/seconde. Pas de token bucket. |
| 16 | **Transaction ID fourni par l'appelant** | `TransactionIdGenerator` est interne ; un bot qui rejoue après crash ne peut pas réutiliser le txnId → doublons côté serveur. |
| **17bis** | **Timeout d'inactivité HTTP** (`setIdleTimeout`) + timeout par requête | **Nouveau** : `connectTimeout` seul ne suffit pas. |
| **17ter** | **Header Authorization optionnel** pour `/login`, `/versions`, `/.well-known` | **Nouveau** : sinon bootstrap impossible proprement. |
| **17quater** | **Headers custom + Content-Type paramétrable** | **Nouveau** : prérequis E2EE, media, `User-Agent`. |

### P2 — Nécessaire pour devenir un vrai client (au-delà du bot)

| # | Manque |
|---|--------|
| 17 | **Multi-comptes / multi-homeserver** : `DefaultMatrixClient` est figé sur un token + un serveur. Un client doit gérer plusieurs sessions, découvrir le homeserver (`.well-known/matrix/client`). Dépend de #17ter. |
| 18 | **Recherche** (`/search`, `/publicRooms`) |
| 19 | **Répertoire / profils** (`/profile/{userId}`, `/user_directory/search`, avatar, displayname) |
| 20 | **Gestion des devices** (`/devices`, `/delete_devices`) et vérification croisée |
| 21 | **Notifications push** (`/pushers`, règles) |
| 22 | **Événements éphémères** : `ephemeral` (typing, receipts), `account_data` (tags, favoris, `m.direct`) — absents du parser |
| 23 | **Pagination / scrollback unifié** (pas d'abstraction timeline côté client) |
| 24 | **Observabilité** : métriques, hooks de cycle de vie (`onSyncError`, `onTokenInvalid`), pas de listener au-delà du `Consumer<MatrixEvent>` unique |
| 25 | ~~**Transport**~~ — ✅ **fermé**, sauf points 1/2/3/7/8/10 ci-dessus qui deviennent des tickets ciblés |
| 26 | **Threads / fils** complets (`m.thread`, `rel_type`) |
| 27 | **Widgets / VoIP / calls** — hors périmètre probable, à exclure explicitement |
| 28 | **Tests d'intégration contre Synapse/Tchap** absents ; `MockHttpTransport` réutilisable serait utile |

---

## Roadmap resserrée

### Sprint 1 (débloque le bot)

1. `AuthService` (login/logout/refresh) — dépend de #17ter (header optionnel).
2. **Refonte des exceptions** : `MatrixException` racine, `MatrixTransportException extends MatrixException`, `MatrixServerException(errcode, message) extends MatrixException`, `RateLimitException extends MatrixServerException`. Parser `errcode`/`error` dans `VertxHttpTransport.handle()`. Adapter `SyncEngine` (arrêt sur `M_UNKNOWN_TOKEN`/`M_FORBIDDEN`).
3. **`HttpTransport.delete()`** + `send(method, path, headers, body)` générique ; `VertxHttpTransport` en profite pour ajouter `setIdleTimeout`.
4. `MessageService.sendEvent(roomId, type, content)` + helpers `sendReply`, `sendReaction`, `sendHtml`.
5. `MediaService` (upload + `m.image`/`m.file`) — dépend de #3.

### Sprint 2 (bot robuste)

6. Parser `/sync` complet (`state`, `leave`, `invite_state`, `ephemeral`, `account_data`).
7. Filtres `/sync` par type.
8. State room + `createRoom` + `invite`.
9. Historique (`/messages`, `/context`).
10. Rate limiter client (token bucket) + txnId injectable.

### Sprint 3 (client Matrix)

11. **E2EE** (module séparé) — dépend de headers custom + transport binaire.
12. Multi-sessions / `.well-known`.
13. Profils, devices, répertoire, recherche, publicRooms, pushers.
14. Observabilité (hooks `onSyncError`, `onTokenInvalid`, métriques).

### Sprint 4

15. Tests d'intégration Synapse/Tchap + `MockHttpTransport` partagé.

---

## Points de vigilance transverses

- **`DefaultMatrixClient.start()`** : passe `RUNNING` avant la fin du premier sync ; en cas d'échec du premier sync, le statut reste `RUNNING` (pas de `FAILED`). À corriger dans la foulée de #6.
- **`Session.isValid()`** ne reflète pas la validité réelle (juste « token non vide ») ; `verifySession()` est manuel. Un vrai client doit rafraîchir périodiquement.
- **`SyncEventParser`** jette `RuntimeException` générique sur JSON invalide → perte du token de reprise potentielle ; à typer.
- **`DefaultMatrixRoom`** n'expose pas l'état du salon (nom, membres) alors que `Room.encrypted` existe : signature d'une abstraction `RoomState` manquante.

---

## Tickets transport à ouvrir tout de suite

1. `HttpTransport#delete(String, String)` + `send(method, path, headers, body)`.
2. Header `Authorization` optionnel (token `null`/blank).
3. Headers custom + `Content-Type` paramétrable.
4. `setIdleTimeout` + timeout par requête.
5. Parser `errcode`/`error`/`retry_after_ms` dans `VertxHttpTransport` et lever `MatrixServerException`.
6. Aligner la hiérarchie d'exceptions sur le contrat `HttpTransport`.
7. `SyncEngine` : distinguer erreurs fatales (401/403) et transitoires (429/5xx/réseau).
