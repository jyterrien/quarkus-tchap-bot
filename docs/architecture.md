# Architecture

Voir `discusion-initiale.txt` pour le document de référence.

Modules :
- `matrix-client-api` : contrats publics (aucune dépendance Quarkus)
- `matrix-client-core` : implémentation protocolaire Matrix
- `matrix-transport-http` : transport HTTP (Vert.x)
- `quarkus-tchap-bot/runtime` : intégration CDI, config, lifecycle
- `quarkus-tchap-bot/deployment` : build-time processing Quarkus

## Threads de discussion

La prise en charge des threads (relation Matrix `m.thread`) traverse toutes les couches :

- **Réception** : `MatrixEvent.threadRootId()` (matrix-client-api) est reporté par `TchapMessageMapper`
  sur `TchapMessage.threadRootId()`. Il vaut `null` pour un message hors thread, y compris pour la
  racine d'un thread (qui est un message ordinaire). `TchapMessage.isInThread()` en est le raccourci.
- **Envoi** : `TchapRoom.sendTextInThread(threadRootId, body)` / `sendNoticeInThread(...)` pour un
  salon nommé, et `TchapClient.sendTextInThread(roomId, threadRootId, body)` / `sendNoticeInThread(...)`
  pour un salon quelconque.
- **Répondre à un message reçu** : passer `TchapMessage.replyThreadId()`. C'est la racine du thread si
  le message en fait partie, sinon l'identifiant du message lui-même (la réponse ouvre alors un nouveau
  thread). On ne passe jamais l'identifiant d'une réponse comme racine.

```java
void onMessage(@Observes @TchapRoomName("oncall") TchapMessage message) {
    oncall.sendTextInThread(message.replyThreadId(), "Bien reçu");
}
```

`TchapMessage` et `MatrixEvent` conservent leur constructeur antérieur (sans thread) : le code existant
compile sans changement.

## Sondes de santé

Deux sondes distinctes, chacune dans son seul groupe (sinon elle apparaît en double dans `/q/health`) :
`TchapReadinessCheck` (le client est démarré) et `TchapLivenessCheck` (la boucle `/sync` progresse, d'après
`MatrixClient.syncHealth()`). Voir `docs/configuration.md`.

## Authentification et renouvellement du token

`MatrixHttpApi` ne garde plus de token : il interroge un `TokenProvider` à chaque appel. Deux implémentations :
`StaticTokenProvider` (token fourni, non renouvelable) et `PasswordTokenProvider` (login, refresh, reconnexion,
persistance dans un `CredentialStore`). Sur un refus du serveur (`MatrixServerException.isTokenRejected()`),
`MatrixHttpApi` demande `renew(token)` puis rejoue la requête une seule fois ; les renouvellements simultanés sont
fusionnés en un seul. Des identifiants refusés (`AuthenticationException`) arrêtent `SyncEngine`, qui prévient le
client (`FAILED`). Voir `docs/configuration.md`.

## Arrêt et tâches de fond

Le bot n'a pas de tâche planifiée de renouvellement du token : le renouvellement est fait à la demande (juste
avant une requête dont le token est proche de l'expiration, ou après un refus du serveur), donc rien à arrêter de
ce côté. Les seuls threads propres au bot sont `tchap-sync` (boucle `/sync`) et les threads d'écriture des
magasins de fichiers (`tchap-token-io`, `tchap-credentials-io`).

À l'arrêt de Quarkus, dans l'ordre :

1. `TchapLifecycle` (sur `ShutdownEvent`) arrête le client : la boucle ne relance plus rien.
2. La requête de long polling déjà partie ne peut pas être annulée ; elle échoue quand le transport se ferme, ou
   aboutit après coup. `SyncEngine` l'ignore (numéro de génération incrémenté à chaque `start()`/`stop()`) :
   pas d'échec compté, pas de message journalisé en WARN, pas d'événement livré, pas de nouveau token
   sauvegardé. Le même garde empêche une requête de l'ancienne boucle de relancer une seconde boucle après un
   redémarrage.
