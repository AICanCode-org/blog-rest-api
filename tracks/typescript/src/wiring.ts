// Builds the services from settings. Shared by the process entrypoint and the integration tests.
import {
  LlmClient,
  LlmModerator,
  LlmSummarizer,
  ModerationService,
  RuleModerator,
  SummaryService,
  TemplateSummarizer,
} from "./ai.js";
import { AuthService } from "./auth.js";
import { systemClock, type Clock } from "./clock.js";
import { CommentService } from "./comments.js";
import type { Settings } from "./config.js";
import { PostService } from "./posts.js";
import type { CommentRepository, PostRepository, UserRepository } from "./repository.js";
import { PasswordHasher, resolveSecret, TokenService } from "./security.js";
import type { AppDeps } from "./app.js";

export function buildAi(settings: Settings): { moderation: ModerationService; summaries: SummaryService } {
  if (settings.aiProvider === "llm") {
    const client = new LlmClient(
      settings.llmBaseUrl,
      settings.llmModel,
      settings.llmApiKey,
      settings.llmTimeoutMs,
    );
    return {
      moderation: new ModerationService(new RuleModerator(), new LlmModerator(client)),
      summaries: new SummaryService(new TemplateSummarizer(), new LlmSummarizer(client)),
    };
  }
  return {
    moderation: new ModerationService(new RuleModerator()),
    summaries: new SummaryService(new TemplateSummarizer()),
  };
}

export interface Repos {
  users: UserRepository;
  posts: PostRepository;
  comments: CommentRepository;
}

export async function wire(
  settings: Settings,
  repos: Repos,
  opts: { clock?: Clock; ai?: ReturnType<typeof buildAi>; warn?: (msg: string) => void } = {},
): Promise<AppDeps> {
  const clock = opts.clock ?? systemClock;
  const warn = opts.warn ?? ((msg: string) => console.warn(msg));
  const { moderation, summaries } = opts.ai ?? buildAi(settings);
  const tokens = new TokenService(resolveSecret(settings.jwtSecret, warn), clock);
  const auth = new AuthService(repos.users, new PasswordHasher(settings.bcryptCost), tokens, clock, { warn });
  if (settings.adminEmail && settings.adminPassword) {
    await auth.ensureAdmin(settings.adminEmail, settings.adminPassword);
  }
  return {
    auth,
    posts: new PostService(repos.posts, clock, summaries),
    comments: new CommentService(repos.comments, repos.posts, moderation, clock),
    clock,
  };
}
