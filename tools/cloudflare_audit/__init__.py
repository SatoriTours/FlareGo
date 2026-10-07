"""Reproducible Cloudflare coverage audit tooling.

The package turns the pinned official OpenAPI schema plus a hand-reviewed
implemented-subset mapping into deterministic CSV and snapshot artifacts.
It is offline-only: it never reads credentials and never calls Cloudflare.
"""
