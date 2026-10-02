// Hand-rolled Prometheus counters (text exposition format 0.0.4). v1.1.
// No client library on purpose: /metrics is just text. Names are identical in all three tracks.

export const COUNTERS = {
  devblog_users_registered_total: "Accounts created with POST /auth/register.",
  devblog_logins_failed_total: "Login attempts answered with 401.",
  devblog_posts_published_total: "Posts published for the first time.",
  devblog_comments_created_total: "Comments created (visible or held).",
  devblog_comments_held_total: "Comments the moderator put on hold for a human.",
  devblog_ai_fallback_total: "Moderation or summary calls that fell back to the offline path.",
  devblog_llm_short_circuits_total: "LLM calls skipped because the circuit breaker was open.",
} as const;

export type CounterName = keyof typeof COUNTERS;

export class Metrics {
  private values = new Map<CounterName, number>(
    (Object.keys(COUNTERS) as CounterName[]).map((name) => [name, 0]),
  );

  inc(name: CounterName, amount = 1) {
    this.values.set(name, (this.values.get(name) ?? 0) + amount);
  }

  value(name: CounterName) {
    return this.values.get(name) ?? 0;
  }

  render(): string {
    const lines: string[] = [];
    for (const [name, help] of Object.entries(COUNTERS) as [CounterName, string][]) {
      lines.push(`# HELP ${name} ${help}`, `# TYPE ${name} counter`, `${name} ${this.value(name)}`);
    }
    return lines.join("\n") + "\n";
  }
}
