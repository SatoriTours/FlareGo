"""Cloudflare coverage ledger: ownership, phase and qualification review.

The ledger is audit data, not an execution allowlist. Every entry is a machine
proposal derived from the pinned schema metadata plus the hand-reviewed source
subset in ``implemented.json``; nothing here is a user approval, an availability
verdict for a real account, or a runtime write permission.

Design rules enforced by this module:

* METHOD+path stays the interface identity and ``operationId`` indexes the
  ledger; duplicate JSON keys are rejected instead of silently overwritten.
* Unknown availability is never turned into ``verified_available``,
  ``provider_removed`` or ``console_only`` without explicit evidence, and no
  operation is shrunk to L0 or dropped from the denominator without recorded
  user approval.
* HTTP methods only *suggest* semantics and risk. POST stays ``unknown`` because
  parameters decide whether it reads or writes; unreviewed writes stay blocked.
* Ambiguous products stay in the explicit ``unclassified-review`` work package
  with a concrete gap instead of hiding inside a generic bucket.
"""
from __future__ import annotations

import csv
import json
import re
from collections import Counter
from pathlib import Path
from typing import Any

from .catalog import PERMISSION_FIELDS, deprecation_signals
from .schema import HTTP_METHODS, enumerate_operations, load_schema

SUPPORTED_METHODS = tuple(method.upper() for method in HTTP_METHODS)

REVIEW_FORMAT = "flarego.cloudflare-audit.review/1"
CATALOG_PATH = "docs/cloudflare-audit/endpoints.csv"

PHASES = ("P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8", "P9")
OWNER_ROLES = ("日常工作", "方案设计，架构思考", "Gemini", "高消耗工作")
DEFAULT_OWNER_ROLE = "日常工作"
ACCEPTANCE_OWNER_ROLE = "方案设计，架构思考"
# scope_kind is the API authorization/resource scope, never the source support status.
SCOPE_KINDS = ("account", "zone", "user", "organization", "global", "principal", "unknown")
SOURCE_SUPPORT_VALUES = ("source_subset", "unimplemented")
PROTOCOLS = ("rest", "graphql", "s3", "realtime", "other")
SEMANTICS = ("read", "write", "unknown")
RISKS = ("low", "medium", "high", "unknown")
PAGINATION_VALUES = ("unreviewed", "page", "cursor", "none")
AVAILABILITY_DECISIONS = (
    "documented_unverified",
    "verified_available",
    "restricted",
    "provider_removed",
    "console_only",
)
DEPRECATION_DECISIONS = ("active", "deprecated", "migration_required")
TARGET_LEVELS = ("L0", "L2", "L3")

REVIEW_FIELDS = (
    "product_id",
    "phase",
    "owner_role",
    "scope_kind",
    "protocol",
    "semantics",
    "risk",
    "pagination",
    "availability_decision",
    "deprecation_decision",
    "target_level",
    "test_prerequisites",
    "needs_review",
    "evidence",
)

BLOCKER_PERMISSION_ABSENT = "permission_metadata_absent"
BLOCKER_PERMISSION_SHAPE = "permission_metadata_invalid_shape"
BLOCKER_AVAILABILITY_CONFLICT = "availability_conflict"
BLOCKER_DEPRECATION_CONFLICT = "deprecation_conflict"
BLOCKER_FORGE_HIDDEN = "forge_hidden_flag_requires_review"
BLOCKER_RISK_REVIEW = "manual_risk_review_required"
BLOCKER_UNCLASSIFIED = "unclassified_product"
BLOCKER_MALFORMED_EVIDENCE = "malformed_evidence"
BLOCKER_K2_HOST = "k2_stream_host_acceptance_required"

K2_STREAM_HOST = "https://{stream_id}.k2.cloudflarestorage.com"
K2_STREAM_HOST_NOTE = (
    "path-level servers in the fixed schema; the K2 stream endpoint needs its own "
    "acceptance and must not receive API-base host credentials by default"
)

GLOBAL_ACCOUNT_BLOCKER = "authorized_account_absent"

# Currently approved implementation lists. Everything else stays a long-term batch
# target and must not be read as permission to start coding that product.
APPROVAL_P1_NEW = "p1_approved_new_request"
APPROVAL_P1_COMPAT = "p1_existing_compat"
APPROVAL_P2_CANDIDATE = "p2_candidate"
APPROVAL_FUTURE = "future_batch_not_yet_approved"
IMPLEMENTATION_APPROVALS = (
    APPROVAL_P1_NEW,
    APPROVAL_P1_COMPAT,
    APPROVAL_P2_CANDIDATE,
    APPROVAL_FUTURE,
)
P1_APPROVED_NEW_REQUESTS = (
    "accounts-list-accounts",
    "user-api-tokens-verify-token",
    "account-api-tokens-verify-token",
    "listWorkers",
    "account-billing-history-get-billing-history",
)
P1_APPROVED_NEW_WORK_PACKAGE = "P1-approved-new-request"
P1_COMPAT_WORK_PACKAGE = "P1-compat-migration"

# Path-derived API scopes. Unknown is explicit and never silently becomes global.
SCOPE_PATH_RULES = (
    ("/accounts/{account_id}", "account", "path scope /accounts/{account_id}"),
    ("/zones/{zone_id}", "zone", "path scope /zones/{zone_id}"),
    ("/organizations/{organization_id}", "organization", "path scope /organizations/{organization_id}"),
    ("/user/", "user", "path scope /user/*"),
)
SCOPE_GLOBAL_EXACT = {
    "/ips": "public Cloudflare IP range list with no credential scope",
    "/health": "public health endpoint with no credential scope",
    "/api/v4/health": "public health endpoint with no credential scope",
}
SCOPE_GLOBAL_PREFIXES = (("/radar/", "public Radar datasets with no account or zone scope"),)
SCOPE_UNKNOWN_PREFIXES = (
    ("/tenants/", "tenant scope has no matching scope kind; needs review"),
    (
        "/subscriptions",
        "K2 data-plane stream route with a separate stream host; account/global scope is not established",
    ),
)
SCOPE_UNKNOWN_EXACT = {
    "/live": "internal/misc route without a documented resource scope",
    "/ready": "internal/misc route without a documented resource scope",
    "/signed-url": "internal/misc route without a documented resource scope",
    "/internal/submit": "internal/misc route without a documented resource scope",
    "/produce": (
        "K2 data-plane stream route with a separate stream host; account/global scope is not established"
    ),
}
SCOPE_PRINCIPAL_BASIS = (
    "no path id; the credential subject determines which resources are visible"
)

GLOBAL_BLOCKERS = (
    {
        "code": GLOBAL_ACCOUNT_BLOCKER,
        "detail": (
            "No authorized Cloudflare account or real-account evidence exists in this "
            "repository, so no operation can reach verified_available or completed L2/L3 acceptance."
        ),
    },
    {
        "code": "user_approval_absent",
        "detail": (
            "The user has not approved per-operation L0-only or exclusion decisions, so no "
            "operation is shrunk to L0 or removed from the denominator."
        ),
    },
)

UNCLASSIFIED_PRODUCT_ID = "unclassified-review"
UNCLASSIFIED_SPEC_REFERENCE = "§3.2 尾部标签与新产品（未归类，P0 完成前必须人工消除）"
DEFAULT_UNCLASSIFIED_GAP = (
    "tag has no product row in spec §3.1/§3.2 and no path prefix identifies the owning product"
)
UNCLASSIFIED_GAPS = {
    "workers_k2_other": (
        "tag mixes /accounts/{account_id}/k2/streams with /subscriptions/* and no spec row "
        "identifies the owning product"
    ),
    "reports_other": (
        "tag only covers /accounts/{account_id}/reporting/* and the owning product is not "
        "identified in spec §3.1/§3.2"
    ),
    "Field Extractors": (
        "tag only covers /accounts/{account_id}/field_extractors/* with no spec row naming "
        "the owning product"
    ),
}

UNCLASSIFIED_PACKAGE = {
    "product_id": UNCLASSIFIED_PRODUCT_ID,
    "phase": "P8",
    "target_level": "L2",
    "spec_reference": UNCLASSIFIED_SPEC_REFERENCE,
    "tags": (),
}

