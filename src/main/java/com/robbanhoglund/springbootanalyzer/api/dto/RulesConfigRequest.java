package com.robbanhoglund.springbootanalyzer.api.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record RulesConfigRequest(
        @Size(max = 500, message = "disabledRuleIds must contain 500 entries or fewer")
                List<
                                @Pattern(
                                        regexp = "[A-Z0-9_]{1,128}",
                                        message =
                                                "ruleId must contain only uppercase letters,"
                                                        + " numbers, and underscores")
                                String>
                        disabledRuleIds) {}