3. Les magasins créés par l'extension (`SyncTokenStore`, `CredentialStore`) sont fermés : les écritures en
   attente, notamment le dernier refresh token, sont terminées avant l'arrêt du thread. Un magasin fourni par
   l'application n'est pas fermé par l'extension.
4. Le transport est fermé.

Les observers des événements CDI s'exécutent sur le pool de workers de Quarkus, pas sur l'event loop Vert.x qui
exécute la boucle `/sync`.

## Messages enrichis

Un seul point d'entrée pour tout ce qui dépasse le texte simple : `send(OutgoingMessage)` (sur `MatrixRoom`,
`TchapRoom`, et `send(roomId, message)` sur `MessageService` et `TchapClient`). `OutgoingMessage` est immuable et se
construit par étapes :

```java
OutgoingMessage.text("Bien reçu")                                      // m.text
OutgoingMessage.notice("Déploiement terminé")                          // m.notice
OutgoingMessage.emote("surveille la file")                             // m.emote
    .withHtml("<b>Déploiement</b> terminé")                            // format org.matrix.custom.html
    .inReplyTo("$abc")                                                 // m.in_reply_to
    .inThread("$root")                                                 // m.thread
```

- **HTML** : `body` reste obligatoire, c'est le texte de repli (clients sans HTML, notifications). Le serveur ne
  filtre pas `formatted_body` : n'utiliser que les balises autorisées par la spécification Matrix et passer tout
  texte venant d'un utilisateur par `OutgoingMessage.escapeHtml(...)`.
- **Réponse** : `m.in_reply_to` désigne un message précis. La citation de repli dans le texte n'est pas envoyée
  (retirée de la spécification Matrix 1.13) ; les clients affichent la citation à partir de la relation.
- **Réponse dans un thread** : `inReplyTo` + `inThread` donnent un `m.thread` dont le `m.in_reply_to` désigne
  vraiment le message visé (`is_falling_back: false`). `inThread` seul garde le comportement précédent (repli vers la
  racine, pour les clients sans threads).
- **Réception** : `MatrixEvent.replyToEventId()` / `TchapMessage.replyToEventId()` donnent le message visé, ou
  `null`. La réponse de repli que les clients ajoutent dans un thread n'est pas une vraie réponse : elle donne
  `null`, comme un message sans relation.
- **Répondre à un message reçu** : `TchapMessage.reply(texte)` construit la réponse ciblée, dans le thread du
  message s'il en fait partie, sans en ouvrir un sinon. `message.reply("ok").withType(Type.NOTICE)` pour une
  notice.

```java
void onMessage(@Observes @TchapRoomName("oncall") TchapMessage message) {
    oncall.send(message.reply("Bien reçu"));
}
```

Les méthodes précédentes (`sendText`, `sendNotice`, `sendTextInThread`, `sendNoticeInThread`) restent et produisent
le même contenu qu'avant. Un texte vide est refusé (`IllegalArgumentException`).

### Éditions et réactions

```java
room.edit("$monMessage", OutgoingMessage.text("Texte corrigé"));   // m.replace
room.react("$leurMessage", "👍");                                    // m.annotation
```

- **Éditer** (`edit(eventId, nouveauContenu)`) : le nouveau contenu est dans `m.new_content`, le contenu de premier
  niveau est un repli « * texte » pour les clients sans gestion des éditions. Le nouveau contenu ne peut pas changer
  la réponse ni le thread du message (`IllegalArgumentException`). Seul l'auteur peut éditer son message ; le reçu
  renvoyé est celui de l'événement d'édition, pas du message d'origine.
- **Réagir** (`react(eventId, clé)`) : la clé est en général un emoji. Réagir deux fois avec la même clé au même
  message est refusé par le serveur.
- **Supprimer** (`redact(eventId, motif)`) : supprime un événement (`PUT .../redact/...`), un message comme une
  réaction. Pour **retirer une réaction**, supprimer son événement : son identifiant est le reçu rendu par `react`.
  Le contenu est effacé chez tout le monde ; le bot peut supprimer ses propres événements, ceux des autres demandent
  le droit de modération du salon (sinon `MatrixServerException` 403, `M_FORBIDDEN`). Le motif est facultatif.
  Le reçu est celui de l'événement de suppression.

