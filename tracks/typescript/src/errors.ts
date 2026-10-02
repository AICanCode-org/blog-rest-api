// Domain errors that map 1:1 to RFC 9457 problem responses (identical in every track).
export const PROBLEM_BASE = "https://devblog.example/problems/";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly slug: string,
    readonly title: string,
    readonly detail?: string,
  ) {
    super(detail ?? title);
  }

  toProblem(): Record<string, unknown> {
    const body: Record<string, unknown> = {
      type: PROBLEM_BASE + this.slug,
      title: this.title,
      status: this.status,
    };
    if (this.detail) body.detail = this.detail;
    return body;
  }
}

export const validationError = (detail: string) =>
  new ApiError(400, "validation-error", "Validation failed", detail);
export const unauthorized = (detail = "authentication required") =>
  new ApiError(401, "unauthorized", "Unauthorized", detail);
export const invalidCredentials = () =>
  new ApiError(401, "invalid-credentials", "Invalid credentials", "wrong e-mail or password");
export const invalidRefreshToken = () =>
  new ApiError(401, "invalid-refresh-token", "Invalid refresh token", "sign in again");
export const forbidden = (detail = "you are not allowed to do this") =>
  new ApiError(403, "forbidden", "Forbidden", detail);
export const notFound = (what: string) => new ApiError(404, "not-found", "Not found", `${what} not found`);
export const emailTaken = () =>
  new ApiError(409, "email-taken", "E-mail already registered", "an account with this e-mail exists");
export const versionConflict = (current: number) =>
  new ApiError(
    409,
    "version-conflict",
    "Version conflict",
    `the post was changed by someone else (current version ${current})`,
  );

/** Thrown by a repository when a unique constraint (e-mail, slug) is violated. */
export class DuplicateError extends Error {}
