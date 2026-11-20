package fr.gouv.tchap.matrix.media;

/**
 * Média téléchargé, en mémoire.
 *
 * @param content
 *            octets du média
 * @param contentType
 *            type MIME annoncé par le serveur, ou {@code null}
 * @param filename
 *            nom de fichier annoncé par le serveur, réduit à un nom simple
 *            (sans chemin), ou {@code null}. Fourni par un tiers : à ne pas
 *            utiliser tel quel pour écrire sur un disque sans le contrôler.
 */
public record MediaContent(byte[] content, String contentType, String filename) {

	public MediaContent {
		content = content == null ? new byte[0] : content.clone();
	}

	/** Copie défensive. */
	@Override
	public byte[] content() {
		return content.clone();
	}

	/** Taille du média en octets. */
	public int size() {
		return content.length;
	}

	/** Ne décrit que la taille : le contenu peut être volumineux. */
	@Override
	public String toString() {
		return "MediaContent[" + content.length + " bytes, contentType=" + contentType + ", filename=" + filename + "]";
	}
}
