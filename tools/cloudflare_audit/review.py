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
import datetime
import hashlib
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
#
# Closed fact pipeline: authoritative context/universe -> environment
# authorization/entitlement -> capture/matrix -> same-environment plan and query
# evidence -> derived status/count/unit/source -> comparison with the declared
# ledger. A stage with invalid facts never emits qualifying facts, and later
# gates never read raw declarations as authority.
# --------------------------------------------------------------------------- #

PROTOCOLS_FORMAT = "flarego.cloudflare-audit.protocols/1"
S3_VERIFICATION_STATUSES = ("awaiting_authorized_environment", "provider_unsupported")
GRAPHQL_REVIEW_STATUSES = ("awaiting_authorized_schema", "awaiting_query_acceptance", "verified")
ENVIRONMENT_STATUSES = ("awaiting_authorized_environment", "available", "blocked")
ADMISSION_RISKS = ("low", "medium", "high", "unknown")
FORBIDDEN_COMBINED_KEYS = (
    "total_coverage",
    "combined_denominator",
    "overall_coverage",
    "combined_total",
)
FORBIDDEN_DENOMINATOR_NAMES = ("total", "combined", "overall", "all")
REQUIRED_DENOMINATOR_NAMES = ("rest", "s3", "graphql", "realtime")
NON_REST_DENOMINATORS = ("s3", "graphql", "realtime")
DENOMINATOR_UNITS = {
    "rest": ("operations",),
    "s3": ("operations",),
    "graphql": ("datasets_and_fields", "datasets", "fields"),
    "realtime": ("sessions_and_streams",),
}
GRAPHQL_SINGLE_UNITS = ("datasets", "fields")
GRAPHQL_UNKNOWN_UNIT = "datasets_and_fields"
REALTIME_UNIT = "sessions_and_streams"
PROTOCOL_CONTEXT_KINDS = ("pinned", "caller_owned")
GRAPHQL_OVERVIEW_URL = (
    "https://raw.githubusercontent.com/cloudflare/cloudflare-docs/"
    "e3a9cf758c28273178d56918399068c6d2eb8a1d/src/content/docs/analytics/graphql-api/index.mdx"
)
NORMATIVE_SOURCE_REFS = {
    "rest": "docs/cloudflare-audit/snapshot.json",
    "s3": "docs/cloudflare-audit/r2-s3-operations.csv",
    "graphql": GRAPHQL_OVERVIEW_URL,
    "realtime": "docs/cloudflare-audit/endpoints.csv",
}
# Small fixed entitlement policy for the GraphQL qualification path. Categories
# are authorization-scope metadata only: they never substitute for real account
# verification or for an L2 acceptance result.
GRAPHQL_ENTITLEMENTS = {
    "capture": {
        "actions": ("schema_introspection_capture",),
        "permissions": ("analytics_read",),
    },
    "query": {
        "actions": ("dataset_read_only_query",),
        "permissions": ("analytics_read",),
    },
}
REQUIRED_GLOBAL_BLOCKER_CODES = (
    "authorized_account_absent",
    "authorized_schema_capture_absent",
    "stream_host_credentials_absent",
)
S3_SOURCE_NAME = "s3-compat"
S3_SOURCE_COMMIT = "e3a9cf758c28273178d56918399068c6d2eb8a1d"
S3_SOURCE_SHA256 = "a3a9f5b86efc022ae7cbe813e49b682f0dbb3dfc7ff1eac5498d3af62c9e75d8"
S3_SOURCE_URL = (
    "https://raw.githubusercontent.com/cloudflare/cloudflare-docs/"
    "e3a9cf758c28273178d56918399068c6d2eb8a1d/src/content/docs/r2/api/s3/api.mdx"
)
S3_COMPAT_PAGE_URL = "https://developers.cloudflare.com/r2/api/s3/api/"
GRAPHQL_CAPTURE_FIELDS = (
    "environment",
    "captured_at",
    "artifact_sha256",
    "authorized_by",
    "datasets",
    "query_kinds",
)
PLAN_EVIDENCE_FIELDS = (
    "environment",
    "plan_label",
    "authorized_by",
    "captured_at",
    "artifact_sha256",
    "query_scope",
)
CAPABILITY_RECORD_FIELDS = (
    "record_id",
    "environment",
    "captured_at",
    "query_scope",
    "result",
    "evidence_artifact",
)
ADMISSION_BOUNDARY_FIELDS = (
    "impact_boundary",
    "confirmation_boundary",
    "reconciliation_boundary",
    "verification_boundary",
)
ENVIRONMENT_FIELDS = (
    "environment_id",
    "label",
    "plan",
    "allowed_action_categories",
    "owner_role",
    "permission_categories",
    "cleanup_responsibility",
    "status",
    "authorization_reference",
    "blockers",
    "notes",
)
BLOCKER_FIELDS = ("code", "detail")
CLEARANCE_RECORD_FIELDS = ("reference", "date")
ADMISSION_FIELDS = (
    "operation_id",
    "method",
    "path",
    "source",
    "risk",
    "risk_review",
    "confirmation_required",
    "verification_boundary",
    "impact_boundary",
    "confirmation_boundary",
    "reconciliation_boundary",
    "auto_replay",
    "readback_required",
    "proposed",
)
S3_REQUIRED_FIELDS = (
    "owner_role",
    "phase",
    "source_name",
    "source_commit",
    "source_sha256",
    "source_quote_ref",
    "feature_table_status",
    "acceptance_target_level",
    "verification_status",
)
S3_FEATURE_COLUMNS = (
    "vendor_supported_features",
    "vendor_unsupported_features",
    "vendor_experimental_features",
    "feature_groups_reviewed",
)
ISO_UTC = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
ISO_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")

