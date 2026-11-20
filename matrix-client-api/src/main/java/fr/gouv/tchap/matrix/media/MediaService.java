package fr.gouv.tchap.matrix.media;

import fr.gouv.tchap.matrix.message.OutgoingMessage;
import java.util.concurrent.CompletionStage;

/**
 * Médias du homeserver : téléverser un fichier, le télécharger, et l'envoyer
 * dans un salon. Le contenu est manipulé en mémoire : prévu pour des images,
 * documents et pièces jointes de taille raisonnable, pas pour de très gros
 * fichiers.
 * <p>
 * Les médias des salons chiffrés sont eux-mêmes chiffrés : non pris en charge
 * tant que le chiffrement de bout en bout ne l'est pas.
 */
public interface MediaService {

	/**
	 * Téléverse un fichier ({@code POST /_matrix/media/v3/upload}) et rend son
	 * adresse {@code mxc://}. Le fichier n'est rattaché à aucun message : pour
	 * l'envoyer dans un salon, utiliser ensuite {@code OutgoingMessage.image},
	 * {@code OutgoingMessage.file} etc., ou directement {@link #prepareMessage}.
	 *
	 * @param contentType
	 *            type MIME du fichier, ex. {@code image/png}
	 * @param filename
	 *            nom du fichier, facultatif
	 * @throws IllegalArgumentException
	 *             si {@code content} est nul ou vide, ou {@code contentType} vide
	 */
	CompletionStage<MxcUri> upload(byte[] content, String contentType, String filename);

	/**
	 * Télécharge un média. Utilise l'accès authentifié ({@code /client/v1/media})
	 * et se rabat sur l'ancien accès ({@code /media/v3}) si le serveur ne le
	 * connaît pas. Un média absent échoue en {@code MatrixServerException} 404.
	 */
	CompletionStage<MediaContent> download(MxcUri uri);

	/**
	 * Téléverse un fichier et construit le message à envoyer pour le présenter :
	 * image, audio, vidéo ou fichier selon {@code contentType}, avec le type MIME
	 * et la taille. Les dimensions et la durée, inconnues ici, sont omises :
	 * construire le message à la main avec {@link MediaAttachment} pour les
	 * renseigner.
	 *
	 * @param filename
	 *            nom du fichier, conservé dans le message
	 * @param caption
	 *            texte affiché avec le média ; {@code null} pour utiliser le nom du
	 *            fichier
	 */
	CompletionStage<OutgoingMessage> prepareMessage(byte[] content, String contentType, String filename,
			String caption);
}
