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
