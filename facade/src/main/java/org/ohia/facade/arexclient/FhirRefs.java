package org.ohia.facade.arexclient;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a FHIR reference the same way whether it is written relative (Organization/abc) or absolute
 * (https://their-server/fhir/Organization/abc, with or without /_history/1). Two references to the same resource
 * must compare equal; comparing the raw text made an absolute and a relative one look like different things.
 */
public final class FhirRefs {

    private static final Pattern TYPE_AND_ID = Pattern.compile("(?:^|/)([A-Z][A-Za-z]+)/([A-Za-z0-9\\-.]{1,64})(?:/_history/[^/]+)?$");

    private FhirRefs() {
    }

    /** "Type/id" for a literal reference, whether relative or absolute; anything else (urn:uuid, fragments) unchanged. */
    public static String normalize(String reference) {
        if (reference == null) {
            return null;
        }
        String r = reference.trim();
        int cut = r.indexOf('?');
        if (cut >= 0) {
            r = r.substring(0, cut);
        }
        Matcher m = TYPE_AND_ID.matcher(r);
        return m.find() && !r.startsWith("urn:") ? m.group(1) + "/" + m.group(2) : r;
    }

    public static boolean same(String a, String b) {
        return a != null && b != null && normalize(a).equals(normalize(b));
    }
}
