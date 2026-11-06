package org.ohia.facade.common;

/**
 * The target's host name does not resolve. It is a {@link ForbiddenTargetException} (we still do not contact it,
 * and a send is still refused with 403), but it is not "unsafe": the name simply does not exist, which is a
 * different thing for a person reading a receipt.
 */
public class UnresolvableTargetException extends ForbiddenTargetException {
    public UnresolvableTargetException(String message) {
        super(message);
    }
}