# Frozen, independently reviewed inventory of the pinned S3 compatibility table:
# operation -> (vendor supported, sha256 of the canonical feature vectors). It is
# committed here so the source gate runs in a clean checkout without the ignored
# markdown copy; the markdown is still checksum-verified when it is present.
S3_SOURCE_INVENTORY: dict[str, tuple[bool, str]] = {
        "AbortMultipartUpload": (True, "55c1a95cc53fae34959da73c9b0eb5a4e654bf526e948664c04453b7f6276eca"),
        "CompleteMultipartUpload": (True, "15ebcd03ba4f2e79ec7142f4612ceebf9c69d27a2049c5b445d97a1a3d38255f"),
        "CopyObject": (True, "e3daf278f66887a4a3907f2013f4b74ef94170e64241a900a9ceabad50d38755"),
        "CreateBucket": (True, "c5928e247fa9df709ee26bee8d8ba3d899a87ee866b77a1f60655619643eaa79"),
        "CreateMultipartUpload": (True, "ec7b16b7d06d26caa0210668a3703b8b218f8ec99d61f0c123315e5da6d30e6d"),
        "DeleteBucket": (True, "240f055157623f955db6f688cc3e8f6f4612f32501596d361e5f616fd85db6d5"),
        "DeleteBucketCors": (True, "240f055157623f955db6f688cc3e8f6f4612f32501596d361e5f616fd85db6d5"),
        "DeleteObject": (True, "6c1e601a574cef2fc8dd52412b00851059cc5e4a445f17270c8c3982002b2ce5"),
        "DeleteObjectTagging": (False, "240f055157623f955db6f688cc3e8f6f4612f32501596d361e5f616fd85db6d5"),
        "DeleteObjects": (True, "6c1e601a574cef2fc8dd52412b00851059cc5e4a445f17270c8c3982002b2ce5"),
        "GetBucketAccelerateConfiguration": (False, "240f055157623f955db6f688cc3e8f6f4612f32501596d361e5f616fd85db6d5"),
        "GetBucketAcl": (False, "240f055157623f955db6f688cc3e8f6f4612f32501596d361e5f616fd85db6d5"),
        "GetBucketAnalyticsConfiguration": (False, "53fd2ba884ab23c870df8fc0efd1c53762103116b64deef663d0e0e428dc162a"),
        "GetBucketCors": (True, "240f055157623f955db6f688cc3e8f6f4612f32501596d361e5f616fd85db6d5"),
        "GetBucketEncryption": (True, "240f055157623f955db6f688cc3e8f6f4612f32501596d361e5f616fd85db6d5"),
        "GetBucketIntelligentTieringConfiguration": (False, "5fd12720949fa6635bc1d4fc24bc5a9935ca95d852d58dd66220f4c509df6251"),
        "GetBucketInventoryConfiguration": (False, "53fd2ba884ab23c870df8fc0efd1c53762103116b64deef663d0e0e428dc162a"),
        "GetBucketLifecycle": (False, "240f055157623f955db6f688cc3e8f6f4612f32501596d361e5f616fd85db6d5"),
        "GetBucketLifecycleConfiguration": (True, "240f055157623f955db6f688cc3e8f6f4612f32501596d361e5f616fd85db6d5"),
        "GetBucketLocation": (True, "240f055157623f955db6f688cc3e8f6f4612f32501596d361e5f616fd85db6d5"),
        "GetBucketLogging": (False, "240f055157623f955db6f688cc3e8f6f4612f32501596d361e5f616fd85db6d5"),
        "GetBucketMetricsConfiguration": (False, "53fd2ba884ab23c870df8fc0efd1c53762103116b64deef663d0e0e428dc162a"),
        "GetBucketNotification": (False, "240f055157623f955db6f688cc3e8f6f4612f32501596d361e5f616fd85db6d5"),
        "GetBucketNotificationConfiguration": (False, "240f055157623f955db6f688cc3e8f6f4612f32501596d361e5f616fd85db6d5"),
        "GetBucketOwnershipControls": (False, "240f055157623f955db6f688cc3e8f6f4612f32501596d361e5f616fd85db6d5"),
        "GetBucketPolicy": (False, "240f055157623f955db6f688cc3e8f6f4612f32501596d361e5f616fd85db6d5"),
        "GetBucketPolicyStatus": (False, "240f055157623f955db6f688cc3e8f6f4612f32501596d361e5f616fd85db6d5"),
        "GetBucketReplication": (False, "240f055157623f955db6f688cc3e8f6f4612f32501596d361e5f616fd85db6d5"),
        "GetBucketRequestPayment": (False, "240f055157623f955db6f688cc3e8f6f4612f32501596d361e5f616fd85db6d5"),
        "GetBucketTagging": (False, "240f055157623f955db6f688cc3e8f6f4612f32501596d361e5f616fd85db6d5"),
        "GetBucketVersioning": (False, "240f055157623f955db6f688cc3e8f6f4612f32501596d361e5f616fd85db6d5"),
        "GetBucketWebsite": (False, "240f055157623f955db6f688cc3e8f6f4612f32501596d361e5f616fd85db6d5"),
        "GetObject": (True, "eb5d4a0659cba37808894e293f304eb58edebf92b0c435f95c2d847db50f8353"),
        "GetObjectLockConfiguration": (False, "240f055157623f955db6f688cc3e8f6f4612f32501596d361e5f616fd85db6d5"),
        "GetObjectTagging": (False, "15ebcd03ba4f2e79ec7142f4612ceebf9c69d27a2049c5b445d97a1a3d38255f"),
        "GetPublicAccessBlock": (False, "240f055157623f955db6f688cc3e8f6f4612f32501596d361e5f616fd85db6d5"),
        "HeadBucket": (True, "240f055157623f955db6f688cc3e8f6f4612f32501596d361e5f616fd85db6d5"),
        "HeadObject": (True, "56d024c858d93b07886f15a503011d6cfa85d85a5ae57f4a8c2ac154e3bcb5ab"),
        "ListBucketAnalyticsConfigurations": (False, "5b22df6f7e035e7afe04edd9012287a43d421ea9bdb8e159401d1cdaa7561b79"),
        "ListBucketIntelligentTieringConfigurations": (False, "5b22df6f7e035e7afe04edd9012287a43d421ea9bdb8e159401d1cdaa7561b79"),
        "ListBucketInventoryConfigurations": (False, "5b22df6f7e035e7afe04edd9012287a43d421ea9bdb8e159401d1cdaa7561b79"),
        "ListBucketMetricsConfigurations": (False, "5b22df6f7e035e7afe04edd9012287a43d421ea9bdb8e159401d1cdaa7561b79"),
        "ListBuckets": (True, "565d240f5343e625ae579a4d45a770f1f02c6368b5ed4d06da4fbe6f47c28866"),
        "ListMultipartUploads": (True, "49f4f0d4a71a9565e67ea237389c0f79248ca22a0861c221406433f23352f483"),
        "ListObjects": (True, "8093f210cd6509bf3c93696e903bac2d27c7ec467ab94125e6dd08deffc24d00"),
        "ListObjectsV2": (True, "2a7ffa0613163acd1afd48e3eb52323167871c03d9002a92e49569331bcc0159"),
        "ListParts": (True, "0ed2d39a35f8ac6028dae246c1d33895fbacf11e8473223c39c6de893b2fac4a"),
        "PutBucketAccelerateConfiguration": (False, "8dd39bbf3f880fd53596f1d90743b8cc82d38c7c0902a9dfd837d8004fffde01"),
        "PutBucketAcl": (False, "f103c6488ae4c36bf7aedbe04144eb162f84b27c741bc56f8cdc6a29fe59dd15"),
        "PutBucketAnalyticsConfiguration": (False, "53fd2ba884ab23c870df8fc0efd1c53762103116b64deef663d0e0e428dc162a"),
        "PutBucketCors": (True, "d3df5e25df17a326eeb2e55c736925ee9059385e02d6152873933cb3897a381e"),
        "PutBucketEncryption": (False, "d3df5e25df17a326eeb2e55c736925ee9059385e02d6152873933cb3897a381e"),
        "PutBucketIntelligentTieringConfiguration": (False, "53fd2ba884ab23c870df8fc0efd1c53762103116b64deef663d0e0e428dc162a"),
        "PutBucketInventoryConfiguration": (False, "53fd2ba884ab23c870df8fc0efd1c53762103116b64deef663d0e0e428dc162a"),
        "PutBucketLifecycle": (False, "d3df5e25df17a326eeb2e55c736925ee9059385e02d6152873933cb3897a381e"),
        "PutBucketLifecycleConfiguration": (True, "d3df5e25df17a326eeb2e55c736925ee9059385e02d6152873933cb3897a381e"),
        "PutBucketLogging": (False, "263fa6261e7f232199ad2ad2da2c226c25f4c26c40978665c5e7fc0a1f6714a7"),
        "PutBucketMetricsConfiguration": (False, "53fd2ba884ab23c870df8fc0efd1c53762103116b64deef663d0e0e428dc162a"),
        "PutBucketNotification": (False, "263fa6261e7f232199ad2ad2da2c226c25f4c26c40978665c5e7fc0a1f6714a7"),
        "PutBucketNotificationConfiguration": (False, "be061ad7f9e8ca7505ce099afde9bd7a29e9b487a98a3d8270f54c5d5141b102"),
        "PutBucketOwnershipControls": (False, "872ca7587e45c941176510b8fc3efedfac0390657b31f298f00005268584bcba"),
        "PutBucketPolicy": (False, "482b0b085c704f475980496cda7984c0c4b79d39ef54b4a0d408e0b9ab52481a"),
        "PutBucketReplication": (False, "48fdcdf1d61cdf1f46ed5e9d170a05171bc096e1e2828e0f8169a049ab859618"),
        "PutBucketRequestPayment": (False, "263fa6261e7f232199ad2ad2da2c226c25f4c26c40978665c5e7fc0a1f6714a7"),
        "PutBucketTagging": (False, "263fa6261e7f232199ad2ad2da2c226c25f4c26c40978665c5e7fc0a1f6714a7"),
        "PutBucketVersioning": (False, "ed23df21910a9f18f0b8bc09c546ef68e90c99a879926d3b637c971b8b3c2226"),
        "PutBucketWebsite": (False, "872ca7587e45c941176510b8fc3efedfac0390657b31f298f00005268584bcba"),
        "PutObject": (True, "a58ac42ce0e5905c30cc03b8fa3cc912814e85f0020482f6f77e79e1b50ef502"),
        "PutObjectLockConfiguration": (False, "cce33219bb451e93408265d9c9b33714ef2d55eb1b5dbc14ee5ad456fa404b7a"),
        "PutObjectTagging": (False, "4a49f80b98d81a4ebb7a522aaf99584bfd360a793b87e65e14e00377d84cb6aa"),
        "PutPublicAccessBlock": (False, "263fa6261e7f232199ad2ad2da2c226c25f4c26c40978665c5e7fc0a1f6714a7"),
        "UploadPart": (True, "e6554490485293bcb4ae0ce931d7b1a61b1c45aa82e8c791b025f7f530affb0e"),
        "UploadPartCopy": (True, "750b50c351625106f6f2852fffa352f0273c75e06eabbcc9b6e433054c249c61"),
}


def s3_feature_items(value: Any) -> list[str]:
    if not isinstance(value, str):
        return []
    return [item.strip() for item in value.split(" | ") if item.strip()]


def s3_feature_digest(row: dict) -> str:
    """Digest of one row's canonical feature vectors (supported|unsupported|experimental)."""
    canonical = "|".join(
        ",".join(s3_feature_items(row.get(field, "")))
        for field in (
            "vendor_supported_features",
            "vendor_unsupported_features",
            "vendor_experimental_features",
        )
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _normalize_key(key: Any) -> str:
    return re.sub(r"[^a-z0-9]", "", str(key).lower())


NORMALIZED_SENSITIVE_KEYS = frozenset(
    _normalize_key(name)
    for name in (
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
        "secret",
        "password",
        "private_key",
        "credential_path",
        "credential_file",
        "credentials_path",
        "payment",
        "payment_method",
        "card_number",
        "cvv",
        "cvc",
        "bearer",
        "authorization",
        "s3_secret",
        "api_key",
    )
)
NORMALIZED_SENSITIVE_SUBSTRINGS = (
    "secret",
    "password",
    "privatekey",
    "credentialpath",
    "credentialfile",
    "cardnumber",
    "paymentmethod",
    "apikey",
    "accesstoken",
    "sessiontoken",
)
SENSITIVE_VALUE_PATTERNS = (
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    re.compile(r"\bBearer\s+\S{8,}", re.IGNORECASE),
    # A path-rooted token containing a credential-ish directory; ordinary URL
    # authorities ("scheme://") are excluded, while quoting/parentheses do not
    # hide an embedded local path.
    re.compile(
        r"(?:^|[\s:=('\"\[{,`])/(?!/)[^\s'\"()\[\]{}`]*"
        r"(?:\.aws|\.ssh|\.config|\.cloudflare|\.netrc|credential|secret|token)",
        re.IGNORECASE,
    ),
    re.compile(r"(?:^|[\s:=('\"\[{,`])~[^\s'\"()\[\]{}`]*", re.IGNORECASE),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\bey[A-Za-z0-9_-]{8,}\."),
    re.compile(r"\b[0-9a-f]{32}\b"),
    re.compile(r"\b(?:\d[ -]?){13,19}\b"),
)


def _shape(value: Any) -> str:
    """Payload-free description: type and shape only, never any content."""
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, (int, float)):
        return f"{type(value).__name__}"
    if isinstance(value, str):
        return f"string of length {len(value)}"
    if isinstance(value, (list, tuple, set)):
        return f"{type(value).__name__} of length {len(value)}"
    if isinstance(value, dict):
        return f"object with {len(value)} key(s)"
    return type(value).__name__


def _protocol_dict(value: Any, label: str, code: str, errors: list[str]) -> dict:
    if isinstance(value, dict):
        return value
    errors.append(f"{code}: {label} must be an object, got {_shape(value)}")
    return {}


def _protocol_list(value: Any, label: str, code: str, errors: list[str]) -> list:
    if isinstance(value, list):
        return value
    errors.append(f"{code}: {label} must be a list, got {_shape(value)}")
    return []


def _protocol_text(value: Any, label: str, code: str, errors: list[str]) -> str:
    if isinstance(value, str) and value.strip():
        return value
    errors.append(f"{code}: {label} must be a non-empty string, got {_shape(value)}")
    return ""


def _protocol_date(value: Any, label: str, code: str, errors: list[str]) -> str:
    if not isinstance(value, str) or not ISO_UTC.match(value):
        errors.append(f"{code}: {label} must be an ISO-8601 UTC timestamp, got {_shape(value)}")
        return ""
    try:
        datetime.datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError:
        errors.append(f"{code}: {label} must be a real calendar date")
        return ""
    return value


def _protocol_calendar_date(value: Any, label: str, code: str, errors: list[str]) -> str:
    if isinstance(value, str) and ISO_DATE.match(value):
        try:
            datetime.datetime.strptime(value, "%Y-%m-%d")
        except ValueError:
            errors.append(f"{code}: {label} must be a real calendar date")
            return ""
        return value
    return _protocol_date(value, label, code, errors)


def _protocol_hex64(value: Any, label: str, code: str, errors: list[str]) -> str:
    if isinstance(value, str) and HEX64.match(value):
        return value
    errors.append(f"{code}: {label} must be a 64-character lowercase hex digest, got {_shape(value)}")
    return ""


