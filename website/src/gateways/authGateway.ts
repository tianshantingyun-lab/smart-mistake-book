import { z } from "zod";
import type { AdminSession } from "../types";
import { getContentApiBaseUrl } from "./gatewayConfig";

const SESSION_KEY = "smart-mistake-book.website.admin-session";

const adminSessionSchema: z.ZodType<AdminSession> = z
  .object({
    mode: z.enum(["demo", "authenticated"]),
    displayName: z.string().min(1),
    startedAt: z.union([z.iso.datetime({ offset: true }), z.iso.date()]),
  })
  .strict();

export interface AuthGateway {
  readonly mode: "local-demo" | "remote";
  current(): Promise<AdminSession | null>;
  loginDemo(): Promise<AdminSession>;
  logout(): Promise<void>;
}

export class AuthGatewayError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
    this.name = "AuthGatewayError";
  }
}

export class DemoAuthGateway implements AuthGateway {
  readonly mode = "local-demo" as const;

  async current(): Promise<AdminSession | null> {
    const raw = sessionStorage.getItem(SESSION_KEY);
    if (!raw) return null;
    try {
      const result = adminSessionSchema.safeParse(JSON.parse(raw));
      if (result.success) return result.data;
    } catch {
      // Invalid demo sessions are removed below.
    }
    sessionStorage.removeItem(SESSION_KEY);
    return null;
  }

  async loginDemo(): Promise<AdminSession> {
    const session: AdminSession = {
      mode: "demo",
      displayName: "演示管理员",
      startedAt: new Date().toISOString(),
    };
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(session));
    return session;
  }

  async logout(): Promise<void> {
    sessionStorage.removeItem(SESSION_KEY);
  }
}

export class RestAuthGateway implements AuthGateway {
  readonly mode = "remote" as const;

  constructor(private readonly baseUrl: string) {}

  private async request(
    path: string,
    init?: RequestInit,
    treatAuthorizationFailureAsSignedOut = false,
  ): Promise<AdminSession | null> {
    const response = await fetch(`${this.baseUrl}${path}`, {
      ...init,
      credentials: "include",
      headers: { Accept: "application/json", ...init?.headers },
    });
    if (
      treatAuthorizationFailureAsSignedOut &&
      (response.status === 401 || response.status === 403)
    ) {
      return null;
    }
    if (!response.ok) {
      throw new AuthGatewayError(
        (await response.text()) || "管理员会话请求失败",
        response.status,
      );
    }
    if (response.status === 204) return null;

    let payload: unknown;
    try {
      payload = await response.json();
    } catch {
      throw new AuthGatewayError("管理员会话服务返回了无法解析的数据", response.status);
    }
    const result = adminSessionSchema.safeParse(payload);
    if (!result.success) {
      throw new AuthGatewayError("管理员会话服务返回的数据结构无效", 502);
    }
    return result.data;
  }

  current(): Promise<AdminSession | null> {
    return this.request("/api/v1/admin/session", undefined, true);
  }

  async loginDemo(): Promise<AdminSession> {
    const session = await this.request("/api/v1/admin/session/demo", {
      method: "POST",
    });
    if (!session) throw new AuthGatewayError("管理员会话未建立", 502);
    return session;
  }

  async logout(): Promise<void> {
    await this.request(
      "/api/v1/admin/session",
      { method: "DELETE" },
      true,
    );
  }
}

export function createAuthGateway(): AuthGateway {
  const baseUrl = getContentApiBaseUrl();
  return baseUrl ? new RestAuthGateway(baseUrl) : new DemoAuthGateway();
}
