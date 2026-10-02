"""Hand-rolled Prometheus counters (text exposition format 0.0.4). v1.1.

No client library on purpose: students see that /metrics is just text. The metric names are
identical in all three tracks (see spec/openapi.yaml, GET /metrics).
"""

from __future__ import annotations

import threading

COUNTERS: dict[str, str] = {
    "devblog_users_registered_total": "Accounts created with POST /auth/register.",
    "devblog_logins_failed_total": "Login attempts answered with 401.",
    "devblog_posts_published_total": "Posts published for the first time.",
    "devblog_comments_created_total": "Comments created (visible or held).",
    "devblog_comments_held_total": "Comments the moderator put on hold for a human.",
    "devblog_ai_fallback_total": "Moderation or summary calls that fell back to the offline path.",
    "devblog_llm_short_circuits_total": "LLM calls skipped because the circuit breaker was open.",
}


class Metrics:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._values = dict.fromkeys(COUNTERS, 0)

    def inc(self, name: str, amount: int = 1) -> None:
        with self._lock:
            self._values[name] += amount

    def value(self, name: str) -> int:
        return self._values[name]

    def render(self) -> str:
        lines: list[str] = []
        for name, help_text in COUNTERS.items():
            lines += [f"# HELP {name} {help_text}", f"# TYPE {name} counter", f"{name} {self._values[name]}"]
        return "\n".join(lines) + "\n"