def _protocol_count(value: Any, label: str, code: str, errors: list[str]) -> int | None:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        errors.append(f"{code}: {label} must be a non-negative integer, got {_shape(value)}")
        return None
    return value


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
            normalized = _normalize_key(key)
            if normalized in NORMALIZED_SENSITIVE_KEYS or any(
                part in normalized for part in NORMALIZED_SENSITIVE_SUBSTRINGS
            ):
                findings.append(
                    f"privacy.sensitive_key: {label} contains a sensitive key that must not be "
                    "recorded in the environment registry"
                )
                continue
            findings.extend(_sensitive_findings(f"{label}.{_shape(key)}", value))
    elif isinstance(node, list):
        for index, value in enumerate(node):
            findings.extend(_sensitive_findings(f"{label}[{index}]", value))
    elif isinstance(node, str):
        for pattern in SENSITIVE_VALUE_PATTERNS:
            if pattern.search(node):
                findings.append(
                    f"privacy.sensitive_value: {label} looks like credential material or a local "
                    "credential path and must not be recorded"
                )
                break
    return findings


def _normalize_catalog(catalog_operations: Any, errors: list[str]) -> list[dict] | None:
    """Normalize a catalog into unique identity facts, without echoing content."""
    if not isinstance(catalog_operations, list):
        errors.append(f"context.operations_invalid: catalog must be a list, got {_shape(catalog_operations)}")
        return None
    try:
        normalized = normalize_operations(catalog_operations)
    except ValueError as error:
        errors.append(f"context.operations_invalid: catalog is not usable ({type(error).__name__})")
        return None
    if not normalized:
        errors.append("context.operations_invalid: catalog must contain at least one operation")
        return None
    return [
        {
            "operation_id": operation["operation_id"],
            "method": operation["method"],
            "path": operation["path"],
        }
        for operation in normalized
    ]


def _pinned_universe(errors: list[str]) -> dict:
    """Read the tracked normative context: committed catalog, REST count and pins."""
    root = Path(__file__).resolve().parents[2]
    catalog_path = root / "docs/cloudflare-audit/endpoints.csv"
    snapshot_path = root / "docs/cloudflare-audit/snapshot.json"
    universe: dict[str, Any] = {
        "kind": "pinned",
        "operations": None,
        "rest_operations": None,
        "s3_universe": dict(S3_SOURCE_INVENTORY),
        "source_refs": dict(NORMATIVE_SOURCE_REFS),
    }
    operations: list[dict] | None = []
    valid = True
    try:
        with catalog_path.open(encoding="utf-8-sig", newline="") as handle:
            raw_operations = [
                {"operation_id": row["operation_id"], "method": row["method"], "path": row["path"]}
                for row in csv.DictReader(handle)
            ]
        operations = _normalize_catalog(raw_operations, errors)
        snapshot = json.loads(snapshot_path.read_text(encoding="utf-8"))
        if not isinstance(snapshot, dict):
            raise ValueError("snapshot must be an object")
        counts = snapshot.get("counts")
        declared = counts.get("operations") if isinstance(counts, dict) else None
        if isinstance(declared, bool) or not isinstance(declared, int) or declared <= 0:
            raise ValueError("snapshot.counts.operations must be a positive integer")
        if operations is not None and declared != len(
            {operation["operation_id"] for operation in operations}
        ):
            errors.append(
                "context.pinned_count_mismatch: snapshot.counts.operations must equal the unique "
                "normalized committed catalog operation count"
            )
            valid = False
        else:
            universe["rest_operations"] = declared
        docs = snapshot.get("supplementary_docs")
        if docs is not None:
            if not isinstance(docs, dict):
                errors.append(
                    "context.pinned_source_conflict: snapshot supplementary_docs must be an object when present"
                )
                valid = False
            else:
                commit = docs.get("commit")
                if commit is not None and commit != S3_SOURCE_COMMIT:
                    errors.append(
                        "context.pinned_source_conflict: snapshot supplementary commit does not match the "
                        "frozen source contract"
                    )
                    valid = False
                sources = docs.get("sources")
                if sources is not None:
                    if not isinstance(sources, list):
                        errors.append(
                            "context.pinned_source_conflict: snapshot supplementary_docs.sources must be a "
                            "list when present"
                        )
                        valid = False
                    else:
                        for index, source in enumerate(sources):
                            if not isinstance(source, dict):
                                errors.append(
                                    "context.pinned_source_conflict: snapshot supplementary source entries "
                                    "must be objects"
                                )
                                valid = False
                                continue
                            for field in ("name", "url", "sha256"):
                                if not isinstance(source.get(field), str) or not source.get(field).strip():
                                    errors.append(
                                        "context.pinned_source_conflict: snapshot supplementary source entries "
                                        "must carry non-empty name/url/sha256 strings"
                                    )
                                    valid = False
                            if source.get("name") == S3_SOURCE_NAME:
                                if (
                                    source.get("sha256") != S3_SOURCE_SHA256
                                    or source.get("url") != S3_SOURCE_URL
                                    or commit != S3_SOURCE_COMMIT
                                ):
                                    errors.append(
                                        "context.pinned_source_conflict: snapshot S3 supplementary source "
                                        "metadata does not match the frozen source contract"
                                    )
                                    valid = False
                            if source.get("name") == "graphql-overview" and source.get("url") != GRAPHQL_OVERVIEW_URL:
                                errors.append(
                                    "context.pinned_source_conflict: snapshot GraphQL overview source metadata "
                                    "does not match the frozen source contract"
                                )
                                valid = False
    except (OSError, ValueError, KeyError, TypeError) as error:
        errors.append(f"context.pinned_unavailable: pinned protocol context is unusable ({type(error).__name__})")
        valid = False
    if not operations:
        errors.append("context.pinned_unavailable: committed catalog has no operations")
        valid = False
    universe["operations"] = operations if valid else None
    if not valid:
        universe["rest_operations"] = None
    return universe


def _protocol_universe(
    context: Any, catalog_operations: Any, errors: list[str]
) -> dict | None:
    """Stage 0: resolve the authoritative universe (pinned or caller-owned)."""
    if context is None:
        pinned = _pinned_universe(errors)
        if catalog_operations is not None:
            _compare_universes(
                _normalize_catalog(catalog_operations, errors),
                pinned.get("operations"),
                errors,
                "context.catalog_mismatch",
            )
        return pinned
    if not isinstance(context, dict):
        errors.append(f"context.invalid: context must be an object, got {_shape(context)}")
        return None
    kind = context.get("kind")
    if kind == "pinned":
        pinned = _pinned_universe(errors)
        for field, expected in (
            ("rest_operations", pinned.get("rest_operations")),
            ("s3_subset", sorted(pinned["s3_universe"])),
            ("source_refs", dict(NORMATIVE_SOURCE_REFS)),
        ):
            if field in context and context.get(field) != expected:
                errors.append(
                    f"context.pinned_mismatch: declared {field} must match the canonical pinned facts"
                )
        if "operations" in context:
            _compare_universes(
                _normalize_catalog(context.get("operations"), errors),
                pinned.get("operations"),
                errors,
                "context.pinned_mismatch",
            )
        if catalog_operations is not None:
            _compare_universes(
                _normalize_catalog(catalog_operations, errors),
                pinned.get("operations"),
                errors,
                "context.catalog_mismatch",
            )
        return pinned
    if kind != "caller_owned":
        errors.append(
            f"context.kind_invalid: context.kind must be one of {', '.join(PROTOCOL_CONTEXT_KINDS)}, "
            f"got {_shape(kind)}"
        )
        return None
    operations = _normalize_catalog(context.get("operations"), errors)
    rest_operations = context.get("rest_operations")
    if isinstance(rest_operations, bool) or not isinstance(rest_operations, int) or rest_operations <= 0:
        errors.append(
            f"context.rest_count_invalid: context.rest_operations must be a positive integer, got "
            f"{_shape(rest_operations)}"
        )
        rest_operations = None
    elif operations is not None and rest_operations != len(
        {operation["operation_id"] for operation in operations}
    ):
        errors.append(
            "context.rest_count_mismatch: context.rest_operations must equal the unique normalized "
            "operation count of the caller-owned catalog"
        )
        rest_operations = None
    subset = context.get("s3_subset")
    universe_subset: dict[str, tuple[bool, str]] = {}
    if not isinstance(subset, list) or not subset:
        errors.append(
            f"context.s3_subset_invalid: context.s3_subset must be a non-empty list, got {_shape(subset)}"
        )
    else:
        for item in subset:
            if not isinstance(item, str) or item not in S3_SOURCE_INVENTORY:
                errors.append(
                    "context.s3_subset_unknown: caller-owned S3 subset contains an identity that is not "
                    "part of the frozen inventory"
                )
                universe_subset = {}
                break
            if item in universe_subset:
                errors.append("context.s3_subset_duplicate: caller-owned S3 subset contains duplicates")
                universe_subset = {}
                break
            universe_subset[item] = S3_SOURCE_INVENTORY[item]
    source_refs = context.get("source_refs")
    resolved_refs: dict[str, str] = {}
    if not isinstance(source_refs, dict):
        errors.append(
            f"context.source_refs_invalid: context.source_refs must be an object, got {_shape(source_refs)}"
        )
    else:
        for name in REQUIRED_DENOMINATOR_NAMES:
            value = source_refs.get(name)
            if not isinstance(value, str) or not value.strip():
                errors.append(
                    f"context.source_refs_invalid: context.source_refs.{name} must be a non-empty "
                    "public-style label"
                )
            else:
                resolved_refs[name] = value
    if catalog_operations is not None:
        _compare_universes(
            _normalize_catalog(catalog_operations, errors),
            operations,
            errors,
            "context.catalog_conflict",
        )
    if (
        operations is None
        or rest_operations is None
        or not universe_subset
        or len(resolved_refs) != len(REQUIRED_DENOMINATOR_NAMES)
    ):
        return None
    return {
        "kind": "caller_owned",
        "operations": operations,
        "rest_operations": rest_operations,
        "s3_universe": universe_subset,
        "source_refs": resolved_refs,
    }


def _identity_map(operations: list[dict] | None) -> dict[str, tuple[str, str]]:
    if not operations:
        return {}
    return {
        operation["operation_id"]: (operation["method"], operation["path"]) for operation in operations
    }


def _compare_universes(
    supplied: list[dict] | None, expected: list[dict] | None, errors: list[str], code: str
) -> None:
    """Compare the complete identity -> METHOD+path mapping, in every context mode."""
    if supplied is None or not expected:
        return
    if _identity_map(supplied) != _identity_map(expected):
        errors.append(
            f"{code}: supplied catalog must match the authoritative universe identity and METHOD+path "
            "bindings exactly"
        )


def _catalog_index(universe: dict | None, errors: list[str]) -> dict[str, tuple[str, str]] | None:
    if universe is None or not universe.get("operations"):
        return None
    return {
        operation["operation_id"]: (operation["method"], operation["path"])
        for operation in universe["operations"]
    }


