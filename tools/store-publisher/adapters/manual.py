"""Manual-store adapter.

Kept in its own module so the registry and documentation make the boundary
obvious: these stores have no approved unattended submission API in this
project.
"""

from __future__ import annotations

from .base import ManualActionRequired, ManualAdapter

__all__ = ["ManualActionRequired", "ManualAdapter"]
