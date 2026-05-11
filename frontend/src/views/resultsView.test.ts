import { beforeEach, describe, expect, it, vi } from 'vitest';

import { renderResultsView, type ResultsViewActions, type ResultsViewState } from './resultsView';
import type { AnalyzeRepositoryResponse, Finding } from '../types';

function defaultState(): ResultsViewState {
  return {
    findingsSeverity: 'ALL',
    findingsCategory: 'ALL',
    findingsRuntimeDetection: 'ALL',
    findingsConfidence: 'ALL',
    findingsText: '',
    findingsExpanded: false,
    findingsGrouped: true,
    configurationSearch: '',
    configurationFocus: 'ALL',
    configurationProfile: 'ALL',
    configurationSource: 'ALL',
    configurationKind: 'ALL',
    configurationSensitiveOnly: false,
    configurationView: 'flat',
    configurationChangedOnly: false,
    configurationExpanded: false,
    configurationExpandedRowKey: null,
    findingsSort: { key: 'severity', direction: 'desc' },
    inboundSort: { key: 'path', direction: 'asc' },
    outboundSort: { key: 'host', direction: 'asc' },
    configuredUrlsSort: { key: 'property', direction: 'asc' },
    configurationSort: { key: 'property', direction: 'asc' },
    componentsSort: { key: 'class', direction: 'asc' },
    dependenciesSort: { key: 'group', direction: 'asc' },
    componentType: 'ALL',
    componentText: '',
    componentsExpanded: false,
    dependencyText: '',
    resolvedDependencyConfiguration: 'ALL',
    resolvedDependencyDirectOnly: false,
    rawJsonExpanded: false,
    httpInboundExpanded: false,
    httpOutboundExpanded: false,
    httpConfiguredExpanded: false,
    httpActuatorExpanded: false,
    codeModal: {
      open: false,
      title: '',
      summary: '',
      ruleType: '',
      target: '—',
      severity: 'INFO',
      category: 'MAINTAINABILITY',
      confidence: 'MEDIUM',
      runtimeDetection: 'NOT_NORMALLY_DETECTED',
      isPropertySource: false,
      analysisId: null,
      occurrences: [],
      selectedOccurrenceIndex: 0,
      loading: false,
      errorMessage: '',
      snippet: null,
      returnFocusId: null
    }
  };
}

function defaultActions(overrides: Partial<ResultsViewActions> = {}): ResultsViewActions {
  const noop = (..._args: Array<unknown>) => undefined;
  return {
    onFindingsSeverityChange: noop,
    onFindingsCategoryChange: noop,
    onFindingsRuntimeDetectionChange: noop,
    onFindingsConfidenceChange: noop,
    onFindingsTextChange: noop,
    onToggleFindingsExpanded: noop,
    onFindingsGroupedChange: noop,
    onConfigurationSearchChange: noop,
    onConfigurationFocusChange: noop,
    onConfigurationProfileChange: noop,
    onConfigurationSourceChange: noop,
    onConfigurationKindChange: noop,
    onConfigurationSensitiveOnlyChange: noop,
    onConfigurationViewChange: noop,
    onConfigurationChangedOnlyChange: noop,
    onToggleConfigurationExpanded: noop,
    onToggleConfigurationRow: noop,
    onSetFindingsSort: noop,
    onSetInboundSort: noop,
    onSetOutboundSort: noop,
    onSetConfiguredUrlsSort: noop,
    onSetConfigurationSort: noop,
    onSetComponentsSort: noop,
    onSetDependenciesSort: noop,
    onComponentTypeChange: noop,
    onComponentTextChange: noop,
    onToggleComponentsExpanded: noop,
    onDependencyTextChange: noop,
    onResolvedDependencyConfigurationChange: noop,
    onResolvedDependencyDirectOnlyChange: noop,
    onRawJsonExpandedChange: noop,
    onToggleHttpInboundExpanded: noop,
    onToggleHttpOutboundExpanded: noop,
    onToggleHttpConfiguredExpanded: noop,
    onToggleHttpActuatorExpanded: noop,
    onOpenFindingCode: noop,
    onOpenPropertySource: noop,
    onOpenComponentSource: noop,
    onOpenHttpSource: noop,
    onCloseFindingCode: noop,
    onSelectFindingCodeOccurrence: noop,
    ...overrides
  };
}

