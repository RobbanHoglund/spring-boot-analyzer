package com.robbanhoglund.springbootanalyzer.api.dto;

import java.util.List;

public record RulesConfigRequest(List<String> disabledRuleIds) {}