# Explicit product work packages. Phase, target level and spec reference come from
# docs/cloudflare-coverage-audit.md §3.1/§3.2 (product tables) and §5 (L2/L3 goal).
# P8 is split into concrete subprojects so it cannot become a black hole.
PRODUCT_PACKAGES: tuple[dict[str, Any], ...] = (
    {
        "product_id": "account-and-identity-administration",
        "phase": "P8",
        "target_level": "L2",
        "spec_reference": (
            "§3.1 账号与凭据（P1 仅限已批准请求清单；成员／角色／Token 管理属 P8）"
        ),
        "tags": (
            "Account", "Accounts", "Account Members", "Account Owned API Tokens",
            "Account Permission Groups", "Account Resource Groups", "Account Roles",
            "Account Request Tracer", "Account Settings", "Account User Group Members",
            "Account User Groups", "Active session", "Communication Preferences",
            "Dashboard - Users", "OAuth Clients", "User", "User API Tokens",
            "User's Account Memberships", "User's Invites", "User's Organizations",
        ),
    },
    {
        "product_id": "billing-usage-and-costs",
        "phase": "P4",
        "target_level": "L2",
        "spec_reference": (
            "§3.1 Billing（P1 仅账号／用户账单历史试点；用量／成本／套餐主要 P4）"
        ),
        "tags": (
            "Account Billing", "Account Billing History", "Account Billing Profile",
            "Account Entitlements", "Account Subscriptions", "Billable Usage",
            "Billable Usage V2", "Rate Plans", "Usage Analytics", "User Billing History",
            "User Billing Profile", "User Subscription", "Zone Rate Plan", "Zone Subscription",
        ),
    },
    {
        "product_id": "workers-versions-and-previews",
        "phase": "P3",
        "target_level": "L2",
        "spec_reference": "§3.1 Workers（版本／预览／profiling 属 P3）",
        "tags": ("Versions", "Previews", "Preview Deployments", "Worker Profiling"),
    },
    {
        "product_id": "zones-and-zone-settings",
        "phase": "P2",
        "target_level": "L3",
        "spec_reference": "§3.1 Zones（P2 基础 Zone 生命周期与设置）",
        "tags": (
            "Zone", "Zone Cache Settings", "Zone Cloud Connector Rules GET",
            "Zone Cloud Connector Rules PUT", "Zone Entitlements", "Zone Environments",
            "Zone Holds", "Zone Settings",
        ),
    },
    {
        "product_id": "dns-records-and-settings",
        "phase": "P2",
        "target_level": "L3",
        "spec_reference": "§3.1 DNS",
        "tags": (
            "DNS Analytics", "DNS Records for an Account", "DNS Records for a Zone",
            "DNS Settings for an Account", "DNS Settings for a Zone", "DNSSEC",
        ),
    },
    {
        "product_id": "ssl-tls-and-certificates",
        "phase": "P2",
        "target_level": "L3",
        "spec_reference": "§3.2 SSL／TLS／Certificates（P2 基础模式与验证）",
        "tags": (
            "Analyze Certificate", "Automatic SSL/TLS", "SSL Verification",
            "SSL/TLS Mode Recommendation", "Universal SSL Settings for a Zone",
        ),
    },
    {
        "product_id": "workers-platform",
        "phase": "P3",
        "target_level": "L3",
        "spec_reference": "§3.1 Workers（P1 仅新模型试点请求；其余属 P3）",
        "tags": (
            "Domains", "Worker Cron Trigger", "Worker Deployments", "Worker Environment",
            "Worker Routes", "Worker Script", "Worker Subdomain", "Worker Tail Logs",
            "Worker Versions", "Workers", "Workers Account Settings",
            "Workers Smart Placement",
        ),
    },
    {
        "product_id": "workers-observability",
        "phase": "P3",
        "target_level": "L2",
        "spec_reference": "§3.1 Workers（observability／tail）",
        "tags": (
            "Agents", "Destinations", "Issues", "Keys", "Metrics Export", "Query",
            "Query run", "Saved Queries", "Shared", "Usage", "Values",
        ),
    },
    {
        "product_id": "workers-builds",
        "phase": "P3",
        "target_level": "L2",
        "spec_reference": "§3.1 Workers（builds／CI）",
        "tags": (
            "Build Tokens", "Builds", "Deploy Hooks", "Environment Variables",
            "GitHub Integration", "Repository Connections", "Triggers",
        ),
    },
    {
        "product_id": "pages",
        "phase": "P3",
        "target_level": "L3",
        "spec_reference": "§3.2 Pages",
        "tags": (
            "Pages Assets", "Pages Build Cache", "Pages Deployment", "Pages Domains",
            "Pages Project",
        ),
    },
    {
        "product_id": "r2-objects-and-buckets",
        "phase": "P3",
        "target_level": "L3",
        "spec_reference": "§3.1 R2 / §4.3",
        "tags": ("R2 Account", "R2 Bucket", "R2 Object", "R2 Super Slurper"),
    },
    {
        "product_id": "d1-databases",
        "phase": "P3",
        "target_level": "L3",
        "spec_reference": "§3.1 D1",
        "tags": ("D1",),
    },
    {
        "product_id": "kv-namespaces",
        "phase": "P3",
        "target_level": "L3",
        "spec_reference": "§3.1 KV",
        "tags": ("Workers KV Namespace",),
    },
    {
        "product_id": "registrar",
        "phase": "P4",
        "target_level": "L2",
        "spec_reference": "§3.2 Registrar / §4.1",
        "tags": ("Extension", "Registrar Domains", "Registrar Registration", "Registrar Transfer"),
    },
    {
        "product_id": "analytics-query-and-logs",
        "phase": "P4",
        "target_level": "L2",
        "spec_reference": "§3.2 Logs／Analytics／Notifications",
        "tags": (
            "ART Analytics", "Analytics SQL", "Instant Logs jobs for a zone",
            "Live Tail", "Log Explorer Datasets", "Log Explorer Queries",
            "Logcontrol CMB config for an account", "Logpull", "Logpush jobs for a zone",
            "Logpush jobs for an account", "Logpush transformers", "Logs Received",
            "Observability", "Requests - Dashboard", "User Analytics (Deprecated)",
            "Zone Analytics (Deprecated)",
        ),
    },
    {
        "product_id": "notifications-and-webhooks",
        "phase": "P4",
        "target_level": "L2",
        "spec_reference": "§3.2 Logs／Analytics／Notifications（通知）",
        "tags": (
            "Notification Alert Types", "Notification History",
            "Notification Mechanism Eligibility", "Notification Silences",
            "Notification destinations with PagerDuty", "Notification policies",
            "Notification webhooks",
        ),
    },
    {
        "product_id": "rules-waf-bots",
        "phase": "P5",
        "target_level": "L3",
        "spec_reference": "§3.2 Rules／WAF／Bots（含 P5 网站规则：Page Rules／Zone Lockdown／Snippets）",
        "tags": (
            "AI Security for Apps", "Account Rulesets", "Available Page Rules settings",
            "Bot Settings", "Botnet Threat Feed", "CSAM Scanner Settings", "Feedback",
            "Filters", "Firewall rules", "Fraud Detection", "IP Access rules for a user",
            "IP Access rules for a zone", "IP Access rules for an account",
            "Leaked Credential Checks", "Lists", "Managed Transforms", "Page Rules",
            "Precursor", "Rate Limiting Analytics", "Rate limits for a zone", "Rules",
            "User Agent Blocking rules", "WAF overrides", "WAF packages", "WAF rule groups",
            "WAF rules", "Zone Lockdown", "Zone Rulesets", "Zone Snippets",
        ),
    },
    {
        "product_id": "api-shield-and-client-security",
        "phase": "P5",
        "target_level": "L2",
        "spec_reference": "§3.2 API Shield／Client-side Security",
        "tags": (
            "API Shield API Discovery", "API Shield Client Certificates for a Zone",
            "API Shield Endpoint Management", "API Shield Labels",
            "API Shield Schema Validation 2.0", "API Shield Settings",
            "API Shield WAF Expression Templates", "Client-Side Security", "Content Scanning",
            "Schema Validation", "Schema Validation Settings",
            "Token Validation Token Configuration", "Token Validation Token Rules",
        ),
    },
    {
        "product_id": "load-balancing-and-health-checks",
        "phase": "P5",
        "target_level": "L2",
        "spec_reference": "§3.2 Load Balancing／Health Checks",
        "tags": (
            "Account Load Balancer Monitor Groups", "Account Load Balancer Monitors",
            "Account Load Balancer Pools", "Account Load Balancer Search",
            "Account Load Balancers", "Endpoint Health Checks", "Health Checks",
            "Load Balancer Healthcheck Events", "Load Balancer Monitors",
            "Load Balancer Pools", "Load Balancer Regions", "Load Balancers",
        ),
    },
    {
        "product_id": "email-routing-and-sending",
        "phase": "P5",
        "target_level": "L2",
        "spec_reference": "§3.2 Email Routing／Sending",
        "tags": (
            "Email Auth", "Email Routing destination addresses",
            "Email Routing routing rules", "Email Routing settings",
            "Email Routing suppressions", "Email Sending", "Email Sending subdomains",
            "Email Sending suppressions",
        ),
    },
    {
        "product_id": "certificates-advanced-and-mtls",
        "phase": "P5",
        "target_level": "L2",
        "spec_reference": "§3.2 SSL／TLS／Certificates（高级证书与 mTLS，P5）",
        "tags": (
            "Certificate Packs", "Custom CSRs for a Zone", "Custom CSRs for an Account",
            "Custom Origin Trust Store", "Custom SSL for a Zone", "DCV Delegation",
            "Keyless SSL for a Zone", "mTLS Certificate Management", "Origin CA",
            "Origin Post-Quantum", "Origin TLS", "Per-Hostname TLS Settings",
            "Per-hostname Authenticated Origin Pull", "Total TLS",
            "Zone-Level Authenticated Origin Pulls",
        ),
    },
    {
        "product_id": "cdn-performance-advanced",
        "phase": "P5",
        "target_level": "L2",
        "spec_reference": "§3.2 Cache／CDN／Performance（高级缓存／证书，P5）",
        "tags": (
            "Argo Analytics for Geolocation", "Argo Analytics for Zone", "Argo Smart Routing",
            "Cache Reserve Clear", "DLS Regional Services", "NEL Settings", "Observatory",
            "Origin Cloud Regions", "Prefix Bindings", "Regions", "Smart Shield Settings",
            "Smart Tiered Cache", "Tiered Caching", "URL Normalization",
        ),
    },
    {
        "product_id": "r2-advanced-and-s3",
        "phase": "P5",
        "target_level": "L2",
        "spec_reference": "§3.1 R2 / §3.3 R2 S3 与 catalog",
        "tags": (
            "Basin Catalog Management", "Credential Management", "Maintenance Configuration",
            "Namespace Management", "R2 Catalog", "R2 Catalog Management",
            "Table Maintenance Configuration", "Table Management",
        ),
    },
    {
        "product_id": "zero-trust-access",
        "phase": "P6",
        "target_level": "L2",
        "spec_reference": "§3.2 Tunnel／Zero Trust Access",
        "tags": (
            "Access Authenticator Device AAGUIDs", "Access Bookmark applications (Deprecated)",
            "Access IdP federation grants", "Access JIT request logs",
            "Access SAML encryption certificates", "Access SCIM update logs",
            "Access application-scoped policies", "Access applications",
            "Access authentication logs", "Access custom pages", "Access groups",
            "Access identity providers", "Access key configuration",
            "Access mTLS authentication", "Access policy tester", "Access reusable policies",
            "Access service tokens", "Access short-lived certificate CAs", "Access tags",
            "Access user populations", "Applications", "Custom assets for a zone",
            "Custom assets for an account", "Custom pages for a zone",
            "Custom pages for an account", "Integrations", "MCP Portal",
            "MCP Portal Analytics", "MCP Portal Servers", "SCIM Discovery", "SCIM Groups",
            "SCIM Users", "SSO", "Zone-Level Access applications", "Zone-Level Access groups",
            "Zone-Level Access identity providers", "Zone-Level Access mTLS authentication",
            "Zone-Level Access policies", "Zone-Level Access service tokens",
            "Zone-Level Access short-lived certificate CAs", "Zone-Level Zero Trust organization",
        ),
    },
    {
        "product_id": "zero-trust-gateway",
        "phase": "P6",
        "target_level": "L2",
        "spec_reference": "§3.2 Gateway／WARP／Devices",
        "tags": (
            "Connectivity Services", "Gateway CA", "Zero Trust Gateway DNS destination IPv4 address pairs",
            "Zero Trust Gateway PAC files",
            "Zero Trust Gateway application and application type mappings",
            "Zero Trust Gateway categories", "Zero Trust Gateway locations",
            "Zero Trust Gateway operations", "Zero Trust Gateway proxy endpoints",
            "Zero Trust Gateway rules", "Zero Trust Risk Scoring",
            "Zero Trust Risk Scoring Integrations", "Zero Trust SSH Settings",
            "Zero Trust Subnets", "Zero Trust accounts", "Zero Trust applications review status",
            "Zero Trust certificates", "Zero Trust lists", "Zero Trust organization",
            "Zero Trust seats", "Zero Trust users", "Zero Trust Connectivity Settings",
        ),
    },
    {
        "product_id": "zero-trust-tunnel",
        "phase": "P6",
        "target_level": "L2",
        "spec_reference": "§3.2 Tunnel",
        "tags": (
            "Cloudflare Tunnel", "Cloudflare Tunnel Configuration", "Tunnel Routing",
            "Tunnel Virtual Network", "Zero Trust Hostname Route",
        ),
    },
    {
        "product_id": "zero-trust-devices",
        "phase": "P6",
        "target_level": "L2",
        "spec_reference": "§3.2 Gateway／WARP／Devices",
        "tags": (
            "Browser Extension Config", "Browser Extension Inventories", "Client Versions",
            "Deployment Groups", "Device Managed Networks", "Device Posture Integrations",
            "Device posture rules", "Devices", "Devices Resilience", "IP Profiles",
            "Override Codes", "Physical Devices", "Registrations", "WARP Change Events",
            "warp-teams-device-api_other",
        ),
    },
    {
        "product_id": "zero-trust-data-security",
        "phase": "P6",
        "target_level": "L2",
        "spec_reference": "§3.2 DLP／CASB／DEX（Data Security posture）",
        "tags": (
            "Data Security", "content", "exports", "finding_types", "findings", "policies",
            "remediations", "webhooks",
        ),
    },
    {
        "product_id": "vulnerability-scanner",
        "phase": "P6",
        "target_level": "L2",
        "spec_reference": "§3.2 DLP／CASB／DEX／Infrastructure Access（漏洞扫描）",
        "tags": ("Credential Sets", "Credentials", "Scans", "Target Environments"),
    },
    {
        "product_id": "dlp-and-casb",
        "phase": "P6",
        "target_level": "L2",
        "spec_reference": "§3.2 DLP／CASB",
        "tags": (
            "DLP Custom Prompt Topics", "DLP Data Classes", "DLP Data Tag Categories",
            "DLP Data Tag Category Templates", "DLP Data Tags", "DLP Datasets",
            "DLP Document Fingerprints", "DLP Email", "DLP Entries", "DLP Integration Entries",
            "DLP Predefined Entries", "DLP Profiles", "DLP Sensitivity Group Templates",
            "DLP Sensitivity Groups", "DLP Sensitivity Levels", "DLP Settings",
        ),
    },
    {
        "product_id": "dex-and-infrastructure-access",
        "phase": "P6",
        "target_level": "L2",
        "spec_reference": "§3.2 DEX／Infrastructure Access",
        "tags": (
            "DEX Remote Commands", "DEX Rules", "DEX Synthetic Application Monitoring",
            "Infrastructure Access Targets",
        ),
    },
    {
        "product_id": "queues",
        "phase": "P7",
        "target_level": "L2",
        "spec_reference": "§3.2 Queues",
        "tags": ("Queue",),
    },
    {
        "product_id": "durable-objects",
        "phase": "P7",
        "target_level": "L2",
        "spec_reference": "§3.2 Durable Objects",
        "tags": ("Durable Objects Namespace",),
    },
    {
        "product_id": "workflows",
        "phase": "P7",
        "target_level": "L2",
        "spec_reference": "§3.2 Workflows",
        "tags": ("Workflows",),
    },
    {
        "product_id": "containers",
        "phase": "P7",
        "target_level": "L2",
        "spec_reference": "§3.2 Containers",
        "tags": ("Container Images", "Container Instances", "Image Registries", "Rollouts"),
    },
    {
        "product_id": "hyperdrive",
        "phase": "P7",
        "target_level": "L2",
        "spec_reference": "§3.2 Hyperdrive",
        "tags": ("Hyperdrive",),
    },
    {
        "product_id": "vectorize",
        "phase": "P7",
        "target_level": "L2",
        "spec_reference": "§3.2 Vectorize",
        "tags": ("Vectorize", "Vectorize Beta (Deprecated)"),
    },
    {
        "product_id": "ai-platform",
        "phase": "P7",
        "target_level": "L2",
        "spec_reference": "§3.2 Workers AI／AI Gateway／AI Search",
        "tags": (
            "AI Gateway", "AI Gateway Custom Domains", "AI Gateway Custom Provider Costs",
            "AI Gateway Custom Providers", "AI Gateway Datasets", "AI Gateway Dynamic Routes",
            "AI Gateway Evaluations", "AI Gateway Gateways", "AI Gateway Logs",
            "AI Gateway Provider Configs", "AI Search Account Search", "AI Search Instances",
            "AI Search Instances Items", "AI Search Instances Jobs", "AI Search Namespaces",
            "AI Search Tokens", "AutoRAG Jobs", "AutoRAG RAG", "AutoRAG RAG Search",
            "Finetune Asset", "Workers AI", "Workers AI Finetune",
        ),
    },
    {
        "product_id": "data-platform",
        "phase": "P7",
        "target_level": "L2",
        "spec_reference": "§3.2 Pipelines／Secrets Store／Artifacts／Analytics Engine",
        "tags": ("Analytics Engine", "Artifacts", "Secrets Store", "workers_pipelines_other"),
    },
    {
        "product_id": "images-and-stream",
        "phase": "P7",
        "target_level": "L2",
        "spec_reference": "§3.2 Images／Stream",
        "tags": (
            "Cloudflare Images", "Cloudflare Images Keys", "Cloudflare Images Sourcing Kit",
            "Cloudflare Images Variants", "Images", "Stream Audio Tracks",
            "Stream Live Inputs", "Stream MP4 Downloads", "Stream Signing Keys",
            "Stream Subtitles/Captions", "Stream Video Clipping", "Stream Videos",
            "Stream Watermark Profile", "Stream Webhook",
        ),
    },
    {
        "product_id": "realtime-and-media",
        "phase": "P7",
        "target_level": "L2",
        "spec_reference": "§3.2 Browser Rendering／Realtime",
        "tags": (
            "Analytics", "Apps", "Live streams", "LivestreamAnalytics", "Meetings",
            "MoQ Relays", "Presets", "Realtime SFU Apps", "Realtime TURN Keys", "Recordings",
            "Sessions", "Webhooks",
        ),
    },
    {
        "product_id": "browser-rendering",
        "phase": "P7",
        "target_level": "L2",
        "spec_reference": "§3.2 Browser Rendering",
        "tags": ("brapi",),
    },
    {
        "product_id": "agent-memory",
        "phase": "P7",
        "target_level": "L2",
        "spec_reference": "§3.2 Workers AI／AI（Agent Memory）",
        "tags": ("Memory", "Namespaces"),
    },
    {
        "product_id": "organizations-and-audit",
        "phase": "P8",
        "target_level": "L2",
        "spec_reference": "§3.1 账号与凭据（组织／审计）",
        "tags": (
            "Account-Level Custom Nameservers",
            "Account-Level Custom Nameservers Usage for a Zone", "Audit Logs",
            "OrganizationMembers", "Organizations", "Resource Sharing", "Resource Tagging",
            "Tenant-Level Custom Nameservers", "Tenants",
        ),
    },
    {
        "product_id": "saas-platforms",
        "phase": "P8",
        "target_level": "L2",
        "spec_reference": "§3.2 Cloudflare for Platforms／SaaS",
        "tags": (
            "Custom Hostname Fallback Origin for a Zone", "Custom Hostname for a Zone",
            "Workers for Platforms",
        ),
    },
    {
        "product_id": "advanced-dns",
        "phase": "P8",
        "target_level": "L2",
        "spec_reference": "§3.1 高级 DNS",
        "tags": (
            "Custom Nameserver Sets for an Account", "DNS Firewall", "DNS Firewall Analytics",
            "DNS Internal Views for an Account", "Secondary DNS (ACL)", "Secondary DNS (Peer)",
            "Secondary DNS (Primary Zone)", "Secondary DNS (Secondary Zone)",
            "Secondary DNS (TSIG)",
        ),
    },
    {
        "product_id": "magic-network",
        "phase": "P8",
        "target_level": "L2",
        "spec_reference": "§3.2 Magic WAN／Transit／Spectrum",
        "tags": (
            "CNIs", "Catalog Sync", "Cloud Integrations", "Interconnects", "Magic Account Apps",
            "Magic BGP Filter Profiles", "Magic BGP Settings", "Magic CF1 Site Ramps",
            "Magic CF1 Sites", "Magic Connectors", "Magic GRE tunnels", "Magic IPsec tunnels",
            "Magic Interconnects", "Magic Network Monitoring Configuration",
            "Magic Network Monitoring Rules", "Magic Network Monitoring VPC Flow logs",
            "Magic PCAP collection", "Magic Redundancy Groups", "Magic Site ACLs",
            "Magic Site App Configs", "Magic Site LANs", "Magic Site NetFlow Config",
            "Magic Site WANs", "Magic Sites", "Magic Static Routes", "On-ramps", "Resources",
            "Settings", "Slots", "dos-flowtrackd-api_other",
        ),
    },
    {
        "product_id": "ipam-and-spectrum",
        "phase": "P8",
        "target_level": "L2",
        "spec_reference": "§3.2 Magic／IPAM／Spectrum",
        "tags": (
            "IP Address Management Address Maps", "IP Address Management BGP Prefixes",
            "IP Address Management Dynamic Advertisement", "IP Address Management Leases",
            "IP Address Management Prefix Delegation", "IP Address Management Prefixes",
            "IP Address Management Service Bindings", "Spectrum Analytics",
            "Spectrum Applications",
        ),
    },
    {
        "product_id": "radar",
        "phase": "P8",
        "target_level": "L2",
        "spec_reference": "§3.2 Radar",
        "tags": (
            "Radar AI Bots", "Radar AI Inference", "Radar AS112", "Radar Agent Readiness",
            "Radar Annotations", "Radar Autonomous Systems", "Radar BGP", "Radar Bots",
            "Radar Certificate Transparency", "Radar DNS", "Radar Datasets",
            "Radar Domains Ranking", "Radar Email Routing", "Radar Email Security",
            "Radar Geolocations", "Radar HTTP", "Radar IP", "Radar Internet Services Ranking",
            "Radar Layer 3 Attacks", "Radar Layer 7 Attacks", "Radar Leaked Credential Checks",
            "Radar Locations", "Radar Markdown for Agents", "Radar NetFlows", "Radar Origins",
            "Radar Post-Quantum", "Radar Quality", "Radar Robots.txt", "Radar Search",
            "Radar TCP Resets and Timeouts", "Radar Top-Level Domains",
            "Radar Traffic Anomalies", "Radar Verified Bots", "Radar Web Crawlers",
        ),
    },
    {
        "product_id": "cloudforce-one",
        "phase": "P8",
        "target_level": "L2",
        "spec_reference": "§3.2 Radar／Intel／Security Center／Cloudforce One",
        "tags": (
            "Approvals", "Attacker", "BinDB", "Category", "Collections", "Collections - Items",
            "Custom Indicator Feeds", "Dashboard Assets", "Dataset", "Datasets", "Event",
            "Events", "Groups", "Indicator", "Indicator Types", "Indicators", "Permissions",
            "Priority Intelligence Requirements", "Priority Intelligence Requirements (PIR)",
            "Request for Information (RFI)", "RFI", "RFI Messages", "Tag", "TagCategory",
            "Target Industry", "Threat Signals",
        ),
    },
    {
        "product_id": "brand-protection",
        "phase": "P8",
        "target_level": "L2",
        "spec_reference": "§3.2 Cloudforce One／Brand Protection",
        "tags": (
            "Brand Protection", "Public - Credential Monitor", "brand_protection",
            "domain_search", "logo_match",
        ),
    },
    {
        "product_id": "intel-and-security-center",
        "phase": "P8",
        "target_level": "L2",
        "spec_reference": "§3.2 Radar／Intel／Security Center",
        "tags": (
            "ASN Intelligence", "CT Alerting", "Country", "Domain Discovery", "Domain History",
            "Domain Intelligence", "IP Intelligence", "IP List", "Miscategorization",
            "Passive DNS by IP", "Security Center Audit Log", "Security Center Insights",
            "Security Center Partners", "Security Center Scans", "Sinkhole Config",
            "URL Intelligence", "URL Scanner", "URL Scanner (Deprecated)",
            "Vulnerability Discovery", "WHOIS Record", "security.txt",
        ),
    },
    {
        "product_id": "email-security",
        "phase": "P8",
        "target_level": "L2",
        "spec_reference": "§3.2 Email Security",
        "tags": ("Email Security", "Email Security Analytics", "Email Security Settings"),
    },
    {
        "product_id": "web-and-edge-products",
        "phase": "P8",
        "target_level": "L2",
        "spec_reference": "§3.2 Zaraz／Web Analytics／Waiting Room／Web3",
        "tags": (
            "Google Tag Gateway", "Turnstile", "Waiting Room", "Web Analytics", "Web3 Hostname",
            "Zaraz",
        ),
    },
    {
        "product_id": "monetization-and-pay-per-crawl",
        "phase": "P8",
        "target_level": "L2",
        "spec_reference": "§3.2 Monetization／AI Audit／Pay per Crawl",
        "tags": (
            "AI Audit", "Monetization", "ppc_config", "ppc_crawlers", "ppc_stripe", "ppc_terms",
            "ppu_configuration", "ppu_enabled_domains", "ppu_pricing", "ppu_usage_reports",
            "ppu_usage_stats",
        ),
    },
    {
        "product_id": "billing-payments-and-compliance",
        "phase": "P8",
        "target_level": "L2",
        "spec_reference": "§3.1 Billing（支付与合规）",
        "tags": ("Account Payment Methods",),
    },
    {
        "product_id": "flagship-experimentation",
        "phase": "P8",
        "target_level": "L2",
        "spec_reference": "§3.2 尾部标签与新产品（Flagship）",
        "tags": ("Changelog", "Definitions", "Evaluation", "Flags"),
    },
    {
        "product_id": "platform-utilities",
        "phase": "P8",
        "target_level": "L2",
        "spec_reference": "§3.2 尾部标签与新产品",
        "tags": ("Cloudflare IPs", "Health"),
    },
    {
        "product_id": "tail-maintenance-and-diagnostics",
        "phase": "P8",
        "target_level": "L2",
        "spec_reference": "§3.2 尾部标签与新产品（维护／投诉／诊断）",
        "tags": ("Diagnostics", "tseng-abuse-complaint-processor_other"),
    },
    {
        "product_id": "field-extractors-alpha",
        "phase": "P8",
        "target_level": "L2",
        "spec_reference": "§3.2 尾部标签与新产品（field extractors alpha SDK family）",
        "classification_evidence": (
            "fixed schema x-fern-sdk-group-name=fieldExtractors; GET/PUT/DELETE "
            "/accounts/{account_id}/field_extractors/{extractor}; x-fern-availability=alpha; "
            "no token-permission metadata; route-family ownership only, not vendor availability"
        ),
        "qualification_blockers": ("alpha_sdk_family_unqualified",),
        "tags": ("Field Extractors",),
    },
    {
        "product_id": "account-reporting-control-plane",
        "phase": "P8",
        "target_level": "L2",
        "spec_reference": "§3.2 尾部标签与新产品（account reporting control plane）",
        "classification_evidence": (
            "fixed schema x-fern-sdk-group-name reporting.industry/policies/reports; "
            "/accounts/{account_id}/reporting/*; #reports:read and #reports:edit token groups; "
            "route-family ownership only, entitlement and availability stay pending"
        ),
        "qualification_blockers": ("reporting_qualification_and_entitlement_pending",),
        "tags": ("reports_other",),
    },
    {
        "product_id": "k2-streams-beta-control-data",
        "phase": "P8",
        "target_level": "L2",
        "spec_reference": "§3.2 尾部标签与新产品（K2 streams beta control/data plane）",
        "classification_evidence": (
            "fixed schema x-fern-sdk-group-name k2.streams/k2.streams.subscriptions/"
            "k2.streams.subscriptions.batches; x-fern-availability=beta; six account-scoped "
            "control-plane routes plus eight stream-host data-plane routes; route-family "
            "ownership only, protocol/host acceptance stays pending"
        ),
        "qualification_blockers": ("k2_beta_qualification_pending",),
        "tags": ("workers_k2_other",),
    },
    UNCLASSIFIED_PACKAGE,
)

