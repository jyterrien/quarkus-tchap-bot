package fr.gouv.tchap.quarkus.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.gouv.tchap.matrix.event.MatrixEvent;
import java.util.Optional;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Convertit un événement Matrix brut en {@link TchapMessage} destiné aux
 * observers CDI. Retient uniquement les {@code m.room.message}, et écarte les
 * messages du bot lui-même quand {@code ignoreSelf} est activé et que son
 * identifiant est connu. La racine du thread éventuel
 * ({@link MatrixEvent#threadRootId()}) est reportée sur le message. Classe sans
 * dépendance à CDI : testable sans démarrer Quarkus.
 */
public class TchapMessageMapper {

	private static final Logger LOG = Logger.getLogger(TchapMessageMapper.class);

	/** ObjectMapper est thread-safe : une seule instance pour tous les messages. */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final boolean ignoreSelf;
	private final Supplier<String> botUserId;

	public TchapMessageMapper(boolean ignoreSelf, String botUserId) {
		this(ignoreSelf, () -> botUserId);
	}

	/**
	 * L'identifiant du bot est demandé à chaque événement : en connexion par mot de
	 * passe il n'est connu qu'une fois connecté.
	 */
	public TchapMessageMapper(boolean ignoreSelf, Supplier<String> botUserId) {
		this.ignoreSelf = ignoreSelf;
		this.botUserId = botUserId;
	}

	public Optional<TchapMessage> map(MatrixEvent ev) {
		LOG.debugf("Matrix event: type=%s room=%s sender=%s", ev.type(), ev.roomId(), ev.sender());

		String self = botUserId.get();
		if (ignoreSelf && self != null && self.equals(ev.sender())) {
			return Optional.empty();
		}
		if (!"m.room.message".equals(ev.type())) {
			return Optional.empty();
		}
		try {
			JsonNode content = MAPPER.readTree(ev.content());
			String body = content.path("body").asText("");
			String msgtype = content.path("msgtype").asText("m.text");
			return Optional.of(new TchapMessage(ev.eventId(), ev.roomId(), ev.sender(), body, ev.timestamp(), false,
					msgtype, ev.threadRootId()));
		} catch (Exception e) {
			LOG.warnf("Cannot parse m.room.message content for event %s: %s", ev.eventId(), e.getMessage());
			return Optional.empty();
		}
	}
}
