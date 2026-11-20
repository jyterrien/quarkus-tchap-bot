package fr.gouv.tchap.matrix.message;

import fr.gouv.tchap.matrix.media.MxcUri;

/**
 * Média joint à un message ({@code m.image}, {@code m.file}, {@code m.audio},
 * {@code m.video}) : où il se trouve sur le homeserver et ses métadonnées.
 * Immuable ; les méthodes {@code with*} renvoient une copie.
 *
 * @param url
 *            adresse {@code mxc://} du média déjà téléversé
 * @param mimeType
 *            type MIME, ou {@code null}
 * @param size
 *            taille en octets, ou {@code null}
 * @param width
 *            largeur en pixels (image, vidéo), ou {@code null}
 * @param height
 *            hauteur en pixels (image, vidéo), ou {@code null}
 * @param durationMs
 *            durée en millisecondes (audio, vidéo), ou {@code null}
 * @param filename
 *            nom d'origine du fichier, ou {@code null}. Quand il est donné,
 *            {@code body} du message peut être une légende plutôt que le nom.
 */
public record MediaAttachment(String url, String mimeType, Long size, Integer width, Integer height, Long durationMs,
		String filename) {

	public MediaAttachment {
		MxcUri.parse(url); // refuse une adresse qui n'est pas mxc://serveur/id
		if (mimeType != null && mimeType.isBlank()) {
			throw new IllegalArgumentException("mimeType must not be blank");
		}
		if (size != null && size < 0) {
			throw new IllegalArgumentException("size must not be negative");
		}
		if (width != null && width <= 0 || height != null && height <= 0) {
			throw new IllegalArgumentException("width and height must be positive");
		}
		if (durationMs != null && durationMs < 0) {
			throw new IllegalArgumentException("durationMs must not be negative");
		}
		if (filename != null && filename.isBlank()) {
			throw new IllegalArgumentException("filename must not be blank");
		}
	}

	/**
	 * Média à l'adresse {@code uri}, de type {@code mimeType} et de {@code size}
	 * octets.
	 */
	public static MediaAttachment of(MxcUri uri, String mimeType, long size) {
		return new MediaAttachment(uri.toString(), mimeType, size, null, null, null, null);
	}

	public MediaAttachment withDimensions(int newWidth, int newHeight) {
		return new MediaAttachment(url, mimeType, size, newWidth, newHeight, durationMs, filename);
	}

	public MediaAttachment withDuration(long newDurationMs) {
		return new MediaAttachment(url, mimeType, size, width, height, newDurationMs, filename);
	}

	public MediaAttachment withFilename(String newFilename) {
		return new MediaAttachment(url, mimeType, size, width, height, durationMs, newFilename);
	}

	/** L'adresse sous forme analysée. */
	public MxcUri uri() {
		return MxcUri.parse(url);
	}
}