def _check_operation_reference(
    entry: Any, label: str, catalog: dict[str, tuple[str, str]] | None, errors: list[str]
) -> bool:
    if not isinstance(entry, dict):
        errors.append(f"catalog.reference_invalid: {label} must be an object, got {_shape(entry)}")
        return False
    operation_id = entry.get("operation_id")
    if not isinstance(operation_id, str) or not operation_id:
        errors.append(f"catalog.reference_invalid: {label}.operation_id must be a non-empty string")
        return False
    if catalog is None:
        errors.append(
            f"catalog.context_missing: {label} cannot be verified without a validated catalog universe"
        )
        return False
    known = catalog.get(operation_id)
    if known is None:
        errors.append(
            f"catalog.unknown_operation: {label}.operation_id is not part of the authoritative universe"
        )
        return False
    declared = (entry.get("method"), entry.get("path"))
    if declared != known:
        errors.append(
            f"catalog.binding_mismatch: {label} METHOD+path does not match the authoritative universe"
        )
        return False
    return True


def _environment_facts(environments: list, errors: list[str]) -> dict[str, dict]:
    """Stage 1: validated environment authorization and entitlement facts."""
    facts: dict[str, dict] = {}
    for position, entry in enumerate(environments):
        label = f"protocols.environments[{position}]"
        item = _protocol_dict(entry, label, "qualification.environment_invalid", errors)
        for key in item:
            if key not in ENVIRONMENT_FIELDS:
                errors.append(
                    f"qualification.environment_field: {label} has a field outside the environment "
                    "registry (only labels, plan, allowed action categories, owner, permission "
                    "categories, cleanup, status and an authorization reference are allowed)"
                )
        environment_id = _protocol_text(
            item.get("environment_id"), f"{label}.environment_id", "qualification.environment_invalid", errors
        )
        plan = _protocol_text(item.get("plan"), f"{label}.plan", "qualification.environment_invalid", errors)
        _protocol_text(item.get("label"), f"{label}.label", "qualification.environment_invalid", errors)
        _protocol_text(
            item.get("owner_role"), f"{label}.owner_role", "qualification.environment_invalid", errors
        )
        _protocol_text(
            item.get("cleanup_responsibility"),
            f"{label}.cleanup_responsibility",
            "qualification.environment_invalid",
            errors,
        )
        status = item.get("status")
        if status not in ENVIRONMENT_STATUSES:
            errors.append(
                f"qualification.environment_status: {label}.status must be one of "
                f"{', '.join(ENVIRONMENT_STATUSES)}, got {_shape(status)}"
            )
        actions: set[str] = set()
        permissions: set[str] = set()
        for field, target in (
            ("allowed_action_categories", actions),
            ("permission_categories", permissions),
        ):
            values = _protocol_list(item.get(field), f"{label}.{field}", "qualification.environment_invalid", errors)
            if not values:
                errors.append(f"qualification.environment_invalid: {label}.{field} must list at least one category")
            for value in values:
                if not isinstance(value, str) or not value.strip():
                    errors.append(
                        f"qualification.environment_invalid: {label}.{field} items must be non-empty strings"
                    )
                    continue
                target.add(value)
        if "notes" in item and not isinstance(item.get("notes"), str):
            errors.append(
                f"qualification.environment_invalid: {label}.notes must be a plain string, got "
                f"{_shape(item.get('notes'))}"
            )
        blocker_codes: list[str] = []
        for blocker_index, blocker in enumerate(
            _protocol_list(item.get("blockers"), f"{label}.blockers", "qualification.environment_invalid", errors)
        ):
            blocker_label = f"{label}.blockers[{blocker_index}]"
            if not isinstance(blocker, dict):
                errors.append(
                    f"qualification.environment_invalid: {blocker_label} must be an object, got "
                    f"{_shape(blocker)}"
                )
                continue
            for key in blocker:
                if key not in BLOCKER_FIELDS:
                    errors.append(
                        f"qualification.environment_field: {blocker_label} has a field outside the "
                        "blocker contract (only code and detail are allowed)"
                    )
            code = _protocol_text(
                blocker.get("code"), f"{blocker_label}.code", "qualification.environment_invalid", errors
            )
            if code:
                blocker_codes.append(code)
            _protocol_text(
                blocker.get("detail"), f"{blocker_label}.detail", "qualification.environment_invalid", errors
            )
        reference = item.get("authorization_reference")
        if status == "available":
            if not isinstance(reference, str) or not reference.strip():
                errors.append(
                    f"qualification.environment_reference_missing: {label}.authorization_reference is "
                    "required before an environment can be available"
                )
            if blocker_codes:
                errors.append(
                    f"qualification.environment_blocked: {label} must not be available while it keeps "
                    "unresolved blockers"
                )
        elif reference not in (None, ""):
            errors.append(
                f"qualification.environment_reference_unexpected: {label}.authorization_reference must "
                "stay empty while the environment is not available"
            )
        errors.extend(_sensitive_findings(label, item))
        if environment_id:
            if environment_id in facts:
                errors.append(
                    f"qualification.environment_duplicate: {label}.environment_id duplicates an earlier "
                    "environment id"
                )
            facts[environment_id] = {
                "status": status,
                "plan": plan,
                "actions": actions,
                "permissions": permissions,
                "authorization_reference": reference if isinstance(reference, str) else "",
                "blocker_codes": blocker_codes,
                "authorized": status == "available"
                and isinstance(reference, str)
                and bool(reference.strip())
                and not blocker_codes,
            }
    return facts


def _missing_entitlements(environment: dict, requirement: str) -> list[str]:
    """Return the missing action/permission categories for one entitlement requirement."""
    spec = GRAPHQL_ENTITLEMENTS[requirement]
    missing = [f"action:{name}" for name in spec["actions"] if name not in environment["actions"]]
    missing += [f"permission:{name}" for name in spec["permissions"] if name not in environment["permissions"]]
    return missing


def _schema_matrix(capture: dict, label: str, errors: list[str]) -> dict[str, set[str]]:
    """Validate the captured dataset/field matrix used to qualify query scopes."""
    matrix: dict[str, set[str]] = {}
    entries = _protocol_list(capture.get("datasets"), f"{label}.datasets", "qualification.capture_invalid", errors)
    if not entries:
        errors.append(f"qualification.capture_invalid: {label}.datasets must list the captured datasets")
    for index, entry in enumerate(entries):
        entry_label = f"{label}.datasets[{index}]"
        item = _protocol_dict(entry, entry_label, "qualification.capture_invalid", errors)
        dataset = _protocol_text(
            item.get("dataset"), f"{entry_label}.dataset", "qualification.capture_invalid", errors
        )
        fields = _protocol_list(
            item.get("fields"), f"{entry_label}.fields", "qualification.capture_invalid", errors
        )
        if not fields:
            errors.append(f"qualification.capture_invalid: {entry_label}.fields must list at least one field")
        clean_fields: set[str] = set()
        for field in fields:
            if not isinstance(field, str) or not field.strip():
                errors.append(
                    f"qualification.capture_invalid: {entry_label}.fields items must be non-empty strings"
                )
                continue
            clean_fields.add(field)
        if dataset:
            if dataset in matrix:
                errors.append(
                    f"qualification.capture_invalid: {entry_label}.dataset duplicates an earlier dataset"
                )
            matrix[dataset] = clean_fields
    return matrix


def _capture_facts(
    graphql: dict, environments: dict[str, dict], errors: list[str]
) -> dict | None:
    """Stage 2: validated capture linked to exactly one entitled environment."""
    evidence = _protocol_dict(
        graphql.get("schema_evidence"), "protocols.graphql.schema_evidence", "qualification.capture_invalid", errors
    )
    capture = evidence.get("authorized_introspection")
    if capture is None:
        return None
    label = "protocols.graphql schema_evidence.authorized_introspection"
    if not isinstance(capture, dict):
        errors.append(
            f"qualification.capture_invalid: {label} must be an object or null, got {_shape(capture)}"
        )
        return None
    valid = True
    for field in GRAPHQL_CAPTURE_FIELDS:
        if field not in capture:
            errors.append(f"qualification.capture_invalid: {label}.{field} is required")
            valid = False
    environment = _protocol_text(
        capture.get("environment"), f"{label}.environment", "qualification.capture_invalid", errors
    )
    linked = environments.get(environment) if environment else None
    if environment and linked is None:
        errors.append(
            f"qualification.environment_unknown: {label}.environment is not a registered environment"
        )
        valid = False
    elif linked is not None:
        if not linked["authorized"]:
            errors.append(
                f"qualification.environment_not_available: {label}.environment is not available "
                "(status/blockers); an awaiting or blocked environment cannot authorize a capture"
            )
            valid = False
        else:
            missing = _missing_entitlements(linked, "capture")
            if missing:
                errors.append(
                    f"qualification.entitlement_missing: {label}.environment does not grant the capture "
                    f"entitlement ({', '.join(missing)})"
                )
                valid = False
    authorized_by = _protocol_text(
        capture.get("authorized_by"), f"{label}.authorized_by", "qualification.capture_invalid", errors
    )
    if authorized_by and linked is not None:
        if authorized_by != linked["authorization_reference"]:
            errors.append(
                f"qualification.capture_authority_mismatch: {label}.authorized_by must equal the linked "
                "environment authorization reference"
            )
            valid = False
    captured_at = _protocol_date(
        capture.get("captured_at"), f"{label}.captured_at", "qualification.capture_invalid", errors
    )
    artifact = _protocol_hex64(
        capture.get("artifact_sha256"), f"{label}.artifact_sha256", "qualification.capture_invalid", errors
    )
    matrix = _schema_matrix(capture, label, errors)
    query_kinds: set[str] = set()
    raw_kinds = _protocol_list(
        capture.get("query_kinds"), f"{label}.query_kinds", "qualification.capture_invalid", errors
    )
    if not raw_kinds:
        errors.append(f"qualification.capture_invalid: {label}.query_kinds must list the captured query kinds")
    for kind in raw_kinds:
        if not isinstance(kind, str) or not kind.strip():
            errors.append(
                f"qualification.capture_invalid: {label}.query_kinds items must be non-empty strings"
            )
            continue
        query_kinds.add(kind)
    if not (environment and authorized_by and captured_at and artifact and matrix and query_kinds):
        valid = False
    if not valid or linked is None:
        return None
    return {
        "environment": environment,
        "environment_plan": linked["plan"],
        "authorized_by": authorized_by,
        "captured_at": captured_at,
        "artifact_sha256": artifact,
        "matrix": matrix,
        "query_kinds": query_kinds,
        "dataset_count": len(matrix),
        "field_count": sum(len(fields) for fields in matrix.values()),
    }


def _scope_in_matrix(scope: Any, capture: dict) -> bool:
    """True when a query scope references only captured datasets/fields/query kinds."""
    if not isinstance(scope, dict):
        return False
    dataset = scope.get("dataset")
    fields = scope.get("fields")
    kinds = scope.get("query_kinds")
    if not isinstance(dataset, str) or dataset not in capture["matrix"]:
        return False
    if not isinstance(fields, list) or not fields:
        return False
    if any(not isinstance(field, str) or field not in capture["matrix"][dataset] for field in fields):
        return False
    if not isinstance(kinds, list) or not kinds:
        return False
    return all(isinstance(kind, str) and kind in capture["query_kinds"] for kind in kinds)


