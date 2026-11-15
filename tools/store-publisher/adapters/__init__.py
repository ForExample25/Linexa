"""Adapter registry.

Only GitHub Releases has a read-only verifier. Every store console is manual
until the owner supplies an authenticated session and the platform exposes an
approved API. Do not add an adapter that scrapes a console or stores a password.
"""

from __future__ import annotations

from .base import ManualActionRequired, ManualAdapter, StoreAdapter
from .github_release import GitHubReleaseAdapter

__all__ = [
    "ManualActionRequired",
    "ManualAdapter",
    "StoreAdapter",
    "GitHubReleaseAdapter",
    "build_adapters",
]


def build_adapters(stores: list[dict]) -> dict[str, StoreAdapter]:
    """Build an adapter per store config entry.

    The manual adapters carry the configured blockers so a caller gets an
    actionable error instead of a fake success.
    """
    adapters: dict[str, StoreAdapter] = {}
    for store in stores:
        store_id = store["id"]
        if store_id == "github-releases":
            adapters[store_id] = GitHubReleaseAdapter()
            continue
        reasons = list(store.get("blockers") or [])
        if not reasons:
            reasons = ["No authenticated store session is available."]
        adapters[store_id] = ManualAdapter(
            store_id=store_id,
            name=store["name"],
            portal=store["portal"],
            reasons=reasons,
        )
    return adapters
