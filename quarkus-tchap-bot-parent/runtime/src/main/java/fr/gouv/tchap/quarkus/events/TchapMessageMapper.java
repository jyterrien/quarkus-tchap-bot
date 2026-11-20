package fr.gouv.tchap.quarkus.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.gouv.tchap.matrix.event.MatrixEvent;
import fr.gouv.tchap.matrix.media.MxcUri;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Convertit un événement Matrix brut en événement destiné aux observers CDI :
 * <ul>
 * <li>{@link #map} : un {@code m.room.message} qui n'est pas une édition, en
 * {@link TchapMessage} (avec la racine du thread et le message visé s'il y en
 * a) ;</li>
 * <li>{@link #mapEdit} : une édition, en {@link TchapMessageEdited} (jamais
 * republiée comme un nouveau message) ;</li>
 * <li>{@link #mapReaction} : un {@code m.reaction}, en
 * {@link TchapReaction}.</li>
 * </ul>
 * Chaque méthode ne retient que son type d'événement. Les événements du bot
 * lui-même sont écartés quand {@code ignoreSelf} est activé et que son
 * identifiant est connu. Classe sans dépendance à CDI : testable sans démarrer
 * Quarkus.
 */
public class TchapMessageMapper {

	private static final Logger LOG = Logger.getLogger(TchapMessageMapper.class);

	/** ObjectMapper est thread-safe : une seule instance pour tous les messages. */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final Set<String> MEDIA_TYPES = Set.of("m.image", "m.file", "m.audio", "m.video");

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

	private boolean isFromSelf(MatrixEvent ev) {
		String self = botUserId.get();
		return ignoreSelf && self != null && self.equals(ev.sender());
	}

	/**
	 * Média d'un message {@code m.image}, {@code m.file}, {@code m.audio} ou
	 * {@code m.video} : {@code url} (adresse mxc://) et {@code info}. Null pour un
	 * autre type, quand l'adresse manque ou est invalide, et pour un média chiffré
	 * (champ {@code file} au lieu de {@code url}).
	 */
	private static TchapMedia mediaOf(String msgtype, JsonNode content) {
		if (!MEDIA_TYPES.contains(msgtype)) {
			return null;
		}
		JsonNode url = content.path("url");
		if (!url.isTextual() || !MxcUri.isValid(url.asText())) {
			return null;
		}
		JsonNode info = content.path("info");
		String mimeType = info.path("mimetype").isTextual() ? info.path("mimetype").asText() : null;
		Long size = info.path("size").canConvertToLong() && info.path("size").asLong() >= 0
				? info.path("size").asLong()
				: null;
		String filename = content.path("filename").isTextual() && !content.path("filename").asText().isBlank()
				? content.path("filename").asText()
				: null;
		return new TchapMedia(url.asText(), mimeType, size, filename);
	}

	public Optional<TchapMessage> map(MatrixEvent ev) {
		LOG.debugf("Matrix event: type=%s room=%s sender=%s", ev.type(), ev.roomId(), ev.sender());

		if (isFromSelf(ev)) {
			return Optional.empty();
		}
		if (!"m.room.message".equals(ev.type()) || ev.isEdit()) {
			return Optional.empty();
		}
		try {
			JsonNode content = MAPPER.readTree(ev.content());
			String body = content.path("body").asText("");
			String msgtype = content.path("msgtype").asText("m.text");
			return Optional.of(new TchapMessage(ev.eventId(), ev.roomId(), ev.sender(), body, ev.timestamp(), false,
					msgtype, ev.threadRootId(), ev.replyToEventId(), mediaOf(msgtype, content)));
		} catch (Exception e) {
			LOG.warnf("Cannot parse m.room.message content for event %s: %s", ev.eventId(), e.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * Édition d'un message : {@code m.room.message} dont la relation est
	 * {@code m.replace}. Le nouveau contenu est lu dans {@code m.new_content} ; à
	 * défaut, dans le texte de repli de premier niveau.
	 */
	public Optional<TchapMessageEdited> mapEdit(MatrixEvent ev) {
		if (!"m.room.message".equals(ev.type()) || !ev.isEdit() || isFromSelf(ev)) {
			return Optional.empty();
		}
		try {
			JsonNode content = MAPPER.readTree(ev.content());
			JsonNode newContent = content.path("m.new_content");
			JsonNode source = newContent.isObject() ? newContent : content;
			return Optional.of(new TchapMessageEdited(ev.eventId(), ev.roomId(), ev.sender(), ev.replacesEventId(),
					source.path("body").asText(""), ev.timestamp(), source.path("msgtype").asText("m.text")));
		} catch (Exception e) {
			LOG.warnf("Cannot parse edit content for event %s: %s", ev.eventId(), e.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * Réaction : événement {@code m.reaction} dont la relation est
	 * {@code m.annotation}, avec l'événement visé et la clé (l'emoji). Une réaction
	 * sans cible ou sans clé est ignorée.
	 */
	public Optional<TchapReaction> mapReaction(MatrixEvent ev) {
		if (!"m.reaction".equals(ev.type()) || isFromSelf(ev)) {
			return Optional.empty();
		}
		try {
			JsonNode relatesTo = MAPPER.readTree(ev.content()).path("m.relates_to");
			String target = relatesTo.path("event_id").asText(null);
			String key = relatesTo.path("key").asText(null);
			if (!"m.annotation".equals(relatesTo.path("rel_type").asText(null)) || target == null || target.isBlank()
					|| key == null || key.isEmpty()) {
				return Optional.empty();
			}
			return Optional.of(new TchapReaction(ev.eventId(), ev.roomId(), ev.sender(), target, key, ev.timestamp()));
		} catch (Exception e) {
			LOG.warnf("Cannot parse m.reaction content for event %s: %s", ev.eventId(), e.getMessage());
			return Optional.empty();
		}
	}
}
