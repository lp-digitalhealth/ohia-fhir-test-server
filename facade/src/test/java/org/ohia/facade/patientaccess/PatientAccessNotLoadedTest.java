package org.ohia.facade.patientaccess;

import static org.ohia.facade.patientaccess.PatientAccessFixtures.BASE;
import static org.ohia.facade.patientaccess.PatientAccessFixtures.TOKEN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** A server on which nobody has loaded the payer-side data yet: it must say so, not pretend the member has no claims. */
@SpringBootTest
@AutoConfigureMockMvc
class PatientAccessNotLoadedTest {

    static final Path dataDir = newTempDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        PatientAccessFixtures.properties(r, dataDir);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void readIs404AndNamesTheWayToLoad() throws Exception {
        mvc.perform(get(BASE + "/Patient/patient-joe-castle-payer").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.issue[0].details.text").value(Matchers.containsString("seed-patient-access")));
    }

    @Test
    void searchIsAnEmptyBundleThatSaysNothingIsLoaded() throws Exception {
        mvc.perform(get(BASE + "/Patient").param("identifier", PatientAccessFixtures.ONLINE_ID).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.entry.length()").value(1))
                .andExpect(jsonPath("$.entry[0].search.mode").value("outcome"))
                .andExpect(jsonPath("$.entry[0].resource.issue[0].details.text").value(Matchers.containsString("not loaded yet")));
    }

    private static Path newTempDir() {
        try {
            return Files.createTempDirectory("patient-access-empty");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
