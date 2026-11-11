package fr.gouv.tchap.quarkus.rooms;

import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Qualifier;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Désigne un salon déclaré sous {@code quarkus.tchap.rooms."nom"}.
 *
 * <pre>{@code
 * &#64;Inject @TchapRoomName("oncall") TchapRoom oncall;
 *
 * void onSupervisorMessage(@Observes @TchapRoomName("supervisors") TchapMessage message) { ... }
 * }</pre>
 *
 * Un observer sans ce qualificatif continue de recevoir les messages de tous
 * les salons.
 */
@Qualifier
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.TYPE})
public @interface TchapRoomName {

	/** Nom du salon dans la configuration. */
	String value();

	/** Instance programmatique, pour Event.select(...) ou Instance.select(...). */
	// Pattern CDI standard : AnnotationLiteral + interface d'annotation
	final class Literal extends AnnotationLiteral<TchapRoomName> implements TchapRoomName {

		private static final long serialVersionUID = 1L;

		private final String value;

		public Literal(String value) {
			this.value = value;
		}

		@Override
		public String value() {
			return value;
		}
	}
}
