export function getContentApiBaseUrl(): string | null {
  const baseUrl = import.meta.env.VITE_CONTENT_API_BASE_URL?.trim();
  return baseUrl ? baseUrl.replace(/\/$/, "") : null;
}
