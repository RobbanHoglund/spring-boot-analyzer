export interface AnalyzeRepositoryRequest {
  repositoryUrl: string;
  branch?: string | null;
  credentials?: AnalyzeRepositoryCredentials;
}

export interface AnalyzeRepositoryCredentials {
  username?: string | null;
  token: string;
}

export interface AnalyzeRepositoryResponse {
  repositoryUrl?: string;
  branch?: string | null;
  workspaceId?: string;
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
  [key: string]: unknown;
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

export interface Finding {
  severity?: string;
  message?: string;
  location?: string;
  rule?: string;
  category?: string;
  [key: string]: unknown;
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
