import { Blob as NodeBlob } from 'node:buffer';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, configureApi, SessionExpiredError } from '../api';

describe('API client', () => {
  // Node's fetch needs its native Blob; jsdom's Blob has no text() method.
  beforeEach(() => vi.stubGlobal('Blob', NodeBlob));

  afterEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('adds the in-memory authentication token and locale headers', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } })
    );
    configureApi({
      token: 'token-1', locale: 'zh-CN', unauthorized: vi.fn(), refresh: async () => ''
    });
    await api.items();
    const headers = new Headers(fetchMock.mock.calls[0][1]?.headers);
    expect(headers.get('Authorization')).toBe('Bearer token-1');
    expect(headers.get('X-Locale')).toBe('zh-CN');
  });

  it('refreshes once and retries a password change after a 401', async () => {
    const refresh = vi.fn().mockImplementation(async () => {
      configureApi({ token: 'new-token', locale: 'en', unauthorized, refresh });
      return 'new-token';
    });
    const unauthorized = vi.fn();
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response('{"error":"expired"}', { status: 401 }))
      .mockResolvedValueOnce(new Response('', { status: 200 }));
    configureApi({ token: 'old-token', locale: 'en', unauthorized, refresh });

    await expect(api.changePassword('current-password', 'new-password')).resolves.toBeNull();

    expect(refresh).toHaveBeenCalledOnce();
    expect(unauthorized).not.toHaveBeenCalled();
    const retryHeaders = new Headers(fetchMock.mock.calls[1][1]?.headers);
    expect(retryHeaders.get('Authorization')).toBe('Bearer new-token');
    expect(fetchMock).toHaveBeenCalledTimes(2);
    for (const [path, init] of fetchMock.mock.calls) {
      expect(path).toBe('/api/auth/password');
      expect(init?.method).toBe('PUT');
      expect(JSON.parse(String(init?.body))).toEqual({
        currentPassword: 'current-password', newPassword: 'new-password'
      });
    }
  });

  it('does not refresh when password change returns a validation error', async () => {
    const refresh = vi.fn();
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response('{"error":"The current password is incorrect"}', { status: 400 }));
    configureApi({ token: 'token-1', locale: 'en', unauthorized: vi.fn(), refresh });

    await expect(api.changePassword('wrong-password', 'new-password'))
      .rejects.toThrow('The current password is incorrect');

    expect(refresh).not.toHaveBeenCalled();
    expect(fetchMock).toHaveBeenCalledOnce();
  });

  it('uses a CSRF token for browser login', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response(
        '{"token":"csrf-1","headerName":"X-XSRF-TOKEN"}',
        { status: 200 }
      ))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        accessToken: 'access', tokenType: 'Bearer', expiresIn: 600,
        user: { id: 1, username: 'admin', role: 'ADMIN' }
      }), { status: 200 }));
    configureApi({ token: '', locale: 'en', unauthorized: vi.fn(), refresh: async () => '' });

    await api.login('admin', 'long-enough-password');

    const headers = new Headers(fetchMock.mock.calls[1][1]?.headers);
    expect(headers.get('X-XSRF-TOKEN')).toBe('csrf-1');
  });

  it('refetches the CSRF token and retries once after a CSRF rejection', async () => {
    const authResponse = JSON.stringify({
      accessToken: 'access', tokenType: 'Bearer', expiresIn: 600,
      user: { id: 1, username: 'admin', role: 'ADMIN' }
    });
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response(
        '{"token":"stale-token","headerName":"X-XSRF-TOKEN"}',
        { status: 200 }
      ))
      .mockResolvedValueOnce(new Response(
        '{"code":"CSRF_INVALID","error":"The security token is invalid or expired"}',
        { status: 403 }
      ))
      .mockResolvedValueOnce(new Response(
        '{"token":"fresh-token","headerName":"X-XSRF-TOKEN"}',
        { status: 200 }
      ))
      .mockResolvedValueOnce(new Response(authResponse, { status: 200 }));
    configureApi({ token: '', locale: 'en', unauthorized: vi.fn(), refresh: async () => '' });

    await expect(api.login('admin', 'long-enough-password')).resolves.toBeDefined();

    expect(fetchMock).toHaveBeenCalledTimes(4);
    const retryHeaders = new Headers(fetchMock.mock.calls[3][1]?.headers);
    expect(retryHeaders.get('X-XSRF-TOKEN')).toBe('fresh-token');
  });

  it('does not retry an ordinary permission failure', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response(
        '{"token":"csrf-1","headerName":"X-XSRF-TOKEN"}',
        { status: 200 }
      ))
      .mockResolvedValueOnce(new Response(
        '{"error":"You do not have permission to perform this action"}',
        { status: 403 }
      ));
    configureApi({ token: '', locale: 'en', unauthorized: vi.fn(), refresh: async () => '' });

    await expect(api.login('admin', 'long-enough-password'))
      .rejects.toThrow('You do not have permission to perform this action');
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('stops after one CSRF retry', async () => {
    const csrfFailure = () => new Response(
      '{"code":"CSRF_INVALID","error":"The security token is invalid or expired"}',
      { status: 403 }
    );
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response(
        '{"token":"csrf-1","headerName":"X-XSRF-TOKEN"}',
        { status: 200 }
      ))
      .mockResolvedValueOnce(csrfFailure())
      .mockResolvedValueOnce(new Response(
        '{"token":"csrf-2","headerName":"X-XSRF-TOKEN"}',
        { status: 200 }
      ))
      .mockResolvedValueOnce(csrfFailure());
    configureApi({ token: '', locale: 'en', unauthorized: vi.fn(), refresh: async () => '' });

    await expect(api.login('admin', 'long-enough-password'))
      .rejects.toThrow('The security token is invalid or expired');
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it.each([
    ['create draft', () => api.createOrder('Draft sale', [{ item_id: 3, quantity: 2 }], 'DRAFT', '2026-07-10'),
      '/api/orders', 'POST', { text: 'Draft sale', items: [{ item_id: 3, quantity: 2 }], status: 'DRAFT', order_date: '2026-07-10' }],
    ['edit text', () => api.updateOrderText(42, 'Updated draft sale'), '/api/orders/42/text', 'PUT', { text: 'Updated draft sale' }],
    ['edit date', () => api.updateOrderDate(42, '2026-07-11'), '/api/orders/42/date', 'PUT', { order_date: '2026-07-11' }],
    ['add items', () => api.addOrderItems(42, [{ item_id: 4, quantity: 1 }]), '/api/orders/42/items', 'POST', { items: [{ item_id: 4, quantity: 1 }] }],
    ['remove item', () => api.removeOrderItem(42, 7), '/api/orders/42/items/7', 'DELETE', undefined],
    ['confirm', () => api.confirmOrder(42), '/api/orders/42/confirm', 'POST', undefined],
    ['cancel', () => api.cancelOrder(42), '/api/orders/42/cancel', 'POST', undefined],
    ['pick up', () => api.updateOrderPickup(42, true), '/api/orders/42/pickup', 'PUT', { picked_up: true }]
  ] as const)('sends the %s request', async (_name, send, path, method, body) => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{"id":42,"status":"DRAFT"}'));
    configureApi({ token: 'token-1', locale: 'en', unauthorized: vi.fn(), refresh: async () => '' });
    await send();
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith(path, expect.objectContaining({ method }));
    expect(fetchMock.mock.calls[0][1]?.body).toBe(JSON.stringify(body));
  });

  describe.each([
    ['JSON', () => api.items()],
    ['blob', () => api.templateFile('simple')]
  ] as const)('%s authenticated requests', (_name, send) => {
    it('refreshes once and parses a successful retry', async () => {
      const unauthorized = vi.fn();
      const refresh = vi.fn(async () => {
        configureApi({ token: 'new-token', locale: 'zh-CN', unauthorized, refresh });
        return 'new-token';
      });
      configureApi({ token: 'old-token', locale: 'zh-CN', unauthorized, refresh });
      const fetchMock = vi.spyOn(globalThis, 'fetch')
        .mockResolvedValueOnce(new Response('{"error":"expired"}', { status: 401 }))
        .mockResolvedValueOnce(new Response('[]'));
      const result = await send();
      if (Array.isArray(result)) expect(result).toEqual([]);
      else {
        await expect(result.text()).resolves.toBe('[]');
        expect(result.size).toBe(2);
      }
      expect(refresh).toHaveBeenCalledOnce();
      expect(unauthorized).not.toHaveBeenCalled();
      expect(fetchMock).toHaveBeenCalledTimes(2);
      const init = fetchMock.mock.calls[1][1];
      expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer new-token');
      expect(new Headers(init?.headers).get('X-Locale')).toBe('zh-CN');
      expect(init?.credentials).toBe('same-origin');
    });

    it('stops retrying after a second 401 and preserves the API error', async () => {
      const unauthorized = vi.fn();
      const refresh = vi.fn(async () => {
        configureApi({ token: 'new-token', locale: 'en', unauthorized, refresh });
        return 'new-token';
      });
      configureApi({ token: 'old-token', locale: 'en', unauthorized, refresh });
      const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async () =>
        new Response('{"error":"Still expired","code":"EXPIRED"}', { status: 401 }));
      await expect(send()).rejects.toMatchObject({ message: 'Still expired', status: 401, code: 'EXPIRED' });
      expect(refresh).toHaveBeenCalledOnce();
      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('expires the session when refresh returns no token', async () => {
      const unauthorized = vi.fn();
      const refresh = vi.fn(async () => '');
      configureApi({ token: 'old-token', locale: 'en', unauthorized, refresh });
      const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 401 }));
      await expect(send()).rejects.toBeInstanceOf(SessionExpiredError);
      expect(refresh).toHaveBeenCalledOnce();
      expect(unauthorized).toHaveBeenCalledOnce();
      expect(fetchMock).toHaveBeenCalledOnce();
    });
  });

  it('uses query parameters for barcodes that contain path separators', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{}', { status: 200 }));

    await api.itemByBarcode('A/B');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/items/by-barcode?barcode=A%2FB',
      expect.any(Object)
    );
  });

  it('encodes order searches on each paginated endpoint', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(() => Promise.resolve(
      new Response(JSON.stringify({
        items: [], page: 0, size: 50, totalItems: 0, totalPages: 0
      }), { status: 200 })
    ));

    await api.ordersPage(0, 50, ' widget/A ');
    await api.archivedOrdersPage(1, 25, '100%');
    await api.cancelledOrdersPage(2, 10, 'cancelled item');
    await api.ordersPage(2);

    expect(fetchMock).toHaveBeenNthCalledWith(
      1, '/api/orders/page?page=0&size=50&q=widget%2FA', expect.any(Object)
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2, '/api/orders/archived/page?page=1&size=25&q=100%25', expect.any(Object)
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      3, '/api/orders/cancelled/page?page=2&size=10&q=cancelled%20item', expect.any(Object)
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      4, '/api/orders/page?page=2&size=50', expect.any(Object)
    );
  });

  it('loads the admin daily-out-items dashboard', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(() => Promise.resolve(new Response(JSON.stringify({
      date: '2026-07-26',
      time_zone: 'Asia/Shanghai',
      totals: {
        gross_quantity: 0, restored_quantity: 0, net_quantity: 0,
        net_amount: 0, net_cost: 0, net_profit: 0
      },
      items: []
    }), { status: 200 })));

    await api.dailyOutItems();

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/dashboard/daily-out-items',
      expect.any(Object)
    );

    await api.dailyOutItems('2026-07-25');

    expect(fetchMock).toHaveBeenLastCalledWith(
      '/api/dashboard/daily-out-items?date=2026-07-25',
      expect.any(Object)
    );

    await api.dailyOutItemOrders(7, '2026-07-25');

    expect(fetchMock).toHaveBeenLastCalledWith(
      '/api/dashboard/daily-out-items/7/orders?date=2026-07-25',
      expect.any(Object)
    );

  });
});
