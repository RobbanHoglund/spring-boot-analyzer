export interface AnalyzeRepositoryRequest {
  repositoryUrl: string;
  branch?: string | null;
  credentials?: AnalyzeRepositoryCredentials;
  analysisMode?: AnalysisMode;
}

export type AnalysisMode = 'STATIC_ONLY' | 'EXTENDED';

export interface AnalyzeRepositoryCredentials {
  username?: string | null;
  token: string;
}

export interface AnalyzeRepositoryResponse {
  repositoryUrl?: string;
  branch?: string | null;
  workspaceId?: string;
  analysisId?: string;
  commitSha?: string | null;
  clonedPath?: string;
  buildTool?: string;
  springBootDetected?: boolean;
  javaVersionHint?: string | null;
  mainApplicationClasses?: DetectedClass[] | string[];
  detectedComponents?: DetectedClass[];
  dependencies?: string[];
  findings?: Finding[];
  configurationAnalysis?: ConfigurationAnalysis;
  runtimeStackAnalysis?: RuntimeStackAnalysis;
  httpSurfaceAnalysis?: HttpSurfaceAnalysis;
  gradleModelAnalysis?: GradleModelAnalysis;
  schedulingAnalysis?: SchedulingAnalysis;
  messagingAnalysis?: MessagingAnalysis;
  [key: string]: unknown;
}

export interface SchedulingAnalysis {
  scheduledTasks?: ScheduledTaskEndpoint[];
  asyncMethods?: AsyncMethodEndpoint[];
  enableSchedulingPresent?: boolean;
  enableAsyncPresent?: boolean;
}

export interface ScheduledTaskEndpoint {
  className?: string | null;
  methodName?: string | null;
  sourceFile?: string | null;
  line?: number | null;
  scheduleType?: string | null;
  scheduleValue?: string | null;
  zone?: string | null;
}

export interface AsyncMethodEndpoint {
  className?: string | null;
  methodName?: string | null;
  sourceFile?: string | null;
  line?: number | null;
}

export interface MessagingAnalysis {
  listeners?: MessageListenerEndpoint[];
}

export interface MessageListenerEndpoint {
  listenerType?: string | null;
  destinations?: string[];
  groupId?: string | null;
  className?: string | null;
  methodName?: string | null;
  sourceFile?: string | null;
  line?: number | null;
}

export interface DetectedClass {
  fullyQualifiedClassName?: string;
  simpleClassName?: string;
  simpleName?: string;
  packageName?: string;
  filePath?: string;
  componentType?: string;
  springComponentType?: string;
  annotationNames?: string[];
  annotations?: string[];
  [key: string]: unknown;
}

export interface RelatedFindingSignal {
  ruleId?: string;
  title?: string;
  severity?: string;
  confidence?: string;
  evidence?: string;
  sourceLocation?: SourceLocation;
}

export interface Finding {
  severity?: string;
  message?: string;
  location?: string;
  rule?: string;
  ruleId?: string;
  title?: string;
  category?: string;
  runtimeDetection?: string;
  confidence?: string;
  shortMessage?: string;
  whyBadPractice?: string;
  possibleImpact?: string;
  recommendation?: string;
  evidence?: string;
  limitations?: string;
  sourceFile?: string;
  line?: number | null;
  target?: string;
  primaryLocation?: SourceLocation;
  highlightRanges?: HighlightRange[];
  occurrences?: FindingOccurrence[];
  relatedSignals?: RelatedFindingSignal[];
  [key: string]: unknown;
}

export interface SourceLocation {
  filePath?: string;
  startLine?: number;
  endLine?: number;
  startColumn?: number | null;
  endColumn?: number | null;
  symbol?: string | null;
  language?: string | null;
  githubUrl?: string | null;
}

export interface HighlightRange {
  startLine?: number;
  endLine?: number;
  startColumn?: number | null;
  endColumn?: number | null;
  kind?: string | null;
}

export interface FindingOccurrence {
  message?: string | null;
  location?: SourceLocation | null;
  highlightRanges?: HighlightRange[];
}

export interface SourceSnippetLine {
  lineNumber: number;
  text: string;
  highlight?: boolean;
}

export interface SourceSnippetResponse {
  filePath?: string;
  language?: string | null;
  startLine?: number;
  endLine?: number;
  githubUrl?: string | null;
  lines?: SourceSnippetLine[];
  highlightRanges?: HighlightRange[];
}

export type TokenProvider = 'github' | 'gitlab' | 'bitbucket' | 'other';

export interface TokenProfile {
  id: string;
  name: string;
  provider: TokenProvider;
  host: string;
  username: string;
  token: string;
  createdAt: string;
  updatedAt: string;
}