```java
MessageReceipt r = room.react("$leurMessage", "👍").toCompletableFuture().join();
room.redact(r.eventId(), null);                       // retirer ma réaction
room.redact("$monMessage", "envoyé par erreur");      // supprimer un message
```

  La réception d'une suppression (`m.room.redaction`) n'est pas encore publiée comme événement CDI.
- **Recevoir une édition** : elle n'est **plus** publiée comme un nouveau `TchapMessage` (elle arrivait comme un
  message « * texte » et re-déclenchait le bot). Elle arrive comme un événement CDI `TchapMessageEdited` (message
  remplacé, nouveau texte, nouveau `msgtype`) :

```java
void onEdit(@Observes TchapMessageEdited edit) {
    // edit.replacesEventId() désigne le message d'origine, edit.body() le nouveau texte
}
```

- **Recevoir une réaction** : événement CDI `TchapReaction` (message visé, clé).
- Les deux sont publiés comme les messages : avec le qualificatif `@TchapRoomName` du salon pour un salon nommé, et
  ils respectent `ignore-self-messages`. Au niveau du SDK, `MatrixEvent.replacesEventId()` / `isEdit()` donnent la
  cible d'une édition ; les réactions sont des événements de type `m.reaction` dont la relation est lue par
  l'extension.
- `MessageService.sendEvent` n'existe pas encore, mais `MatrixHttpApi.sendEvent(roomId, type, txnId, json)` envoie
  un événement de type quelconque (utilisé pour `m.reaction`).

## Fermeture de la session

`MatrixClient.logout()` (à appeler après `stop()`) ferme la session sur le serveur par `TokenProvider.logout()` :
sans effet pour un `StaticTokenProvider`, `POST /logout` pour un `PasswordTokenProvider`, qui oublie ensuite les
identifiants (`CredentialStore.clear()` : mémoire vidée, fichier supprimé) et refuse désormais de fournir ou de
renouveler un token. `TchapLifecycle` l'appelle à l'arrêt de Quarkus, après l'arrêt de la synchronisation et avant la
fermeture du transport, quand `quarkus.tchap.auth.logout-on-shutdown` le demande (voir `docs/configuration.md`), y
compris quand la synchronisation est désactivée.

## Erreurs

Toutes les erreurs du SDK sont des `MatrixException` : un seul `catch (MatrixException)` les attrape toutes, le 429
compris.

```
MatrixException
├── AuthenticationException     identifiants refusés ou impossibles à renouveler : inutile de réessayer
├── MatrixTransportException    la requête n'a pas abouti (réseau, connexion coupée, transport fermé)
└── MatrixServerException       le serveur a répondu par une erreur HTTP (statut, errcode, soft_logout)
    └── RateLimitException      429 : réessayer après retryAfterMillis()
```

Pour décider s'il faut réessayer :

| Erreur | Réessayer ? |
|---|---|
| `MatrixTransportException` | oui, le serveur n'a rien répondu |
| `MatrixServerException` 5xx (`isServerError()`) | oui, en général transitoire |
| `RateLimitException` (`isRateLimited()`) | oui, après `retryAfterMillis()` |
| `MatrixServerException` 4xx hors 429 (`isClientError()`) | non, refus définitif |
| `AuthenticationException` | non |

`isClientError()` exclut volontairement le 429 : sinon le renouvellement du token prendrait un 429 pour un refus
définitif des identifiants (« login refused ») et se reconnecterait à tort. Une erreur `MatrixServerException` garde son
message « HTTP statut: corps » ; `isTokenRejected()` repère un token refusé (401 ou `M_UNKNOWN_TOKEN`).

`MatrixTransportException` et `RateLimitException` sont définies dans `matrix-client-api` (même paquet qu'avant,
`fr.gouv.tchap.matrix.exception`) : les imports existants restent valides.

## Transport HTTP

`HttpTransport` offre `get`, `put`, `post` (JSON, inchangés), `delete`, et une requête générique
`send(TransportRequest)` dont tout le reste découle dans `VertxHttpTransport` :