# Path prefixes override the tag table for tags that genuinely span products.
# Order matters: the first matching prefix wins.
PATH_OVERRIDES: tuple[tuple[str, str], ...] = (
    ("/accounts/{account_id}/workers/observability/", "workers-observability"),
    ("/accounts/{account_id}/workers/dispatch/", "saas-platforms"),
    ("/accounts/{account_id}/workers/workers/", "workers-platform"),
    ("/accounts/{account_id}/workers/", "workers-platform"),
    ("/accounts/{account_id}/builds/", "workers-builds"),
    ("/accounts/{account_id}/media/", "images-and-stream"),
    ("/accounts/{account_id}/realtime/", "realtime-and-media"),
    ("/accounts/{account_id}/flagship/", "flagship-experimentation"),
    ("/accounts/{account_id}/containers/", "containers"),
    ("/accounts/{account_id}/cloudforce-one/", "cloudforce-one"),
    ("/accounts/{account_id}/vuln_scanner/", "vulnerability-scanner"),
    ("/accounts/{account_id}/data-security/", "zero-trust-data-security"),
    ("/accounts/{account_id}/intel/", "intel-and-security-center"),
    ("/accounts/{account_id}/magic/", "magic-network"),
    ("/accounts/{account_id}/cni/", "magic-network"),
    ("/accounts/{account_id}/access/", "zero-trust-access"),
    ("/accounts/{account_id}/devices/", "zero-trust-devices"),
    ("/accounts/{account_id}/gateway/", "zero-trust-gateway"),
    ("/accounts/{account_id}/registrar", "registrar"),
)

READ_METHODS = ("GET", "HEAD", "OPTIONS", "TRACE")
WRITE_METHODS = ("PUT", "PATCH", "DELETE")

# Availability labels only: "preview" here means a dry-run preview endpoint and
# "deprecated" is handled by the deprecation decision, so neither is a conflict.
_RESTRICTED_HINT = re.compile(r"\b(alpha|beta|restricted|experimental)\b", re.I)

PREREQUISITE_ORDER = (
    "authorized_cloudflare_account",
    "isolated_test_scope",
    "account_scoped_token",
    "zone_scoped_token",
    "permission_metadata_evidence",
    "plan_entitlement_evidence",
    "manual_risk_review",
    "rollback_or_recovery_check",
    "migration_decision",
    "product_ownership_decision",
    "route_family_qualification_review",
    "separate_stream_host_acceptance",
)