export interface RepositoryProfile {
  id: string;
  name: string;
  repositoryUrl: string;
  branch?: string | null;
  authMode: 'none' | 'token' | 'ssh';
  tokenProfileId?: string | null;
  notes?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TokenProfileInput {
  id?: string;
  name: string;
  provider: TokenProvider;
  host: string;
  username: string;
  token: string;
}

export interface RepositoryProfileInput {
  id?: string;
  name: string;
  repositoryUrl: string;
  branch?: string | null;
  authMode: 'none' | 'token' | 'ssh';
  tokenProfileId?: string | null;
  notes?: string | null;
}

export interface ConfigurationAnalysis {
  files?: ConfigurationFile[];
  properties?: ApplicationProperty[];
  codeReferences?: PropertyReference[];
  configurationPropertiesClasses?: ConfigurationPropertiesClass[];
  summary?: ConfigurationSummary;
}

export interface ConfigurationSummary {
  configuredPropertyCount?: number;
  knownSpringBootPropertyCount?: number;
  customPropertyCount?: number;
  unknownPropertyCount?: number;
  codeReferenceCount?: number;
  sensitiveValueCount?: number;
  profiles?: string[];
}

export interface ConfigurationFile {
  path?: string;
  profile?: string;
  type?: string;
  propertyCount?: number;
}

export interface ApplicationProperty {
  name?: string;
  value?: string | null;
  valueRedacted?: boolean;
  placeholderValue?: boolean;
  sourceFile?: string;
  line?: number | null;
  profile?: string | null;
  kind?: string;
  documentation?: PropertyDocumentation;
  references?: PropertyReference[];
}

export interface PropertyDocumentation {
  known?: boolean;
  type?: string | null;
  description?: string | null;
  defaultValue?: string | null;
  sourceType?: string | null;
  deprecated?: boolean;
  deprecationReason?: string | null;
  hints?: PropertyValueHint[];
}

export interface PropertyValueHint {
  value?: string | null;
  description?: string | null;
}

export interface PropertyReference {
  propertyName?: string;
  referenceType?: string;
  sourceFile?: string;
  line?: number | null;
  className?: string;
  defaultValue?: string | null;
  required?: boolean;
  expectedValue?: string | null;
  matchIfMissing?: boolean | null;
}

export interface ConfigurationPropertiesClass {
  prefix?: string;
  className?: string;
  sourceFile?: string;
  description?: string | null;
  properties?: CustomPropertyDefinition[];
}

export interface CustomPropertyDefinition {
  propertyName?: string;
  javaName?: string;
  type?: string | null;
  validationAnnotations?: string[];
  description?: string | null;
}

export interface RuntimeStackAnalysis {
  springBootVersion?: string | null;
  springBootVersionSource?: string | null;
  javaVersion?: string | null;
  webStack?: string | null;
  webStackReason?: string | null;
  virtualThreads?: VirtualThreadAnalysis;
  mainClass?: string | null;
}

export interface VirtualThreadAnalysis {
  enabledByProperty?: boolean;
  javaVersionCompatible?: boolean;
  explicitVirtualThreadApiUsage?: boolean;
  scheduledWorkDetected?: boolean;
  keepAliveConfigured?: boolean;
  summary?: string | null;
  evidence?: string[];
}

export interface HttpSurfaceAnalysis {
  summary?: HttpSurfaceSummary;
  inboundEndpoints?: InboundEndpoint[];
  outboundEndpoints?: OutboundEndpoint[];
  configuredUrls?: ConfiguredUrl[];
  actuatorExposures?: ActuatorEndpointExposure[];
}

export interface HttpSurfaceSummary {
  inboundEndpointCount?: number;
  outboundEndpointCount?: number;
  configuredUrlCount?: number;
  actuatorExposureCount?: number;
  basePaths?: string[];
  externalHosts?: string[];
}

export interface InboundEndpoint {
  httpMethod?: string | null;
  path?: string | null;
  controllerClass?: string | null;
  handlerMethod?: string | null;
  sourceFile?: string | null;
  line?: number | null;
  produces?: string | null;
  consumes?: string | null;
  parameters?: string[];
  source?: string | null;
}

export interface OutboundEndpoint {
  method?: string | null;
  urlOrTemplate?: string | null;
  host?: string | null;
  baseUrl?: string | null;
  fullUrlPreview?: string | null;
  clientType?: string | null;
  sourceFile?: string | null;
  line?: number | null;
  className?: string | null;
  methodName?: string | null;
  fromConfiguration?: boolean;
  configurationPropertyName?: string | null;
}

export interface ConfiguredUrl {
  propertyName?: string | null;
  value?: string | null;
  valueRedacted?: boolean;
  host?: string | null;
  referencedPropertyName?: string | null;
  sourceFile?: string | null;
  line?: number | null;
  profile?: string | null;
  kind?: string | null;
}

export interface ActuatorEndpointExposure {
  propertyName?: string | null;
  value?: string | null;
  sourceFile?: string | null;
  line?: number | null;
  profile?: string | null;
  exposedEndpoints?: string[];
}

export interface GradleModelAnalysis {
  status?: string | null;
  gradleVersion?: string | null;
  javaVersion?: string | null;
  executionMode?: string | null;
  reportFile?: string | null;
  failureType?: string | null;
  errorMessage?: string | null;
  sanitizedBuildModel?: boolean;
  sanitizedBuildReason?: string | null;
  appliedWorkarounds?: GradleSettingsPluginWorkaround[];
  settingsPlugins?: GradleSettingsPluginModel[];
  pluginResolutionFailures?: GradlePluginResolutionFailure[];
  pluginDeclarations?: GradlePluginDeclaration[];
  pluginResolutionBridge?: GradlePluginResolutionBridgeResult | null;
  pluginBridgeUsed?: boolean;
  pluginBridgeStatus?: string | null;
  projects?: GradleProjectModel[];
  plugins?: GradlePluginModel[];
  repositories?: GradleRepositoryModel[];
  configurations?: GradleConfigurationModel[];
  declaredDependencies?: GradleDependencyModel[];
  resolvedDependencies?: GradleResolvedDependencyModel[];
  resolutionResults?: GradleResolutionResult[];
  dependencyConflicts?: GradleDependencyConflict[];
  sourceSets?: GradleSourceSetModel[];
  tasks?: GradleTaskModel[];
  javaToolchains?: GradleJavaToolchainModel[];
  findings?: Finding[];
}

export interface GradleSettingsPluginWorkaround {
  pluginId?: string | null;
  version?: string | null;
  sourceFile?: string | null;
  line?: number | null;
  action?: string | null;
  reason?: string | null;
}

export interface GradleSettingsPluginModel {
  pluginId?: string | null;
  version?: string | null;
  sourceFile?: string | null;
  line?: number | null;
}

export interface GradlePluginResolutionFailure {
  pluginId?: string | null;
  version?: string | null;
  artifact?: string | null;
  settingsFile?: string | null;
  line?: number | null;
  searchedRepositories?: string[];
  message?: string | null;
}

export interface GradlePluginDeclaration {
  pluginId?: string | null;
  version?: string | null;
  sourceFile?: string | null;
  line?: number | null;
  source?: string | null;
  applyFalse?: boolean;
}

export interface GradlePluginResolutionBridgeResult {
  successful?: boolean;
  localMavenRepository?: string | null;
  resolvedPlugins?: ResolvedGradlePlugin[];
  failures?: GradlePluginBridgeFailure[];
  findings?: Finding[];
}

export interface ResolvedGradlePlugin {
  pluginId?: string | null;
  version?: string | null;
  markerCoordinates?: string | null;
  implementationCoordinates?: string | null;
  sourceFile?: string | null;
  line?: number | null;
  markerDownloaded?: boolean;
  implementationDownloaded?: boolean;
  transitiveArtifactCount?: number;
}

export interface GradlePluginBridgeFailure {
  pluginId?: string | null;
  version?: string | null;
  markerCoordinates?: string | null;
  sourceFile?: string | null;
  line?: number | null;
  failureType?: string | null;
  message?: string | null;
  markerPresentLocally?: boolean;
  implementationPresentLocally?: boolean;
  implementationCoordinates?: string | null;
}

export interface GradleProjectModel {
  path?: string | null;
  name?: string | null;
  projectDir?: string | null;
}

export interface GradlePluginModel {
  projectPath?: string | null;
  pluginId?: string | null;
  implementationClass?: string | null;
}

export interface GradleRepositoryModel {
  projectPath?: string | null;
  name?: string | null;
  type?: string | null;
  url?: string | null;
}

export interface GradleConfigurationModel {
  projectPath?: string | null;
  name?: string | null;
  resolvable?: boolean;
  consumable?: boolean;
  dependencyCount?: number;
  declaredDependencyCount?: number;
  allDependencyCount?: number;
  extendsFrom?: string[];
}

export interface GradleDependencyModel {
  projectPath?: string | null;
  configuration?: string | null;
  notation?: string | null;
  group?: string | null;
  artifact?: string | null;
  version?: string | null;
}

export interface GradleResolvedDependencyModel {
  projectPath?: string | null;
  configuration?: string | null;
  group?: string | null;
  artifact?: string | null;
  version?: string | null;
  direct?: boolean;
  selectedReason?: string | null;
}

export interface GradleResolutionResult {
  projectPath?: string | null;
  configuration?: string | null;
  attempted?: boolean;
  successful?: boolean;
  fallbackUsed?: boolean;
  errorType?: string | null;
  errorMessage?: string | null;
  resolvedDependencyCount?: number;
}

export interface GradleDependencyConflict {
  projectPath?: string | null;
  configuration?: string | null;
  group?: string | null;
  artifact?: string | null;
  requestedVersions?: string | null;
  selectedVersion?: string | null;
}

export interface GradleSourceSetModel {
  projectPath?: string | null;
  name?: string | null;
  javaDirs?: string[];
  resourceDirs?: string[];
}

export interface GradleTaskModel {
  projectPath?: string | null;
  name?: string | null;
  group?: string | null;
  description?: string | null;
}

export interface GradleJavaToolchainModel {
  projectPath?: string | null;
  languageVersion?: string | null;
  vendor?: string | null;
  implementation?: string | null;
}
