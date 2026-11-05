package org.ohia.facade.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "facade.api-token=unit-test-token-123")
class OdeTokenFilterTest {

    @Autowired
    MockMvc mvc;

    @Test
    void odeWithoutTokenIs401WithChallenge() throws Exception {
        mvc.perform(get("/ode/health")).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
    }

    @Test
    void odeWithWrongTokenIs401() throws Exception {
        mvc.perform(get("/ode/health").header("Authorization", "Bearer nope")).andExpect(status().isUnauthorized());
    }

    @Test
    void odeWithTokenPlusExtraCharacterIs401() throws Exception {
        mvc.perform(get("/ode/health").header("Authorization", "Bearer unit-test-token-123X"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void odeWithLowercaseSchemeIs401() throws Exception {
        mvc.perform(get("/ode/health").header("Authorization", "bearer unit-test-token-123"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void odeWithRightTokenIs200() throws Exception {
        mvc.perform(get("/ode/health").header("Authorization", "Bearer unit-test-token-123"))
                .andExpect(status().isOk());
    }

    @Test
    void internalHealthNeedsNoToken() throws Exception {
        mvc.perform(get("/internal/health")).andExpect(status().isOk());
    }
}