def _package_index() -> dict[str, dict]:
    index: dict[str, dict] = {}
    duplicates: list[str] = []
    for package in PRODUCT_PACKAGES:
        for tag in package["tags"]:
            if tag in index:
                duplicates.append(
                    f"{tag!r}: {index[tag]['product_id']!r} and {package['product_id']!r}"
                )
                continue
            index[tag] = package
    if duplicates:
        raise ValueError("tags assigned to more than one product: " + "; ".join(duplicates))
    return index


TAG_INDEX = _package_index()
PACKAGE_BY_ID = {package["product_id"]: package for package in PRODUCT_PACKAGES}


def _describe_value(value: Any) -> str:
    if isinstance(value, list):
        inner = ", ".join(type(item).__name__ for item in value) or "empty"
        return f"list[{inner}]"
    if isinstance(value, dict):
        return "object{" + ", ".join(sorted(value)) + "}" if value else "empty object"
    return f"{type(value).__name__} {value!r}"


def permission_reference_valid(field: str, value: Any) -> bool:
    """Validate the documented fixed-schema shape of one permission field.

    ``x-api-token-group`` is a non-empty list of permission-name strings and
    ``x-cfPermissionsRequired`` is an object with a non-empty ``enum`` list of
    scope strings. Booleans, numbers, nested lists and empty enums never count.
    """
    if field == "x-api-token-group":
        return (
            isinstance(value, list)
            and bool(value)
            and all(isinstance(item, str) and item.strip() for item in value)
        )
    if field == "x-cfPermissionsRequired":
        if not isinstance(value, dict) or not value:
            return False
        enum = value.get("enum")
        return (
            isinstance(enum, list)
            and bool(enum)
            and all(isinstance(item, str) and item.strip() for item in enum)
        )
    return False


def permission_evidence(metadata: dict) -> tuple[bool, list[str]]:
    """Return ``(permissions_present, shape_errors)`` for permission metadata.

    A key that is absent or explicitly ``null`` in the pinned schema is simply
    absent (no error). A key with an invalid inner shape is an explicit, readable
    failure that can never become positive permission evidence.
    """
    present = False
    errors: list[str] = []
    for field in PERMISSION_FIELDS:
        if field not in metadata:
            continue
        value = metadata[field]
        if value is None:
            continue
        if permission_reference_valid(field, value):
            present = True
        else:
            errors.append(
                f"{field} has an invalid shape ({_describe_value(value)}); permission metadata "
                "must not be derived from it"
            )
    return present, errors


def _identity_problems(operation: dict) -> list[str]:
    """Validate identity fields without coercing invalid values into valid ones."""
    problems: list[str] = []
    operation_id = operation.get("operation_id")
    if not isinstance(operation_id, str) or not operation_id.strip():
        problems.append(f"operationId must be a non-empty string, got {operation_id!r}")
    method = operation.get("method")
    if not isinstance(method, str) or not method.strip():
        problems.append(f"method must be a non-empty string, got {method!r}")
    elif method.strip().upper() not in SUPPORTED_METHODS:
        problems.append(f"method {method!r} is not a supported HTTP method")
    path = operation.get("path")
    if not isinstance(path, str) or not path.strip():
        problems.append(f"path must be a non-empty string, got {path!r}")
    elif not path.startswith("/"):
        problems.append(f"path {path!r} must be an absolute OpenAPI route starting with '/'")
    return problems


def _raw_permission_evidence(raw: Any, operation_id: str) -> tuple[bool, list[str]]:
    """Parse CSV permission metadata; malformed JSON is a readable failure."""
    if raw in (None, ""):
        return False, []
    parsed = _json_column(raw, "token_permissions_raw", operation_id)
    if not isinstance(parsed, dict):
        return False, []
    return permission_evidence(parsed)


def _json_column(value: Any, field: str, operation_id: str) -> Any:
    if value in (None, "", "null"):
        return None
    if not isinstance(value, str):
        return value
    try:
        return json.loads(value)
    except json.JSONDecodeError as error:
        raise ValueError(f"{operation_id}: catalog column {field} is not valid JSON: {error}") from error


def normalize_operations(operations: list[dict]) -> list[dict]:
    """Normalize raw enumerated operations or catalog CSV rows.

    Both input shapes are accepted so the ledger can be validated against the
    generated CSV or against ``enumerate_operations`` output. A missing field is
    left unknown rather than treated as a positive qualification, and malformed
    permission JSON is a readable failure instead of a granted permission.
    """
    normalized: list[dict] = []
    for operation in operations:
        if not isinstance(operation, dict):
            raise ValueError(f"operation must be a mapping, got {type(operation).__name__}")
        problems = _identity_problems(operation)
        if problems:
            raise ValueError("invalid operation identity: " + "; ".join(problems))
        operation_id = operation["operation_id"]
        method = operation["method"].strip().upper()
        path = operation["path"]
        metadata = operation.get("metadata")
        if isinstance(metadata, dict):
            tags = operation.get("tags") or []
            availability = metadata.get("x-fern-availability", "unspecified")
            stability = metadata.get("x-stability", "unspecified")
            permissions_present, permission_shape_errors = permission_evidence(metadata)
            plan_availability = metadata.get("x-cfPlanAvailability") or {}
            forge_hidden = metadata.get("x-forge-hidden")
            fern_ignore = metadata.get("x-fern-ignore")
            signals = deprecation_signals(metadata)
            deprecated_boolean = bool(metadata.get("deprecated"))
            summary = metadata.get("summary") or ""
            flarego_status = operation.get("flarego_status")
            coverage_note = operation.get("coverage_note")
        else:
            raw_tags = operation.get("tags")
            tags = raw_tags.split(" | ") if isinstance(raw_tags, str) else list(raw_tags or [])
            availability = operation.get("official_availability", "unspecified")
            stability = operation.get("official_stability", "unspecified")
            permissions_present, permission_shape_errors = _raw_permission_evidence(
                operation.get("token_permissions_raw"), operation_id
            )
            plan_availability = _json_column(
                operation.get("plan_availability_raw"), "plan_availability_raw", operation_id
            ) or {}
            forge_hidden = _json_column(operation.get("forge_hidden"), "forge_hidden", operation_id)
            fern_ignore = _json_column(operation.get("fern_ignore"), "fern_ignore", operation_id)
            raw_signals = operation.get("deprecation_signals") or ""
            signals = [signal for signal in raw_signals.split(" | ") if signal]
            deprecated_boolean = str(operation.get("deprecated_boolean", "")).lower() == "true"
            summary = operation.get("summary") or ""
            flarego_status = operation.get("flarego_status")
            coverage_note = operation.get("coverage_note")
        if not isinstance(plan_availability, dict):
            plan_availability = {}
        normalized.append(
            {
                "operation_id": operation_id,
                "method": method,
                "path": path,
                "tags": [tag for tag in tags if tag],
                "availability": availability,
                "stability": stability,
                "permissions_present": bool(permissions_present),
                "permission_shape_errors": list(permission_shape_errors),
                "plan_availability": plan_availability,
                "forge_hidden": forge_hidden,
                "fern_ignore": fern_ignore,
                "deprecation_signals": signals,
                "deprecated_boolean": deprecated_boolean,
                "summary": summary,
                "flarego_status": flarego_status,
                "coverage_note": coverage_note,
            }
        )
    return normalized


def identity_errors(operations: list[dict]) -> list[str]:
    """Report duplicate operationId or duplicate METHOD+path identities.

    Returned as readable errors so validation can fail closed without raising;
    generation and summarization fail explicitly instead.
    """
    errors: list[str] = []
    try:
        normalized = normalize_operations(operations)
    except ValueError as error:
        return [f"invalid operation input: {error}"]
    seen_ids: dict[str, str] = {}
    seen_identities: dict[tuple[str, str], str] = {}
    for operation in normalized:
        operation_id = operation["operation_id"]
        identity = (operation["method"], operation["path"])
        if operation_id in seen_ids:
            errors.append(
                f"duplicate operationId in input: {operation_id!r} appears more than once"
            )
        else:
            seen_ids[operation_id] = operation_id
        if identity in seen_identities:
            errors.append(
                f"duplicate METHOD+path in input: {identity[0]} {identity[1]} is claimed by "
                f"{seen_identities[identity]!r} and {operation_id!r}"
            )
        else:
            seen_identities[identity] = operation_id
    return errors


def _require_unique_identities(operations: list[dict]) -> list[dict]:
    errors = identity_errors(operations)
    if errors:
        raise ValueError("invalid operation identities: " + "; ".join(errors))
    return normalize_operations(operations)


def assign_product(operation: dict) -> tuple[dict, str]:
    """Return the owning package and the recorded assignment basis."""
    path = operation["path"]
    for prefix, product_id in PATH_OVERRIDES:
        if path.startswith(prefix):
            return PACKAGE_BY_ID[product_id], f"path_prefix: {prefix}"
    for tag in operation["tags"]:
        package = TAG_INDEX.get(tag)
        if package is not None:
            return package, f"tag: {tag}"
    first = operation["tags"][0] if operation["tags"] else "<no tags>"
    return UNCLASSIFIED_PACKAGE, f"tag: {first}"


def scope_for(operation: dict) -> tuple[str, str]:
    """Return the API authorization/resource scope and the evidence for it.

    ``scope_kind`` describes account/zone/user/organization/global/principal
    boundaries only; the source support status lives in ``evidence.source_support``.
    Unknown scopes stay explicitly unknown instead of silently becoming global.
    """
    path = operation["path"]
    for prefix, kind, basis in SCOPE_PATH_RULES:
        if path.startswith(prefix):
            return kind, basis
    if path in SCOPE_GLOBAL_EXACT:
        return "global", SCOPE_GLOBAL_EXACT[path]
    for prefix, basis in SCOPE_GLOBAL_PREFIXES:
        if path.startswith(prefix):
            return "global", basis
    for prefix, basis in SCOPE_UNKNOWN_PREFIXES:
        if path.startswith(prefix):
            return "unknown", basis
    if path in SCOPE_UNKNOWN_EXACT:
        return "unknown", SCOPE_UNKNOWN_EXACT[path]
    if "{" not in path:
        return "principal", SCOPE_PRINCIPAL_BASIS
    return "unknown", "path id scope does not match a known scope kind; needs review"


def plan_availability_flag(operation: dict) -> str:
    """Reduce the raw plan availability metadata to a reviewable flag."""
    plan = operation["plan_availability"]
    values = [value for value in plan.values() if isinstance(value, bool)]
    if not values:
        return "unknown"
    return "some_plan_enabled" if any(values) else "no_plan_enabled"


def semantics_hint(method: str) -> str:
    """Method-derived hint only; POST stays unknown because parameters decide."""
    if method in READ_METHODS:
        return "read"
    if method in WRITE_METHODS:
        return "write"
    return "unknown"


def risk_hint(method: str) -> str:
    if method == "DELETE":
        return "high"
    if method in ("PUT", "PATCH"):
        return "medium"
    if method in READ_METHODS:
        return "low"
    return "unknown"


def deprecation_decision(operation: dict) -> str:
    explicit = operation["deprecated_boolean"] or any(
        signal in ("deprecated=true", "x-fern-availability=deprecated")
        for signal in operation["deprecation_signals"]
    )
    if explicit:
        return "deprecated"
    if operation["deprecation_signals"]:
        return "migration_required"
    return "active"


def availability_decision(operation: dict) -> str:
    if plan_availability_flag(operation) == "no_plan_enabled":
        return "restricted"
    return "documented_unverified"


def derived_blockers(operation: dict) -> list[str]:
    """Blockers derived from pinned facts, independent of hand-written evidence.

    A ledger edit cannot whitelist an operation by deleting ``evidence.blockers``:
    missing or malformed permission metadata, a Forge-hidden-only flag, an
    unreviewed write, an unclassified product or a route family whose
    qualification is still pending all block because the fact is re-derived here.
    """
    blockers: list[str] = []
    if not operation["permissions_present"]:
        blockers.append(BLOCKER_PERMISSION_ABSENT)
    if operation.get("permission_shape_errors"):
        # One valid permission field must never mask a sibling field whose inner
        # shape is invalid; that invalidity is itself a fact-derived blocker.
        blockers.append(BLOCKER_PERMISSION_SHAPE)
    if operation["availability"] == "generally-available" and _RESTRICTED_HINT.search(
        operation["summary"]
    ):
        blockers.append(BLOCKER_AVAILABILITY_CONFLICT)
    if deprecation_decision(operation) == "migration_required" and (
        operation["availability"] == "generally-available"
    ):
        blockers.append(BLOCKER_DEPRECATION_CONFLICT)
    if operation["forge_hidden"] is True:
        blockers.append(BLOCKER_FORGE_HIDDEN)
    if semantics_hint(operation["method"]) != "read":
        blockers.append(BLOCKER_RISK_REVIEW)
    package, _ = assign_product(operation)
    blockers.extend(package.get("qualification_blockers", ()))
    if package["product_id"] == UNCLASSIFIED_PRODUCT_ID:
        blockers.append(BLOCKER_UNCLASSIFIED)
    if is_k2_data_plane(operation["path"]):
        blockers.append(BLOCKER_K2_HOST)
    return sorted(set(blockers))


def is_k2_data_plane(path: str) -> bool:
    """K2 stream data-plane routes use a separate stream host, not the API base."""
    return path == "/produce" or path.startswith("/subscriptions")


def recorded_blockers(entry: dict) -> list[str]:
    evidence = entry.get("evidence") if isinstance(entry, dict) else None
    if not isinstance(evidence, dict):
        return []
    blockers = evidence.get("blockers")
    return [code for code in blockers if isinstance(code, str)] if isinstance(blockers, list) else []


