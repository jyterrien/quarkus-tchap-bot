package fr.gouv.tchap.quarkus.deployment;

import fr.gouv.tchap.quarkus.rooms.InjectedRoomNames;
import fr.gouv.tchap.quarkus.rooms.TchapRoom;
import fr.gouv.tchap.quarkus.rooms.TchapRoomName;
import fr.gouv.tchap.quarkus.rooms.TchapRoomRecorder;
import fr.gouv.tchap.quarkus.rooms.TchapRooms;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.BeanRegistrationPhaseBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.arc.processor.DotNames;
import io.quarkus.arc.processor.InjectionPointInfo;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import jakarta.inject.Singleton;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ClassType;
import org.jboss.jandex.DotName;
import org.jboss.jandex.ParameterizedType;
import org.jboss.jandex.Type;

import java.util.HashSet;
import java.util.Set;

/**
 * Enregistre un bean synthétique {@code @TchapRoomName("nom") TchapRoom} pour chaque nom
 * effectivement injecté dans l'application.
 *
 * Les noms de salons sont de la configuration RUNTIME : le build ne peut pas les lire.
 * On les déduit donc des points d'injection (même technique que les clients nommés d'autres extensions) ;
 * leur présence dans la configuration est vérifiée au démarrage par TchapRooms.
 */
class TchapRoomProcessor {

    private static final DotName TCHAP_ROOM_NAME = DotName.createSimple(TchapRoomName.class.getName());
    private static final DotName TCHAP_ROOM = DotName.createSimple(TchapRoom.class.getName());
    private static final DotName TCHAP_ROOMS = DotName.createSimple(TchapRooms.class.getName());

    /** Les classes du module runtime ne sont pas indexées automatiquement : le qualificatif doit l'être. */
    @BuildStep
    AdditionalBeanBuildItem roomNameQualifier() {
        return AdditionalBeanBuildItem.unremovableOf(TchapRoomName.class);
    }

    @BuildStep
    AdditionalBeanBuildItem roomRegistry() {
        return AdditionalBeanBuildItem.unremovableOf(TchapRooms.class);
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void roomBeans(TchapRoomRecorder recorder,
                   BeanRegistrationPhaseBuildItem registrationPhase,
                   BuildProducer<SyntheticBeanBuildItem> syntheticBeans) {

        Set<String> names = new HashSet<>();
        for (InjectionPointInfo injectionPoint : registrationPhase.getInjectionPoints()) {
            AnnotationInstance qualifier = injectionPoint.getRequiredQualifier(TCHAP_ROOM_NAME);
            if (qualifier == null) {
                continue;
            }
            // Le qualificatif sur un autre type n'a pas de bean synthétique : ArC signalera la dépendance non satisfaite
            if (TCHAP_ROOM.equals(injectedType(injectionPoint).name())) {
                names.add(qualifier.value().asString());
            }
        }

        for (String name : names) {
            syntheticBeans.produce(SyntheticBeanBuildItem.configure(TchapRoom.class)
                .scope(Singleton.class)
                .unremovable()
                .setRuntimeInit()
                .addQualifier(AnnotationInstance.builder(TchapRoomName.class).add("value", name).build())
                .addInjectionPoint(ClassType.create(TCHAP_ROOMS))
                .createWith(recorder.room(name))
                .done());
        }

        // Toujours produit (éventuellement vide) : TchapRooms l'injecte pour valider la configuration
        syntheticBeans.produce(SyntheticBeanBuildItem.configure(InjectedRoomNames.class)
            .scope(Singleton.class)
            .unremovable()
            .setRuntimeInit()
            .runtimeValue(recorder.injectedNames(names))
            .done());
    }

    /** Type réellement injecté : Instance&lt;T&gt; est déballé en T. */
    private static Type injectedType(InjectionPointInfo injectionPoint) {
        Type required = injectionPoint.getRequiredType();
        if (DotNames.INSTANCE.equals(required.name()) && required instanceof ParameterizedType) {
            return required.asParameterizedType().arguments().get(0);
        }
        return required;
    }
}