def _scope_included(scope: Any, allowed: Any) -> bool:
    """True when ``scope`` is a subset of an authorized plan scope."""
    if not isinstance(scope, dict) or not isinstance(allowed, dict):
        return False
    if scope.get("dataset") != allowed.get("dataset"):
        return False
    allowed_fields = allowed.get("fields")
    allowed_kinds = allowed.get("query_kinds")
    if not isinstance(allowed_fields, list) or not isinstance(allowed_kinds, list):
        return False
    fields = scope.get("fields")
    kinds = scope.get("query_kinds")
    if not isinstance(fields, list) or not isinstance(kinds, list):
        return False
    return all(field in allowed_fields for field in fields) and all(kind in allowed_kinds for kind in kinds)


def _plan_evidence_facts(
    graphql: dict, environments: dict[str, dict], capture: dict | None, errors: list[str]
) -> dict | None:
    """Stage 3a: validated plan evidence for the same environment and scope."""
    plan = graphql.get("plan_evidence")
    if plan is None:
        return None
    label = "protocols.graphql.plan_evidence"
    if not isinstance(plan, dict):
        errors.append(f"qualification.plan_evidence_invalid: {label} must be an object or null, got {_shape(plan)}")
        return None
    valid = True
    for field in PLAN_EVIDENCE_FIELDS:
        if field not in plan:
            errors.append(f"qualification.plan_evidence_invalid: {label}.{field} is required")
            valid = False
    environment = _protocol_text(
        plan.get("environment"), f"{label}.environment", "qualification.plan_evidence_invalid", errors
    )
    linked = environments.get(environment) if environment else None
    if linked is None:
        errors.append(
            f"qualification.plan_evidence_invalid: {label}.environment must link a registered environment"
        )
        valid = False
    elif capture is None or environment != capture["environment"]:
        errors.append(
            f"qualification.plan_evidence_environment_mismatch: {label}.environment must be the same "
            "environment as the schema capture"
        )
        valid = False
    elif not linked["authorized"]:
        errors.append(
            f"qualification.plan_evidence_invalid: {label}.environment is not available (status/blockers)"
        )
        valid = False
    plan_label = _protocol_text(
        plan.get("plan_label"), f"{label}.plan_label", "qualification.plan_evidence_invalid", errors
    )
    if plan_label and linked is not None and plan_label != linked["plan"]:
        errors.append(
            f"qualification.plan_evidence_plan_mismatch: {label}.plan_label must equal the linked "
            "environment plan label"
        )
        valid = False
    authorized_by = _protocol_text(
        plan.get("authorized_by"), f"{label}.authorized_by", "qualification.plan_evidence_invalid", errors
    )
    if authorized_by and linked is not None and authorized_by != linked["authorization_reference"]:
        errors.append(
            f"qualification.plan_evidence_authority_mismatch: {label}.authorized_by must equal the linked "
            "environment authorization reference"
        )
        valid = False
    captured_at = _protocol_date(
        plan.get("captured_at"), f"{label}.captured_at", "qualification.plan_evidence_invalid", errors
    )
    artifact = _protocol_hex64(
        plan.get("artifact_sha256"), f"{label}.artifact_sha256", "qualification.plan_evidence_invalid", errors
    )
    scope = plan.get("query_scope")
    if capture is not None and not _scope_in_matrix(scope, capture):
        errors.append(
            f"qualification.plan_evidence_scope_mismatch: {label}.query_scope must reference the captured "
            "dataset, fields and query kinds"
        )
        valid = False
    if not (environment and linked is not None and plan_label and authorized_by and captured_at and artifact and valid):
        return None
    return {
        "environment": environment,
        "plan_label": plan_label,
        "authorized_by": authorized_by,
        "captured_at": captured_at,
        "artifact_sha256": artifact,
        "query_scope": scope,
    }


def _capability_facts(
    graphql: dict,
    environments: dict[str, dict],
    capture: dict | None,
    plan: dict | None,
    errors: list[str],
) -> list[dict]:
    """Stage 3b: independent query acceptance inside the captured and authorized scope."""
    records: list[dict] = []
    seen_ids: list[str] = []
    for index, record in enumerate(
        _protocol_list(
            graphql.get("capability_acceptance"),
            "protocols.graphql.capability_acceptance",
            "qualification.record_invalid",
            errors,
        )
    ):
        label = f"protocols.graphql.capability_acceptance[{index}]"
        item = _protocol_dict(record, label, "qualification.record_invalid", errors)
        valid = True
        for field in CAPABILITY_RECORD_FIELDS:
            if field not in item:
                errors.append(f"qualification.record_invalid: {label}.{field} is required")
                valid = False
        record_id = _protocol_text(item.get("record_id"), f"{label}.record_id", "qualification.record_invalid", errors)
        if record_id:
            if record_id in seen_ids:
                errors.append(
                    f"qualification.record_duplicate: {label}.record_id duplicates an earlier acceptance record"
                )
                valid = False
            seen_ids.append(record_id)
        captured_at = _protocol_date(
            item.get("captured_at"), f"{label}.captured_at", "qualification.record_invalid", errors
        )
        artifact = _protocol_hex64(
            item.get("evidence_artifact"), f"{label}.evidence_artifact", "qualification.record_invalid", errors
        )
        if item.get("result") != "verified":
            errors.append(
                f"qualification.record_invalid: {label}.result must be 'verified', got {_shape(item.get('result'))}"
            )
            valid = False
        environment = item.get("environment")
        linked = environments.get(environment) if isinstance(environment, str) else None
        if capture is None:
            errors.append(
                f"qualification.record_invalid: {label} cannot be qualified without a validated schema capture"
            )
            valid = False
        elif linked is None:
            errors.append(
                f"qualification.environment_unknown: {label}.environment is not a registered environment"
            )
            valid = False
        else:
            if environment != capture["environment"]:
                errors.append(
                    f"qualification.record_environment_mismatch: {label}.environment must be the same "
                    "environment as the schema capture"
                )
                valid = False
            if not linked["authorized"]:
                errors.append(
                    f"qualification.environment_not_available: {label}.environment is not available "
                    "(status/blockers)"
                )
                valid = False
            else:
                missing = _missing_entitlements(linked, "query")
                if missing:
                    errors.append(
                        f"qualification.entitlement_missing: {label}.environment does not grant the query "
                        f"entitlement ({', '.join(missing)})"
                    )
                    valid = False
            if plan is None:
                errors.append(
                    f"qualification.plan_evidence_missing: {label} cannot qualify a positive capability "
                    "without validated plan evidence for the same environment and scope"
                )
                valid = False
            elif not _scope_included(item.get("query_scope"), plan["query_scope"]):
                errors.append(
                    f"qualification.plan_scope_mismatch: {label}.query_scope must be included in the "
                    "authorized plan evidence scope"
                )
                valid = False
        if capture is not None and captured_at and captured_at < capture["captured_at"]:
            errors.append(
                f"qualification.record_order: {label}.captured_at must not predate the schema capture"
            )
            valid = False
        if plan is not None and captured_at and captured_at < plan["captured_at"]:
            errors.append(
                f"qualification.record_order: {label}.captured_at must not predate the plan evidence"
            )
            valid = False
        if capture is not None and not _scope_in_matrix(item.get("query_scope"), capture):
            errors.append(
                f"qualification.record_scope_mismatch: {label}.query_scope must reference the captured "
                "dataset, fields and query kinds"
            )
            valid = False
        if not (record_id and captured_at and artifact and valid):
            valid = False
        if valid:
            records.append(
                {
                    "record_id": record_id,
                    "environment": environment,
                    "captured_at": captured_at,
                    "evidence_artifact": artifact,
                    "query_scope": item["query_scope"],
                }
            )
    return records


def _blocker_facts(protocols: dict, errors: list[str]) -> tuple[dict[str, str], list[str]]:
    """Validate global blockers; return cleared references and still-active codes."""
    clearances: dict[str, str] = {}
    active: list[str] = []
    seen: list[str] = []
    for index, blocker in enumerate(
        _protocol_list(protocols.get("blockers"), "protocols.blockers", "blockers.invalid", errors)
    ):
        label = f"protocols.blockers[{index}]"
        item = _protocol_dict(blocker, label, "blockers.invalid", errors)
        code = _protocol_text(item.get("code"), f"{label}.code", "blockers.invalid", errors)
        _protocol_text(item.get("detail"), f"{label}.detail", "blockers.invalid", errors)
        if not code:
            continue
        if code in seen:
            errors.append(f"blockers.duplicate_code: {label}.code duplicates an earlier global blocker code")
            continue
        seen.append(code)
        cleared_by = item.get("cleared_by")
        record = item.get("clearance_record")
        if cleared_by in (None, "") and record in (None, ""):
            active.append(code)
            continue
        record_label = f"{label}.clearance_record"
        if not isinstance(record, dict):
            errors.append(f"blockers.clearance_invalid: {record_label} must be an object to clear the blocker")
            active.append(code)
            continue
        for key in record:
            if key not in CLEARANCE_RECORD_FIELDS + ("environment",):
                errors.append(
                    f"blockers.clearance_invalid: {record_label} has a field outside the clearance contract"
                )
        reference = _protocol_text(
            record.get("reference"), f"{record_label}.reference", "blockers.clearance_invalid", errors
        )
        _protocol_calendar_date(record.get("date"), f"{record_label}.date", "blockers.clearance_invalid", errors)
        if not isinstance(cleared_by, str) or not cleared_by.strip() or reference != cleared_by:
            errors.append(
                f"blockers.clearance_mismatch: {label} requires cleared_by to match "
                "clearance_record.reference exactly"
            )
            active.append(code)
            continue
        clearances[code] = cleared_by
    for code in REQUIRED_GLOBAL_BLOCKER_CODES:
        if code not in seen:
            errors.append(
                f"blockers.missing_required: protocols.blockers must record {code!r} unless it is explicitly "
                "cleared with a clearance record; removing the blocker is not clearance"
            )
    return clearances, active


def _s3_row_label(index: int, name: Any, universe: dict) -> str:
    """Label a row without echoing untrusted text: the name only when it is pinned."""
    if isinstance(name, str) and name in universe["s3_universe"]:
        return f"s3_rows[{index}] {name}"
    return f"s3_rows[{index}]"


