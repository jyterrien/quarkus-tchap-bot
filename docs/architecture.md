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
