package org.ohia.facade.common;

import java.util.Locale;

/**
 * The connectathon use cases this server can seed, send and receive. The id is what appears in URLs, in
 * the registry and in records ("uc07", "uc04a").
 */
public enum UseCase {
    UC07("uc07", "UC07 pediatric root-canal referral"),
    UC04A("uc04a", "UC04a teledentistry referral");

    private final String id;
    private final String label;

    UseCase(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    /** A request that names no use case means UC07, which is what every request meant before UC04a existed. */
    public static UseCase parseOrDefault(String text) {
        return text == null || text.isBlank() ? UC07 : parse(text);
    }

    public static UseCase parse(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        for (UseCase u : values()) {
            if (u.id.equals(t)) {
                return u;
            }
        }
        throw new BadDataException("Unknown use case '" + text + "'. Use uc07 or uc04a.");
    }
}