def _s3_facts(s3_rows: Any, universe: dict | None, errors: list[str]) -> dict:
    """Stage 1b: S3 rows validated against the authoritative universe, then counted."""
    inventory = universe["s3_universe"] if universe else dict(S3_SOURCE_INVENTORY)
    facts: dict[str, Any] = {
        "documented": 0,
        "vendor_supported": 0,
        "provider_unsupported": 0,
        "names": [],
        "verified": 0,
        "rows": {},
    }
    if universe is None:
        errors.append("s3.universe_unavailable: no authoritative S3 universe is available")
        return facts
    rows = _protocol_list(s3_rows, "s3_rows", "s3.invalid", errors)
    seen: dict[str, int] = {}
    for index, row in enumerate(rows):
        if not isinstance(row, dict):
            errors.append(f"s3.invalid: s3_rows[{index}] must be an object, got {_shape(row)}")
            continue
        name = row.get("operation")
        if not isinstance(name, str) or not name:
            errors.append(f"s3.invalid: s3_rows[{index}] has no operation name")
            continue
        if name in seen:
            errors.append(
                f"s3.duplicate_identity: duplicate S3 operation identity in s3_rows (indexes "
                f"{seen[name]} and {index})"
            )
            continue
        seen[name] = index
        inventory_entry = inventory.get(name)
        label = _s3_row_label(index, name, universe)
        if inventory_entry is None:
            errors.append(
                f"s3.unknown_identity: {label} is not part of the authoritative S3 source universe"
            )
            continue
        expected_supported, expected_digest = inventory_entry
        supported = _csv_flag(row.get("cloudflare_supports_operation"))
        if supported is None:
            errors.append(
                f"s3.flag_invalid: {label} cloudflare_supports_operation must be exactly 'true' or "
                f"'false', got {_shape(row.get('cloudflare_supports_operation'))}"
            )
            continue
        if supported != expected_supported:
            errors.append(
                f"s3.support_mismatch: {label} vendor support flag contradicts the authoritative source "
                f"inventory (expected {'true' if expected_supported else 'false'})"
            )
            continue
        for field in S3_REQUIRED_FIELDS:
            if not isinstance(row.get(field), str) or not row.get(field).strip():
                errors.append(f"s3.field_invalid: {label} {field} must be a non-empty string")
        for field in S3_FEATURE_COLUMNS:
            if not isinstance(row.get(field), str):
                errors.append(
                    f"s3.field_invalid: {label} {field} must be a string of ' | ' separated items, got "
                    f"{_shape(row.get(field))}"
                )
        groups = row.get("feature_groups_reviewed")
        if isinstance(groups, str):
            for group in s3_feature_items(groups):
                if not group.endswith(":"):
                    errors.append(
                        f"s3.feature_group_invalid: {label} feature_groups_reviewed items must be group "
                        "headers ending with ':'"
                    )
        if s3_feature_digest(row) != expected_digest:
            errors.append(f"s3.feature_mismatch: {label} feature vectors do not match the pinned source inventory")
        if row.get("source_name") != S3_SOURCE_NAME:
            errors.append(f"s3.source_mismatch: {label} source_name must be the pinned source name")
        if row.get("source_commit") != S3_SOURCE_COMMIT:
            errors.append(f"s3.source_mismatch: {label} source_commit must be the pinned source commit")
        if row.get("source_sha256") != S3_SOURCE_SHA256:
            errors.append(f"s3.source_mismatch: {label} source_sha256 must be the pinned source digest")
        quote_ref = row.get("source_quote_ref")
        if isinstance(quote_ref, str) and (
            name not in quote_ref or "Feature column" not in quote_ref
        ):
            errors.append(
                f"s3.source_mismatch: {label} source_quote_ref must point at this operation's Feature column"
            )
        if row.get("official_compatibility_page") != S3_COMPAT_PAGE_URL:
            errors.append(f"s3.source_mismatch: {label} official_compatibility_page must be the pinned page")
        aws_reference = row.get("aws_operation_reference")
        if not isinstance(aws_reference, str) or f"/API_{name}.html" not in aws_reference:
            errors.append(
                f"s3.source_mismatch: {label} aws_operation_reference must reference the pinned operation "
                "identity document"
            )
        status = row.get("verification_status")
        if status not in S3_VERIFICATION_STATUSES:
            errors.append(
                f"s3.status_mismatch: {label} verification_status must be one of "
                f"{', '.join(S3_VERIFICATION_STATUSES)}, got {_shape(status)}"
            )
        if not supported:
            if status != "provider_unsupported":
                errors.append(
                    f"s3.status_mismatch: {label} provider-unsupported rows must keep "
                    "verification_status=provider_unsupported"
                )
            if row.get("acceptance_target_level") != "n/a":
                errors.append(
                    f"s3.target_mismatch: {label} provider-unsupported rows are not FlareGo implementation "
                    "targets and must not carry an acceptance target level"
                )
            if row.get("test_prerequisites"):
                errors.append(
                    f"s3.target_mismatch: {label} provider-unsupported rows must not carry test prerequisites"
                )
        else:
            if status != "awaiting_authorized_environment":
                errors.append(
                    f"s3.status_mismatch: {label} vendor-supported rows must stay "
                    "verification_status=awaiting_authorized_environment"
                )
            if row.get("acceptance_target_level") != "L2":
                errors.append(f"s3.target_mismatch: {label} vendor-supported rows need an explicit L2 target")
            if not row.get("test_prerequisites"):
                errors.append(f"s3.target_mismatch: {label} vendor-supported rows need test prerequisites")
        for field in ("flarego_implemented", "verified"):
            flag = _csv_flag(row.get(field))
            if flag is None:
                errors.append(f"s3.flag_invalid: {label} {field} must be exactly 'true' or 'false'")
            elif flag:
                errors.append(f"s3.evidence_invalid: {label} {field}=true is not supported by any pinned evidence")
        if row.get("feature_table_status") == "not_listed_in_pinned_table" and (
            row.get("vendor_supported_features") or row.get("vendor_unsupported_features")
        ):
            errors.append(
                f"s3.feature_mismatch: {label} feature_table_status says the pinned table lists no "
                "features, so no feature names may be recorded"
            )
        if _csv_flag(row.get("verified")) is True:
            evidence = row.get("verification_evidence")
            if not isinstance(evidence, dict) or not evidence.get("environment") or not evidence.get("source"):
                errors.append(
                    f"s3.evidence_invalid: {label} verification_evidence must be a structured record with "
                    "environment and source before verified can be true"
                )
            else:
                facts["verified"] += 1
        facts["rows"][name] = row
    # Universe completeness: the input set must equal the authoritative universe.
    missing = sorted(set(inventory) - set(seen))
    extra = sorted(set(seen) - set(inventory))
    if missing:
        errors.append(
            "s3.universe_incomplete: input S3 rows are missing authoritative identities "
            f"({len(missing)}): {', '.join(missing)}"
        )
    if extra:
        errors.append(
            f"s3.universe_incomplete: input S3 rows contain {len(extra)} identities outside the "
            "authoritative universe"
        )
    facts["documented"] = len(inventory)
    facts["vendor_supported"] = sum(1 for supported, _ in inventory.values() if supported)
    facts["provider_unsupported"] = len(inventory) - facts["vendor_supported"]
    facts["names"] = sorted(inventory)
    return facts