function baseFinding(overrides: Partial<Finding> = {}): Finding {
  return {
    severity: 'WARNING',
    message: 'Exception is caught but the catch block is empty.',
    shortMessage: 'Exception is caught but the catch block is empty.',
    title: 'Empty catch block',
    ruleId: 'JAVA_EMPTY_CATCH_BLOCK',
    category: 'EXCEPTION_HANDLING',
    confidence: 'HIGH',
    runtimeDetection: 'NOT_NORMALLY_DETECTED',
    sourceFile: 'src/main/java/com/example/Demo.java',
    line: 3,
    target: 'Demo#run',
    primaryLocation: {
      filePath: 'src/main/java/com/example/Demo.java',
      startLine: 3,
      endLine: 5,
      language: 'java'
    },
    highlightRanges: [{ startLine: 3, endLine: 5, kind: 'issue' }],
    ...overrides
  };
}

function baseResult(findings: Finding[]): AnalyzeRepositoryResponse {
  return {
    repositoryUrl: 'https://github.com/example/demo.git',
    workspaceId: 'analysis-1',
    analysisId: 'analysis-1',
    commitSha: 'abc123',
    findings,
    runtimeStackAnalysis: {
      springBootVersion: '3.5.13',
      javaVersion: '25',
      webStack: 'SERVLET_MVC',
      webStackReason: 'Static servlet signals',
      virtualThreads: {
        enabledByProperty: false,
        javaVersionCompatible: true,
        explicitVirtualThreadApiUsage: false,
        scheduledWorkDetected: false,
        keepAliveConfigured: false,
        summary: 'No virtual thread signal was detected.',
        evidence: []
      },
      mainClass: 'com.example.demo.DemoApplication'
    },
    httpSurfaceAnalysis: {
      summary: {
        inboundEndpointCount: 2,
        outboundEndpointCount: 1,
        actuatorExposureCount: 1,
        externalHosts: ['api.example.com']
      },
      inboundEndpoints: [],
      outboundEndpoints: [],
      configuredUrls: [],
      actuatorExposures: [{ propertyName: 'management.endpoints.web.exposure.include', value: 'health,info' }]
    },
    configurationAnalysis: {
      files: [],
      properties: [
        {
          name: 'spring.datasource.url',
          value: 'jdbc:postgresql://localhost:5432/demo',
          sourceFile: 'src/main/resources/application.properties',
          line: 3,
          profile: 'default',
          kind: 'SPRING_BOOT'
        },
        {
          name: 'spring.datasource.url',
          value: 'jdbc:h2:mem:testdb',
          sourceFile: 'src/test/resources/application-test.properties',
          line: 3,
          profile: 'test',
          kind: 'SPRING_BOOT'
        }
      ],
      codeReferences: [],
      configurationPropertiesClasses: [],
      summary: {
        configuredPropertyCount: 2,
        knownSpringBootPropertyCount: 2,
        customPropertyCount: 0,
        unknownPropertyCount: 0,
        codeReferenceCount: 0,
        sensitiveValueCount: 1,
        profiles: ['default', 'test']
      }
    },
    detectedComponents: [
      {
        fullyQualifiedClassName: 'com.example.demo.SecurityConfig',
        componentType: 'CONFIGURATION',
        annotationNames: ['EnableWebSecurity']
      },
      {
        fullyQualifiedClassName: 'com.example.demo.OrderRepository',
        componentType: 'REPOSITORY',
        annotationNames: ['Repository']
      }
    ],
    dependencies: [
      'org.springframework.boot:spring-boot-starter-web',
      'org.springframework.boot:spring-boot-starter-security',
      'org.springframework.boot:spring-boot-starter-actuator',
      'org.springframework.boot:spring-boot-starter-jdbc',
      'org.flywaydb:flyway-core',
      'org.postgresql:postgresql',
      'com.h2database:h2'
    ]
  };
}

