package fr.gouv.tchap.matrix.core.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.exception.MatrixException;
import fr.gouv.tchap.matrix.exception.MatrixServerException;
import fr.gouv.tchap.matrix.media.MediaContent;
import fr.gouv.tchap.matrix.media.MediaService;
import fr.gouv.tchap.matrix.media.MxcUri;
import fr.gouv.tchap.matrix.message.MediaAttachment;
import fr.gouv.tchap.matrix.message.OutgoingMessage;
import fr.gouv.tchap.matrix.transport.TransportResponse;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@link MediaService} au-dessus de {@link MatrixHttpApi}. */
public final class DefaultMediaService implements MediaService {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Pattern FILENAME_STAR = Pattern.compile("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern FILENAME = Pattern.compile("filename\\s*=\\s*(\"([^\"]*)\"|[^;]+)",
			Pattern.CASE_INSENSITIVE);

	private final MatrixHttpApi api;

	public DefaultMediaService(MatrixHttpApi api) {
		this.api = Objects.requireNonNull(api, "api is required");
	}

	@Override
	public CompletionStage<MxcUri> upload(byte[] content, String contentType, String filename) {
		if (content == null || content.length == 0) {
			throw new IllegalArgumentException("content is required");
		}
		if (contentType == null || contentType.isBlank()) {
			throw new IllegalArgumentException("contentType is required");
		}
		return api.uploadMedia(content, contentType, filename).thenApply(DefaultMediaService::parseUpload);
	}

	private static MxcUri parseUpload(String response) {
		try {
			JsonNode uri = MAPPER.readTree(response).path("content_uri");
			if (!uri.isTextual()) {
				throw new MatrixException("Invalid upload response: missing content_uri");
			}
			return MxcUri.parse(uri.asText());
		} catch (IllegalArgumentException e) {
			throw new MatrixException("Invalid upload response: " + e.getMessage(), e);
		} catch (MatrixException e) {
			throw e;
		} catch (Exception e) {
			// Le corps n'est pas recopié
			throw new MatrixException("Invalid upload response (not JSON)", e);
		}
	}

	@Override
	public CompletionStage<MediaContent> download(MxcUri uri) {
		Objects.requireNonNull(uri, "uri is required");
		return api.downloadMedia(uri.serverName(), uri.mediaId(), true).exceptionallyCompose(ex -> {
			Throwable cause = unwrap(ex);
			if (isEndpointUnknown(cause)) {
				// Serveur sans accès authentifié aux médias : ancien accès
				return api.downloadMedia(uri.serverName(), uri.mediaId(), false);
			}
			return CompletableFuture.<TransportResponse>failedFuture(cause);
		}).thenApply(DefaultMediaService::toContent);
	}

	/**
	 * Un serveur qui ignore l'accès authentifié répond {@code M_UNRECOGNIZED} : on
	 * se rabat sur l'ancien. Un média absent ({@code M_NOT_FOUND}) n'est pas ce cas
	 * : repartir sur l'ancien accès n'y changerait rien.
	 */
	private static boolean isEndpointUnknown(Throwable cause) {
		return cause instanceof MatrixServerException server && "M_UNRECOGNIZED".equals(server.errcode());
	}

	private static MediaContent toContent(TransportResponse response) {
		return new MediaContent(response.body(), response.header("Content-Type"),
				filenameOf(response.header("Content-Disposition")));
	}

	/**
	 * Nom de fichier annoncé par {@code Content-Disposition}, réduit à un nom
	 * simple : la valeur vient d'un tiers, un chemin ({@code ../../x}) ne doit pas
	 * en sortir.
	 */
	static String filenameOf(String contentDisposition) {
		if (contentDisposition == null || contentDisposition.isBlank()) {
			return null;
		}
		String name = null;
		Matcher star = FILENAME_STAR.matcher(contentDisposition);
		if (star.find()) {
			try {
				name = URLDecoder.decode(star.group(2).trim().replace("+", "%2B"), charsetOf(star.group(1)));
			} catch (RuntimeException e) {
				name = null;
			}
		}
		if (name == null) {
			Matcher plain = FILENAME.matcher(contentDisposition);
			if (plain.find()) {
				name = plain.group(2) != null ? plain.group(2) : plain.group(1).trim();
			}
		}
		return baseName(name);
	}

	private static java.nio.charset.Charset charsetOf(String name) {
		return "iso-8859-1".equals(name.toLowerCase(Locale.ROOT))
				? StandardCharsets.ISO_8859_1
				: StandardCharsets.UTF_8;
	}

	/**
	 * Dernier élément d'un chemin (séparateurs {@code /} et {@code \\}), sans NUL
	 * ni espaces autour.
	 */
	private static String baseName(String name) {
		if (name == null) {
			return null;
		}
		String cleaned = name.replace("\0", "").trim();
		int cut = Math.max(cleaned.lastIndexOf('/'), cleaned.lastIndexOf('\\'));
		cleaned = cleaned.substring(cut + 1).trim();
		return cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..") ? null : cleaned;
	}

	@Override
	public CompletionStage<OutgoingMessage> prepareMessage(byte[] content, String contentType, String filename,
			String caption) {
		return upload(content, contentType, filename).thenApply(uri -> {
			MediaAttachment media = MediaAttachment.of(uri, contentType, content.length);
			if (filename != null && !filename.isBlank()) {
				media = media.withFilename(filename);
			}
			String text = caption != null && !caption.isBlank()
					? caption
					: filename != null && !filename.isBlank() ? filename : "fichier";
			return messageFor(contentType, text, media);
		});
	}

	private static OutgoingMessage messageFor(String contentType, String text, MediaAttachment media) {
		String type = contentType.toLowerCase(Locale.ROOT);
		if (type.startsWith("image/")) {
			return OutgoingMessage.image(text, media);
		}
		if (type.startsWith("audio/")) {
			return OutgoingMessage.audio(text, media);
		}
		if (type.startsWith("video/")) {
			return OutgoingMessage.video(text, media);
		}
		return OutgoingMessage.file(text, media);
	}

	private static Throwable unwrap(Throwable t) {
		Throwable cause = t;
		while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause;
	}
}
