export const API_BASE_URL =
  (import.meta.env.VITE_API_BASE_URL as string | undefined)?.replace(/\/$/, '') ??
  'http://localhost:8000';

export async function requestJson<T>(
  path: string,
  init: RequestInit = {},
  errorPrefix = 'Request failed',
): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  if (init.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers,
  });

  if (!response.ok) {
    const suffix = response.statusText ? ` ${response.statusText}` : '';
    throw new Error(`${errorPrefix}: ${response.status}${suffix}`);
  }

  return response.json() as Promise<T>;
}
