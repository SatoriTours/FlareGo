// 架构设计契约草案，尚未绑定 SDK、HTTP 框架或存储实现。
export type ProviderId =
  "cloudflare" | "aws" | "gcp" | "azure" | "aliyun" | "tencent";
export type DecimalString = string;
export interface Money {
  amount: DecimalString;
  currency: string;
}
export interface CloudScope {
  accountId: string;
  projectId?: string;
  subscriptionId?: string;
  region: string;
  zoneId?: string;
}
export interface ResourceRef {
  workspaceId: string;
  connectionId: string;
  provider: ProviderId;
  scope: CloudScope;
  type: string;
  externalId: string;
}
export interface RequestContext {
  workspaceId: string;
  connectionId: string;
  actorId: string;
  traceId: string;
  scope: CloudScope;
}
export interface Page<T> {
  items: T[];
  nextCursor?: string;
  observedAt: string;
}
export interface Capability {
  key: string;
  availability:
    | "available"
    | "permission_required"
    | "unsupported"
    | "console_only"
    | "temporarily_unavailable";
  requiredPermissions: string[];
  reason?: string;
  consoleUrl?: string;
}
export interface ResourceSnapshot {
  ref: ResourceRef;
  name: string;
  state: string;
  observedAt: string;
  version?: string;
}
export interface InventoryPort {
  list(
    ctx: RequestContext,
    input: { type?: string; cursor?: string },
  ): Promise<Page<ResourceSnapshot>>;
  get(ctx: RequestContext, ref: ResourceRef): Promise<ResourceSnapshot>;
}
export interface DnsRecord {
  externalId?: string;
  type: "A" | "AAAA" | "CNAME" | "TXT" | "MX";
  name: string;
  content: string;
  ttlSeconds: number | "auto";
  priority?: number;
}
export interface CloudflareDnsOptions {
  proxyEnabled: boolean;
}
export type ProviderDnsOptions =
  | { provider: "cloudflare"; options: CloudflareDnsOptions }
  | { provider: Exclude<ProviderId, "cloudflare"> };
export interface DnsChange {
  zone: ResourceRef;
  change:
    | { kind: "create"; record: DnsRecord }
    | {
        kind: "update";
        record: DnsRecord & { externalId: string };
        expectedVersion?: string;
      }
    | { kind: "delete"; externalId: string; expectedVersion?: string };
  providerOptions?: ProviderDnsOptions;
}
export interface ProviderReceipt {
  providerRequestId?: string;
  state: "running" | "succeeded" | "failed" | "action_required" | "unknown";
  resource?: ResourceRef;
}
export interface DnsPort {
  list(
    ctx: RequestContext,
    zone: ResourceRef,
    cursor?: string,
  ): Promise<Page<DnsRecord>>;
  apply(
    ctx: RequestContext,
    change: DnsChange,
    operationKey: string,
  ): Promise<ProviderReceipt>;
}
export interface RegistrationQuote {
  quoteId: string;
  domain: string;
  registrable: boolean;
  reason?: string;
  registration?: Money;
  renewal?: Money;
  years: number;
  checkedAt: string;
  expiresAt: string;
}
export interface RegistrarPort {
  check(
    ctx: RequestContext,
    domain: string,
    years: number,
  ): Promise<RegistrationQuote>;
  register(
    ctx: RequestContext,
    input: {
      quoteId: string;
      domain: string;
      autoRenew: boolean;
      operationKey: string;
    },
  ): Promise<ProviderReceipt>;
  reconcile(
    ctx: RequestContext,
    domain: string,
    providerRequestId?: string,
  ): Promise<ProviderReceipt>;
}
export interface ObjectStoragePort {
  listBuckets(
    ctx: RequestContext,
    cursor?: string,
  ): Promise<Page<ResourceSnapshot>>;
  listObjects(
    ctx: RequestContext,
    bucket: ResourceRef,
    input: { prefix?: string; cursor?: string },
  ): Promise<Page<{ key: string; sizeBytes: string }>>;
  createBucket(
    ctx: RequestContext,
    input: { name: string; region: string },
    operationKey: string,
  ): Promise<ProviderReceipt>;
}
export interface EdgeFunctionsPort {
  listVersions(
    ctx: RequestContext,
    fn: ResourceRef,
  ): Promise<Page<{ id: string; createdAt: string }>>;
  deploy(
    ctx: RequestContext,
    input: { fn: ResourceRef; versionId: string; trafficPercent: number },
    operationKey: string,
  ): Promise<ProviderReceipt>;
}
export interface VirtualMachinesPort {
  start(
    ctx: RequestContext,
    vm: ResourceRef,
    operationKey: string,
  ): Promise<ProviderReceipt>;
  stop(
    ctx: RequestContext,
    vm: ResourceRef,
    operationKey: string,
  ): Promise<ProviderReceipt>;
}
export interface CostEntry {
  id: string;
  money: Money;
  service: string;
  periodStart: string;
  periodEnd: string;
  isEstimated: boolean;
  source: string;
  observedAt: string;
}
export interface CostPort {
  listCosts(
    ctx: RequestContext,
    period: { start: string; end: string },
    cursor?: string,
  ): Promise<Page<CostEntry>>;
}
export interface ProviderAdapter {
  id: ProviderId;
  capabilities(ctx: RequestContext): Promise<Capability[]>;
  inventory: InventoryPort;
  dns?: DnsPort;
  registrar?: RegistrarPort;
  objects?: ObjectStoragePort;
  edgeFunctions?: EdgeFunctionsPort;
  virtualMachines?: VirtualMachinesPort;
  costs?: CostPort;
}
// 注册表返回多个小端口；业务用例显式请求自己需要的端口。
export interface ProviderRegistry {
  resolve(connectionId: string): Promise<ProviderAdapter>;
}
export type OperationState =
  | "queued"
  | "running"
  | "succeeded"
  | "failed"
  | "action_required"
  | "unknown"
  | "reconciling";
export interface Operation {
  id: string;
  workspaceId: string;
  connectionId: string;
  actorId: string;
  planId: string;
  idempotencyKey: string;
  inputHash: string;
  state: OperationState;
  providerRequestId?: string;
  createdAt: string;
  updatedAt: string;
}
// 此处的调用草案省略实现。SecretResolver、OperationRepository、Queue、Clock
// 均应定义为 application 层的端口，供 infrastructure 层实现。
