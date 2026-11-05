package org.ohia.facade.common;

/** The UC07 package has not been seeded yet. Maps to HTTP 409. */
public class NotSeededException extends RuntimeException {
    public NotSeededException() {
        super("The UC07 package has not been seeded. Call POST /ode/admin/seed first.");
    }
}
