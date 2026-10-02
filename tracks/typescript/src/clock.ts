// Time. Services never call new Date() directly: they get a clock, so tests can travel in time.
// JavaScript dates already have millisecond precision, which is what the API, the keyset cursor
// and the database agree on (ADR-0002).
export type Clock = () => Date;

export const systemClock: Clock = () => new Date();

/** ISO-8601 UTC with millisecond precision and a 'Z' suffix, identical in every track. */
export const iso = (d: Date | null | undefined): string | null => (d ? d.toISOString() : null);
