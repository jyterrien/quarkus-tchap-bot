package fr.gouv.tchap.quarkus.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.gouv.tchap.matrix.event.MatrixEvent;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Convertit un événement Matrix brut en {@link TchapMessage} destiné aux observers CDI.
 *
 * Retient uniquement les {@code m.room.message}, et écarte les messages du bot lui-même
 * quand {@code ignoreSelf} est activé et que son identifiant est connu.
 * Classe sans dépendance à CDI : testable sans démarrer Quarkus.
 */
public class TchapMessageMapper {

    private static final Logger LOG = Logger.getLogger(TchapMessageMapper.class);

    /** ObjectMapper est thread-safe : une seule instance pour tous les messages. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final boolean ignoreSelf;
    private final String botUserId;

    public TchapMessageMapper(boolean ignoreSelf, String botUserId) {
        this.ignoreSelf = ignoreSelf;
        this.botUserId = botUserId;
    }

    public Optional<TchapMessage> map(MatrixEvent ev) {
        LOG.debugf("Matrix event: type=%s room=%s sender=%s", ev.type(), ev.roomId(), ev.sender());

        if (ignoreSelf && botUserId != null && botUserId.equals(ev.sender())) {
            return Optional.empty();
        }
        if (!"m.room.message".equals(ev.type())) {
            return Optional.empty();
        }
        try {
            JsonNode content = MAPPER.readTree(ev.content());
            String body = content.path("body").asText("");
            String msgtype = content.path("msgtype").asText("m.text");
            return Optional.of(new TchapMessage(
                ev.eventId(), ev.roomId(), ev.sender(), body, ev.timestamp(), false, msgtype));
        } catch (Exception e) {
            LOG.warnf("Cannot parse m.room.message content for event %s: %s", ev.eventId(), e.getMessage());
            return Optional.empty();
        }
    }
}
