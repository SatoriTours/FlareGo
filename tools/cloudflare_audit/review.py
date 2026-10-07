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
from .schema import enumerate_operations, load_schema

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
BLOCKER_AVAILABILITY_CONFLICT = "availability_conflict"
BLOCKER_DEPRECATION_CONFLICT = "deprecation_conflict"
BLOCKER_FORGE_HIDDEN = "forge_hidden_flag_requires_review"
BLOCKER_RISK_REVIEW = "manual_risk_review_required"
BLOCKER_UNCLASSIFIED = "unclassified_product"

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
SCOPE_UNKNOWN_PREFIXES = (("/tenants/", "tenant scope has no matching scope kind; needs review"),)
SCOPE_UNKNOWN_EXACT = {
    "/live": "internal/misc route without a documented resource scope",
    "/ready": "internal/misc route without a documented resource scope",
    "/signed-url": "internal/misc route without a documented resource scope",
    "/internal/submit": "internal/misc route without a documented resource scope",
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
    "tags": ("workers_k2_other", "reports_other", "Field Extractors"),
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
        "spec_reference": "§3.1 Zones / §3.2 Cache／CDN／Performance（Zone 级）",
        "tags": (
            "Available Page Rules settings", "Page Rules", "Zone", "Zone Cache Settings",
            "Zone Cloud Connector Rules GET", "Zone Cloud Connector Rules PUT",
            "Zone Entitlements", "Zone Environments", "Zone Holds", "Zone Lockdown",
            "Zone Settings", "Zone Snippets",
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
        "spec_reference": "§3.2 SSL／TLS／Certificates",
        "tags": (
            "Analyze Certificate", "Automatic SSL/TLS", "Certificate Packs",
            "Custom CSRs for a Zone", "Custom CSRs for an Account", "Custom Origin Trust Store",
            "Custom SSL for a Zone", "DCV Delegation", "SSL Verification",
            "SSL/TLS Mode Recommendation", "Total TLS", "Universal SSL Settings for a Zone",
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
        "spec_reference": "§3.2 Rules／WAF／Bots",
        "tags": (
            "AI Security for Apps", "Account Rulesets", "Bot Settings", "Botnet Threat Feed",
            "CSAM Scanner Settings", "Feedback", "Filters", "Firewall rules",
            "Fraud Detection", "IP Access rules for a user", "IP Access rules for a zone",
            "IP Access rules for an account", "Leaked Credential Checks", "Lists",
            "Managed Transforms", "Precursor", "Rate Limiting Analytics",
            "Rate limits for a zone", "Rules", "User Agent Blocking rules", "WAF overrides",
            "WAF packages",
            "WAF rule groups", "WAF rules", "Zone Rulesets",
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
        "spec_reference": "§3.2 SSL／TLS／Certificates（高级／mTLS）",
        "tags": (
            "Keyless SSL for a Zone", "mTLS Certificate Management", "Origin CA",
            "Origin Post-Quantum", "Origin TLS", "Per-Hostname TLS Settings",
            "Per-hostname Authenticated Origin Pull", "Zone-Level Authenticated Origin Pulls",
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
    left unknown rather than treated as a positive qualification.
    """
    normalized: list[dict] = []
    for operation in operations:
        if not isinstance(operation, dict):
            raise ValueError(f"operation must be a mapping, got {type(operation).__name__}")
        operation_id = operation.get("operation_id")
        if not isinstance(operation_id, str) or not operation_id:
            raise ValueError(f"operation is missing a usable operationId: {operation!r}")
        metadata = operation.get("metadata")
        if isinstance(metadata, dict):
            tags = operation.get("tags") or []
            availability = metadata.get("x-fern-availability", "unspecified")
            stability = metadata.get("x-stability", "unspecified")
            permissions_present = any(field in metadata for field in PERMISSION_FIELDS)
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
            permissions_present = operation.get("token_permissions_raw") not in (None, "", "{}")
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
        normalized.append(
            {
                "operation_id": operation_id,
                "method": str(operation.get("method", "")).upper(),
                "path": operation.get("path", ""),
                "tags": [tag for tag in tags if tag],
                "availability": availability,
                "stability": stability,
                "permissions_present": bool(permissions_present),
                "plan_availability": plan_availability if isinstance(plan_availability, dict) else {},
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
    missing permission metadata, a Forge-hidden-only flag, an unreviewed write or
    an unclassified product still block because the fact is re-derived here.
    """
    blockers: list[str] = []
    if not operation["permissions_present"]:
        blockers.append(BLOCKER_PERMISSION_ABSENT)
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
    if assign_product(operation)[0]["product_id"] == UNCLASSIFIED_PRODUCT_ID:
        blockers.append(BLOCKER_UNCLASSIFIED)
    return blockers


def recorded_blockers(entry: dict) -> list[str]:
    blockers = (entry.get("evidence") or {}).get("blockers")
    return [code for code in blockers if isinstance(code, str)] if isinstance(blockers, list) else []


def effective_blockers(operation: dict, entry: dict) -> list[str]:
    """Union of fact-derived and recorded blockers (facts cannot be washed out)."""
    return sorted(set(derived_blockers(operation)) | set(recorded_blockers(entry)))


def account_blocker_active(review: dict) -> bool:
    """True while the global no-authorized-account blocker is not cleared."""
    blockers = review.get("blockers") if isinstance(review, dict) else None
    if not isinstance(blockers, list):
        return True
    for blocker in blockers:
        if isinstance(blocker, dict) and blocker.get("code") == GLOBAL_ACCOUNT_BLOCKER:
            return not blocker.get("cleared_by")
    return False


def _verified(entry: dict, operation: dict, account_blocked: bool) -> bool:
    """API availability verified against an authorized account (not FlareGo capability)."""
    evidence = entry.get("evidence") or {}
    if entry.get("availability_decision") != "verified_available":
        return False
    if evidence.get("acceptance") != "authenticated_account":
        return False
    if not evidence.get("acceptance_record"):
        return False
    if derived_blockers(operation):
        return False
    return not account_blocked


def _capability_verified(entry: dict, account_blocked: bool) -> bool:
    """FlareGo L2/L3 capability acceptance, reported separately from availability."""
    evidence = entry.get("evidence") or {}
    if evidence.get("capability_acceptance") != "authenticated_account":
        return False
    if not evidence.get("capability_record"):
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
    normalized = normalize_operations(operations)
    overrides = dict(phase_overrides or {})
    mapping = implemented.get("operations", {}) if isinstance(implemented, dict) else {}
    if not isinstance(mapping, dict):
        raise ValueError("implemented mapping must contain an 'operations' object")
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

    if entry.get("target_level") == "L0" and not evidence.get("user_approval"):
        errors.append(
            f"{operation_id}: target_level L0 requires recorded user approval evidence"
        )
    if entry.get("availability_decision") == "verified_available" and (
        evidence.get("acceptance") != "authenticated_account"
    ):
        errors.append(
            f"{operation_id}: availability_decision verified_available requires "
            "evidence.acceptance == 'authenticated_account'"
        )
    if entry.get("availability_decision") == "provider_removed" and not evidence.get(
        "removal_evidence"
    ):
        errors.append(
            f"{operation_id}: availability_decision provider_removed requires removal evidence"
        )
    if entry.get("availability_decision") == "console_only" and not evidence.get(
        "console_only_evidence"
    ):
        errors.append(
            f"{operation_id}: availability_decision console_only requires console-only evidence"
        )
    if entry.get("availability_decision") == "verified_available" and not evidence.get(
        "acceptance_record"
    ):
        errors.append(
            f"{operation_id}: availability_decision verified_available requires an "
            "evidence.acceptance_record from an authorized account"
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
    elif entry.get("semantics") != "read" and BLOCKER_RISK_REVIEW not in blockers:
        errors.append(
            f"{operation_id}: semantics {entry.get('semantics')!r} must stay blocked with "
            f"{BLOCKER_RISK_REVIEW}"
        )
    missing = sorted(set(derived_blockers(operation)) - set(recorded_blockers(entry)))
    if missing:
        errors.append(
            f"{operation_id}: evidence.blockers omits fact-derived blocker(s) {', '.join(missing)}; "
            "the summary derives them from the catalog metadata regardless"
        )
    approvals = evidence.get("approvals")
    if not isinstance(approvals, list) or not approvals:
        errors.append(f"{operation_id}: evidence.approvals must list at least one approval code")
    else:
        for approval in approvals:
            if approval not in IMPLEMENTATION_APPROVALS:
                errors.append(f"{operation_id}: unknown implementation approval {approval!r}")
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
    """Return readable ledger errors without modifying the ledger."""
    normalized = normalize_operations(operations)
    if not isinstance(review, dict) or not isinstance(review.get("operations"), dict):
        return ["review document must contain an 'operations' object indexed by operationId"]
    entries = review["operations"]
    known = {operation["operation_id"] for operation in normalized}

    errors: list[str] = []
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
    return (entry.get("evidence") or {}).get("source_support") == "source_subset"


def summarize_review(operations: list[dict], review: dict) -> dict:
    """Count registered/target/implemented/verified/legacy/blocked and breakdowns.

    ``verified`` is API availability proven against an authorized account and
    ``capability_verified`` is FlareGo L2/L3 capability acceptance; they are
    reported separately and both stay 0 while no authorized account exists.
    """
    normalized = normalize_operations(operations)
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
        evidence = entry.get("evidence") or {}
        blockers = effective_blockers(operation, entry)
        product_id = entry.get("product_id", "<missing>")
        report["registered"] += 1
        if entry.get("target_level") in ("L2", "L3"):
            report["target"] += 1
        if is_implemented(operation, entry):
            report["implemented"] += 1
        if _verified(entry, operation, account_blocked):
            report["verified"] += 1
        if _capability_verified(entry, account_blocked):
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
        for approval in evidence.get("approvals") or []:
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
    """Optional deep check: pinned schema enumeration vs catalog vs ledger."""
    errors: list[str] = []
    enumerated = {operation["operation_id"] for operation in enumerate_operations(schema)}
    catalog = {operation["operation_id"] for operation in normalize_operations(catalog_operations)}
    entries = review.get("operations", {}) if isinstance(review, dict) else {}
    for operation_id in sorted(enumerated - catalog):
        errors.append(f"schema operation missing from the catalog CSV: {operation_id}")
    for operation_id in sorted(catalog - enumerated):
        errors.append(f"catalog CSV operation is absent from the schema: {operation_id}")
    for operation_id in sorted(enumerated - set(entries)):
        errors.append(f"schema operation has no review entry: {operation_id}")
    return errors