def effective_blockers(operation: dict, entry: dict) -> list[str]:
    """Union of fact-derived and recorded blockers (facts cannot be washed out)."""
    blockers = set(derived_blockers(operation)) | set(recorded_blockers(entry))
    evidence = entry.get("evidence") if isinstance(entry, dict) else None
    if not isinstance(evidence, dict) or not evidence:
        blockers.add(BLOCKER_MALFORMED_EVIDENCE)
    return sorted(blockers)


def _account_blocker_matches(review: dict) -> list[dict]:
    blockers = review.get("blockers") if isinstance(review, dict) else None
    if not isinstance(blockers, list):
        return []
    return [
        blocker
        for blocker in blockers
        if isinstance(blocker, dict) and blocker.get("code") == GLOBAL_ACCOUNT_BLOCKER
    ]


def _clearance_complete(blocker: dict) -> bool:
    cleared_by = blocker.get("cleared_by")
    record = blocker.get("clearance_record")
    has_reference = isinstance(cleared_by, str) and bool(cleared_by.strip())
    has_record = isinstance(record, dict) and bool(record)
    return has_reference and has_record


def _blockers_structure_errors(review: dict) -> list[str]:
    """Structure errors of ``review.blockers`` shared by validation and qualification.

    Both entry points must agree: a list that contains structurally damaged
    records can never be interpreted as an account-gate clearance.
    """
    blockers = review.get("blockers") if isinstance(review, dict) else None
    if not isinstance(blockers, list):
        return ["review.blockers must be a list of global blocker records"]
    errors: list[str] = []
    for blocker in blockers:
        if not isinstance(blocker, dict) or not isinstance(blocker.get("code"), str):
            errors.append(
                f"review.blockers items must be objects with a string code, got {blocker!r}"
            )
    return errors


def account_blocker_active(review: dict) -> bool:
    """True while authorized-account evidence is missing, empty or uncleared.

    Absence, omission, structural damage and malformed records are unknown/blocked,
    never clearance. Clearance needs exactly one well-formed record with an explicit
    reference plus a clearance record; duplicate or conflicting records cannot clear.
    """
    if _blockers_structure_errors(review):
        return True
    matches = _account_blocker_matches(review)
    if len(matches) != 1:
        return True
    return not _clearance_complete(matches[0])


def global_blocker_errors(review: dict) -> list[str]:
    """Readable structure errors for the global account-blocker records."""
    errors = list(_blockers_structure_errors(review))
    matches = _account_blocker_matches(review)
    if len(matches) > 1:
        errors.append(
            f"review.blockers has {len(matches)} duplicate {GLOBAL_ACCOUNT_BLOCKER!r} records; "
            "duplicate or conflicting account-blocker records cannot clear the gate"
        )
    for match in matches:
        attempted = "cleared_by" in match or "clearance_record" in match
        if attempted and not _clearance_complete(match):
            errors.append(
                f"review.blockers {GLOBAL_ACCOUNT_BLOCKER!r} has an incomplete clearance record "
                "(needs cleared_by plus a non-empty clearance_record); it cannot clear the gate"
            )
    return errors


def _pending_qualification(entry: dict) -> bool:
    return entry.get("needs_review") is not False or entry.get("scope_kind") == "unknown"


def _verified(entry: dict, operation: dict, account_blocked: bool) -> bool:
    """API availability verified against an authorized account (not FlareGo capability)."""
    evidence = entry.get("evidence") if isinstance(entry, dict) else None
    if not isinstance(evidence, dict):
        return False
    if entry.get("availability_decision") != "verified_available":
        return False
    if evidence.get("acceptance") != "authenticated_account":
        return False
    if not isinstance(evidence.get("acceptance_record"), dict) or not evidence["acceptance_record"]:
        return False
    if derived_blockers(operation) or recorded_blockers(entry):
        return False
    if _pending_qualification(entry):
        return False
    return not account_blocked


def _capability_verified(entry: dict, operation: dict, account_blocked: bool) -> bool:
    """FlareGo L2/L3 capability acceptance, reported separately from availability."""
    evidence = entry.get("evidence") if isinstance(entry, dict) else None
    if not isinstance(evidence, dict):
        return False
    if evidence.get("capability_acceptance") != "authenticated_account":
        return False
    record = evidence.get("capability_record")
    if not isinstance(record, dict) or not record:
        return False
    if derived_blockers(operation) or recorded_blockers(entry):
        return False
    if _pending_qualification(entry):
        return False
    return not account_blocked


def _prerequisites(operation: dict, entry: dict) -> list[str]:
    wanted = {
        "authorized_cloudflare_account",
        "isolated_test_scope",
    }
    if "{account_id}" in operation["path"]:
        wanted.add("account_scoped_token")
    if "{zone_id}" in operation["path"]:
        wanted.add("zone_scoped_token")
    if not operation["permissions_present"]:
        wanted.add("permission_metadata_evidence")
    if entry["availability_decision"] == "restricted":
        wanted.add("plan_entitlement_evidence")
    if entry["semantics"] != "read":
        wanted.add("manual_risk_review")
        wanted.add("rollback_or_recovery_check")
    if entry["deprecation_decision"] != "active":
        wanted.add("migration_decision")
    if entry["product_id"] == UNCLASSIFIED_PRODUCT_ID:
        wanted.add("product_ownership_decision")
    if is_k2_data_plane(operation["path"]):
        wanted.add("separate_stream_host_acceptance")
    if entry["evidence"].get("classification_evidence"):
        wanted.add("route_family_qualification_review")
    return [name for name in PREREQUISITE_ORDER if name in wanted]


def _entry_for(
    operation: dict,
    package: dict,
    basis: str,
    subset_entry: dict | None,
    override: dict | None = None,
) -> dict:
    override = override or {}
    product_id = package["product_id"]
    semantics = semantics_hint(operation["method"])
    scope, scope_basis = scope_for(operation)
    source_support = "source_subset" if subset_entry else "unimplemented"
    approvals = sorted(set(override.get("approvals") or []))
    if subset_entry:
        approvals = sorted(set(approvals) | {APPROVAL_P1_COMPAT})
    if not approvals:
        approvals = [APPROVAL_FUTURE]
    for approval in approvals:
        if approval not in IMPLEMENTATION_APPROVALS:
            raise ValueError(f"unknown implementation approval {approval!r}")
    if subset_entry:
        phase = "P1"
        phase_basis = "existing_compat_subset"
    elif override.get("phase"):
        phase = override["phase"]
        phase_basis = "approved_operation_list"
    else:
        phase = package["phase"]
        phase_basis = "product_package_default"
    if phase not in PHASES:
        raise ValueError(f"phase {phase!r} must be one of {', '.join(PHASES)}")
    work_package = override.get("work_package") or (
        P1_COMPAT_WORK_PACKAGE if subset_entry else product_id
    )
    entry: dict[str, Any] = {
        "product_id": product_id,
        "phase": phase,
        "owner_role": DEFAULT_OWNER_ROLE,
        "scope_kind": scope,
        "protocol": "rest",
        "semantics": semantics,
        "risk": risk_hint(operation["method"]),
        "pagination": "unreviewed",
        "availability_decision": availability_decision(operation),
        "deprecation_decision": deprecation_decision(operation),
        "target_level": package["target_level"],
        "test_prerequisites": [],
        "needs_review": True,
        "evidence": {
            "assignment": basis,
            "spec_reference": package["spec_reference"],
            "scope_basis": scope_basis,
            "source_support": source_support,
            "phase_basis": phase_basis,
            "work_package": work_package,
            "approvals": approvals,
            "availability_raw": {
                "x-fern-availability": operation["availability"],
                "x-stability": operation["stability"],
            },
            "permissions_present": operation["permissions_present"],
            "plan_availability": plan_availability_flag(operation),
            "deprecation_signals": operation["deprecation_signals"],
            "forge_hidden": operation["forge_hidden"],
            "fern_ignore": operation["fern_ignore"],
            "blockers": derived_blockers(operation),
        },
    }
    if subset_entry:
        entry["evidence"]["source_scope"] = subset_entry.get("scope", "")
        entry["evidence"]["source_file"] = subset_entry.get("source_file", "")
        entry["evidence"]["test_evidence"] = subset_entry.get("test_evidence", "")
    if product_id == UNCLASSIFIED_PRODUCT_ID:
        tag = basis.split(": ", 1)[-1]
        entry["evidence"]["gap"] = UNCLASSIFIED_GAPS.get(tag, DEFAULT_UNCLASSIFIED_GAP)
    if package.get("classification_evidence"):
        entry["evidence"]["classification_evidence"] = package["classification_evidence"]
    if is_k2_data_plane(operation["path"]):
        entry["evidence"]["protocol_review"] = {
            "host": K2_STREAM_HOST,
            "note": K2_STREAM_HOST_NOTE,
        }
    entry["test_prerequisites"] = _prerequisites(operation, entry)
    return entry


def propose_review(
    operations: list[dict],
    implemented: dict,
    *,
    phase_overrides: dict[str, dict] | None = None,
    provenance: dict | None = None,
) -> dict:
    """Build the review ledger proposal for every operation.

    ``phase_overrides`` carries the explicitly approved per-operation lists
    (P1 requests, P1 compat set, P2 candidates). Everything else keeps its
    long-term product batch and is marked ``future_batch_not_yet_approved``.
    The result is deterministic, records each proposal's assignment basis and
    keeps every entry in ``needs_review``; it is not a user approval.
    """
    normalized = _require_unique_identities(operations)
    overrides = dict(phase_overrides or {})
    mapping = implemented.get("operations", {}) if isinstance(implemented, dict) else {}
    if not isinstance(mapping, dict):
        raise ValueError("implemented mapping must contain an 'operations' object")
    known_ids = {operation["operation_id"] for operation in normalized}
    for label, references in (
        ("implemented mapping", set(mapping)),
        ("phase override", set(overrides)),
    ):
        unknown = sorted(references - known_ids)
        if unknown:
            raise ValueError(
                f"{label} references operationId(s) absent from the input operations: "
                + ", ".join(unknown)
            )
    entries: dict[str, dict] = {}
    for operation in sorted(normalized, key=lambda item: (item["path"], item["method"])):
        operation_id = operation["operation_id"]
        subset_entry = mapping.get(operation_id)
        if subset_entry is not None:
            declared = (str(subset_entry.get("method", "")).upper(), subset_entry.get("path"))
            if declared != (operation["method"], operation["path"]):
                raise ValueError(
                    f"implemented mapping {operation_id!r} declares {declared} but the catalog "
                    f"has {(operation['method'], operation['path'])}"
                )
        package, basis = assign_product(operation)
        entries[operation_id] = _entry_for(
            operation, package, basis, subset_entry, overrides.get(operation_id)
        )
    document = {
        "format": REVIEW_FORMAT,
        "generated_from": {
            "tool": "tools/cloudflare_audit/review.py propose_review",
            "review_state": "proposed_not_approved",
            "owner_role_default": DEFAULT_OWNER_ROLE,
            "acceptance_owner_role": ACCEPTANCE_OWNER_ROLE,
            "scope_kind_meaning": (
                "API authorization/resource scope only (account/zone/user/organization/global/"
                "principal/unknown); source support lives in evidence.source_support"
            ),
            "phase_meaning": (
                "planned batch for the remaining product work; evidence.approvals lists the "
                "currently approved implementation lists"
            ),
            "raw_evidence": (
                f"{CATALOG_PATH} keyed by operation_id: official_availability, official_stability, "
                "token_permissions_raw, plan_availability_raw, deprecation_signals, forge_hidden, "
                "fern_ignore, summary"
            ),
            "note": (
                "Machine proposal from pinned schema metadata plus the reviewed source subset. "
                "It is not a user approval, not an execution allowlist, not a runtime write "
                "permission and not an authenticated acceptance result."
            ),
            **(provenance or {}),
        },
        "blockers": [dict(blocker) for blocker in GLOBAL_BLOCKERS],
        "operations": entries,
    }
    return document


