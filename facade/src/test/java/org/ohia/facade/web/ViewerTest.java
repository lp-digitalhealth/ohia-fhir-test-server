package org.ohia.facade.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The viewer shows content a sender controls, so the page must be built so that content cannot run.
 * The static-analysis tests below read the page's own source and fail if an unsafe construct appears.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "facade.api-token=unit-test-token-123")
class ViewerTest {

    @Autowired
    MockMvc mvc;

    private String page(String path) throws Exception {
        return mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void thePageLoadsWithoutATokenAndHoldsNoData() throws Exception {
        String html = page("/viewer/index.html");
        assertTrue(html.contains("<title>OHIA test server</title>"));
        assertFalse(html.contains("unit-test-token-123"), "the page must never contain the token");
        assertFalse(html.contains("REF-"), "the page itself must hold no referral ids");
        assertFalse(Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matcher(html).find(), "no receipt ids");
        assertFalse(html.contains("example.org"), "no sender addresses");
    }

    /** MockMvc records a forward instead of following it; the real forward is checked against a real server. */
    @Test
    void theFolderUrlsForwardToTheIndexPage() throws Exception {
        for (String path : new String[] {"/viewer", "/viewer/"}) {
            assertEquals("/viewer/index.html", mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getForwardedUrl(), path);
        }
    }

    @Test
    void theDataBehindItStillNeedsTheToken() throws Exception {
        mvc.perform(get("/ode/receipts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/ode/receipts/00000000-0000-0000-0000-000000000000")).andExpect(status().isUnauthorized());
    }

    @Test
    void everyViewerResponseCarriesTheStrictBrowserHeaders() throws Exception {
        for (String path : new String[] {"/viewer/index.html", "/viewer/viewer.js", "/viewer/viewer.css"}) {
            mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(header().string("Content-Security-Policy", ViewerSecurityFilter.CSP))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        assertFalse(ViewerSecurityFilter.CSP.contains("unsafe-inline"));
        assertFalse(ViewerSecurityFilter.CSP.contains("unsafe-eval"));
        assertTrue(ViewerSecurityFilter.CSP.contains("script-src 'self'"));
        assertTrue(ViewerSecurityFilter.CSP.contains("frame-ancestors 'none'"));
    }

    /** The headers apply only to the viewer, so they cannot interfere with the API. */
    @Test
    void theViewerHeadersAreNotAddedToTheApi() throws Exception {
        assertEquals(null, mvc.perform(get("/internal/health")).andReturn().getResponse().getHeader("Content-Security-Policy"));
    }

    @Test
    void theHtmlHasNoInlineScriptHandlerOrStyle() throws Exception {
        String html = page("/viewer/index.html");
        assertTrue(Pattern.compile("<script[^>]*\\ssrc=\"viewer\\.js\"[^>]*></script>").matcher(html).find());
        assertFalse(Pattern.compile("<script(?![^>]*\\ssrc=)[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html).find(), "no inline <script>");
        assertFalse(Pattern.compile("\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE).matcher(html).find(), "no inline event handlers");
        assertFalse(Pattern.compile("\\sstyle\\s*=", Pattern.CASE_INSENSITIVE).matcher(html).find(), "no inline style attributes");
        assertFalse(html.toLowerCase().contains("javascript:"));
    }

    /** Control for the rule that matters most: sender-controlled text may only be inserted as text. */
    @Test
    void theScriptNeverUsesAnyApiThatParsesMarkupOrRunsStrings() throws Exception {
        String source = page("/viewer/viewer.js").replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        for (String forbidden : new String[] {"innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function", "setTimeout(\"",
                "setInterval(\"", "DOMParser", "createContextualFragment", "srcdoc", "javascript:"}) {
            assertFalse(source.contains(forbidden), "viewer.js must not use " + forbidden);
        }
        assertTrue(source.contains("textContent"), "text must be inserted with textContent");
        assertFalse(Pattern.compile("\\.setAttribute\\(\\s*['\"]on", Pattern.CASE_INSENSITIVE).matcher(source).find());
    }

    /** The send form must ask the person before anything leaves this server, and must not keep the tester's token. */
    @Test
    void theSendFormConfirmsBeforeSendingAndClearsTheTestersToken() throws Exception {
        String html = page("/viewer/index.html");
        for (String id : new String[] {"tab-received", "tab-send", "tab-sent", "filter-usecase", "send-usecase", "send-client", "send-target", "send-bearer",
                "send-button", "seed-button", "sent-list"}) {
            assertTrue(html.contains("id=\"" + id + "\""), "the page needs #" + id);
        }
        assertTrue(Pattern.compile("id=\"send-bearer\"[^>]*type=\"password\"").matcher(html).find(), "the tester's token is a password field");
        String source = page("/viewer/viewer.js");
        int confirm = source.indexOf("window.confirm(");
        int post = source.indexOf("apiPost('/ode/send'");
        assertTrue(confirm > 0 && post > confirm, "the person must confirm before the send request is made");
        assertTrue(source.contains("$('send-bearer').value = ''"), "the tester's token must be cleared after every send");
        assertFalse(source.contains("sessionStorage.setItem('ohiaSend") || source.contains("localStorage"), "the tester's token must never be stored");
    }

    @Test
    void theScriptSendsTheTokenOnlyToThisServer() throws Exception {
        String source = page("/viewer/viewer.js");
        assertFalse(Pattern.compile("fetch\\(\\s*['\"]https?://").matcher(source).find(), "only same-origin paths may be fetched");
        assertFalse(source.contains("XMLHttpRequest"));
        assertFalse(source.contains("localStorage"), "the token must live in the tab only (sessionStorage), never localStorage");
    }
}
