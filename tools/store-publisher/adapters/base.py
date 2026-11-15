"""Store adapter contracts.

An adapter may verify a public artifact or report that a human must act. It
must never pretend to submit an app, and it must never handle a password, OTP,
CAPTCHA, or identity document.
"""

from __future__ import annotations

import abc
from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class Capability:
    """What an adapter can actually do without owner credentials."""

    can_verify: bool
    can_submit: bool
    reason: str


@dataclass(frozen=True)
class SubmissionResult:
    """Result of an attempted submission."""

    status: str
    message: str
    evidence: dict[str, Any] = field(default_factory=dict)


class ManualActionRequired(RuntimeError):
    """Raised when a step needs a human account, CAPTCHA, OTP, or payment."""

    def __init__(self, store: str, portal: str, reasons: list[str]):
        self.store = store
        self.portal = portal
        self.reasons = reasons
        super().__init__(
            f"{store}: manual action required at {portal}. "
            + " ".join(reasons)
        )


class StoreAdapter(abc.ABC):
    """Base class for a store adapter."""

    id: str
    name: str
    portal: str

    @abc.abstractmethod
    def capabilities(self) -> Capability:
        """Return what the adapter can do without owner credentials."""

    def submit(self, **_kwargs: Any) -> SubmissionResult:
        """Submit an app. Manual adapters must not fake this."""
        raise ManualActionRequired(
            self.name,
            self.portal,
            ["No authenticated submission session is available to the agent."],
        )


class ManualAdapter(StoreAdapter):
    """A store whose console is intentionally manual."""

    def __init__(self, store_id: str, name: str, portal: str, reasons: list[str]):
        self.id = store_id
        self.name = name
        self.portal = portal
        self._reasons = reasons

    def capabilities(self) -> Capability:
        return Capability(
            can_verify=False,
            can_submit=False,
            reason="; ".join(self._reasons),
        )

    def submit(self, **_kwargs: Any) -> SubmissionResult:
        raise ManualActionRequired(self.name, self.portal, self._reasons)
