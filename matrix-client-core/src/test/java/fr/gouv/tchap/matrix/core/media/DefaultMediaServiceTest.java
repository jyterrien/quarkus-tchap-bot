package fr.gouv.tchap.matrix.core.media;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.exception.MatrixException;
import fr.gouv.tchap.matrix.exception.MatrixServerException;
import fr.gouv.tchap.matrix.media.MediaContent;
import fr.gouv.tchap.matrix.media.MxcUri;
import fr.gouv.tchap.matrix.message.OutgoingMessage;
import fr.gouv.tchap.matrix.message.OutgoingMessage.Type;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import fr.gouv.tchap.matrix.transport.TransportRequest;
import fr.gouv.tchap.matrix.transport.TransportResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultMediaServiceTest {

	private static final String UPLOAD_OK = "{\"content_uri\":\"mxc://srv/new1\"}";
	private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, (byte) 255};

	private final HttpTransport transport = mock(HttpTransport.class);
	private final DefaultMediaService service = new DefaultMediaService(new MatrixHttpApi(transport, "tok"));
	private final List<TransportRequest> requests = new ArrayList<>();

	/** Répond à {@code send} (téléversement). */
	private void sendAnswers(String body) {
		doAnswer(inv -> {
			requests.add(inv.getArgument(0));
			return CompletableFuture.completedFuture(body);
		}).when(transport).send(any());
	}

	/** Répond à {@code execute} (téléchargement) selon le chemin demandé. */
	private void executeAnswers(Function<String, CompletionStage<TransportResponse>> byPath) {
		doAnswer(inv -> {
			TransportRequest request = inv.getArgument(0);
			requests.add(request);
			return byPath.apply(request.path());
		}).when(transport).execute(any());
	}

	private static CompletionStage<TransportResponse> ok(byte[] body, String contentType, String disposition) {
		java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
		if (contentType != null) {
			headers.put("Content-Type", contentType);
		}
		if (disposition != null) {
			headers.put("Content-Disposition", disposition);
		}
		return CompletableFuture.completedFuture(new TransportResponse(200, headers, body));
	}

	private static CompletionStage<TransportResponse> failure(int status, String errcode) {
		return CompletableFuture.failedFuture(new MatrixServerException(
				"HTTP " + status + ": {\"errcode\":\"" + errcode + "\"}", status, errcode, false));
	}

	private static Throwable failureOf(CompletionStage<?> stage) {
		return assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join()).getCause();
	}

	// ---- téléversement
	// -----------------------------------------------------------------------

	@Test
	void upload_postsTheBinaryBody_withItsContentTypeAndTheFilename() {
		sendAnswers(UPLOAD_OK);

		MxcUri uri = service.upload(PNG, "image/png", "mon graphique.png").toCompletableFuture().join();

		assertEquals("mxc://srv/new1", uri.toString());
		TransportRequest request = requests.get(0);
		assertEquals("POST", request.method());
		assertEquals("/_matrix/media/v3/upload?filename=mon+graphique.png", request.path());
		assertEquals("tok", request.accessToken());
		assertEquals("image/png", request.contentType());
		assertArrayEquals(PNG, request.body());
	}

	@Test
	void upload_withoutFilename_hasNoQueryString() {
		sendAnswers(UPLOAD_OK);

		service.upload(PNG, "image/png", null).toCompletableFuture().join();
		service.upload(PNG, "image/png", " ").toCompletableFuture().join();

		assertEquals("/_matrix/media/v3/upload", requests.get(0).path());
		assertEquals("/_matrix/media/v3/upload", requests.get(1).path());
	}

	@Test
	void upload_rejectsBadArguments_beforeAnyRequest() {
		assertThrows(IllegalArgumentException.class, () -> service.upload(null, "image/png", "a.png"));
		assertThrows(IllegalArgumentException.class, () -> service.upload(new byte[0], "image/png", "a.png"));
		assertThrows(IllegalArgumentException.class, () -> service.upload(PNG, null, "a.png"));
		assertThrows(IllegalArgumentException.class, () -> service.upload(PNG, " ", "a.png"));

		verifyNoInteractions(transport);
	}

	@Test
	void upload_failsOnAResponseWithoutAUsableContentUri() {
		for (String bad : new String[]{"{}", "{\"content_uri\":42}", "{\"content_uri\":\"http://x/y\"}",
				"pas du json"}) {
			sendAnswers(bad);

			Throwable failure = failureOf(service.upload(PNG, "image/png", "a.png"));

			assertInstanceOf(MatrixException.class, failure, bad);
		}
	}

	@Test
	void upload_tooLarge_isTheServersFinalRefusal() {
		doAnswer(inv -> CompletableFuture.failedFuture(
				new MatrixServerException("HTTP 413: {\"errcode\":\"M_TOO_LARGE\"}", 413, "M_TOO_LARGE", false)))
				.when(transport).send(any());

		MatrixServerException failure = assertInstanceOf(MatrixServerException.class,
				failureOf(service.upload(PNG, "image/png", "a.png")));

		assertEquals(413, failure.statusCode());
		assertTrue(failure.isClientError());
	}

	// ---- téléchargement
	// ----------------------------------------------------------------------

	@Test
	void download_usesTheAuthenticatedEndpointFirst() {
		executeAnswers(path -> ok(PNG, "image/png", "attachment; filename=\"chart.png\""));

		MediaContent content = service.download(new MxcUri("srv", "abc")).toCompletableFuture().join();

		assertEquals(1, requests.size());
		assertEquals("GET", requests.get(0).method());
		assertEquals("/_matrix/client/v1/media/download/srv/abc", requests.get(0).path());
		assertEquals("tok", requests.get(0).accessToken());
		assertArrayEquals(PNG, content.content());
		assertEquals("image/png", content.contentType());
		assertEquals("chart.png", content.filename());
		assertEquals(PNG.length, content.size());
	}

	@Test
	void download_fallsBackToTheLegacyEndpoint_whenTheServerDoesNotKnowTheAuthenticatedOne() {
		executeAnswers(path -> path.startsWith("/_matrix/client/v1/")
				? failure(404, "M_UNRECOGNIZED")
				: ok(PNG, "image/png", null));

		MediaContent content = service.download(new MxcUri("srv", "abc")).toCompletableFuture().join();

		assertEquals(2, requests.size());
		assertEquals("/_matrix/client/v1/media/download/srv/abc", requests.get(0).path());
		assertEquals("/_matrix/media/v3/download/srv/abc", requests.get(1).path());
		assertArrayEquals(PNG, content.content());
		assertNull(content.filename());
	}

	@Test
	void download_ofAMissingMedia_doesNotFallBack() {
		executeAnswers(path -> failure(404, "M_NOT_FOUND"));

		MatrixServerException failure = assertInstanceOf(MatrixServerException.class,
				failureOf(service.download(new MxcUri("srv", "gone"))));

		assertEquals(404, failure.statusCode());
		assertEquals(1, requests.size(), "un média absent le serait aussi sur l'ancien accès");
	}

	@Test
	void download_encodesTheServerAndTheId() {
		executeAnswers(path -> ok(PNG, null, null));

		service.download(new MxcUri("matrix.example.org:8448", "a=b_c")).toCompletableFuture().join();

		assertEquals("/_matrix/client/v1/media/download/matrix.example.org%3A8448/a%3Db_c", requests.get(0).path());
	}

	@Test
	void download_rejectsANullUri() {
		assertThrows(NullPointerException.class, () -> service.download(null));
	}

	@Test
	void download_ofAServerWithoutExecute_failsWithUnsupportedOperation() {
		HttpTransport minimal = new HttpTransport() {
			@Override
			public CompletionStage<String> get(String path, String accessToken) {
				return CompletableFuture.completedFuture("{}");
			}

			@Override
			public CompletionStage<String> put(String path, String accessToken, String jsonBody) {
				return CompletableFuture.completedFuture("{}");
			}

			@Override
			public CompletionStage<String> post(String path, String accessToken, String jsonBody) {
				return CompletableFuture.completedFuture("{}");
			}
		};
		DefaultMediaService unsupported = new DefaultMediaService(new MatrixHttpApi(minimal, "tok"));

		assertInstanceOf(UnsupportedOperationException.class,
				failureOf(unsupported.download(new MxcUri("srv", "abc"))));
	}

	// ---- nom de fichier annoncé par le serveur
	// -----------------------------------------------

	@Test
	void filenameOf_readsQuotedAndBareFilenames() {
		assertEquals("a.png", DefaultMediaService.filenameOf("attachment; filename=\"a.png\""));
		assertEquals("a.png", DefaultMediaService.filenameOf("inline; filename=a.png"));
		assertEquals("a b.png", DefaultMediaService.filenameOf("attachment; filename=\"a b.png\""));
	}

	@Test
	void filenameOf_prefersTheExtendedEncodedForm() {
		assertEquals("café.png",
				DefaultMediaService.filenameOf("attachment; filename=\"cafe.png\"; filename*=utf-8''caf%C3%A9.png"));
		assertEquals("a+b.png", DefaultMediaService.filenameOf("attachment; filename*=UTF-8''a+b.png"));
	}

	@Test
	void filenameOf_reducesAnyPathToASimpleName() {
		assertEquals("passwd", DefaultMediaService.filenameOf("attachment; filename=\"../../etc/passwd\""));
		assertEquals("evil.exe", DefaultMediaService.filenameOf("attachment; filename=\"C:\\Windows\\evil.exe\""));
		assertEquals("x.png", DefaultMediaService.filenameOf("attachment; filename*=utf-8''..%2F..%2Fx.png"));
	}

	@Test
	void filenameOf_returnsNullWhenThereIsNothingUsable() {
		assertNull(DefaultMediaService.filenameOf(null));
		assertNull(DefaultMediaService.filenameOf(""));
		assertNull(DefaultMediaService.filenameOf("attachment"));
		assertNull(DefaultMediaService.filenameOf("attachment; filename=\"..\""));
		assertNull(DefaultMediaService.filenameOf("attachment; filename=\"\""));
		assertNull(DefaultMediaService.filenameOf("attachment; filename=\"dir/\""));
	}

	// ---- téléverser et préparer le message
	// ---------------------------------------------------

	@Test
	void prepareMessage_buildsAnImageMessage_withTypeSizeAndFilename() {
		sendAnswers(UPLOAD_OK);

		OutgoingMessage message = service.prepareMessage(PNG, "image/png", "chart.png", "Mon graphique")
				.toCompletableFuture().join();

		assertEquals(Type.IMAGE, message.type());
		assertEquals("Mon graphique", message.body());
		assertEquals("mxc://srv/new1", message.media().url());
		assertEquals("image/png", message.media().mimeType());
		assertEquals((long) PNG.length, message.media().size());
		assertEquals("chart.png", message.media().filename());
	}

	@Test
	void prepareMessage_withoutCaption_usesTheFilenameAsBody() {
		sendAnswers(UPLOAD_OK);

		OutgoingMessage message = service.prepareMessage(PNG, "image/png", "chart.png", null).toCompletableFuture()
				.join();

		assertEquals("chart.png", message.body());
	}

	@Test
	void prepareMessage_withNeitherCaptionNorFilename_stillHasABody() {
		sendAnswers(UPLOAD_OK);

		OutgoingMessage message = service.prepareMessage(PNG, "application/pdf", null, " ").toCompletableFuture()
				.join();

		assertEquals(Type.FILE, message.type());
		assertFalse(message.body().isBlank());
		assertNull(message.media().filename());
	}

	@Test
	void prepareMessage_picksTheTypeFromTheContentType() {
		sendAnswers(UPLOAD_OK);

		Map<String, Type> expected = Map.of("image/jpeg", Type.IMAGE, "IMAGE/PNG", Type.IMAGE, "audio/ogg", Type.AUDIO,
				"video/mp4", Type.VIDEO, "application/pdf", Type.FILE, "text/plain", Type.FILE,
				"application/octet-stream", Type.FILE);
		expected.forEach((contentType, type) -> assertEquals(type,
				service.prepareMessage(PNG, contentType, "f", null).toCompletableFuture().join().type(), contentType));
	}

	@Test
	void prepareMessage_failsLikeUpload_whenTheUploadFails() {
		doAnswer(inv -> CompletableFuture.failedFuture(new MatrixException("Vert.x transport error: down")))
				.when(transport).send(any());

		assertInstanceOf(MatrixException.class, failureOf(service.prepareMessage(PNG, "image/png", "a.png", null)));
	}

	@Test
	void theUploadedBytes_areNotAlteredByThePreparation() {
		sendAnswers(UPLOAD_OK);

		service.prepareMessage(PNG, "image/png", "a.png", null).toCompletableFuture().join();

		ArgumentCaptor<TransportRequest> captor = ArgumentCaptor.forClass(TransportRequest.class);
		verify(transport).send(captor.capture());
		assertArrayEquals(PNG, captor.getValue().body());
		assertEquals(new String(PNG, StandardCharsets.ISO_8859_1).length(), captor.getValue().body().length);
	}
}
