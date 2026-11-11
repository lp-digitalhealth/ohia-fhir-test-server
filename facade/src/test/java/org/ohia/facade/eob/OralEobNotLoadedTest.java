package org.ohia.facade.eob;

import static org.ohia.facade.eob.OralEobFixtures.TOKEN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** A server on which nobody has loaded the oral EOBs yet: it must say so, not pretend there are no matches. */
@SpringBootTest
@AutoConfigureMockMvc
class OralEobNotLoadedTest {

    static final Path dataDir = newTempDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        OralEobFixtures.properties(r, dataDir);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void readIs404AndNamesTheWayToLoad() throws Exception {
        mvc.perform(get("/fhir/ExplanationOfBenefit/eob-inoffice-claims-sharing").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.issue[0].details.text").value(org.hamcrest.Matchers.containsString("seed-eob")));
    }

    @Test
    void searchIsAnEmptyBundleThatSaysNothingIsLoaded() throws Exception {
        mvc.perform(get("/fhir/ExplanationOfBenefit").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.entry.length()").value(1))
                .andExpect(jsonPath("$.entry[0].search.mode").value("outcome"))
                .andExpect(jsonPath("$.entry[0].resource.issue[0].details.text").value(org.hamcrest.Matchers.containsString("No oral EOBs are loaded")));
    }

    private static Path newTempDir() {
        try {
            return Files.createTempDirectory("oral-eob-empty");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
