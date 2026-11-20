package fr.gouv.tchap.matrix.message;

import java.util.Objects;

/**
 * Message à envoyer : type, texte, version HTML facultative et relations
 * facultatives (réponse à un message, thread). Immuable : les méthodes
 * {@code with*}, {@code inReplyTo} et {@code inThread} renvoient une copie.
 *
 * <pre>
 * OutgoingMessage.text("Bien reçu").inReplyTo("$abc")
 * OutgoingMessage.notice("Déploiement terminé").withHtml("&lt;b&gt;Déploiement&lt;/b&gt; terminé")
 * OutgoingMessage.emote("se met au travail")
 * OutgoingMessage.image("graphique.png", MediaAttachment.of(uri, "image/png", 18_432).withDimensions(800, 600))
 * </pre>
 *
 * Un message de type image, fichier, audio ou vidéo porte un
 * {@link MediaAttachment} (un média déjà téléversé, voir
 * {@code MediaService.upload}) ; les autres types n'en portent pas.
 *
 * @param body
 *            texte brut. Toujours obligatoire : c'est ce qu'affichent les
 *            clients qui ne rendent pas le HTML, et les notifications.
 * @param formattedBody
 *            version HTML du message (format {@code org.matrix.custom.html}),
 *            ou {@code null}. Le serveur ne la filtre pas : n'y mettre que les
 *            balises autorisées par la spécification Matrix, et échapper tout
 *            texte venant d'un utilisateur avec {@link #escapeHtml(String)}.
 * @param replyToEventId
 *            événement auquel ce message répond précisément, ou {@code null}
 * @param threadRootId
 *            racine du thread dans lequel envoyer le message, ou {@code null}
 *            hors thread. Toujours la racine, jamais une réponse.
 * @param media
 *            média joint, obligatoire pour les types image, fichier, audio et
 *            vidéo, interdit pour les autres. {@code body} sert alors de
 *            légende (ou de nom de fichier).
 */
