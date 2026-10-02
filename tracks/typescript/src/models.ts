// Plain data objects shared by services and repositories (no framework imports).
export const ROLES = ["READER", "AUTHOR", "ADMIN"] as const;
export type Role = (typeof ROLES)[number];

export interface User {
  id: number;
  email: string;
  passwordHash: string;
  displayName: string;
  role: Role;
  createdAt: Date;
}

/** Who is calling, as proven by a verified access token. */
export interface Principal {
  userId: number;
  role: Role;
}
export const isAdmin = (p: Principal) => p.role === "ADMIN";

export interface RefreshToken {
  id: number;
  userId: number;
  tokenHash: string;
  createdAt: Date;
  expiresAt: Date;
  revokedAt: Date | null;
}

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
  user: User;
}

export type PostStatus = "draft" | "published";

export interface Post {
  id: number;
  slug: string;
  authorId: number;
  authorName: string;
  title: string;
  body: string;
  status: PostStatus;
  tags: string[];
  version: number;
  createdAt: Date;
  updatedAt: Date;
  publishedAt: Date | null;
  /** v1.1 */
  coverImageUrl: string | null;
}

export type Label = "ok" | "spam" | "toxic";

export interface Moderation {
  label: Label;
  confidence: number;
  provider: "rules" | "llm";
  fallback: boolean;
  reason: string;
}

export type CommentStatus = "visible" | "held" | "rejected";

export interface Comment {
  id: number;
  postId: number;
  postSlug: string;
  authorId: number;
  authorName: string;
  parentId: number | null;
  body: string;
  status: CommentStatus;
  moderation: Moderation;
  createdAt: Date;
  moderatedBy: number | null;
  moderatedAt: Date | null;
}

export interface TagCount {
  name: string;
  posts: number;
}

export interface FeedQuery {
  limit: number;
  tag: string | null;
  q: string | null;
  /** keyset: (publishedAt, id) of the last item seen */
  after: { publishedAt: Date; id: number } | null;
}
