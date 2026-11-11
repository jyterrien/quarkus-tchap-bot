# Simple Bot Example

Bot minimal répondant `pong` à un message `!ping`, **confiné au salon
d'intégration** `!BotsetIntgrationsTchapU2tHdMEN80D:agent.dinum.tchap.gouv.fr`.
répond dans le fil de discussion courant ou en créé un pour répondre.

Le salon est déclaré dans `application.properties` :

```properties
quarkus.tchap.rooms."integration".room=!BotsetIntgrationsTchapU2tHdMEN80D:agent.dinum.tchap.gouv.fr