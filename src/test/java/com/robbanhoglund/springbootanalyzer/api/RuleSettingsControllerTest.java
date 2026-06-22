package com.robbanhoglund.springbootanalyzer.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.robbanhoglund.springbootanalyzer.api.dto.RuleInfoDto;
import com.robbanhoglund.springbootanalyzer.application.InvalidRuleConfigException;
import com.robbanhoglund.springbootanalyzer.application.UserRuleConfigService;
import com.robbanhoglund.springbootanalyzer.error.GlobalExceptionHandler;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RuleSettingsController.class)
@Import(GlobalExceptionHandler.class)
class RuleSettingsControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private UserRuleConfigService userRuleConfigService;

    @Test
    void servesRules() throws Exception {
        given(userRuleConfigService.getAllRulesWithStatus())
                .willReturn(
                        List.of(
                                new RuleInfoDto(
                                        "SPRING_SECRET_LITERAL",
                                        "Sensitive property uses a literal value",
                                        "WARNING",
                                        "SECURITY",
                                        "NOT_NORMALLY_DETECTED",
                                        true)));

        mockMvc.perform(get("/api/settings/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules[0].ruleId").value("SPRING_SECRET_LITERAL"))
                .andExpect(jsonPath("$.rules[0].enabled").value(true));
    }

    @Test
    void savesValidatedRuleIds() throws Exception {
        mockMvc.perform(
                        put("/api/settings/rules")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "disabledRuleIds": [
                                            "SPRING_SECRET_LITERAL",
                                            "JAVA_EMPTY_CATCH_BLOCK"
                                          ]
                                        }
                                        """))
                .andExpect(status().isNoContent());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> disabledRuleIdsCaptor = ArgumentCaptor.forClass(Set.class);
        then(userRuleConfigService).should().setDisabledRuleIds(disabledRuleIdsCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(disabledRuleIdsCaptor.getValue())
                .containsExactlyInAnyOrder("SPRING_SECRET_LITERAL", "JAVA_EMPTY_CATCH_BLOCK");
    }

    @Test
    void rejectsMalformedRuleIdsBeforeSaving() throws Exception {
        mockMvc.perform(
                        put("/api/settings/rules")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "disabledRuleIds": ["../secret"]
                                        }
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"));
    }

    @Test
    void rejectsUnknownRuleIdsWithGenericClientDetail() throws Exception {
        willThrow(new InvalidRuleConfigException("Unknown rule IDs: [NO_SUCH_RULE]"))
                .given(userRuleConfigService)
                .setDisabledRuleIds(any());

        mockMvc.perform(
                        put("/api/settings/rules")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "disabledRuleIds": ["NO_SUCH_RULE"]
                                        }
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid rule configuration"))
                .andExpect(jsonPath("$.detail").value("Rule configuration request is invalid."));
    }
}
