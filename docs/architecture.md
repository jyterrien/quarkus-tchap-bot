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
  message est refusé par le serveur. Retirer une réaction demande de supprimer l'événement de réaction
  (`/redact`), pas encore pris en charge.
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