describe('renderResultsView findings UI', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } });
  });

  it('renders a compact grouped finding summary with details and code actions', () => {
    const onOpenFindingCode = vi.fn();
    const first = baseFinding();
    const second = baseFinding({
      sourceFile: 'src/main/java/com/example/OtherDemo.java',
      line: 19,
      primaryLocation: {
        filePath: 'src/main/java/com/example/OtherDemo.java',
        startLine: 19,
        endLine: 21,
        language: 'java'
      },
      target: 'OtherDemo#run'
    });

    const view = renderResultsView(
      baseResult([first, second]),
      defaultState(),
      defaultActions({ onOpenFindingCode })
    );
    document.body.appendChild(view);

    expect(document.querySelectorAll('.findings-table thead th')).toHaveLength(5);
    expect(document.querySelector('.finding-summary-title')?.textContent).toBe('Empty catch block');
    expect(document.querySelector('.finding-summary-count')?.textContent).toContain('2 occurrences');
    expect(document.querySelector('.finding-summary-target')?.textContent).toBe('Multiple methods');
    expect(document.querySelector('.finding-summary-kicker')?.textContent).toContain('Grouped pattern');

    const detailsButton = document.querySelector('.finding-expand-button') as HTMLButtonElement;
    detailsButton.click();

    expect(detailsButton.getAttribute('aria-expanded')).toBe('true');
    expect(document.querySelector('.finding-occurrence-code-button')).not.toBeNull();

    (document.querySelectorAll('.finding-occurrence-code-button')[1] as HTMLButtonElement).click();
    expect(onOpenFindingCode).toHaveBeenCalledWith(
      expect.objectContaining({ ruleId: 'JAVA_EMPTY_CATCH_BLOCK' }),
      expect.any(Array),
      expect.stringContaining('finding-code-'),
      1
    );
  });

  it('keeps the row-level View code action for grouped findings when any occurrence has a source location', () => {
    const unresolvedRepresentative = baseFinding({
      sourceFile: undefined,
      line: null,
      primaryLocation: undefined,
      location: 'Source reference',
      target: 'ParserA#parse'
    });
    const locatedOccurrence = baseFinding({
      sourceFile: 'src/main/java/com/example/ParserB.java',
      line: 18,
      primaryLocation: {
        filePath: 'src/main/java/com/example/ParserB.java',
        startLine: 18,
        endLine: 20,
        language: 'java'
      },
      target: 'ParserB#parse'
    });

    const view = renderResultsView(baseResult([unresolvedRepresentative, locatedOccurrence]), defaultState(), defaultActions());
    document.body.appendChild(view);

    expect(document.querySelector('.finding-code-button')).not.toBeNull();
  });

  it('does not group unrelated findings and never renders question-mark sort indicators', () => {
    const unrelated = baseFinding({
      title: 'Raw exception message exposed through HTTP response',
      ruleId: 'SPRING_RAW_EXCEPTION_MESSAGE_HTTP',
      category: 'SECURITY',
      severity: 'WARNING',
      message: 'HTTP response appears to include a raw exception message.',
      shortMessage: 'HTTP response appears to include a raw exception message.',
      target: 'ApiController#get',
      sourceFile: 'src/main/java/com/example/ApiController.java',
      line: 22,
      primaryLocation: {
        filePath: 'src/main/java/com/example/ApiController.java',
        startLine: 22,
        endLine: 24,
        language: 'java'
      }
    });

    const view = renderResultsView(
      baseResult([baseFinding(), unrelated]),
      defaultState(),
      defaultActions()
    );
    document.body.appendChild(view);

    expect(document.querySelectorAll('.finding-summary-row')).toHaveLength(2);
    const sortTexts = [...document.querySelectorAll('.table-sort-button')].map((node) => node.textContent ?? '');
    expect(sortTexts.some((text) => text.includes('?'))).toBe(false);
  });

  it('groups similar findings across severity variants and uses the highest severity in the group', () => {
    const infoFinding = baseFinding({
      severity: 'INFO',
      confidence: 'MEDIUM',
      title: 'Exception is swallowed and replaced with fallback',
      ruleId: 'SPRING_SWALLOWED_EXCEPTION_FALLBACK',
      message: 'Exception is caught and replaced with a fallback result without visible logging or propagation.',
      shortMessage: 'Exception is caught and replaced with a fallback result without visible logging or propagation.',
      target: 'Helper#loadOne',
      sourceFile: 'src/main/java/com/example/Helper.java',
      line: 10,
      primaryLocation: {
        filePath: 'src/main/java/com/example/Helper.java',
        startLine: 10,
        endLine: 12,
        language: 'java'
      }
    });
    const warningFinding = baseFinding({
      title: 'Exception is swallowed and replaced with fallback',
      ruleId: 'SPRING_SWALLOWED_EXCEPTION_FALLBACK',
      message: 'Exception is caught and replaced with a fallback result without visible logging or propagation.',
      shortMessage: 'Exception is caught and replaced with a fallback result without visible logging or propagation.',
      target: 'Helper#loadTwo',
      sourceFile: 'src/main/java/com/example/HelperTwo.java',
      line: 21,
      primaryLocation: {
        filePath: 'src/main/java/com/example/HelperTwo.java',
        startLine: 21,
        endLine: 23,
        language: 'java'
      }
    });

    const view = renderResultsView(baseResult([infoFinding, warningFinding]), defaultState(), defaultActions());
    document.body.appendChild(view);

    expect(document.querySelectorAll('.finding-summary-row')).toHaveLength(1);
    expect(document.querySelector('.finding-summary-count')?.textContent).toContain('2 occurrences');
    expect(document.querySelector('.finding-summary-row td:first-child .badge')?.textContent).toBe('WARNING');

    const detailsButton = document.querySelector('.finding-expand-button') as HTMLButtonElement;
    detailsButton.click();
    expect(document.querySelector('.finding-detail-badges')?.textContent).toContain('WARNING');
    expect(document.querySelector('.finding-detail-badges')?.textContent).toContain('Mixed severity');
  });

  it('deduplicates and ranks top concerns by finding rule', () => {
    const rawHttp = baseFinding({
      title: 'Raw exception message exposed through HTTP response',
      ruleId: 'SPRING_RAW_EXCEPTION_MESSAGE_HTTP',
      category: 'SECURITY',
      severity: 'WARNING',
      message: 'HTTP response appears to include a raw exception message.',
      shortMessage: 'HTTP response appears to include a raw exception message.',
      target: 'ApiController#get'
    });
    const emptyCatchA = baseFinding({
      title: 'Empty catch block',
      ruleId: 'JAVA_EMPTY_CATCH_BLOCK',
      target: 'ParserA#parse'
    });
    const emptyCatchB = baseFinding({
      title: 'Empty catch block',
      ruleId: 'JAVA_EMPTY_CATCH_BLOCK',
      target: 'ParserB#parse',
      sourceFile: 'src/main/java/com/example/ParserB.java',
      line: 18,
      primaryLocation: {
        filePath: 'src/main/java/com/example/ParserB.java',
        startLine: 18,
        endLine: 20,
        language: 'java'
      }
    });

    const view = renderResultsView(baseResult([emptyCatchA, rawHttp, emptyCatchB]), defaultState(), defaultActions());
    document.body.appendChild(view);

    const topConcernNodes = [...document.querySelectorAll('.top-concern-link')] as HTMLAnchorElement[];
    const topConcernLinks = topConcernNodes.map((node) => node.textContent?.trim());
    expect(topConcernLinks.filter((text) => text === 'Empty catch block')).toHaveLength(1);
    expect(topConcernLinks[0]).toBe('Raw exception message exposed through HTTP response');
    expect(document.querySelector('.top-concern-item:last-child .finding-summary-count')?.textContent).toContain('2 occurrences');
    const firstConcernTarget = topConcernNodes[0].getAttribute('href');
    expect(firstConcernTarget).toMatch(/^#finding-concern-/);
    expect(document.querySelector(firstConcernTarget as string)?.querySelector('.finding-summary-title')?.textContent)
      .toBe('Raw exception message exposed through HTTP response');
  });

  it('renders overview summaries and updated runtime-detection wording', () => {
    const weakSecret = baseFinding({
      title: 'Secret placeholder has a weak default value',
      ruleId: 'SPRING_SECRET_WEAK_PLACEHOLDER_DEFAULT',
      category: 'SECURITY',
      severity: 'WARNING',
      message: 'A secret placeholder with a weak default can silently fall back to an insecure value when the environment variable is missing.',
      shortMessage: 'A secret placeholder with a weak default can silently fall back to an insecure value when the environment variable is missing.',
      target: 'admin.security.password'
    });

    const view = renderResultsView(baseResult([baseFinding(), weakSecret]), defaultState(), defaultActions());
    document.body.appendChild(view);

    expect(document.body.textContent).toContain('Top concerns');
    expect(document.body.textContent).toContain('Security posture');
    expect(document.body.textContent).toContain('Persistence');
    expect(document.body.textContent).toContain('Errors are reserved for analyzer failures');
    expect(document.body.textContent).toContain('Warnings are prioritized static review items.');
    expect(document.body.textContent).toContain('Detected statically');
    expect(document.body.textContent).toContain('Depends on active profile');
    expect(document.body.textContent).toContain('Requires runtime verification');
  });

  it('marks heuristic findings for manual review and avoids fake line 1 locations', () => {
    const heuristicFinding = baseFinding({
      title: 'Referenced property is not configured',
      ruleId: 'CONFIG_CODE_REFERENCE_MISSING',
      category: 'CONFIGURATION',
      severity: 'WARNING',
      message: 'Property is referenced in code but no matching configured property was found in scanned files: demo.value',
      shortMessage: 'Property is referenced in code but no matching configured property was found in scanned files: demo.value',
      sourceFile: 'src/main/java/com/example/ConfigReader.java',
      line: null,
      primaryLocation: undefined
    });

    const view = renderResultsView(baseResult([heuristicFinding]), defaultState(), defaultActions());
    document.body.appendChild(view);

    expect(document.body.textContent).toContain('Review manually');
    expect(document.querySelector('.finding-location-path')?.textContent).toBe('src/main/java/com/example/ConfigReader.java');
    expect(document.body.textContent).not.toContain(':1');
    expect(document.querySelector('.finding-location-meta')?.textContent).toContain('Exact line not resolved statically');
  });

  it('replaces vague analyzer titles with a specific UI title and structured location meta', () => {
    const vagueFinding = baseFinding({
      title: undefined,
      ruleId: undefined,
      rule: undefined,
      category: 'MAINTAINABILITY',
      message: 'Detected component appears outside the main application package. This may cause component scanning issues.',
      shortMessage: 'Detected component appears outside the main application package. This may cause component scanning issues.',
      target: 'Multiple targets',
      sourceFile: 'src/main/java/com/example/baddesigned/BadDesigned.java',
      line: 12,
      primaryLocation: {
        filePath: 'src/main/java/com/example/baddesigned/BadDesigned.java',
        startLine: 12,
        endLine: 12,
        language: 'java'
      }
    });

    const view = renderResultsView(baseResult([vagueFinding]), defaultState(), defaultActions());
    document.body.appendChild(view);

    expect(document.querySelector('.finding-summary-title')?.textContent).toBe('Component outside main package');
    expect(document.querySelector('.finding-location-meta')?.textContent).toContain('Line 12');
  });

  it('renders HTTP surface tables with specialized layouts and unclipped technical text', () => {
    const result = baseResult([baseFinding()]);
    result.httpSurfaceAnalysis = {
      summary: {
        inboundEndpointCount: 1,
        outboundEndpointCount: 1,
        configuredUrlCount: 1,
        actuatorExposureCount: 1,
        externalHosts: ['plugins.gradle.org']
      },
      inboundEndpoints: [
        {
          httpMethod: 'POST',
          path: '/api/analyses/{analysisId}/source-snippet',
          controllerClass: 'com.robbanhoglund.springbootanalyzer.api.AnalysisController',
          handlerMethod: 'sourceSnippet',
          sourceFile: 'src/main/java/com/robbanhoglund/springbootanalyzer/api/AnalysisController.java',
          line: 144,
          parameters: ['@PathVariable analysisId', '@RequestParam path', '@RequestParam startLine']
        }
      ],
      outboundEndpoints: [
        {
          clientType: 'HttpClient',
          method: 'POST',
          fullUrlPreview: 'https://example.invalid/bootstrap',
          urlOrTemplate: 'https://example.invalid/bootstrap',
          host: 'example.invalid',
          sourceFile: 'src/main/java/com/example/baddesigned/BadDesigned.java',
          line: 57,
          configurationPropertyName: 'resend.base-url'
        }
      ],
      configuredUrls: [
        {
          propertyName: 'analyzer.gradle.plugin-resolution-bridge.repository-urls',
          value: 'https://plugins.gradle.org/m2/,https://repo.maven.apache.org/maven2/',
          host: 'plugins.gradle.org',
          profile: 'default',
          sourceFile: 'src/main/resources/application.properties',
          line: 78,
          kind: 'HTTP_URL'
        }
      ],
      actuatorExposures: [
        {
          propertyName: 'management.endpoints.web.exposure.include',
          value: 'health,info',
          profile: 'default',
          sourceFile: 'src/main/resources/application.properties',
          line: 4,
          exposedEndpoints: ['health', 'info']
        }
      ]
    };

    const view = renderResultsView(result, defaultState(), defaultActions());
    document.body.appendChild(view);

    expect(document.querySelector('.http-table-inbound')).not.toBeNull();
    expect(document.querySelector('.http-table-outbound')).not.toBeNull();
    expect(document.querySelector('.http-table-configured')).not.toBeNull();
    expect(document.querySelector('.http-table-actuator')).not.toBeNull();
    expect(document.querySelector('.http-cell-controller .cell-technical-wrap')?.textContent)
      .toBe('com.robbanhoglund.springbootanalyzer.api.AnalysisController');
    expect(document.querySelector('.http-cell-source .cell-technical-wrap')?.textContent)
      .toContain('src/main/java/com/robbanhoglund/springbootanalyzer/api/AnalysisController.java:144');
    expect(document.querySelector('.http-cell-value .cell-technical-wrap')?.textContent)
      .toContain('https://plugins.gradle.org/m2/');
  });

  it('assigns stable ids to results filter controls so focus can be restored across rerenders', () => {
    const view = renderResultsView(baseResult([baseFinding()]), defaultState(), defaultActions());
    document.body.appendChild(view);

    expect((document.getElementById('results-findings-text') as HTMLInputElement | null)?.placeholder).toBe('Filter findings');
    expect(document.getElementById('results-findings-category')).not.toBeNull();
    expect(document.getElementById('results-configuration-search')).not.toBeNull();
    expect(document.getElementById('results-configuration-focus')).not.toBeNull();
    expect(document.getElementById('results-components-text')).not.toBeNull();
    expect(document.getElementById('results-dependencies-search')).not.toBeNull();
  });

  it('renders a configuration review overview and compact custom-property meanings', () => {
    const result = baseResult([baseFinding()]);
    result.configurationAnalysis = {
      files: [{ path: 'src/main/resources/application.properties', profile: 'default', propertyCount: 3 }],
      properties: [
        {
          name: 'analyzer.gradle.enabled',
          value: 'true',
          sourceFile: 'src/main/resources/application.properties',
          line: 26,
          profile: 'default',
          kind: 'CUSTOM_CONFIGURATION_PROPERTIES',
          documentation: {
            type: 'boolean',
            description: 'Custom property defined by com.robbanhoglund.springbootanalyzer.config.AnalyzerProperties'
          }
        },
        {
          name: 'management.endpoint.health.show-details',
          value: 'always',
          sourceFile: 'src/main/resources/application.properties',
          line: 4,
          profile: 'default',
          kind: 'SPRING_BOOT',
          documentation: { type: 'string', description: 'Whether to show health details.' }
        },
        {
          name: 'app.password',
          value: '[redacted]',
          valueRedacted: true,
          sourceFile: 'src/main/resources/application.properties',
          line: 8,
          profile: 'default',
          kind: 'CUSTOM_CONFIGURATION_PROPERTIES',
          documentation: { type: 'string' }
        }
      ],
      codeReferences: [{ propertyName: 'missing.value', sourceFile: 'src/main/java/com/example/Demo.java' }],
      configurationPropertiesClasses: [],
      summary: {
        configuredPropertyCount: 3,
        knownSpringBootPropertyCount: 1,
        customPropertyCount: 2,
        unknownPropertyCount: 0,
        codeReferenceCount: 1,
        sensitiveValueCount: 1,
        profiles: ['default']
      }
    };

    const view = renderResultsView(result, defaultState(), defaultActions());
    document.body.appendChild(view);

    expect(document.body.textContent).toContain('Configuration review');
    expect(document.body.textContent).toContain('review signals surfaced statically');
    expect(document.body.textContent).toContain('Mixed configuration namespaces');
    expect(document.body.textContent).toContain('Custom analyzer.gradle property.');
  });

  it('renders components with simple class name first and qualified name as secondary context', () => {
    const result = baseResult([baseFinding()]);
    result.detectedComponents = [
      {
        fullyQualifiedClassName: 'com.robbanhoglund.springbootanalyzer.api.AnalysisController',
        simpleClassName: 'AnalysisController',
        packageName: 'com.robbanhoglund.springbootanalyzer.api',
        componentType: 'REST_CONTROLLER',
        annotationNames: ['RestController', 'RequestMapping'],
        filePath: 'src/main/java/com/robbanhoglund/springbootanalyzer/api/AnalysisController.java'
      }
    ];

    const view = renderResultsView(result, defaultState(), defaultActions());
    document.body.appendChild(view);

    expect(document.querySelector('.component-class-primary')?.textContent).toBe('AnalysisController');
    expect(document.querySelector('.component-class-secondary')?.textContent)
      .toBe('com.robbanhoglund.springbootanalyzer.api.AnalysisController');
    expect(document.querySelector('.component-source-cell .cell-technical-wrap')?.textContent)
      .toContain('src/main/java/com/robbanhoglund/springbootanalyzer/api/AnalysisController.java');
  });

  it('de-emphasizes redundant component annotations when the type badge already conveys the stereotype', () => {
    const result = baseResult([baseFinding()]);
    result.detectedComponents = [
      {
        fullyQualifiedClassName: 'com.robbanhoglund.springbootanalyzer.analyzer.configuration.ConfigurationAnalyzer',
        simpleClassName: 'ConfigurationAnalyzer',
        packageName: 'com.robbanhoglund.springbootanalyzer.analyzer.configuration',
        componentType: 'COMPONENT',
        annotationNames: ['Component'],
        filePath: 'src/main/java/com/robbanhoglund/springbootanalyzer/analyzer/configuration/ConfigurationAnalyzer.java'
      }
    ];

    const view = renderResultsView(result, defaultState(), defaultActions());
    document.body.appendChild(view);

    expect(document.querySelector('.component-annotations-cell')?.classList.contains('is-redundant')).toBe(true);
    expect(document.querySelector('.component-annotations-cell .cell-technical-wrap')?.textContent).toBe('Component');
  });

  it('renders the code modal with multi-line highlights and safe text rendering', () => {
    const state = defaultState();
    state.codeModal = {
      open: true,
      title: 'HTTP client has no visible retry or circuit-breaker handling',
      summary: 'Static analysis did not find visible retry or circuit-breaker handling near this outbound call.',
      ruleType: 'HTTP_NO_VISIBLE_RESILIENCE',
      target: 'ResendClient#send',
      severity: 'INFO',
      category: 'HTTP',
      confidence: 'MEDIUM',
      runtimeDetection: 'NOT_NORMALLY_DETECTED',
      isPropertySource: false,
      analysisId: 'analysis-1',
      occurrences: [
        {
          key: 'occ-1',
          label: 'src/main/java/com/example/ResendClient.java:10',
          summary: 'POST call to Resend without visible retry.',
          location: {
            filePath: 'src/main/java/com/example/ResendClient.java',
            startLine: 10,
            endLine: 10,
            symbol: 'ResendClient#send',
            language: 'java',
            githubUrl: 'https://github.com/example/demo/blob/abc123/src/main/java/com/example/ResendClient.java#L10-L13'
          },
          highlightRanges: [{ startLine: 10, endLine: 10, kind: 'issue' }]
        }
      ],
      selectedOccurrenceIndex: 0,
      loading: false,
      errorMessage: '',
      snippet: {
        filePath: 'src/main/java/com/example/ResendClient.java',
        language: 'java',
        startLine: 8,
        endLine: 14,
        githubUrl: 'https://github.com/example/demo/blob/abc123/src/main/java/com/example/ResendClient.java#L10-L13',
        highlightRanges: [{ startLine: 10, endLine: 10, kind: 'issue' }],
        lines: [
          { lineNumber: 8, text: 'public class ResendClient {' },
          { lineNumber: 9, text: '    void send() {' },
          { lineNumber: 10, text: '        resendClient.post()' },
          { lineNumber: 11, text: '            .uri("/emails")' },
          { lineNumber: 12, text: '            .body("<img src=x onerror=alert(1)>")' },
          { lineNumber: 13, text: '            .retrieve();' },
          { lineNumber: 14, text: '    }' }
        ]
      },
      returnFocusId: null
    };

    const view = renderResultsView(baseResult([baseFinding()]), state, defaultActions());
    document.body.appendChild(view);

    expect(document.querySelector('[role="dialog"]')).not.toBeNull();
    expect(document.querySelectorAll('.code-line-highlight')).toHaveLength(4);
    expect(document.querySelector('.token-keyword')?.textContent).toBe('public');
    expect(document.querySelector('.code-snippet-viewer img')).toBeNull();
    expect(document.querySelector('.code-snippet-link-button')?.getAttribute('href'))
      .toContain('/blob/abc123/src/main/java/com/example/ResendClient.java#L10-L13');
  });

  it('closes the code modal through the close button', () => {
    const onCloseFindingCode = vi.fn();
    const state = defaultState();
    state.codeModal = {
      ...state.codeModal,
      open: true,
      title: 'Empty catch block',
      summary: 'Exception is caught but the catch block is empty.',
      ruleType: 'JAVA_EMPTY_CATCH_BLOCK',
      target: 'Demo#run',
      severity: 'WARNING',
      category: 'EXCEPTION_HANDLING',
      confidence: 'HIGH',
      runtimeDetection: 'NOT_NORMALLY_DETECTED',
      isPropertySource: false,
      analysisId: 'analysis-1',
      occurrences: [
        {
          key: 'occ-1',
          label: 'src/main/java/com/example/Demo.java:3',
          summary: 'Exception is caught but the catch block is empty.',
          location: {
            filePath: 'src/main/java/com/example/Demo.java',
            startLine: 3,
            endLine: 5,
            symbol: 'Demo#run',
            language: 'java',
            githubUrl: 'https://github.com/example/demo/blob/abc123/src/main/java/com/example/Demo.java#L3-L5'
          },
          highlightRanges: [{ startLine: 3, endLine: 5, kind: 'issue' }]
        }
      ],
      selectedOccurrenceIndex: 0,
      loading: false,
      errorMessage: '',
      snippet: {
        filePath: 'src/main/java/com/example/Demo.java',
        language: 'java',
        startLine: 1,
        endLine: 6,
        githubUrl: 'https://github.com/example/demo/blob/abc123/src/main/java/com/example/Demo.java#L3-L5',
        highlightRanges: [{ startLine: 3, endLine: 5, kind: 'issue' }],
        lines: [{ lineNumber: 1, text: 'public class Demo {' }]
      },
      returnFocusId: null
    };

    const view = renderResultsView(baseResult([baseFinding()]), state, defaultActions({ onCloseFindingCode }));
    document.body.appendChild(view);

    (document.getElementById('code-snippet-modal-close') as HTMLButtonElement).click();
    expect(onCloseFindingCode).toHaveBeenCalledOnce();
  });

  // ── Idle-state tests ──────────────────────────────────────────────────────

  describe('idle states (no result)', () => {
    it('renders the initial welcome state when there is no result, no error, and not analyzing', () => {
      const view = renderResultsView(null, defaultState(), defaultActions());
      document.body.appendChild(view);

      expect(document.querySelector('.results-idle-state')).not.toBeNull();
      expect(document.querySelector('.results-idle-title')?.textContent).toBe('Point it at a repository');
      expect(document.querySelectorAll('.results-idle-features li').length).toBeGreaterThan(0);
      // No error icon
      expect(document.querySelector('.results-idle-icon.error-icon')).toBeNull();
    });

    it('renders the analyzing state when isAnalyzing is true and there is no result', () => {
      const view = renderResultsView(null, defaultState(), defaultActions(), {
        isAnalyzing: true,
        statusMessage: 'Cloning repository...'
      });
      document.body.appendChild(view);

      expect(document.querySelector('.results-idle-title')?.textContent).toBe('Analyzing repository…');
      expect(document.querySelector('.results-idle-analyzing-bar')).not.toBeNull();
      // Description should show the status message
      expect(document.querySelector('.results-idle-desc')?.textContent).toContain('Cloning repository...');
    });

    it('renders the error state when errorMessage is set and there is no result', () => {
      const view = renderResultsView(null, defaultState(), defaultActions(), {
        errorMessage: 'Authentication failed: 401 Unauthorized'
      });
      document.body.appendChild(view);

      expect(document.querySelector('.results-idle-title')?.textContent).toBe('Analysis failed');
      expect(document.querySelector('.results-idle-error-message')?.textContent)
        .toBe('Authentication failed: 401 Unauthorized');
      expect(document.querySelector('.results-idle-icon.error-icon')).not.toBeNull();
      // Auth-related hints
      const hints = document.querySelectorAll('.results-idle-error-hints li');
      expect(hints.length).toBeGreaterThan(0);
    });

    it('does not render the error chip in the header when there is no result (error shown in panel)', () => {
      const view = renderResultsView(null, defaultState(), defaultActions(), {
        errorMessage: 'Clone failed: repository not found'
      });
      document.body.appendChild(view);

      expect(document.querySelector('.status-chip-error')).toBeNull();
      expect(document.querySelector('.results-idle-error-message')).not.toBeNull();
    });

    it('shows a warning chip in the header even when there is no result (e.g. SSH warning)', () => {
      const view = renderResultsView(null, defaultState(), defaultActions(), {
        warningMessage: 'SSH repository selected. Browser-stored HTTPS tokens will not be sent.'
      });
      document.body.appendChild(view);

      expect(document.querySelector('.status-chip-warning')?.textContent)
        .toContain('SSH repository selected');
    });
  });

  // ── Partial-analysis notice ───────────────────────────────────────────────

  it('shows a partial-analysis notice when Spring Boot was not detected', () => {
    const result = baseResult([]);
    result.springBootDetected = false;

    const view = renderResultsView(result, defaultState(), defaultActions());
    document.body.appendChild(view);

    expect(document.querySelector('.partial-analysis-notice')).not.toBeNull();
    expect(document.querySelector('.partial-analysis-notice')?.textContent)
      .toContain('Spring Boot was not detected');
  });

  it('does not show a partial-analysis notice when Spring Boot is detected', () => {
    const result = baseResult([baseFinding()]);
    result.springBootDetected = true;

    const view = renderResultsView(result, defaultState(), defaultActions());
    document.body.appendChild(view);

    expect(document.querySelector('.partial-analysis-notice')).toBeNull();
  });

  // ── No-findings message ───────────────────────────────────────────────────

  it('shows a qualified no-findings message that includes a caveat about static analysis limits', () => {
    const view = renderResultsView(baseResult([]), defaultState(), defaultActions());
    document.body.appendChild(view);

    // The findings section's success note is inside #results-findings
    const findingsSection = document.getElementById('results-findings');
    const note = findingsSection?.querySelector('.empty-note.success-note');
    expect(note).not.toBeNull();
    expect(note?.textContent).toContain('No issues detected');
    expect(note?.textContent).toContain('complement static analysis');
  });
});
