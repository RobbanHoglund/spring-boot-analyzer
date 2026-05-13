package com.robbanhoglund.springbootanalyzer.api.dto;

import java.util.List;

public record RulesConfigResponse(List<RuleInfoDto> rules) {}