```java
transport.delete("/_matrix/client/v3/directory/room/%23alias%3Asrv", token);

transport.send(TransportRequest.of("POST", "/_matrix/media/v3/upload?filename=a.png", token)
        .withHeader("X-Request-ID", "req-42")
        .withBody("image/png", bytes)           // corps binaire, Content-Type voulu
        .withTimeout(Duration.ofMinutes(2)));   // à la place du délai du transport
```

- `TransportRequest` est immuable. `Authorization` et `Content-Type` ne se fixent que par `accessToken` et
  `contentType` (les passer en en-têtes est refusé) ; un token nul ou vide n'envoie pas d'`Authorization`. Son
  `toString()` n'affiche ni le token ni le corps.
- `delete` et `send` sont des méthodes `default` de `HttpTransport` : un transport qui ne fournit que `get`/`put`/
  `post` reste valide, `send` échoue alors avec `UnsupportedOperationException`.
- **Délais** (`HttpTransportOptions`) : connexion 30 s, requête 60 s par défaut, et un délai propre à une requête. Le
  délai de requête compte le temps **sans donnée reçue** ; il doit donc dépasser la durée du long polling de `/sync`.
  L'extension Quarkus le relève automatiquement (voir `docs/configuration.md`). Hors Quarkus, le régler avec
  `TransportFactory.vertx(url, options)` ou le constructeur de `VertxHttpTransport`.

## Médias

`MatrixClient.media()` (`MediaService`) téléverse et télécharge ; `TchapClient` et `TchapRoom` ajoutent l'envoi d'un
fichier dans un salon :

```java
// Envoyer : téléverse puis poste un message m.image / m.audio / m.video / m.file selon le type MIME
room.sendMedia(bytes, "image/png", "graphique.png", "Résultat du jour");   // légende facultative

// Recevoir et télécharger
void onMessage(@Observes TchapMessage message) {
    if (message.hasMedia()) {
        tchap.download(message.media()).thenAccept(content -> {
            // content.content(), content.contentType(), content.filename()
        });
    }
}
```

- **Légende** : `body` du message est la légende ; sans légende, le nom du fichier. Le nom d'origine est envoyé à part
  (`filename`, Matrix 1.10). Les dimensions (`w`, `h`) et la durée ne sont pas connues du SDK : pour les renseigner,
  téléverser avec `upload` puis envoyer un message construit à la main :

```java
MxcUri uri = tchap.upload(bytes, "image/png", "graphique.png").toCompletableFuture().join();
room.send(OutgoingMessage.image("Résultat", MediaAttachment.of(uri, "image/png", bytes.length).withDimensions(800, 600)));
```

- **Téléchargement** : par l'accès authentifié (`/_matrix/client/v1/media/download`, Matrix 1.11), avec repli sur
  l'ancien (`/_matrix/media/v3/download`) si le serveur répond `M_UNRECOGNIZED`. Un média absent (`M_NOT_FOUND`) n'est
  pas retenté. Le contenu est lu **en entier en mémoire** : prévu pour des images et documents de taille raisonnable.
  La taille annoncée par l'expéditeur (`TchapMedia.size()`) n'est qu'une indication, à ne pas croire pour dimensionner
  un tampon ; il n'y a pas encore de limite de taille ni de streaming.
- **Sécurité** : `MxcUri` n'accepte que `mxc://serveur/identifiant` avec des caractères sûrs (pas de `/`, de `..`, de
  paramètres) : les deux parties finissent dans le chemin d'une requête. Le `filename` annoncé par un expéditeur ou par
  `Content-Disposition` est réduit à un nom simple (`../../etc/passwd` donne `passwd`) mais vient d'un tiers : à
  contrôler avant d'écrire sur un disque.
- **Médias chiffrés** : un média d'un salon chiffré (champ `file` au lieu de `url`) n'est pas exposé
  (`TchapMessage.media()` est nul, le message l'est quand même) ; il n'est pas lisible tant que le chiffrement de bout
  en bout n'est pas pris en charge.
- **Édition** : un message média ne peut pas être édité (`IllegalArgumentException`).
