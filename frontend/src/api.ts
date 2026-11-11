import type {
  AuthResponse, DashboardRelatedOrdersResponse, DashboardResponse, Item, ItemInput, Locale, Order, OrderPage,
  OrderReturnInput,
  ReceiptTemplate, UserAccount, UserRole, StockMovementPage
} from './types';

export const TOKEN_KEY = 'inventory-auth-token';
export const LOCALE_KEY = 'inventory-locale';
export const TEMPLATE_KEY = 'inventory-sell-template';
export const ORDER_DATE_KEY = 'inventory-sell-order-date';

export class SessionExpiredError extends Error {
  constructor() {
    super('Session expired');
    this.name = 'SessionExpiredError';
  }
}

type Session = {
  token: string;
  locale: Locale;
  unauthorized: () => void;
  refresh: () => Promise<string>;
};

let session: Session = {
  token: '',
  locale: 'en',
  unauthorized: () => undefined,
  refresh: async () => ''
};
let csrfRequest: Promise<{ token: string; headerName: string }> | null = null;

export function configureApi(next: Session) {
  session = next;
}

export function setApiAccessToken(token: string) {
  session = { ...session, token };
}

async function parseResponse<T>(response: Response): Promise<T> {
  const text = await response.text();
  let body: unknown = null;
  if (text) {
    try { body = JSON.parse(text); } catch { body = text; }
  }
  if (!response.ok) {
    const errorBody = body && typeof body === 'object'
      ? body as { error?: unknown; code?: unknown }
      : null;
    const message = errorBody?.error !== undefined
      ? String(errorBody.error)
      : `Request failed with status ${response.status}`;
    const code = errorBody?.code !== undefined ? String(errorBody.code) : undefined;
    throw Object.assign(new Error(message), { status: response.status, code });
  }
  return body as T;
}

async function ensureCsrf() {
  if (csrfRequest) return csrfRequest;
  const pending = fetch('/api/auth/csrf', {
    credentials: 'same-origin',
    headers: { 'X-Locale': session.locale }
  })
    .then(response => parseResponse<{ token: string; headerName: string }>(response));
  csrfRequest = pending;
  try {
    return await pending;
  } finally {
    if (csrfRequest === pending) csrfRequest = null;
  }
}

async function sessionRequest<T>(
  path: string,
  init: RequestInit = {},
  retryCsrf = true
): Promise<T> {
  const csrf = await ensureCsrf();
  const headers = new Headers(init.headers);
  headers.set('Content-Type', 'application/json');
  headers.set('X-Locale', session.locale);
  headers.set(csrf.headerName || 'X-XSRF-TOKEN', csrf.token);
  try {
    return await parseResponse<T>(await fetch(`/api${path}`, {
      ...init,
      headers,
      credentials: 'same-origin'
    }));
  } catch (error) {
    const apiError = error as Error & { code?: string };
    if (retryCsrf && apiError.code === 'CSRF_INVALID') {
      return sessionRequest<T>(path, init, false);
    }
    throw error;
  }
}

async function authenticatedFetch(path: string, init: RequestInit = {}, retry = true): Promise<Response> {
  const requestToken = session.token;
  const headers = new Headers(init.headers);
  headers.set('X-Locale', session.locale);
  if (requestToken) headers.set('Authorization', `Bearer ${requestToken}`);
  if (init.body) headers.set('Content-Type', 'application/json');
  const response = await fetch(`/api${path}`, { ...init, headers, credentials: 'same-origin' });
  if (response.status === 401 && requestToken && retry) {
    const nextToken = session.token !== requestToken ? session.token : await session.refresh();
    if (nextToken) return authenticatedFetch(path, init, false);
    session.unauthorized();
    throw new SessionExpiredError();
  }
  return response;
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  return parseResponse<T>(await authenticatedFetch(path, init));
}

async function requestBlob(path: string): Promise<Blob> {
  const response = await authenticatedFetch(path);
  if (!response.ok) await parseResponse(response);
  return response.blob();
}

function orderPagePath(path: string, page: number, size: number, search: string) {
  const query = search.trim();
  return `${path}?page=${page}&size=${size}${query ? `&q=${encodeURIComponent(query)}` : ''}`;
}

function dailyOutItemOrdersPath(itemId: number, date: string | undefined) {
  const query = new URLSearchParams();
  if (date) query.set('date', date);
  return `/dashboard/daily-out-items/${itemId}/orders?${query}`;
}