public record OutgoingMessage(Type type, String body, String formattedBody, String replyToEventId, String threadRootId,
		MediaAttachment media) {

	/** Type de message (champ {@code msgtype}). */
	public enum Type {
		/** Message ordinaire. */
		TEXT("m.text"),
		/**
		 * Message automatique : les clients ne l'affichent pas comme une intervention
		 * humaine et les bots n'y répondent pas.
		 */
		NOTICE("m.notice"),
		/** Action à la troisième personne, affichée « * nom se met au travail ». */
		EMOTE("m.emote"),
		/** Image. Porte un média. */
		IMAGE("m.image", true),
		/** Fichier quelconque (document, archive...). Porte un média. */
		FILE("m.file", true),
		/** Son. Porte un média. */
		AUDIO("m.audio", true),
		/** Vidéo. Porte un média. */
		VIDEO("m.video", true);

		private final String msgtype;
		private final boolean media;

		Type(String msgtype) {
			this(msgtype, false);
		}

		Type(String msgtype, boolean media) {
			this.msgtype = msgtype;
			this.media = media;
		}

		public String msgtype() {
			return msgtype;
		}

		/** Vrai pour les types qui portent un média (image, fichier, audio, vidéo). */
		public boolean isMedia() {
			return media;
		}
	}

	public OutgoingMessage {
		Objects.requireNonNull(type, "type is required");
		if (body == null || body.isBlank()) {
			throw new IllegalArgumentException("body is required");
		}
		if (formattedBody != null && formattedBody.isBlank()) {
			throw new IllegalArgumentException("formattedBody must not be blank");
		}
		if (replyToEventId != null && replyToEventId.isBlank()) {
			throw new IllegalArgumentException("replyToEventId is required");
		}
		if (threadRootId != null && threadRootId.isBlank()) {
			throw new IllegalArgumentException("threadRootId is required");
		}
		if (type.isMedia() && media == null) {
			throw new IllegalArgumentException("A " + type.msgtype() + " message needs a media");
		}
		if (!type.isMedia() && media != null) {
			throw new IllegalArgumentException("A " + type.msgtype() + " message cannot carry a media");
		}
	}

	/**
	 * Message sans média (signature antérieure à la prise en charge des médias).
	 */
	public OutgoingMessage(Type type, String body, String formattedBody, String replyToEventId, String threadRootId) {
		this(type, body, formattedBody, replyToEventId, threadRootId, null);
	}

	public static OutgoingMessage text(String body) {
		return new OutgoingMessage(Type.TEXT, body, null, null, null, null);
	}

	public static OutgoingMessage notice(String body) {
		return new OutgoingMessage(Type.NOTICE, body, null, null, null, null);
	}

	public static OutgoingMessage emote(String body) {
		return new OutgoingMessage(Type.EMOTE, body, null, null, null, null);
	}

	/**
	 * Image déjà téléversée. {@code body} est la légende, ou le nom du fichier.
	 */
	public static OutgoingMessage image(String body, MediaAttachment media) {
		return new OutgoingMessage(Type.IMAGE, body, null, null, null, media);
	}

	/**
	 * Fichier déjà téléversé. {@code body} est la légende, ou le nom du fichier.
	 */
	public static OutgoingMessage file(String body, MediaAttachment media) {
		return new OutgoingMessage(Type.FILE, body, null, null, null, media);
	}

	/** Son déjà téléversé. {@code body} est la légende, ou le nom du fichier. */
	public static OutgoingMessage audio(String body, MediaAttachment media) {
		return new OutgoingMessage(Type.AUDIO, body, null, null, null, media);
	}

	/** Vidéo déjà téléversée. {@code body} est la légende, ou le nom du fichier. */
	public static OutgoingMessage video(String body, MediaAttachment media) {
		return new OutgoingMessage(Type.VIDEO, body, null, null, null, media);
	}

	/**
	 * Même message avec un autre type (ex. une réponse envoyée en notice). Passer
	 * d'un type texte à un type média, ou l'inverse, est refusé : le média ne suit
	 * pas.
	 */
	public OutgoingMessage withType(Type newType) {
		return new OutgoingMessage(newType, body, formattedBody, replyToEventId, threadRootId, media);
	}

	/**
	 * Ajoute la version HTML. {@code body} reste le texte brut de repli.
	 */
	public OutgoingMessage withHtml(String html) {
		return new OutgoingMessage(type, body, html, replyToEventId, threadRootId, media);
	}

	/**
	 * Répond à un message précis ({@code m.in_reply_to}). Pour répondre à un
	 * message qui est dans un thread, ajouter aussi {@link #inThread(String)} avec
	 * la racine de ce thread, sinon la réponse sort du thread.
	 */
	public OutgoingMessage inReplyTo(String eventId) {
		return new OutgoingMessage(type, body, formattedBody, eventId, threadRootId, media);
	}

	/**
	 * Envoie le message dans le thread dont {@code rootId} est la racine. Sans
	 * {@link #inReplyTo}, les clients sans gestion des threads l'affichent comme
	 * une réponse à la racine.
	 */
	public OutgoingMessage inThread(String rootId) {
		return new OutgoingMessage(type, body, formattedBody, replyToEventId, rootId, media);
	}

	/**
	 * Échappe un texte pour l'insérer dans du HTML ({@code & < > " '}). À appliquer
	 * à tout contenu venant d'un utilisateur ou d'un système externe avant de le
	 * placer dans {@code formattedBody}.
	 */
	public static String escapeHtml(String text) {
		if (text == null) {
			return "";
		}
		StringBuilder out = new StringBuilder(text.length() + 16);
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '&' -> out.append("&amp;");
				case '<' -> out.append("&lt;");
				case '>' -> out.append("&gt;");
				case '"' -> out.append("&quot;");
				case '\'' -> out.append("&#39;");
				default -> out.append(c);
			}
		}
		return out.toString();
	}
}
