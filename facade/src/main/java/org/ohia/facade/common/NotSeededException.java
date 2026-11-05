package org.ohia.facade.common;

/** A use case's package has not been seeded yet. Maps to HTTP 409. */
public class NotSeededException extends RuntimeException {
    public NotSeededException() {
        this(UseCase.UC07);
    }

    public NotSeededException(UseCase useCase) {
        super("The " + useCase.label() + " package has not been seeded. Call POST /ode/admin/seed?useCase=" + useCase.id() + " first.");
    }
}
