package fr.gouv.tchap.matrix.room;

import java.util.concurrent.CompletionStage;

public interface RoomService {
	CompletionStage<Void> join(String roomId);

	CompletionStage<Void> leave(String roomId);

	/**
	 * Rejoint un salon désigné par son identifiant (!id:srv) ou son alias
	 * (#nom:srv) et renvoie l'identifiant résolu. Sans effet si le compte est déjà
	 * membre.
	 */
	CompletionStage<String> resolveAndJoin(String roomIdOrAlias);
}
