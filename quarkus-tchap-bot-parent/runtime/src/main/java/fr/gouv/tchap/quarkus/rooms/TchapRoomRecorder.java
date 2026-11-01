package fr.gouv.tchap.quarkus.rooms;

import io.quarkus.arc.SyntheticCreationalContext;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

import java.util.Set;
import java.util.function.Function;

@Recorder
public class TchapRoomRecorder {

    public RuntimeValue<InjectedRoomNames> injectedNames(Set<String> names) {
        return new RuntimeValue<>(new InjectedRoomNames(names));
    }

    /** Fonction de création du bean synthétique @TchapRoomName(name) TchapRoom. */
    public Function<SyntheticCreationalContext<TchapRoom>, TchapRoom> room(String name) {
        return context -> context.getInjectedReference(TchapRooms.class).get(name);
    }
}