def load_p2_candidates(path: Path) -> dict[str, dict]:
    """Read the approved P2 candidate list (offline, read-only)."""
    with Path(path).open(encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
    candidates: dict[str, dict] = {}
    for row in rows:
        operation_id = row.get("operation_id")
        if not operation_id:
            raise ValueError(f"P2 candidate row without operation_id: {row!r}")
        if operation_id in candidates:
            raise ValueError(f"duplicate operation_id in the P2 candidate list: {operation_id!r}")
        candidates[operation_id] = row
    return candidates


def phase_overrides_for(
    implemented: dict, p2_candidates: dict[str, dict] | None = None
) -> dict[str, dict]:
    """Build the explicit per-operation phase/approval overrides.

    Only the approved lists get an approval code; broad tag membership never
    implies that a whole product may be implemented early.
    """
    overrides: dict[str, dict] = {}
    for operation_id in P1_APPROVED_NEW_REQUESTS:
        overrides[operation_id] = {
            "phase": "P1",
            "approvals": [APPROVAL_P1_NEW],
            "work_package": P1_APPROVED_NEW_WORK_PACKAGE,
        }
    mapping = implemented.get("operations", {}) if isinstance(implemented, dict) else {}
    for operation_id in mapping:
        entry = overrides.setdefault(operation_id, {})
        entry.setdefault("approvals", [])
        entry["approvals"] = sorted(set(entry["approvals"]) | {APPROVAL_P1_COMPAT})
        entry.setdefault("work_package", P1_COMPAT_WORK_PACKAGE)
    for operation_id, row in (p2_candidates or {}).items():
        entry = overrides.setdefault(operation_id, {})
        entry["approvals"] = sorted(
            set(entry.get("approvals") or []) | {APPROVAL_P2_CANDIDATE}
        )
        entry["work_package"] = row.get("proposed_task") or entry.get("work_package", "")
        entry.setdefault("phase", "P2")
    return overrides


def load_review(path: Path) -> dict:
    """Load the ledger, rejecting duplicate JSON keys instead of overwriting."""
    text = Path(path).read_text(encoding="utf-8")

    def no_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict:
        seen: dict[str, Any] = {}
        for key, value in pairs:
            if key in seen:
                raise ValueError(f"duplicate JSON key in review ledger: {key!r}")
            seen[key] = value
        return seen

    try:
        document = json.loads(text, object_pairs_hook=no_duplicate_keys)
    except json.JSONDecodeError as error:
        raise ValueError(f"{path} is not valid JSON: {error}") from error
    if not isinstance(document, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return document


def load_catalog_operations(path: Path) -> list[dict]:
    """Read the generated catalog CSV as operations (offline, read-only)."""
    with Path(path).open(encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def _user_approval_reference(evidence: dict) -> bool:
    """Require a real user reference; booleans, bare strings and coordinator notes do not count."""
    approval = evidence.get("user_approval")
    if not isinstance(approval, dict):
        return False
    if approval.get("source") != "user":
        return False
    reference = approval.get("reference")
    return isinstance(reference, str) and bool(reference.strip())


def _validate_entry(operation_id: str, entry: Any, operation: dict) -> list[str]:
    errors: list[str] = []
    if not isinstance(entry, dict):
        return [f"{operation_id}: review entry must be an object"]
    for field in REVIEW_FIELDS:
        if field not in entry:
            errors.append(f"{operation_id}: review entry is missing required field {field}")
    checks = (
        ("phase", PHASES),
        ("owner_role", OWNER_ROLES),
        ("scope_kind", SCOPE_KINDS),
        ("protocol", PROTOCOLS),
        ("semantics", SEMANTICS),
        ("risk", RISKS),
        ("pagination", PAGINATION_VALUES),
        ("availability_decision", AVAILABILITY_DECISIONS),
        ("deprecation_decision", DEPRECATION_DECISIONS),
        ("target_level", TARGET_LEVELS),
    )
    for field, allowed in checks:
        if field not in entry:
            continue
        value = entry[field]
        if not isinstance(value, str) or value not in allowed:
            errors.append(
                f"{operation_id}: {field}={value!r} must be one of {', '.join(allowed)}"
            )
    if not isinstance(entry.get("product_id"), str) or not entry.get("product_id"):
        errors.append(f"{operation_id}: product_id must be a non-empty string")
    prerequisites = entry.get("test_prerequisites")
    if not isinstance(prerequisites, list) or not prerequisites or not all(
        isinstance(item, str) and item for item in prerequisites
    ):
        errors.append(f"{operation_id}: test_prerequisites must be a non-empty list of names")
    if not isinstance(entry.get("needs_review"), bool):
        errors.append(f"{operation_id}: needs_review must be a boolean")
    evidence = entry.get("evidence")
    if not isinstance(evidence, dict) or not evidence:
        errors.append(f"{operation_id}: evidence must be a non-empty object")
        return errors

    if entry.get("target_level") == "L0":
        reason = evidence.get("reduction_reason")
        if not isinstance(reason, str) or not reason.strip():
            errors.append(
                f"{operation_id}: target_level L0 requires a concrete evidence.reduction_reason"
            )
        if not _user_approval_reference(evidence):
            errors.append(
                f"{operation_id}: target_level L0 requires a real user approval reference "
                "(evidence.user_approval.source == 'user' plus a non-empty reference)"
            )
    if entry.get("availability_decision") == "verified_available" and (
        evidence.get("acceptance") != "authenticated_account"
    ):
        errors.append(
            f"{operation_id}: availability_decision verified_available requires "
            "evidence.acceptance == 'authenticated_account'"
        )
    if entry.get("availability_decision") == "verified_available" and not (
        isinstance(evidence.get("acceptance_record"), dict) and evidence.get("acceptance_record")
    ):
        errors.append(
            f"{operation_id}: availability_decision verified_available requires a structured "
            "evidence.acceptance_record from an authorized account"
        )
    if entry.get("availability_decision") in ("provider_removed", "console_only"):
        key = (
            "removal_evidence"
            if entry["availability_decision"] == "provider_removed"
            else "console_only_evidence"
        )
        if not evidence.get(key):
            errors.append(
                f"{operation_id}: availability_decision {entry['availability_decision']} requires "
                f"{key}"
            )
        reason = evidence.get("reduction_reason")
        if not isinstance(reason, str) or not reason.strip():
            errors.append(
                f"{operation_id}: availability_decision {entry['availability_decision']} requires "
                "a concrete evidence.reduction_reason"
            )
        if not _user_approval_reference(evidence):
            errors.append(
                f"{operation_id}: availability_decision {entry['availability_decision']} requires "
                "a real user approval reference (evidence.user_approval)"
            )
    capability_record = evidence.get("capability_record")
    if "capability_acceptance" in evidence and (
        not isinstance(capability_record, dict) or not capability_record
    ):
        errors.append(
            f"{operation_id}: capability_acceptance requires a structured evidence.capability_record"
        )
    if entry.get("product_id") == UNCLASSIFIED_PRODUCT_ID:
        if entry.get("needs_review") is not True:
            errors.append(
                f"{operation_id}: unclassified-review entries must keep needs_review=true"
            )
        if not evidence.get("gap"):
            errors.append(
                f"{operation_id}: unclassified-review entries must record a concrete evidence.gap"
            )
    blockers = evidence.get("blockers")
    if not isinstance(blockers, list):
        errors.append(f"{operation_id}: evidence.blockers must be a list")
    else:
        for code in blockers:
            if not isinstance(code, str) or not code:
                errors.append(
                    f"{operation_id}: evidence.blockers items must be non-empty strings, got {code!r}"
                )
        if entry.get("semantics") != "read" and BLOCKER_RISK_REVIEW not in blockers:
            errors.append(
                f"{operation_id}: semantics {entry.get('semantics')!r} must stay blocked with "
                f"{BLOCKER_RISK_REVIEW}"
            )
        missing = sorted(set(derived_blockers(operation)) - set(recorded_blockers(entry)))
        if missing:
            errors.append(
                f"{operation_id}: evidence.blockers omits fact-derived blocker(s) "
                f"{', '.join(missing)}; the summary derives them from the catalog metadata regardless"
            )
    approvals = evidence.get("approvals")
    if not isinstance(approvals, list) or not approvals:
        errors.append(f"{operation_id}: evidence.approvals must list at least one approval code")
    else:
        for approval in approvals:
            if approval not in IMPLEMENTATION_APPROVALS:
                errors.append(f"{operation_id}: unknown implementation approval {approval!r}")
    for problem in operation.get("permission_shape_errors", []):
        errors.append(f"{operation_id}: {problem}")
    support = evidence.get("source_support")
    if support not in SOURCE_SUPPORT_VALUES:
        errors.append(
            f"{operation_id}: evidence.source_support={support!r} must be one of "
            f"{', '.join(SOURCE_SUPPORT_VALUES)}"
        )
    if not evidence.get("scope_basis"):
        errors.append(f"{operation_id}: evidence.scope_basis must record why the scope was chosen")
    if not evidence.get("work_package"):
        errors.append(f"{operation_id}: evidence.work_package must name the owning work package")

    status = operation.get("flarego_status")
    if status is not None:
        if status == "implemented_subset" and support != "source_subset":
            errors.append(
                f"{operation_id}: catalog says implemented_subset so evidence.source_support must be "
                f"source_subset, not {support!r}"
            )
        if status != "implemented_subset" and support == "source_subset":
            errors.append(
                f"{operation_id}: evidence claims source_support=source_subset but the catalog "
                f"says {status!r}"
            )
    return errors


def validate_review(
    operations: list[dict], review: dict, *, selected_ids: set[str] | None = None
) -> list[str]:
    """Return readable ledger errors without modifying the ledger.

    Identity corruption (duplicate operationId or duplicate METHOD+path) is a
    global input problem and is reported before any selected_ids scoping, so a
    scoped review can never silently authorize a corrupted catalog.
    """
    identity = identity_errors(operations)
    if identity:
        return identity
    normalized = normalize_operations(operations)
    if not isinstance(review, dict) or not isinstance(review.get("operations"), dict):
        return ["review document must contain an 'operations' object indexed by operationId"]
    entries = review["operations"]
    known = {operation["operation_id"] for operation in normalized}

    errors: list[str] = []
    errors.extend(global_blocker_errors(review))
    if selected_ids is not None:
        for operation_id in sorted(set(selected_ids) - known):
            errors.append(f"selected_ids references unknown operationId: {operation_id}")
    scope = known if selected_ids is None else set(selected_ids)

    for operation_id in sorted(set(entries) - known):
        if selected_ids is None or operation_id in selected_ids:
            errors.append(f"review registers unknown operationId: {operation_id}")

    for operation in normalized:
        operation_id = operation["operation_id"]
        if operation_id not in scope:
            continue
        entry = entries.get(operation_id)
        if entry is None:
            errors.append(f"missing review entry for operationId: {operation_id}")
            continue
        errors.extend(_validate_entry(operation_id, entry, operation))
    return errors


def is_implemented(operation: dict, entry: dict) -> bool:
    """Source-level support comes from the catalog fact, else from recorded evidence."""
    status = operation.get("flarego_status")
    if status is not None:
        return status == "implemented_subset"
    evidence = entry.get("evidence") if isinstance(entry, dict) else None
    return isinstance(evidence, dict) and evidence.get("source_support") == "source_subset"


def summarize_review(operations: list[dict], review: dict) -> dict:
    """Count registered/target/implemented/verified/legacy/blocked and breakdowns.

    ``verified`` is API availability proven against an authorized account and
    ``capability_verified`` is FlareGo L2/L3 capability acceptance; they are
    reported separately and both stay 0 while no authorized account exists.
    """
    normalized = _require_unique_identities(operations)
    entries = review.get("operations") if isinstance(review, dict) else None
    if not isinstance(entries, dict):
        raise ValueError("review document must contain an 'operations' object")
    account_blocked = account_blocker_active(review)

    report: dict[str, Any] = {
        "registered": 0,
        "target": 0,
        "implemented": 0,
        "verified": 0,
        "capability_verified": 0,
        "legacy": 0,
        "blocked": 0,
        "needs_review": 0,
        "unclassified": 0,
        "risk_review_required": 0,
        "by_phase": Counter(),
        "by_product": {},
        "by_target_level": Counter(),
        "by_availability_decision": Counter(),
        "by_semantics": Counter(),
        "by_scope_kind": Counter(),
        "by_source_support": Counter(),
        "by_deprecation_decision": Counter(),
        "by_implementation_approval": Counter(),
        "blockers": Counter(),
        "authorized_account_blocker_active": account_blocked,
    }
    for operation in normalized:
        entry = entries.get(operation["operation_id"])
        if not isinstance(entry, dict):
            continue
        evidence = entry.get("evidence") if isinstance(entry.get("evidence"), dict) else {}
        blockers = effective_blockers(operation, entry)
        product_id = entry.get("product_id", "<missing>")
        report["registered"] += 1
        if entry.get("target_level") in ("L2", "L3"):
            report["target"] += 1
        if is_implemented(operation, entry):
            report["implemented"] += 1
        if _verified(entry, operation, account_blocked):
            report["verified"] += 1
        if _capability_verified(entry, operation, account_blocked):
            report["capability_verified"] += 1
        if entry.get("deprecation_decision") != "active":
            report["legacy"] += 1
        if blockers:
            report["blocked"] += 1
        if entry.get("needs_review") is True:
            report["needs_review"] += 1
        if product_id == UNCLASSIFIED_PRODUCT_ID:
            report["unclassified"] += 1
        if BLOCKER_RISK_REVIEW in blockers:
            report["risk_review_required"] += 1
        report["by_phase"][entry.get("phase", "<missing>")] += 1
        report["by_target_level"][entry.get("target_level", "<missing>")] += 1
        report["by_availability_decision"][
            entry.get("availability_decision", "<missing>")
        ] += 1
        report["by_semantics"][entry.get("semantics", "<missing>")] += 1
        report["by_scope_kind"][entry.get("scope_kind", "<missing>")] += 1
        report["by_source_support"][evidence.get("source_support", "<missing>")] += 1
        report["by_deprecation_decision"][
            entry.get("deprecation_decision", "<missing>")
        ] += 1
        for approval in evidence.get("approvals") if isinstance(evidence.get("approvals"), list) else []:
            if isinstance(approval, str):
                report["by_implementation_approval"][approval] += 1
        for code in blockers:
            report["blockers"][code] += 1
        product = report["by_product"].setdefault(
            product_id,
            {
                "owner_role": entry.get("owner_role"),
                "spec_reference": evidence.get("spec_reference", ""),
                "phases": [],
                "by_phase": Counter(),
                "registered": 0,
                "target": 0,
                "implemented": 0,
                "blocked": 0,
                "needs_review": 0,
            },
        )
        product["by_phase"][entry.get("phase", "<missing>")] += 1
        product["registered"] += 1
        if entry.get("target_level") in ("L2", "L3"):
            product["target"] += 1
        if is_implemented(operation, entry):
            product["implemented"] += 1
        if blockers:
            product["blocked"] += 1
        if entry.get("needs_review") is True:
            product["needs_review"] += 1
    for key in (
        "by_phase",
        "by_target_level",
        "by_availability_decision",
        "by_semantics",
        "by_scope_kind",
        "by_source_support",
        "by_deprecation_decision",
        "by_implementation_approval",
        "blockers",
    ):
        report[key] = dict(sorted(report[key].items()))
    for product in report["by_product"].values():
        product["by_phase"] = dict(sorted(product["by_phase"].items()))
        product["phases"] = sorted(product["by_phase"])
    report["by_product"] = dict(sorted(report["by_product"].items()))
    return report


def cross_check_schema(schema: dict, catalog_operations: list[dict], review: dict) -> list[str]:
    """Optional deep check: schema enumeration vs catalog vs ledger identities.

    Compares operationId to METHOD+path bindings, not just ID sets, so a row that
    keeps an ID but changes its route is reported.
    """
    errors: list[str] = []
    enumerated = {
        operation["operation_id"]: (operation["method"], operation["path"])
        for operation in enumerate_operations(schema)
    }
    catalog = {
        operation["operation_id"]: (operation["method"], operation["path"])
        for operation in normalize_operations(catalog_operations)
    }
    entries = review.get("operations", {}) if isinstance(review, dict) else {}
    for operation_id in sorted(set(enumerated) - set(catalog)):
        errors.append(f"schema operation missing from the catalog CSV: {operation_id}")
    for operation_id in sorted(set(catalog) - set(enumerated)):
        errors.append(f"catalog CSV operation is absent from the schema: {operation_id}")
    for operation_id in sorted(set(enumerated) & set(catalog)):
        if enumerated[operation_id] != catalog[operation_id]:
            errors.append(
                f"operationId {operation_id!r} has a different METHOD+path binding: schema "
                f"{enumerated[operation_id][0]} {enumerated[operation_id][1]} vs catalog "
                f"{catalog[operation_id][0]} {catalog[operation_id][1]}"
            )
    for operation_id in sorted(set(enumerated) - set(entries)):
        errors.append(f"schema operation has no review entry: {operation_id}")
    return errors


# --------------------------------------------------------------------------- #
# Independent protocol ledger (Task 4)
# --------------------------------------------------------------------------- #

PROTOCOLS_FORMAT = "flarego.cloudflare-audit.protocols/1"
S3_VERIFICATION_STATUSES = ("awaiting_authorized_environment", "provider_unsupported")
GRAPHQL_REVIEW_STATUSES = ("awaiting_authorized_schema", "verified")
ENVIRONMENT_STATUSES = ("awaiting_authorized_environment", "available", "blocked")
ADMISSION_RISKS = ("low", "medium", "high", "unknown")
FORBIDDEN_COMBINED_KEYS = (
    "total_coverage",
    "combined_denominator",
    "overall_coverage",
    "combined_total",
)
FORBIDDEN_DENOMINATOR_NAMES = ("total", "combined", "overall", "all")
NON_REST_DENOMINATORS = ("s3", "graphql", "realtime")
ENVIRONMENT_FIELDS = (
    "environment_id",
    "label",
    "plan",
    "allowed_action_categories",
    "owner_role",
    "permission_categories",
    "cleanup_responsibility",
    "status",
    "blockers",
    "notes",
)
ADMISSION_FIELDS = (
    "operation_id",
    "method",
    "path",
    "source",
    "risk",
    "confirmation_required",
    "verification_boundary",
    "proposed",
)
S3_REQUIRED_FIELDS = (
    "owner_role",
    "phase",
    "source_commit",
    "source_sha256",
    "source_quote_ref",
    "feature_table_status",
    "acceptance_target_level",
    "verification_status",
)
SENSITIVE_KEY_SUBSTRINGS = (
    "secret",
    "password",
    "private_key",
    "credential_path",
    "credentials_path",
    "payment",
    "card_number",
    "bearer",
    "authorization",
)
SENSITIVE_KEYS = frozenset(
    {
        "token",
        "api_token",
        "token_value",
        "access_key",
        "access_key_id",
        "account_id",
        "zone_id",
        "session_url",
        "cookie",
        "signing_key",
        "credential_value",
        "client_secret",
    }
)
CREDENTIAL_VALUE_PATTERNS = (
    re.compile(r"^ey[A-Za-z0-9_-]{8,}\."),
    re.compile(r"^AKIA[0-9A-Z]{16}$"),
    re.compile(r"^[0-9a-f]{32}$"),
    re.compile(r"^[A-Za-z0-9+/]{40,}={0,2}$"),
    re.compile(r"^(/|~)"),
    re.compile(r"^[^@\s]+@[^@\s]+\.[A-Za-z]{2,}$"),
    re.compile(r"^Bearer\s+\S{8,}$", re.IGNORECASE),
)


def _protocol_dict(value: Any, label: str, errors: list[str]) -> dict:
    if isinstance(value, dict):
        return value
    errors.append(f"{label} must be an object, got {type(value).__name__}")
    return {}


def _protocol_list(value: Any, label: str, errors: list[str]) -> list:
    if isinstance(value, list):
        return value
    errors.append(f"{label} must be a list, got {type(value).__name__}")
    return []


def _protocol_text(value: Any, label: str, errors: list[str]) -> str:
    if isinstance(value, str) and value.strip():
        return value
    errors.append(f"{label} must be a non-empty string, got {value!r}")
    return ""


def _csv_flag(value: Any) -> bool | None:
    """Strict CSV boolean: only the exact strings ``true``/``false`` count."""
    if value == "true":
        return True
    if value == "false":
        return False
    return None


def _sensitive_findings(label: str, node: Any) -> list[str]:
    """Recursively report credential-shaped keys or values in a registry entry."""
    findings: list[str] = []
    if isinstance(node, dict):
        for key, value in node.items():
            name = str(key).lower()
            if name in SENSITIVE_KEYS or any(part in name for part in SENSITIVE_KEY_SUBSTRINGS):
                findings.append(f"{label}.{key}: sensitive key must not be recorded in the environment registry")
                continue
            findings.extend(_sensitive_findings(f"{label}.{key}", value))
    elif isinstance(node, list):
        for index, value in enumerate(node):
            findings.extend(_sensitive_findings(f"{label}[{index}]", value))
    elif isinstance(node, str):
        for pattern in CREDENTIAL_VALUE_PATTERNS:
            if pattern.match(node):
                findings.append(
                    f"{label}: value looks like credential material or a local path and must not be recorded"
                )
                break
    return findings


def _catalog_index(catalog_operations: list[dict] | None, errors: list[str]) -> dict[str, tuple[str, str]] | None:
    if catalog_operations is None:
        return None
    index: dict[str, tuple[str, str]] = {}
    try:
        normalized = normalize_operations(catalog_operations)
    except ValueError as error:
        errors.append(f"catalog operations are not usable: {error}")
        return None
    for operation in normalized:
        index[operation["operation_id"]] = (operation["method"], operation["path"])
    return index


def _check_operation_reference(
    entry: Any, label: str, catalog: dict[str, tuple[str, str]] | None, errors: list[str]
) -> str:
    if not isinstance(entry, dict):
        errors.append(f"{label} must be an object, got {type(entry).__name__}")
        return ""
    operation_id = entry.get("operation_id")
    if not isinstance(operation_id, str) or not operation_id:
        errors.append(f"{label}.operation_id must be a non-empty string")
        return ""
    if catalog is None:
        return operation_id
    known = catalog.get(operation_id)
    if known is None:
        errors.append(f"{label}: unknown operationId {operation_id!r} is not present in the catalog")
        return operation_id
    declared = (entry.get("method"), entry.get("path"))
    if declared != known:
        errors.append(
            f"{label}: operationId {operation_id!r} has a different METHOD+path binding: "
            f"{declared[0]} {declared[1]} vs catalog {known[0]} {known[1]}"
        )
    return operation_id


def _s3_row_facts(rows: Any, errors: list[str]) -> dict:
    facts: dict[str, Any] = {
        "documented": 0,
        "vendor_supported": 0,
        "provider_unsupported": 0,
        "names": [],
        "verified": 0,
    }
    seen: dict[str, int] = {}
    for index, row in enumerate(_protocol_list(rows, "s3_rows", errors)):
        label = f"s3_rows[{index}]"
        if not isinstance(row, dict):
            errors.append(f"{label} must be an object, got {type(row).__name__}")
            continue
        name = row.get("operation")
        if not isinstance(name, str) or not name:
            errors.append(f"{label} has no operation name")
            continue
        if name in seen:
            errors.append(
                f"duplicate S3 operation {name!r} in s3_rows (indexes {seen[name]} and {index})"
            )
            continue
        seen[name] = index
        facts["documented"] += 1
        facts["names"].append(name)
        supported = _csv_flag(row.get("cloudflare_supports_operation"))
        if supported is None:
            errors.append(
                f"{label} {name}: cloudflare_supports_operation must be exactly 'true' or 'false', "
                f"got {row.get('cloudflare_supports_operation')!r}"
            )
            continue
        if supported:
            facts["vendor_supported"] += 1
        else:
            facts["provider_unsupported"] += 1
        for field in S3_REQUIRED_FIELDS:
            if not row.get(field):
                errors.append(f"{label} {name}: {field} is required")
        status = row.get("verification_status")
        if status not in S3_VERIFICATION_STATUSES:
            errors.append(
                f"{label} {name}: verification_status={status!r} must be one of "
                f"{', '.join(S3_VERIFICATION_STATUSES)}"
            )
        if not supported:
            if status != "provider_unsupported":
                errors.append(
                    f"{label} {name}: provider-unsupported rows must keep "
                    "verification_status=provider_unsupported"
                )
            if row.get("acceptance_target_level") != "n/a":
                errors.append(
                    f"{label} {name}: provider-unsupported rows are not FlareGo implementation "
                    "targets and must not carry an acceptance target level"
                )
            if row.get("test_prerequisites"):
                errors.append(
                    f"{label} {name}: provider-unsupported rows must not carry test prerequisites; "
                    "they are vendor limitations, not pending FlareGo work"
                )
        else:
            if row.get("acceptance_target_level") != "L2":
                errors.append(f"{label} {name}: vendor-supported rows need an explicit L2 target")
            if not row.get("test_prerequisites"):
                errors.append(f"{label} {name}: vendor-supported rows need test prerequisites")
        for field in ("flarego_implemented", "verified"):
            flag = _csv_flag(row.get(field))
            if flag is None:
                errors.append(f"{label} {name}: {field} must be exactly 'true' or 'false'")
            elif flag:
                errors.append(f"{label} {name}: {field}=true is not supported by any pinned evidence")
        if row.get("feature_table_status") == "not_listed_in_pinned_table" and (
            row.get("vendor_supported_features") or row.get("vendor_unsupported_features")
        ):
            errors.append(
                f"{label} {name}: feature_table_status says the pinned table lists no features, "
                "so no feature names may be recorded"
            )
        if _csv_flag(row.get("verified")) is True:
            evidence = row.get("verification_evidence")
            if not isinstance(evidence, dict) or not evidence.get("environment") or not evidence.get("source"):
                errors.append(
                    f"{label} {name}: verification_evidence must be a structured record with "
                    "environment and source before verified can be true"
                )
            else:
                facts["verified"] += 1
    return facts


def validate_protocols(
    protocols: dict, s3_rows: list[dict], catalog_operations: list[dict] | None = None
) -> list[str]:
    """Validate the independent protocol ledger. Read-only; returns readable errors.

    REST, S3, GraphQL and realtime keep separate denominators and can never be
    summed into a combined coverage claim. Missing or opaque evidence fails closed
    instead of becoming positive acceptance, and provider-unsupported S3 rows stay
    registered without becoming FlareGo implementation targets.
    """
    if not isinstance(protocols, dict):
        return [f"protocols ledger must be an object, got {type(protocols).__name__}"]
    errors: list[str] = []
    if protocols.get("format") != PROTOCOLS_FORMAT:
        errors.append(f"protocols.format must be {PROTOCOLS_FORMAT!r}, got {protocols.get('format')!r}")
    for key in FORBIDDEN_COMBINED_KEYS:
        if key in protocols:
            errors.append(
                f"protocols must not declare a combined denominator {key!r}; REST, S3, GraphQL and "
                "realtime stay separate and must not be summed"
            )
    catalog = _catalog_index(catalog_operations, errors)

    # --- denominators -------------------------------------------------------
    denominators = _protocol_list(protocols.get("denominators"), "protocols.denominators", errors)
    names: list[str] = []
    for index, denominator in enumerate(denominators):
        label = f"protocols.denominators[{index}]"
        entry = _protocol_dict(denominator, label, errors)
        name = _protocol_text(entry.get("name"), f"{label}.name", errors)
        if name:
            if name in names:
                errors.append(f"{label}.name duplicate denominator {name!r}")
            names.append(name)
            if name in FORBIDDEN_DENOMINATOR_NAMES or any(
                word in name for word in FORBIDDEN_DENOMINATOR_NAMES
            ):
                errors.append(
                    f"{label}.name {name!r} would combine protocols; keep separate denominators only"
                )
        _protocol_text(entry.get("unit"), f"{label}.unit", errors)
        _protocol_text(entry.get("source"), f"{label}.source", errors)
        if entry.get("independent") is not True:
            errors.append(f"{label}.independent must be true for every protocol denominator")
        if name != "rest" and name in NON_REST_DENOMINATORS and entry.get("operation_ids"):
            for operation_id in _protocol_list(
                entry.get("operation_ids"), f"{label}.operation_ids", errors
            ):
                if catalog is None or operation_id in catalog:
                    errors.append(
                        f"{label}: REST operation {operation_id!r} must not be counted again in the "
                        f"non-REST {name!r} denominator"
                    )

    # --- S3 ---------------------------------------------------------------
    facts = _s3_row_facts(s3_rows, errors)
    s3 = _protocol_dict(protocols.get("s3"), "protocols.s3", errors)
    for field in ("documented", "vendor_supported", "provider_unsupported"):
        if s3.get(field) != facts[field]:
            errors.append(
                f"protocols.s3.{field}={s3.get(field)!r} does not match the {facts[field]} derived "
                "from the S3 rows"
            )
    if facts["provider_unsupported"] and not s3.get("provider_unsupported"):
        errors.append(
            "protocols.s3 must record the provider_unsupported count; those rows are vendor-"
            "unsupported, not pending FlareGo work"
        )
    if s3.get("verified") != facts["verified"]:
        errors.append(
            f"protocols.s3.verified={s3.get('verified')!r} does not match the {facts['verified']} rows "
            "with structured verification evidence"
        )
    source = _protocol_dict(s3.get("source"), "protocols.s3.source", errors)
    for field in ("name", "commit", "sha256", "url"):
        _protocol_text(source.get(field), f"protocols.s3.source.{field}", errors)
    _protocol_text(s3.get("operations_file"), "protocols.s3.operations_file", errors)
    if not _protocol_list(s3.get("limitations"), "protocols.s3.limitations", errors):
        errors.append("protocols.s3.limitations must list the preserved vendor limitations")
    k2 = _protocol_dict(s3.get("k2_stream_host"), "protocols.s3.k2_stream_host", errors)
    host = _protocol_text(k2.get("host"), "protocols.s3.k2_stream_host.host", errors)
    if host and "k2.cloudflarestorage.com" not in host:
        errors.append("protocols.s3.k2_stream_host.host must be the dedicated K2 stream host")
    if k2.get("rest_denominator") is not True:
        errors.append("protocols.s3.k2_stream_host.rest_denominator must be true; those operations are REST")
    k2_operations = _protocol_list(
        k2.get("rest_operations"), "protocols.s3.k2_stream_host.rest_operations", errors
    )
    for index, entry in enumerate(k2_operations):
        label = f"protocols.s3.k2_stream_host.rest_operations[{index}]"
        _check_operation_reference(entry, label, catalog, errors)
        if isinstance(entry, dict) and entry.get("rest_denominator") is not True:
            errors.append(f"{label}.rest_denominator must be true")
    _protocol_text(k2.get("acceptance"), "protocols.s3.k2_stream_host.acceptance", errors)

    # --- GraphQL ----------------------------------------------------------
    graphql = _protocol_dict(protocols.get("graphql"), "protocols.graphql", errors)
    _protocol_text(graphql.get("endpoint"), "protocols.graphql.endpoint", errors)
    _protocol_text(graphql.get("transport"), "protocols.graphql.transport", errors)
    status = graphql.get("review_status")
    if status not in GRAPHQL_REVIEW_STATUSES:
        errors.append(
            f"protocols.graphql.review_status={status!r} must be one of "
            f"{', '.join(GRAPHQL_REVIEW_STATUSES)}"
        )
    evidence = _protocol_dict(graphql.get("schema_evidence"), "protocols.graphql.schema_evidence", errors)
    introspection = evidence.get("authorized_introspection")
    if status == "verified":
        valid_capture = (
            isinstance(introspection, dict)
            and bool(introspection)
            and bool(introspection.get("environment"))
            and bool(introspection.get("captured_at"))
        )
        if not isinstance(introspection, dict) or not introspection:
            errors.append(
                "protocols.graphql schema_evidence.authorized_introspection must be a structured "
                "capture record before the schema can be verified"
            )
        elif not introspection.get("environment") or not introspection.get("captured_at"):
            errors.append(
                "protocols.graphql schema_evidence.authorized_introspection needs environment and "
                "captured_at"
            )
        if not graphql.get("verified"):
            errors.append("protocols.graphql verified status requires a positive verified count")
        if not valid_capture:
            for field in ("dataset_count", "field_count"):
                if graphql.get(field) is not None:
                    errors.append(
                        f"protocols.graphql.{field} must stay unknown (null) while the authorized "
                        "schema capture is absent"
                    )
            if graphql.get("datasets"):
                errors.append(
                    "protocols.graphql.datasets must stay empty while the authorized schema capture "
                    "is absent"
                )
    else:
        if introspection is not None:
            errors.append(
                "protocols.graphql must not carry an introspection record while awaiting the "
                "authorized schema"
            )
        if graphql.get("datasets"):
            errors.append(
                "protocols.graphql.datasets must stay empty: no dataset or field name may be invented "
                "without authorized introspection"
            )
        for field in ("dataset_count", "field_count"):
            if graphql.get(field) is not None:
                errors.append(
                    f"protocols.graphql.{field} must stay unknown (null) while awaiting the authorized "
                    "schema; 0 would claim the schema was enumerated"
                )
        if graphql.get("verified") not in (0, None) or (graphql.get("verified") or 0) != 0:
            errors.append("protocols.graphql.verified must be 0 without authorized schema evidence")
    if not _protocol_list(graphql.get("acceptance_prerequisites"), "protocols.graphql.acceptance_prerequisites", errors):
        errors.append("protocols.graphql.acceptance_prerequisites must list the GraphQL-specific gates")
    for index, family in enumerate(_protocol_list(graphql.get("families"), "protocols.graphql.families", errors)):
        label = f"protocols.graphql.families[{index}]"
        entry = _protocol_dict(family, label, errors)
        for field in ("family", "need", "owner_role", "phase", "target_level", "evidence_status"):
            _protocol_text(entry.get(field), f"{label}.{field}", errors)
        if not _protocol_list(entry.get("blockers"), f"{label}.blockers", errors):
            errors.append(f"{label}.blockers must record the concrete evidence gap")

    # --- realtime ---------------------------------------------------------
    realtime = _protocol_dict(protocols.get("realtime"), "protocols.realtime", errors)
    protocols_seen: list[str] = []
    for index, entry in enumerate(
        _protocol_list(realtime.get("protocols"), "protocols.realtime.protocols", errors)
    ):
        label = f"protocols.realtime.protocols[{index}]"
        item = _protocol_dict(entry, label, errors)
        protocol_id = _protocol_text(item.get("protocol_id"), f"{label}.protocol_id", errors)
        if protocol_id:
            if protocol_id in protocols_seen:
                errors.append(f"{label}.protocol_id duplicate {protocol_id!r}")
            protocols_seen.append(protocol_id)
        for field in (
            "connection_protocol",
            "auth_host_policy",
            "lifecycle",
            "owner_role",
            "phase",
            "target_level",
            "enumeration_status",
        ):
            _protocol_text(item.get(field), f"{label}.{field}", errors)
        sessions = _protocol_list(item.get("session_operations"), f"{label}.session_operations", errors)
        if not sessions:
            errors.append(f"{label}.session_operations must reference the catalog session operations")
        for session_index, session in enumerate(sessions):
            _check_operation_reference(
                session, f"{label}.session_operations[{session_index}]", catalog, errors
            )
        if not _protocol_list(item.get("blockers"), f"{label}.blockers", errors):
            errors.append(f"{label}.blockers must record the concrete protocol evidence gap")
        if item.get("verified") not in (0, None):
            errors.append(f"{label}.verified must stay 0 without protocol evidence")

    # --- environments -----------------------------------------------------
    environments_seen: list[str] = []
    for index, entry in enumerate(
        _protocol_list(protocols.get("environments"), "protocols.environments", errors)
    ):
        label = f"protocols.environments[{index}]"
        item = _protocol_dict(entry, label, errors)
        for key in item:
            if key not in ENVIRONMENT_FIELDS:
                errors.append(
                    f"{label}.{key} is not part of the environment registry; only labels, plan, "
                    "allowed action categories, owner, permission categories, cleanup and status "
                    "may be recorded"
                )
        environment_id = _protocol_text(item.get("environment_id"), f"{label}.environment_id", errors)
        if environment_id:
            if environment_id in environments_seen:
                errors.append(f"{label}.environment_id duplicate {environment_id!r}")
            environments_seen.append(environment_id)
        _protocol_text(item.get("label"), f"{label}.label", errors)
        _protocol_text(item.get("plan"), f"{label}.plan", errors)
        _protocol_text(item.get("owner_role"), f"{label}.owner_role", errors)
        _protocol_text(item.get("cleanup_responsibility"), f"{label}.cleanup_responsibility", errors)
        if item.get("status") not in ENVIRONMENT_STATUSES:
            errors.append(
                f"{label}.status={item.get('status')!r} must be one of {', '.join(ENVIRONMENT_STATUSES)}"
            )
        for field in ("allowed_action_categories", "permission_categories"):
            values = _protocol_list(item.get(field), f"{label}.{field}", errors)
            if not values:
                errors.append(f"{label}.{field} must list at least one category")
            for value in values:
                if not isinstance(value, str) or not value.strip():
                    errors.append(f"{label}.{field} items must be non-empty strings, got {value!r}")
        errors.extend(_sensitive_findings(label, item))

    # --- P1/P2 admission --------------------------------------------------
    admission = _protocol_dict(protocols.get("p1_p2_admission"), "protocols.p1_p2_admission", errors)
    lists: dict[str, list[str]] = {}
    for field in ("p1_new", "p1_existing_compat", "p2_candidates"):
        identifiers: list[str] = []
        for index, entry in enumerate(
            _protocol_list(admission.get(field), f"protocols.p1_p2_admission.{field}", errors)
        ):
            label = f"protocols.p1_p2_admission.{field}[{index}]"
            item = _protocol_dict(entry, label, errors)
            for required in ADMISSION_FIELDS:
                if required not in item:
                    errors.append(f"{label}.{required} is required")
            operation_id = _check_operation_reference(item, label, catalog, errors)
            if operation_id:
                if operation_id in identifiers:
                    errors.append(f"{label}: duplicate operationId {operation_id!r} in {field}")
                identifiers.append(operation_id)
            if item.get("risk") not in ADMISSION_RISKS:
                errors.append(f"{label}.risk={item.get('risk')!r} must be one of {', '.join(ADMISSION_RISKS)}")
            if not isinstance(item.get("confirmation_required"), bool):
                errors.append(f"{label}.confirmation_required must be a boolean")
            _protocol_text(item.get("verification_boundary"), f"{label}.verification_boundary", errors)
            _protocol_text(item.get("source"), f"{label}.source", errors)
            if item.get("proposed") is not True:
                errors.append(f"{label}.proposed must be true; this is a proposal, not an approval")
        lists[field] = identifiers
    counts = _protocol_dict(admission.get("counts"), "protocols.p1_p2_admission.counts", errors)
    for field in ("p1_new", "p1_existing_compat", "p2_candidates"):
        if counts.get(field) != len(lists.get(field, [])):
            errors.append(
                f"protocols.p1_p2_admission.counts.{field}={counts.get(field)!r} does not match the "
                f"{len(lists.get(field, []))} listed operations"
            )
    overlap = sum(
        1
        for operation_id in set(lists.get("p1_existing_compat", [])) & set(lists.get("p2_candidates", []))
    )
    if counts.get("overlap") != overlap:
        errors.append(
            f"protocols.p1_p2_admission.counts.overlap={counts.get('overlap')!r} does not match the "
            f"computed overlap {overlap}"
        )
    union = len(set().union(*[set(value) for value in lists.values()])) if lists else 0
    if counts.get("unique") != union:
        errors.append(
            f"protocols.p1_p2_admission.counts.unique={counts.get('unique')!r} does not match the "
            f"computed unique operation count {union}; the lists overlap and must not be summed"
        )
    if counts.get("sum_of_lists") != sum(len(value) for value in lists.values()):
        errors.append(
            f"protocols.p1_p2_admission.counts.sum_of_lists={counts.get('sum_of_lists')!r} does not "
            "match the total number of listed entries"
        )
    policy = _protocol_dict(admission.get("policy"), "protocols.p1_p2_admission.policy", errors)
    if policy.get("runtime_allowlist") is not False:
        errors.append(
            "protocols.p1_p2_admission.policy.runtime_allowlist must be false; admission is a review "
            "proposal, not a runtime allowlist"
        )

    # --- global blockers --------------------------------------------------
    blocker_codes: list[str] = []
    for index, blocker in enumerate(_protocol_list(protocols.get("blockers"), "protocols.blockers", errors)):
        label = f"protocols.blockers[{index}]"
        item = _protocol_dict(blocker, label, errors)
        code = _protocol_text(item.get("code"), f"{label}.code", errors)
        if code:
            if code in blocker_codes:
                errors.append(f"{label}.code duplicate global blocker {code!r}")
            blocker_codes.append(code)
        _protocol_text(item.get("detail"), f"{label}.detail", errors)
    return errors
