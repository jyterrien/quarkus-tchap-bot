package fr.gouv.tchap.quarkus.events;

import fr.gouv.tchap.matrix.media.MxcUri;

/**
 * Média joint à un message reçu ({@code m.image}, {@code m.file},
 * {@code m.audio}, {@code m.video}). Pour le télécharger :
 * {@code TchapClient.download(media)}.
 * <p>
 * Absent ({@code TchapMessage.media()} nul) pour un média chiffré, c'est-à-dire
 * d'un salon chiffré : il n'est pas lisible tant que le chiffrement de bout en
 * bout n'est pas pris en charge.
 *
 * @param url
 *            adresse {@code mxc://} du média
 * @param mimeType
 *            type MIME annoncé par l'expéditeur, ou {@code null}
 * @param size
 *            taille annoncée en octets, ou {@code null}. Donnée par
 *            l'expéditeur : à ne pas croire pour dimensionner un tampon.
 * @param filename
 *            nom de fichier annoncé, ou {@code null}. Même réserve : un tiers
 *            le choisit.
 */
public record TchapMedia(String url, String mimeType, Long size, String filename) {

	/** L'adresse sous forme analysée. */
	public MxcUri uri() {
		return MxcUri.parse(url);
	}
}
