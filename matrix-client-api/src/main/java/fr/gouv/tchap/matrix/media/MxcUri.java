package fr.gouv.tchap.matrix.media;

import java.util.regex.Pattern;

/**
 * Adresse d'un média sur un homeserver Matrix :
 * {@code mxc://serveur/identifiant}. Validée à la construction : les deux
 * parties finissent dans le chemin d'une requête HTTP, elles ne peuvent donc
 * contenir ni {@code /}, ni {@code ..}, ni rien d'autre que des caractères
 * sûrs.
 *
 * @param serverName
 *            nom du serveur qui héberge le média (domaine, adresse IP, avec un
 *            port éventuel)
 * @param mediaId
 *            identifiant du média sur ce serveur
 */
public record MxcUri(String serverName, String mediaId) {

	private static final String SCHEME = "mxc://";
	private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9.:\\-\\[\\]]+");
	private static final Pattern MEDIA_ID = Pattern.compile("[A-Za-z0-9._=\\-]+");

	public MxcUri {
		if (serverName == null || !SERVER_NAME.matcher(serverName).matches() || isDots(serverName)) {
			throw new IllegalArgumentException("Invalid mxc server name: " + serverName);
		}
		if (mediaId == null || !MEDIA_ID.matcher(mediaId).matches() || isDots(mediaId)) {
			throw new IllegalArgumentException("Invalid mxc media id: " + mediaId);
		}
	}

	private static boolean isDots(String segment) {
		return segment.chars().allMatch(c -> c == '.');
	}

	/**
	 * Lit {@code mxc://serveur/identifiant}.
	 *
	 * @throws IllegalArgumentException
	 *             si le texte n'a pas exactement cette forme (autre schéma, segment
	 *             en trop, paramètres, caractères interdits)
	 */
	public static MxcUri parse(String uri) {
		if (uri == null || !uri.startsWith(SCHEME)) {
			throw new IllegalArgumentException("Not an mxc:// URI: " + uri);
		}
		String rest = uri.substring(SCHEME.length());
		int slash = rest.indexOf('/');
		if (slash <= 0 || slash == rest.length() - 1) {
			throw new IllegalArgumentException("Not an mxc://server/mediaId URI: " + uri);
		}
		return new MxcUri(rest.substring(0, slash), rest.substring(slash + 1));
	}

	/** Vrai si {@code uri} est une adresse {@code mxc://} bien formée. */
	public static boolean isValid(String uri) {
		try {
			parse(uri);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	@Override
	public String toString() {
		return SCHEME + serverName + "/" + mediaId;
	}
}
