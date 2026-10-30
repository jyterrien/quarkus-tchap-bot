# Simple Bot Example

Bot minimal répondant `pong` à un message `!ping`.

## Lancement en dev

```bash
export TCHAP_HOMESERVER_URL=https://matrix.agent.tchap.gouv.fr
export TCHAP_ACCESS_TOKEN=syt_...
export TCHAP_USER_ID=@monbot:tchap.gouv.fr
./mvnw -pl examples/simple-bot quarkus:dev
