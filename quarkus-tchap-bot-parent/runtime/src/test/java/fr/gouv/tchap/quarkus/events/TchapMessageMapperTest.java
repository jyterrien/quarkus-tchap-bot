package fr.gouv.tchap.quarkus.events;

import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.matrix.event.MatrixEvent;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Test unitaire pur : aucun démarrage de Quarkus. */
class TchapMessageMapperTest {

	private static final String BOT = "@bot:tchap.gouv.fr";
	private static final Instant TS = Instant.parse("2026-10-31T10:00:00Z");

	private static MatrixEvent event(String type, String sender, String content) {
		return new MatrixEvent("$e1", "!room:s", sender, type, content, TS);
	}

	@Test
	void mapsRoomMessage() {
		TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);

		Optional<TchapMessage> result = mapper
				.map(event("m.room.message", "@alice:s", "{\"msgtype\":\"m.notice\",\"body\":\"salut\"}"));

		TchapMessage m = result.orElseThrow();
		assertEquals("$e1", m.eventId());
		assertEquals("!room:s", m.roomId());
		assertEquals("@alice:s", m.sender());
		assertEquals("salut", m.body());
		assertEquals("m.notice", m.messageType());
		assertEquals(TS, m.timestamp());
		assertFalse(m.encrypted());
	}

	@Test
	void ignoresOtherEventTypes() {
		TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);

		assertTrue(mapper.map(event("m.room.member", "@alice:s", "{\"membership\":\"join\"}")).isEmpty());
		assertTrue(mapper.map(event("m.room.encrypted", "@alice:s", "{}")).isEmpty());
	}

	@Test
	void ignoresOwnMessages_whenEnabled() {
		TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);

		assertTrue(mapper.map(event("m.room.message", BOT, "{\"body\":\"echo\"}")).isEmpty());
	}

	@Test
	void keepsOwnMessages_whenIgnoreSelfDisabled() {
		TchapMessageMapper mapper = new TchapMessageMapper(false, BOT);

		assertTrue(mapper.map(event("m.room.message", BOT, "{\"body\":\"echo\"}")).isPresent());
	}

	@Test
	void keepsEverything_whenBotUserIdUnknown() {
		TchapMessageMapper mapper = new TchapMessageMapper(true, (String) null);

		assertTrue(mapper.map(event("m.room.message", BOT, "{\"body\":\"echo\"}")).isPresent());
	}

	@Test
	void appliesDefaults_whenBodyAndMsgtypeAreMissing() {
		TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);

		TchapMessage m = mapper.map(event("m.room.message", "@alice:s", "{}")).orElseThrow();

		assertEquals("", m.body());
		assertEquals("m.text", m.messageType());
	}

	@Test
	void invalidContent_isDroppedWithoutThrowing() {
		TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);

		assertDoesNotThrow(() -> assertTrue(mapper.map(event("m.room.message", "@alice:s", "not json")).isEmpty()));
	}

	// ---- threads
	// ------------------------------------------------------------------------------

	private static MatrixEvent threadEvent(String eventId, String threadRootId) {
		return new MatrixEvent(eventId, "!room:s", "@alice:s", "m.room.message",
				"{\"msgtype\":\"m.text\",\"body\":\"re\"}", TS, threadRootId);
	}

	@Test
	void carriesTheThreadRoot_ofAThreadReply() {
		TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);

		TchapMessage m = mapper.map(threadEvent("$reply", "$root")).orElseThrow();

		assertEquals("$root", m.threadRootId());
		assertTrue(m.isInThread());
		assertEquals("$root", m.replyThreadId());
	}

	@Test
	void plainMessage_isNotInAThread_andRepliesOpenAThreadOnItself() {
		TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);

		TchapMessage m = mapper.map(event("m.room.message", "@alice:s", "{\"body\":\"salut\"}")).orElseThrow();

		assertNull(m.threadRootId());
		assertFalse(m.isInThread());
		assertEquals("$e1", m.replyThreadId());
	}

	@Test
	void messageConstructorWithoutThread_staysCompatible() {
		TchapMessage m = new TchapMessage("$e", "!r:s", "@u:s", "hi", TS, false, "m.text");

		assertNull(m.threadRootId());
		assertFalse(m.isInThread());
	}

	@Test
	void botUserId_canBeResolvedLate_asWhenLoggingInWithAPassword() {
		java.util.concurrent.atomic.AtomicReference<String> self = new java.util.concurrent.atomic.AtomicReference<>(
				"@bot.fr:s");
		TchapMessageMapper mapper = new TchapMessageMapper(true, self::get);
		MatrixEvent fromServerId = event("m.room.message", "@bot:s", "{\"body\":\"echo\"}");

		assertTrue(mapper.map(fromServerId).isPresent(), "identifiant du serveur pas encore connu");

		self.set("@bot:s"); // connexion faite : le serveur a donné l'identifiant réel
		assertTrue(mapper.map(fromServerId).isEmpty());
	}

	// ---- réponses
	// ----------------------------------------------------------------------------

	@Test
	void carriesTheReplyTarget_ofAReply() {
		TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);
		MatrixEvent reply = new MatrixEvent("$r", "!room:s", "@alice:s", "m.room.message",
				"{\"msgtype\":\"m.text\",\"body\":\"re\"}", TS, null, "$question");

		TchapMessage m = mapper.map(reply).orElseThrow();

		assertEquals("$question", m.replyToEventId());
		assertTrue(m.isReply());
		assertFalse(m.isInThread());
	}

	@Test
	void plainMessage_isNotAReply() {
		TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);

		TchapMessage m = mapper.map(event("m.room.message", "@alice:s", "{\"body\":\"salut\"}")).orElseThrow();

		assertNull(m.replyToEventId());
		assertFalse(m.isReply());
	}

	// ---- éditions
	// ----------------------------------------------------------------------------

	private static MatrixEvent editEvent(String sender, String content) {
		return new MatrixEvent("$edit", "!room:s", sender, "m.room.message", content, TS, null, null, "$orig");
	}

	private static final String EDIT_CONTENT = "{\"msgtype\":\"m.text\",\"body\":\"* v2\","
			+ "\"m.new_content\":{\"msgtype\":\"m.notice\",\"body\":\"v2\"},"
			+ "\"m.relates_to\":{\"rel_type\":\"m.replace\",\"event_id\":\"$orig\"}}";

	@Test
	void anEdit_isNotPublishedAsANewMessage() {
		TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);

		assertTrue(mapper.map(editEvent("@alice:s", EDIT_CONTENT)).isEmpty());
	}

	@Test
	void anEdit_isMappedToAnEditEvent_withTheNewContent() {
		TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);

		TchapMessageEdited edit = mapper.mapEdit(editEvent("@alice:s", EDIT_CONTENT)).orElseThrow();

		assertEquals("$edit", edit.eventId());
		assertEquals("$orig", edit.replacesEventId());
		assertEquals("!room:s", edit.roomId());
		assertEquals("@alice:s", edit.sender());
		assertEquals("v2", edit.body(), "le nouveau texte, sans le préfixe « * »");
		assertEquals("m.notice", edit.messageType());
		assertEquals(TS, edit.timestamp());
	}

	@Test
	void anEditWithoutNewContent_fallsBackToTheTopLevelBody() {
		TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);
		MatrixEvent ev = editEvent("@alice:s", "{\"msgtype\":\"m.text\",\"body\":\"* v2\"}");

		assertEquals("* v2", mapper.mapEdit(ev).orElseThrow().body());
	}

	@Test
	void mapEdit_ignoresPlainMessages_andOtherTypes() {
		TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);

		assertTrue(mapper.mapEdit(event("m.room.message", "@alice:s", "{\"body\":\"salut\"}")).isEmpty());
		assertTrue(mapper.mapEdit(event("m.room.member", "@alice:s", "{}")).isEmpty());
	}

	@Test
	void anEditByTheBotItself_isIgnored_whenIgnoreSelfIsOn() {
		assertTrue(new TchapMessageMapper(true, BOT).mapEdit(editEvent(BOT, EDIT_CONTENT)).isEmpty());
		assertTrue(new TchapMessageMapper(false, BOT).mapEdit(editEvent(BOT, EDIT_CONTENT)).isPresent());
	}

	@Test
	void anEditWithUnparsableContent_isDropped() {
		assertTrue(new TchapMessageMapper(true, BOT).mapEdit(editEvent("@alice:s", "{pas du json")).isEmpty());
	}

	// ---- réactions
	// ---------------------------------------------------------------------------

	private static MatrixEvent reactionEvent(String sender, String content) {
		return new MatrixEvent("$react", "!room:s", sender, "m.reaction", content, TS);
	}

	private static final String REACTION_CONTENT = "{\"m.relates_to\":{\"rel_type\":\"m.annotation\","
			+ "\"event_id\":\"$orig\",\"key\":\"👍\"}}";

	@Test
	void aReaction_isMappedToAReactionEvent() {
		TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);

		TchapReaction reaction = mapper.mapReaction(reactionEvent("@alice:s", REACTION_CONTENT)).orElseThrow();

		assertEquals("$react", reaction.eventId());
		assertEquals("$orig", reaction.reactedEventId());
		assertEquals("👍", reaction.key());
		assertEquals("@alice:s", reaction.sender());
		assertEquals("!room:s", reaction.roomId());
		assertEquals(TS, reaction.timestamp());
	}

	@Test
	void aReaction_isNeverPublishedAsAMessageOrAnEdit() {
		TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);
		MatrixEvent ev = reactionEvent("@alice:s", REACTION_CONTENT);

		assertTrue(mapper.map(ev).isEmpty());
		assertTrue(mapper.mapEdit(ev).isEmpty());
	}

	@Test
	void aReactionByTheBotItself_isIgnored_whenIgnoreSelfIsOn() {
		assertTrue(new TchapMessageMapper(true, BOT).mapReaction(reactionEvent(BOT, REACTION_CONTENT)).isEmpty());
		assertTrue(new TchapMessageMapper(false, BOT).mapReaction(reactionEvent(BOT, REACTION_CONTENT)).isPresent());
	}

	@Test
	void malformedReactions_areIgnored() {
		TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);

		assertTrue(mapper.mapReaction(reactionEvent("@a:s", "{}")).isEmpty());
		assertTrue(mapper
				.mapReaction(
						reactionEvent("@a:s", "{\"m.relates_to\":{\"rel_type\":\"m.annotation\",\"event_id\":\"$o\"}}"))
				.isEmpty(), "sans clé");
		assertTrue(mapper
				.mapReaction(reactionEvent("@a:s", "{\"m.relates_to\":{\"rel_type\":\"m.annotation\",\"key\":\"👍\"}}"))
				.isEmpty(), "sans cible");
		assertTrue(
				mapper.mapReaction(reactionEvent("@a:s",
						"{\"m.relates_to\":{\"rel_type\":\"m.replace\",\"event_id\":\"$o\",\"key\":\"x\"}}")).isEmpty(),
				"ce n'est pas une annotation");
		assertTrue(mapper.mapReaction(reactionEvent("@a:s", "{pas du json")).isEmpty());
	}

	@Test
	void mapReaction_ignoresMessages() {
		assertTrue(new TchapMessageMapper(true, BOT)
				.mapReaction(event("m.room.message", "@alice:s", "{\"body\":\"x\"}")).isEmpty());
	}

	// ---- médias
	// ------------------------------------------------------------------------------

	private static MatrixEvent mediaEvent(String content) {
		return new MatrixEvent("$media", "!room:s", "@alice:s", "m.room.message", content, TS);
	}

	@Test
	void anImageMessage_carriesItsMedia() {
		TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);

		TchapMessage m = mapper.map(mediaEvent("{\"msgtype\":\"m.image\",\"body\":\"graphique.png\","
				+ "\"url\":\"mxc://srv/abc\",\"filename\":\"chart.png\","
				+ "\"info\":{\"mimetype\":\"image/png\",\"size\":18432,\"w\":800,\"h\":600}}")).orElseThrow();

		assertTrue(m.hasMedia());
		assertEquals("m.image", m.messageType());
		assertEquals("graphique.png", m.body());
		assertEquals("mxc://srv/abc", m.media().url());
		assertEquals("image/png", m.media().mimeType());
		assertEquals(18_432L, m.media().size());
		assertEquals("chart.png", m.media().filename());
		assertEquals("srv", m.media().uri().serverName());
	}

	@Test
	void everyMediaType_isRecognised() {
		TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);

		for (String type : new String[]{"m.image", "m.file", "m.audio", "m.video"}) {
			TchapMessage m = mapper
					.map(mediaEvent("{\"msgtype\":\"" + type + "\",\"body\":\"x\",\"url\":\"mxc://srv/abc\"}"))
					.orElseThrow();

			assertTrue(m.hasMedia(), type);
		}
	}

	@Test
	void aMediaWithoutInfo_isStillUsable() {
		TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);

		TchapMessage m = mapper
				.map(mediaEvent("{\"msgtype\":\"m.file\",\"body\":\"notes.pdf\"," + "\"url\":\"mxc://srv/abc\"}"))
				.orElseThrow();

		assertNull(m.media().mimeType());
		assertNull(m.media().size());
		assertNull(m.media().filename());
	}

	@Test
	void anEncryptedMedia_isNotExposed() {
		TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);

		TchapMessage m = mapper.map(mediaEvent("{\"msgtype\":\"m.image\",\"body\":\"photo.png\","
				+ "\"file\":{\"url\":\"mxc://srv/abc\",\"key\":{},\"iv\":\"x\"}}")).orElseThrow();

		assertFalse(m.hasMedia(), "le média chiffré n'est pas lisible sans E2EE");
		assertEquals("photo.png", m.body(), "le message lui-même reste publié");
	}

	@Test
	void anInvalidMediaUrl_isIgnored_butTheMessageIsStillPublished() {
		TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);

		for (String url : new String[]{"\"http://evil/x\"", "\"mxc://srv/../etc\"", "42", "null", "\"\""}) {
			TchapMessage m = mapper.map(mediaEvent("{\"msgtype\":\"m.image\",\"body\":\"x\",\"url\":" + url + "}"))
					.orElseThrow();

			assertFalse(m.hasMedia(), url);
		}
	}

	@Test
	void textMessages_haveNoMedia_evenWithAUrlField() {
		TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);

		TchapMessage m = mapper
				.map(mediaEvent(
						"{\"msgtype\":\"m.text\",\"body\":\"voir mxc://srv/abc\"," + "\"url\":\"mxc://srv/abc\"}"))
				.orElseThrow();

		assertFalse(m.hasMedia());
	}

	@Test
	void malformedInfo_isIgnored() {
		TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);

		TchapMessage m = mapper.map(mediaEvent("{\"msgtype\":\"m.image\",\"body\":\"x\",\"url\":\"mxc://srv/abc\","
				+ "\"info\":{\"mimetype\":42,\"size\":-5},\"filename\":\" \"}")).orElseThrow();

		assertTrue(m.hasMedia());
		assertNull(m.media().mimeType());
		assertNull(m.media().size());
		assertNull(m.media().filename());
	}
}
