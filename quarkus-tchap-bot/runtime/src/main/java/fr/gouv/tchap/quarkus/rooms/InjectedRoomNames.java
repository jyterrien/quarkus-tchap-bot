package fr.gouv.tchap.quarkus.rooms;

import java.util.Set;

/**
 * Noms de salons réellement injectés dans l'application (@Inject @TchapRoomName("...")).
 * Établi au build par le processeur de l'extension, exposé comme bean synthétique,
 * pour vérifier au démarrage que chaque nom injecté est bien configuré.
 */
public final class InjectedRoomNames {

    private final Set<String> names;

    public InjectedRoomNames(Set<String> names) {
        this.names = Set.copyOf(names);
    }

    public Set<String> names() {
        return names;
    }
}
