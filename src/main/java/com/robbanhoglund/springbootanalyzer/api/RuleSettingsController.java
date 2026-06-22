package com.robbanhoglund.springbootanalyzer.api;

import com.robbanhoglund.springbootanalyzer.api.dto.RulesConfigRequest;
import com.robbanhoglund.springbootanalyzer.api.dto.RulesConfigResponse;
import com.robbanhoglund.springbootanalyzer.application.UserRuleConfigService;
import jakarta.validation.Valid;
import java.util.Collections;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class RuleSettingsController {

    private final UserRuleConfigService userRuleConfigService;

    public RuleSettingsController(UserRuleConfigService userRuleConfigService) {
        this.userRuleConfigService = userRuleConfigService;
    }

    @GetMapping("/rules")
    public RulesConfigResponse getRules() {
        return new RulesConfigResponse(userRuleConfigService.getAllRulesWithStatus());
    }

    @PutMapping("/rules")
    public ResponseEntity<Void> saveRules(@Valid @RequestBody RulesConfigRequest request) {
        Set<String> disabled =
                request.disabledRuleIds() == null
                        ? Collections.emptySet()
                        : Set.copyOf(request.disabledRuleIds());
        userRuleConfigService.setDisabledRuleIds(disabled);
        return ResponseEntity.noContent().build();
    }
}