export const api = {
  stockMovements: (query: URLSearchParams) => request<StockMovementPage>(`/stock-movements?${query}`),
  login: (username: string, password: string) =>
    sessionRequest<AuthResponse>('/auth/session', {
      method: 'POST', body: JSON.stringify({ username, password })
    }),
  refreshSession: () =>
    sessionRequest<AuthResponse>('/auth/session/refresh', { method: 'POST', body: '{}' }),
  logout: async () => {
    try {
      try {
        await sessionRequest<void>('/auth/session', {
          method: 'DELETE',
          headers: session.token ? { Authorization: `Bearer ${session.token}` } : {}
        });
      } catch (error) {
        if ((error as Error & { status?: number }).status !== 401) throw error;
        await sessionRequest<void>('/auth/session', { method: 'DELETE' });
      }
    } finally {
      csrfRequest = null;
    }
  },
  changePassword: (currentPassword: string, newPassword: string) =>
    request<void>('/auth/password', {
      method: 'PUT', body: JSON.stringify({ currentPassword, newPassword })
    }),
  items: () => request<Item[]>('/items'),
  itemByBarcode: (barcode: string) =>
    request<Item>(`/items/by-barcode?barcode=${encodeURIComponent(barcode)}`),
  createItem: (item: ItemInput) => request<Item>('/items', { method: 'POST', body: JSON.stringify(item) }),
  updateItem: (id: number, item: ItemInput, version: number) =>
    request<Item>(`/items/${id}`, {
      method: 'PUT', body: JSON.stringify({ ...item, version })
    }),
  deleteItem: (id: number) => request<void>(`/items/${id}`, { method: 'DELETE' }),
  ordersPage: (page: number, size = 50, search = '') =>
    request<OrderPage>(orderPagePath('/orders/page', page, size, search)),
  archivedOrdersPage: (page: number, size = 50, search = '') =>
    request<OrderPage>(orderPagePath('/orders/archived/page', page, size, search)),
  cancelledOrdersPage: (page: number, size = 50, search = '') =>
    request<OrderPage>(orderPagePath('/orders/cancelled/page', page, size, search)),
  dailyOutItems: (date?: string) => request<DashboardResponse>(
    `/dashboard/daily-out-items${date ? `?date=${encodeURIComponent(date)}` : ''}`
  ),
  dailyOutItemOrders: (itemId: number, date: string | undefined) =>
    request<DashboardRelatedOrdersResponse>(
      dailyOutItemOrdersPath(itemId, date)
    ),
  order: (id: number) => request<Order>(`/orders/${id}`),
  createOrder: (
    text: string,
    items: Array<{ item_id: number; quantity: number }>,
    status: 'DRAFT' | 'CONFIRMED' = 'CONFIRMED',
    orderDate?: string
  ) => request<Order>('/orders', {
    method: 'POST', body: JSON.stringify({ text, items, status, order_date: orderDate })
  }),
  updateOrderText: (id: number, text: string) =>
    request<Order>(`/orders/${id}/text`, { method: 'PUT', body: JSON.stringify({ text }) }),
  updateOrderDate: (id: number, orderDate: string) =>
    request<Order>(`/orders/${id}/date`, {
      method: 'PUT', body: JSON.stringify({ order_date: orderDate })
    }),
  addOrderItems: (id: number, items: Array<{ item_id: number; quantity: number }>) =>
    request<Order>(`/orders/${id}/items`, { method: 'POST', body: JSON.stringify({ items }) }),
  removeOrderItem: (id: number, lineId: number) =>
    request<Order>(`/orders/${id}/items/${lineId}`, { method: 'DELETE' }),
  confirmOrder: (id: number) =>
    request<Order>(`/orders/${id}/confirm`, { method: 'POST' }),
  cancelOrder: (id: number) =>
    request<Order>(`/orders/${id}/cancel`, { method: 'POST' }),
  updateOrderPickup: (id: number, pickedUp: boolean) =>
    request<Order>(`/orders/${id}/pickup`, {
      method: 'PUT', body: JSON.stringify({ picked_up: pickedUp })
    }),
  returnOrder: (id: number, input: OrderReturnInput) =>
    request<Order>(`/orders/${id}/returns`, {
      method: 'POST', body: JSON.stringify(input)
    }),
  archiveOrder: (id: number) =>
    request<Order>(`/orders/${id}/archive`, { method: 'POST' }),
  templates: () => request<ReceiptTemplate[]>('/receipt-templates'),
  templateFile: (id: string) =>
    requestBlob(`/receipt-templates/${encodeURIComponent(id)}/download`),
  users: () => request<UserAccount[]>('/users'),
  createUser: (username: string, password: string, role: UserRole) =>
    request<UserAccount>('/users', {
      method: 'POST', body: JSON.stringify({ username, password, role })
    }),
  updateUser: (id: number, input: { role?: UserRole; active?: boolean }) =>
    request<void>(`/users/${id}`, { method: 'PATCH', body: JSON.stringify(input) }),
  resetUserPassword: (id: number, newPassword: string) =>
    request<void>(`/users/${id}/reset-password`, {
      method: 'POST', body: JSON.stringify({ newPassword })
    }),
  revokeUserSessions: (id: number) =>
    request<void>(`/users/${id}/revoke-sessions`, { method: 'POST', body: '{}' })
};
