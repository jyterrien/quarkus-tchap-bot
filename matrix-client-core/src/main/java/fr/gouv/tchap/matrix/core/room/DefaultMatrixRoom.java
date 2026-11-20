package fr.gouv.tchap.matrix.core.room;

import fr.gouv.tchap.matrix.media.MediaService;
import fr.gouv.tchap.matrix.message.MessageReceipt;
import fr.gouv.tchap.matrix.message.MessageService;
import fr.gouv.tchap.matrix.message.OutgoingMessage;
import fr.gouv.tchap.matrix.room.MatrixRoom;
import java.util.concurrent.CompletionStage;

/** Vue d'un MessageService restreinte à un seul salon. */
public final class DefaultMatrixRoom implements MatrixRoom {

	private final String roomId;
	private final MessageService messages;
	private final MediaService media;

	/** Salon sans accès aux médias : {@link #sendMedia} échoue. */
	public DefaultMatrixRoom(String roomId, MessageService messages) {
		this(roomId, messages, null);
	}

	public DefaultMatrixRoom(String roomId, MessageService messages, MediaService media) {
		if (roomId == null || roomId.isBlank()) {
			throw new IllegalArgumentException("roomId is required");
		}
		this.roomId = roomId;
		this.messages = messages;
		this.media = media;
	}

	@Override
	public String roomId() {
		return roomId;
	}

	@Override
	public CompletionStage<MessageReceipt> sendText(String body) {
		return messages.sendText(roomId, body);
	}

	@Override
	public CompletionStage<MessageReceipt> sendNotice(String body) {
		return messages.sendNotice(roomId, body);
	}

	@Override
	public CompletionStage<MessageReceipt> sendTextInThread(String threadRootId, String body) {
		return messages.sendTextInThread(roomId, threadRootId, body);
	}

	@Override
	public CompletionStage<MessageReceipt> sendNoticeInThread(String threadRootId, String body) {
		return messages.sendNoticeInThread(roomId, threadRootId, body);
	}

	@Override
	public CompletionStage<MessageReceipt> send(OutgoingMessage message) {
		return messages.send(roomId, message);
	}

	@Override
	public CompletionStage<MessageReceipt> edit(String eventId, OutgoingMessage newContent) {
		return messages.edit(roomId, eventId, newContent);
	}

	@Override
	public CompletionStage<MessageReceipt> react(String eventId, String key) {
		return messages.react(roomId, eventId, key);
	}

	@Override
	public CompletionStage<MessageReceipt> sendMedia(byte[] content, String contentType, String filename,
			String caption) {
		if (media == null) {
			throw new IllegalStateException("This room was created without a media service");
		}
		return media.prepareMessage(content, contentType, filename, caption).thenCompose(this::send);
	}

	@Override
	public String toString() {
		return "MatrixRoom[" + roomId + "]";
	}
}
