package org.ohia.facade.send;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import org.ohia.facade.common.ForbiddenTargetException;
import org.ohia.facade.common.UnresolvableTargetException;
import org.ohia.facade.config.FacadeProperties;
import org.springframework.stereotype.Component;

/**
 * Keeps the sender from being used to reach this server's own network. A send target must be an
 * https URL with no credentials in it, and every address its host resolves to must be public
 * (not loopback, private, link-local, carrier-grade NAT, multicast or unspecified). The compose
 * network names (hapi, facade, caddy, hapi-db) resolve to private addresses, so they are refused.
 *
 * <p>Known limit: the address is checked here and the connection is made afterwards by the HTTP
 * client, so a hostile DNS server could in theory change its answer in between (DNS rebinding).
 * The per-tester allow-list of URL prefixes is the stronger control and is checked first.
 */
@Component
public class SsrfGuard {

    private final boolean allowHttp;
    private final boolean allowPrivate;

    public SsrfGuard(FacadeProperties props) {
        this.allowHttp = props.send().allowHttp();
        this.allowPrivate = props.send().allowPrivateTargets();
    }

    /** Returns the validated URI, or throws ForbiddenTargetException. */
    public URI validate(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new ForbiddenTargetException("The target URL is not valid.");
        }
        String scheme = uri.getScheme();
        boolean schemeOk = "https".equalsIgnoreCase(scheme) || (allowHttp && "http".equalsIgnoreCase(scheme));
        if (!schemeOk) {
            throw new ForbiddenTargetException("The target URL must use https.");
        }
        if (uri.getUserInfo() != null) {
            throw new ForbiddenTargetException("The target URL must not contain credentials.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ForbiddenTargetException("The target URL has no host.");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new UnresolvableTargetException("The target host cannot be resolved: " + host);
        }
        if (!allowPrivate) {
            for (InetAddress a : addresses) {
                if (isDisallowed(a)) {
                    throw new ForbiddenTargetException("The target host resolves to a non-public address.");
                }
            }
        }
        return uri;
    }

    static boolean isDisallowed(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first == 0 // 0.0.0.0/8
                    || (first == 100 && second >= 64 && second <= 127) // 100.64.0.0/10 carrier-grade NAT
                    || (first == 192 && second == 0 && (b[2] & 0xff) == 0) // 192.0.0.0/24
                    || first >= 240; // reserved and broadcast
        }
        // IPv6 unique local addresses fc00::/7
        return (b[0] & 0xfe) == 0xfc;
    }
}
