package org.ohia.facade.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** With no token configured, /ode/** must refuse everything, including an empty bearer. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "facade.api-token=")
class OdeTokenFilterFailClosedTest {

    @Autowired
    MockMvc mvc;

    @Test
    void blankConfiguredTokenRefusesEmptyBearer() throws Exception {
        mvc.perform(get("/ode/health").header("Authorization", "Bearer ")).andExpect(status().isUnauthorized());
    }

    @Test
    void blankConfiguredTokenRefusesAnyToken() throws Exception {
        mvc.perform(get("/ode/health").header("Authorization", "Bearer anything")).andExpect(status().isUnauthorized());
    }
}