def validate_protocols(
    protocols: dict,
    s3_rows: list[dict],
    catalog_operations: list[dict] | None = None,
    *,
    context: dict | None = None,
) -> list[str]:
    """Validate the independent protocol ledger. Read-only; returns readable errors.

    The pipeline resolves an authoritative universe, validates environment
    authorization and entitlements, links one schema capture to one entitled
    environment, requires same-environment plan and query evidence inside the
    captured matrix, derives status/count/unit/source from those facts, and only
    then compares the declared ledger. REST, S3, GraphQL and realtime keep
    separate denominators and are never summed into a combined coverage claim.
    """
    if not isinstance(protocols, dict):
        return [f"protocols.invalid: protocols ledger must be an object, got {_shape(protocols)}"]
    errors: list[str] = []
    if protocols.get("format") != PROTOCOLS_FORMAT:
        errors.append(
            f"protocols.invalid: protocols.format must be {PROTOCOLS_FORMAT!r}, got "
            f"{_shape(protocols.get('format'))}"
        )
    for key in FORBIDDEN_COMBINED_KEYS:
        if key in protocols:
            errors.append(
                f"protocols.combined_denominator: protocols must not declare {key!r}; REST, S3, GraphQL "
                "and realtime stay separate and must not be summed"
            )

    universe = _protocol_universe(context, catalog_operations, errors)
    catalog = _catalog_index(universe, errors)

    # --- Stage 1: S3 facts and environments --------------------------------
    facts = _s3_facts(s3_rows, universe, errors)
    environments = _environment_facts(
        _protocol_list(protocols.get("environments"), "protocols.environments", "qualification.environment_invalid", errors),
        errors,
    )
    clearances, active_blockers = _blocker_facts(protocols, errors)

    # --- denominators: declared claims to compare with derived facts -------
    denominators = _protocol_list(
        protocols.get("denominators"), "protocols.denominators", "denominator.invalid", errors
    )
    seen_names: list[str] = []
    by_name: dict[str, dict] = {}
    for index, denominator in enumerate(denominators):
        label = f"protocols.denominators[{index}]"
        entry = _protocol_dict(denominator, label, "denominator.invalid", errors)
        name = _protocol_text(entry.get("name"), f"{label}.name", "denominator.invalid", errors)
        if name:
            if name in seen_names:
                errors.append(f"denominator.duplicate: {label}.name duplicates an earlier denominator name")
            seen_names.append(name)
            by_name[name] = entry
            if any(word in name for word in FORBIDDEN_DENOMINATOR_NAMES):
                errors.append(
                    f"denominator.combined_name: {label}.name would combine protocols; keep separate "
                    "denominators only"
                )
        unit = _protocol_text(entry.get("unit"), f"{label}.unit", "denominator.invalid", errors)
        if name in DENOMINATOR_UNITS and unit and unit not in DENOMINATOR_UNITS[name]:
            errors.append(
                f"denominator.unit_mismatch: {label}.unit must be one of "
                f"{', '.join(DENOMINATOR_UNITS[name])} for the {name} denominator, got {_shape(unit)}"
            )
        source = _protocol_text(entry.get("source"), f"{label}.source", "denominator.invalid", errors)
        expected_source = (universe or {}).get("source_refs", {}).get(name)
        if source and expected_source is not None and source != expected_source:
            errors.append(
                f"denominator.source_mismatch: {label}.source must match the authoritative source label "
                "for this protocol"
            )
        if entry.get("independent") is not True:
            errors.append(f"denominator.invalid: {label}.independent must be true for every protocol denominator")
        if name in NON_REST_DENOMINATORS and entry.get("operation_ids"):
            for operation_id in _protocol_list(
                entry.get("operation_ids"), f"{label}.operation_ids", "denominator.invalid", errors
            ):
                if not isinstance(operation_id, str) or not operation_id:
                    errors.append(
                        f"denominator.invalid: {label}.operation_ids items must be non-empty strings"
                    )
                elif catalog is None or operation_id in catalog:
                    errors.append(
                        f"denominator.rest_recount: {label} must not count a REST operation again in a "
                        "non-REST denominator"
                    )
    for name in REQUIRED_DENOMINATOR_NAMES:
        if name not in by_name:
            errors.append(f"denominator.missing: protocols.denominators must include the {name!r} denominator")

    # --- S3 section and denominator consistency ---------------------------
    s3 = _protocol_dict(protocols.get("s3"), "protocols.s3", "s3.invalid", errors)
    for field in ("documented", "vendor_supported", "provider_unsupported"):
        declared = s3.get(field)
        if declared is not None:
            _protocol_count(declared, f"protocols.s3.{field}", "s3.count_mismatch", errors)
        if declared != facts[field]:
            errors.append(
                f"s3.count_mismatch: protocols.s3.{field} must equal the authoritative universe count "
                f"{facts[field]}"
            )
    if s3.get("verified") != facts["verified"]:
        errors.append(
            f"s3.count_mismatch: protocols.s3.verified must equal the {facts['verified']} rows with "
            "structured verification evidence"
        )
    s3_denominator = by_name.get("s3") or {}
    for field, derived in (
        ("count", facts["documented"]),
        ("vendor_supported", facts["vendor_supported"]),
        ("provider_unsupported", facts["provider_unsupported"]),
    ):
        declared = s3_denominator.get(field)
        if declared is not None:
            _protocol_count(
                declared, f"protocols.denominators s3.{field}", "denominator.count_mismatch", errors
            )
        if declared != derived:
            errors.append(
                f"denominator.count_mismatch: protocols.denominators s3.{field} must equal the "
                f"authoritative universe count {derived}"
            )
    source = _protocol_dict(s3.get("source"), "protocols.s3.source", "s3.invalid", errors)
    expected_source = {
        "name": S3_SOURCE_NAME,
        "commit": S3_SOURCE_COMMIT,
        "sha256": S3_SOURCE_SHA256,
        "url": S3_SOURCE_URL,
    }
    for field, expected in expected_source.items():
        if source.get(field) != expected:
            errors.append(
                f"s3.source_mismatch: protocols.s3.source.{field} must equal the frozen source contract"
            )
    _protocol_text(s3.get("operations_file"), "protocols.s3.operations_file", "s3.invalid", errors)
    if not _protocol_list(s3.get("limitations"), "protocols.s3.limitations", "s3.invalid", errors):
        errors.append("s3.invalid: protocols.s3.limitations must list the preserved vendor limitations")
    k2 = _protocol_dict(s3.get("k2_stream_host"), "protocols.s3.k2_stream_host", "s3.invalid", errors)
    host = _protocol_text(k2.get("host"), "protocols.s3.k2_stream_host.host", "s3.invalid", errors)
    if host and host != K2_STREAM_HOST:
        errors.append(
            "s3.k2_host_mismatch: protocols.s3.k2_stream_host.host must equal the pinned K2 stream host "
            "template exactly"
        )
    if k2.get("rest_denominator") is not True:
        errors.append("s3.invalid: protocols.s3.k2_stream_host.rest_denominator must be true; those operations are REST")
    for index, entry in enumerate(
        _protocol_list(k2.get("rest_operations"), "protocols.s3.k2_stream_host.rest_operations", "s3.invalid", errors)
    ):
        label = f"protocols.s3.k2_stream_host.rest_operations[{index}]"
        _check_operation_reference(entry, label, catalog, errors)
        if isinstance(entry, dict) and entry.get("rest_denominator") is not True:
            errors.append(f"s3.invalid: {label}.rest_denominator must be true")
    _protocol_text(k2.get("acceptance"), "protocols.s3.k2_stream_host.acceptance", "s3.invalid", errors)

    # --- Stage 2/3: capture, plan evidence and query acceptance -----------
    graphql = _protocol_dict(protocols.get("graphql"), "protocols.graphql", "qualification.graphql_invalid", errors)
    _protocol_text(graphql.get("endpoint"), "protocols.graphql.endpoint", "qualification.graphql_invalid", errors)
    _protocol_text(graphql.get("transport"), "protocols.graphql.transport", "qualification.graphql_invalid", errors)
    account_scope = graphql.get("account_scope")
    if account_scope not in ("unknown", "account", "user", "organization"):
        errors.append(
            "qualification.graphql_invalid: protocols.graphql.account_scope must be "
            "unknown/account/user/organization"
        )
    plan_limits = graphql.get("plan_limits")
    if plan_limits not in ("unverified", "verified", "unknown"):
        errors.append(
            "qualification.graphql_invalid: protocols.graphql.plan_limits must record the plan-limit "
            "verification state (unverified/verified/unknown)"
        )
    scope_blockers = _protocol_list(
        graphql.get("account_scope_blockers"),
        "protocols.graphql.account_scope_blockers",
        "qualification.graphql_invalid",
        errors,
    )
    for blocker in scope_blockers:
        if not isinstance(blocker, str) or not blocker.strip():
            errors.append(
                "qualification.graphql_invalid: protocols.graphql.account_scope_blockers items must be "
                "non-empty strings"
            )
    capture = _capture_facts(graphql, environments, errors)
    plan = _plan_evidence_facts(graphql, environments, capture, errors)
    records = _capability_facts(graphql, environments, capture, plan, errors)

    if capture is None:
        derived_status = "awaiting_authorized_schema"
    elif not records:
        derived_status = "awaiting_query_acceptance"
    else:
        derived_status = "verified"
    if derived_status == "verified" and account_scope == "unknown":
        errors.append(
            "qualification.account_scope_unknown: protocols.graphql account_scope must be identified "
            "before a positive capability can be qualified"
        )
    declared_status = graphql.get("review_status")
    if declared_status not in GRAPHQL_REVIEW_STATUSES:
        errors.append(
            f"qualification.graphql_invalid: protocols.graphql.review_status must be one of "
            f"{', '.join(GRAPHQL_REVIEW_STATUSES)}, got {_shape(declared_status)}"
        )
    elif declared_status != derived_status:
        reason = {
            "awaiting_authorized_schema": "no validated schema_evidence.authorized_introspection capture",
            "awaiting_query_acceptance": "capture validated but no plan-qualified capability_acceptance record",
            "verified": "capture, plan evidence and at least one capability acceptance record are validated",
        }[derived_status]
        errors.append(
            f"qualification.status_mismatch: protocols.graphql.review_status must match the validated fact "
            f"state ({derived_status}: {reason})"
        )
    verified = graphql.get("verified")
    if isinstance(verified, bool) or not isinstance(verified, int) or verified < 0:
        errors.append("qualification.graphql_invalid: protocols.graphql.verified must be a non-negative integer")
        verified_count = None
    else:
        verified_count = verified
        if verified != len(records):
            errors.append(
                f"qualification.verified_mismatch: protocols.graphql.verified must equal the "
                f"{len(records)} independently valid capability acceptance records"
            )
    if plan is None:
        if plan_limits == "verified":
            errors.append(
                "qualification.plan_limits_mismatch: protocols.graphql.plan_limits must not claim verified "
                "without validated plan evidence"
            )
    elif plan_limits != "verified":
        errors.append(
            "qualification.plan_limits_mismatch: protocols.graphql.plan_limits must be 'verified' when "
            "plan evidence is validated"
        )
    if capture is None:
        if graphql.get("plan_evidence") is not None:
            errors.append(
                "qualification.plan_evidence_invalid: protocols.graphql.plan_evidence must be null without a "
                "validated schema capture"
            )
        if graphql.get("datasets"):
            errors.append(
                "qualification.graphql_invalid: protocols.graphql.datasets must stay empty without a "
                "validated schema capture"
            )
        for field in ("dataset_count", "field_count"):
            if graphql.get(field) is not None:
                errors.append(
                    f"qualification.graphql_invalid: protocols.graphql.{field} must stay unknown (null) "
                    "without a validated schema capture"
                )
        if account_scope != "unknown" or plan_limits not in ("unverified", "unknown"):
            errors.append(
                "qualification.graphql_invalid: protocols.graphql account_scope and plan_limits must stay "
                "unknown/unverified while no authorized schema capture exists"
            )
        if not scope_blockers:
            errors.append(
                "qualification.graphql_invalid: protocols.graphql.account_scope_blockers must record the "
                "concrete account-scope gap while no capture exists"
            )
    else:
        declared_datasets = graphql.get("datasets")
        if not isinstance(declared_datasets, list):
            errors.append(
                f"qualification.graphql_invalid: protocols.graphql.datasets must be a list, got "
                f"{_shape(declared_datasets)}"
            )
        else:
            declared_matrix: dict[str, list[str]] = {}
            for index, entry in enumerate(declared_datasets):
                label = f"protocols.graphql.datasets[{index}]"
                item = _protocol_dict(entry, label, "qualification.graphql_invalid", errors)
                dataset = _protocol_text(
                    item.get("dataset"), f"{label}.dataset", "qualification.graphql_invalid", errors
                )
                fields = _protocol_list(
                    item.get("fields"), f"{label}.fields", "qualification.graphql_invalid", errors
                )
                clean: list[str] = []
                for field in fields:
                    if not isinstance(field, str) or not field.strip():
                        errors.append(
                            f"qualification.graphql_invalid: {label}.fields items must be non-empty strings"
                        )
                        continue
                    clean.append(field)
                if dataset:
                    declared_matrix[dataset] = clean
            if declared_matrix != {name: sorted(fields) for name, fields in capture["matrix"].items()}:
                errors.append(
                    "qualification.matrix_mismatch: protocols.graphql.datasets must match the captured "
                    "dataset/field matrix exactly"
                )
            for field, expected in (("dataset_count", capture["dataset_count"]), ("field_count", capture["field_count"])):
                value = graphql.get(field)
                if value is None:
                    errors.append(
                        f"qualification.matrix_mismatch: protocols.graphql.{field} must record the captured "
                        f"discovered count {expected} once a schema capture is validated"
                    )
                    continue
                count = _protocol_count(
                    value, f"protocols.graphql.{field}", "qualification.graphql_invalid", errors
                )
                if count is not None and count != expected:
                    errors.append(
                        f"qualification.matrix_mismatch: protocols.graphql.{field} must equal the captured "
                        f"{expected}"
                    )
        if scope_blockers:
            errors.append(
                "qualification.graphql_invalid: protocols.graphql.account_scope_blockers must be empty once "
                "an authorized capture is validated"
            )
    if not _protocol_list(
        graphql.get("acceptance_prerequisites"),
        "protocols.graphql.acceptance_prerequisites",
        "qualification.graphql_invalid",
        errors,
    ):
        errors.append(
            "qualification.graphql_invalid: protocols.graphql.acceptance_prerequisites must list the "
            "GraphQL-specific gates"
        )
    for index, family in enumerate(
        _protocol_list(graphql.get("families"), "protocols.graphql.families", "qualification.graphql_invalid", errors)
    ):
        label = f"protocols.graphql.families[{index}]"
        entry = _protocol_dict(family, label, "qualification.graphql_invalid", errors)
        for field in ("family", "need", "owner_role", "phase", "target_level", "evidence_status"):
            _protocol_text(entry.get(field), f"{label}.{field}", "qualification.graphql_invalid", errors)
        if not _protocol_list(entry.get("blockers"), f"{label}.blockers", "qualification.graphql_invalid", errors):
            errors.append(
                f"qualification.graphql_invalid: {label}.blockers must record the concrete evidence gap"
            )

    # --- GraphQL denominator derived from the same facts -------------------
    graphql_denominator = by_name.get("graphql") or {}
    graphql_count = graphql_denominator.get("count")
    if graphql_count is None:
        if graphql_denominator.get("status") != derived_status:
            errors.append(
                "denominator.status_mismatch: protocols.denominators graphql.status must match the "
                "validated fact state even when the count is unknown"
            )
    else:
        count = _protocol_count(
            graphql_count, "protocols.denominators graphql.count", "denominator.count_mismatch", errors
        )
        unit = graphql_denominator.get("unit")
        if unit not in GRAPHQL_SINGLE_UNITS:
            errors.append(
                "denominator.unit_mismatch: protocols.denominators graphql.count requires the single unit "
                f"{'/'.join(GRAPHQL_SINGLE_UNITS)}; mixed dataset/field totals must stay null"
            )
        elif capture is None:
            errors.append(
                "denominator.count_mismatch: protocols.denominators graphql.count requires a validated "
                "schema capture"
            )
        elif count is not None:
            expected = capture["dataset_count"] if unit == "datasets" else capture["field_count"]
            if count != expected:
                errors.append(
                    f"denominator.count_mismatch: protocols.denominators graphql.count must equal the "
                    f"validated {unit} count {expected}"
                )
        if graphql_denominator.get("status") != derived_status:
            errors.append(
                "denominator.status_mismatch: protocols.denominators graphql.status must match the "
                "validated fact state"
            )
    # REST denominator count comes from the authoritative universe.
    rest_denominator = by_name.get("rest") or {}
    rest_declared = rest_denominator.get("count")
    if rest_declared is not None:
        _protocol_count(
            rest_declared, "protocols.denominators rest.count", "denominator.count_mismatch", errors
        )
    if universe is not None and rest_declared != universe["rest_operations"]:
        errors.append(
            f"denominator.count_mismatch: protocols.denominators rest.count must equal the authoritative "
            f"REST universe count {universe['rest_operations']}"
        )
    if rest_denominator.get("status") not in (None,):
        errors.append("denominator.status_mismatch: the rest denominator is a known universe and must not declare a status")

    # --- realtime ---------------------------------------------------------
    realtime = _protocol_dict(protocols.get("realtime"), "protocols.realtime", "realtime.invalid", errors)
    protocols_seen: list[str] = []
    realtime_verified = 0
    for index, entry in enumerate(
        _protocol_list(realtime.get("protocols"), "protocols.realtime.protocols", "realtime.invalid", errors)
    ):
        label = f"protocols.realtime.protocols[{index}]"
        item = _protocol_dict(entry, label, "realtime.invalid", errors)
        protocol_id = _protocol_text(item.get("protocol_id"), f"{label}.protocol_id", "realtime.invalid", errors)
        if protocol_id:
            if protocol_id in protocols_seen:
                errors.append(f"realtime.duplicate: {label}.protocol_id duplicates an earlier protocol id")
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
            _protocol_text(item.get(field), f"{label}.{field}", "realtime.invalid", errors)
        sessions = _protocol_list(item.get("session_operations"), f"{label}.session_operations", "realtime.invalid", errors)
        if not sessions:
            errors.append(f"realtime.invalid: {label}.session_operations must reference the catalog session operations")
        for session_index, session in enumerate(sessions):
            _check_operation_reference(
                session, f"{label}.session_operations[{session_index}]", catalog, errors
            )
        if not _protocol_list(item.get("blockers"), f"{label}.blockers", "realtime.invalid", errors):
            errors.append(f"realtime.invalid: {label}.blockers must record the concrete protocol evidence gap")
        if item.get("verified") not in (0, None):
            errors.append(f"realtime.invalid: {label}.verified must stay 0 without protocol evidence")
        else:
            realtime_verified += int(item.get("verified") or 0)
    realtime_denominator = by_name.get("realtime") or {}
    if realtime_denominator.get("count") is not None:
        errors.append(
            "denominator.count_mismatch: protocols.denominators realtime.count must stay unknown (null); the "
            "realtime universe is not enumerated and no acceptance record exists"
        )
    if realtime_denominator.get("status") != "awaiting_protocol_evidence":
        errors.append(
            "denominator.status_mismatch: protocols.denominators realtime.status must be "
            "awaiting_protocol_evidence"
        )
    if realtime_denominator.get("unit") != REALTIME_UNIT:
        errors.append(
            f"denominator.unit_mismatch: protocols.denominators realtime.unit must be {REALTIME_UNIT!r}"
        )

    # --- P1/P2 admission --------------------------------------------------
    admission = _protocol_dict(protocols.get("p1_p2_admission"), "protocols.p1_p2_admission", "admission.invalid", errors)
    lists: dict[str, list[str]] = {}
    for field in ("p1_new", "p1_existing_compat", "p2_candidates"):
        identifiers: list[str] = []
        for index, entry in enumerate(
            _protocol_list(admission.get(field), f"protocols.p1_p2_admission.{field}", "admission.invalid", errors)
        ):
            label = f"protocols.p1_p2_admission.{field}[{index}]"
            item = _protocol_dict(entry, label, "admission.invalid", errors)
            for required in ADMISSION_FIELDS:
                if required not in item:
                    errors.append(f"admission.invalid: {label}.{required} is required")
            operation_id = item.get("operation_id")
            if _check_operation_reference(item, label, catalog, errors) and isinstance(operation_id, str):
                if operation_id in identifiers:
                    errors.append(f"admission.duplicate: {label}.operation_id duplicates an earlier entry")
                identifiers.append(operation_id)
            if item.get("risk") not in ADMISSION_RISKS:
                errors.append(
                    f"admission.invalid: {label}.risk must be one of {', '.join(ADMISSION_RISKS)}, got "
                    f"{_shape(item.get('risk'))}"
                )
            if item.get("risk_review") != "pending_manual_review":
                errors.append(
                    f"admission.risk_review: {label}.risk_review must stay 'pending_manual_review'; a "
                    "proposed HTTP-method hint is not a reviewed risk decision"
                )
            method = item.get("method")
            read_like = method in ("GET", "HEAD")
            for boundary in ADMISSION_BOUNDARY_FIELDS:
                _protocol_text(item.get(boundary), f"{label}.{boundary}", "admission.invalid", errors)
            if not isinstance(item.get("confirmation_required"), bool):
                errors.append(f"admission.invalid: {label}.confirmation_required must be a boolean")
            if not isinstance(item.get("auto_replay"), bool):
                errors.append(f"admission.invalid: {label}.auto_replay must be a boolean")
            elif item.get("auto_replay"):
                errors.append(
                    f"admission.no_replay: {label}.auto_replay must stay false; no reviewed write policy "
                    "exists and an unknown write must never be automatically replayed"
                )
            if not isinstance(item.get("readback_required"), bool):
                errors.append(f"admission.invalid: {label}.readback_required must be a boolean")
            if read_like:
                if item.get("confirmation_required") is True:
                    errors.append(f"admission.invalid: {label} read operations must not require a write confirmation")
                if item.get("readback_required") is True:
                    errors.append(f"admission.invalid: {label} read operations do not need a write read-back step")
            else:
                if item.get("confirmation_required") is not True:
                    errors.append(f"admission.invalid: {label} write or unknown semantics require explicit confirmation")
                if item.get("readback_required") is not True:
                    errors.append(f"admission.invalid: {label} write or unknown semantics require a cloud read-back step")
                if item.get("risk") == "low":
                    errors.append(f"admission.invalid: {label} write or unknown semantics must not be proposed as low risk")
            _protocol_text(item.get("source"), f"{label}.source", "admission.invalid", errors)
            if item.get("proposed") is not True:
                errors.append(f"admission.invalid: {label}.proposed must be true; this is a proposal, not an approval")
        lists[field] = identifiers
    counts = _protocol_dict(admission.get("counts"), "protocols.p1_p2_admission.counts", "admission.invalid", errors)
    for field in ("p1_new", "p1_existing_compat", "p2_candidates"):
        if counts.get(field) != len(lists.get(field, [])):
            errors.append(
                f"admission.count_mismatch: protocols.p1_p2_admission.counts.{field} must match the "
                f"{len(lists.get(field, []))} listed operations"
            )
    overlap = sum(
        1 for operation_id in set(lists.get("p1_existing_compat", [])) & set(lists.get("p2_candidates", []))
    )
    if counts.get("overlap") != overlap:
        errors.append(
            f"admission.count_mismatch: protocols.p1_p2_admission.counts.overlap must equal the computed "
            f"overlap {overlap}"
        )
    union = len(set().union(*[set(value) for value in lists.values()])) if lists else 0
    if counts.get("unique") != union:
        errors.append(
            f"admission.count_mismatch: protocols.p1_p2_admission.counts.unique must equal the computed "
            f"unique operation count {union}; the lists overlap and must not be summed"
        )
    if counts.get("sum_of_lists") != sum(len(value) for value in lists.values()):
        errors.append(
            "admission.count_mismatch: protocols.p1_p2_admission.counts.sum_of_lists must equal the total "
            "number of listed entries"
        )
    policy = _protocol_dict(admission.get("policy"), "protocols.p1_p2_admission.policy", "admission.invalid", errors)
    if policy.get("runtime_allowlist") is not False:
        errors.append(
            "admission.runtime_allowlist: protocols.p1_p2_admission.policy.runtime_allowlist must be false; "
            "admission is a review proposal, not a runtime allowlist"
        )

    # --- Stage 4: qualification gating from the same validated facts -------
    if active_blockers:
        if verified_count:
            errors.append(
                "qualification.blocker_active: protocols.graphql.verified must be 0 while global "
                f"qualification blockers are active ({len(active_blockers)} active)"
            )
        if s3.get("verified"):
            errors.append(
                "qualification.blocker_active: protocols.s3.verified must be 0 while global qualification "
                "blockers are active"
            )
        if realtime_verified:
            errors.append(
                "qualification.blocker_active: realtime verified counts must be 0 while global qualification "
                "blockers are active"
            )
    return errors
