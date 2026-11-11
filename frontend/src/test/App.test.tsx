import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useNavigate } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import App from '../App';
import { AuthProvider } from '../auth';
import { I18nProvider } from '../i18n';
import { ORDER_DATE_KEY, TOKEN_KEY } from '../api';
import { downloadBlob, exportOrderToExcel } from '../receipt-excel';
import { shanghaiToday } from '../utils/businessDate';

vi.mock('../receipt-excel', () => ({
  exportOrderToExcel: vi.fn(async () => {
    throw new Error('template missing');
  }),
  downloadBlob: vi.fn()
}));

function renderApp(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <I18nProvider><AuthProvider><App /></AuthProvider></I18nProvider>
    </MemoryRouter>
  );
}

const authenticated = {
  accessToken: 'valid-token',
  tokenType: 'Bearer',
  expiresIn: 600,
  user: { id: 1, username: 'admin', role: 'ADMIN' }
};

const cashierAuthenticated = {
  ...authenticated,
  user: { id: 2, username: 'cashier', role: 'CASHIER' }
};

const draftOrder = {
  id: 99, text: 'Draft sale', status: 'DRAFT', total_quantity: 1, total_amount: 12,
  returned_quantity: 0, returned_amount: 0, net_quantity: 1, net_amount: 12,
  total_profit: 2, returned_profit: 0, net_profit: 2,
  total_cost: 10, returned_cost: 0, net_cost: 10, return_status: 'NOT_RETURNED',
  order_date: '2026-01-03', created_at: '2026-01-01T10:00:00Z', created_by: 'admin', modified_at: null, modified_by: null,
  confirmed_at: null, confirmed_by: null, cancelled_at: null, cancelled_by: null,
  archived_at: null, archived_by: null,
  picked_up: false, picked_up_at: null,
  items: [{
    id: 7, item_id: 3, title: 'Widget', barcode: 'ABC', quantity: 1,
    returned_quantity: 0, remaining_quantity: 1, paid_amount: 12, subtotal: 12,
    cost: 10, cost_subtotal: 10, profit: 2, profit_subtotal: 2,
    created_at: '2026-01-01T10:00:00Z', created_by: 'admin', removed_at: null, removed_by: null
  }], removed_items: [], returns: []
};

describe('application routing', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    vi.clearAllMocks();
    vi.restoreAllMocks();
  });

  it('opens item stock history and filters and pages its movements', async () => {
    const user = userEvent.setup();
    const requests: URLSearchParams[] = [];
    let failNext = false;
    vi.spyOn(window, 'alert').mockImplementation(() => undefined);
    vi.spyOn(globalThis, 'fetch').mockImplementation(input => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) return Promise.resolve(new Response('{"token":"csrf"}'));
      if (path.endsWith('/auth/session/refresh')) return Promise.resolve(new Response(JSON.stringify(authenticated)));
      if (path.endsWith('/items')) return Promise.resolve(new Response(JSON.stringify([
        { id: 7, title: 'Widget', quantity: 8, cost: 1, profit: 0, price: 1, version: 0 }
      ])));
      if (path.includes('/stock-movements?')) {
        const query = new URL(path, 'http://localhost').searchParams;
        requests.push(query);
        if (failNext) {
          failNext = false;
          return Promise.resolve(new Response('{"error":"History temporarily unavailable"}', { status: 503 }));
        }
        return Promise.resolve(new Response(JSON.stringify({
          items: [{ id: 2, item_id: null, item_id_snapshot: 7, title_snapshot: 'Widget',
            delta: -2, quantity_before: 10, quantity_after: 8, reason: 'ORDER_RESERVED',
            order_id: 42, order_text: 'Updated sale', created_by: 'cashier', created_at: '2026-09-05T01:00:00Z' },
          { id: 1, item_id: 7, item_id_snapshot: 7, title_snapshot: 'Original Widget',
            delta: 10, quantity_before: 0, quantity_after: 10, reason: 'OPENING_STOCK',
            order_id: null, order_text: null, created_by: 'admin', created_at: '2026-09-05T00:00:00Z' }],
          page: Number(query.get('page') || 0), size: 50, totalItems: 51, totalPages: 2
        })));
      }
      return Promise.resolve(new Response('[]'));
    });
    renderApp('/items');
    await user.click(await screen.findByRole('link', { name: 'History' }));
    expect(await screen.findByText('-2')).toBeInTheDocument();
    expect(screen.getByText('cashier')).toBeInTheDocument();
    expect(screen.getByText(/Deleted item/)).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Order note or reference' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Updated sale' })).toHaveAttribute('href', '/orders/42');
    expect(screen.queryByText('#42')).not.toBeInTheDocument();
    expect(within(screen.getByRole('cell', { name: '-' })).queryByRole('link')).not.toBeInTheDocument();
    expect(requests.at(-1)?.get('itemId')).toBe('7');
    expect(requests.at(-1)?.get('date')).toBeNull();
    expect(screen.getByLabelText('Movement date')).toHaveValue('');
    expect(screen.getByText('All dates · Asia/Shanghai')).toBeInTheDocument();
    await user.selectOptions(screen.getByRole('combobox', { name: 'Reason' }), 'ORDER_RESERVED');
    await waitFor(() => expect(requests.at(-1)?.get('reason')).toBe('ORDER_RESERVED'));
    await user.click(await screen.findByRole('button', { name: 'Next' }));
    await waitFor(() => expect(requests.at(-1)?.get('page')).toBe('1'));
    failNext = true;
    await user.type(screen.getByRole('searchbox', { name: 'Search items' }), 'Widget');
    await waitFor(() => expect(requests.at(-1)?.get('q')).toBe('Widget'));
    expect(requests.at(-1)?.get('page')).toBeNull();
    const failedQuery = requests.at(-1)?.toString();
    await user.click(await screen.findByRole('button', { name: 'Refresh' }));
    expect(await screen.findByText('-2')).toBeInTheDocument();
    expect(requests.at(-1)?.toString()).toBe(failedQuery);
    expect(screen.getByRole('searchbox', { name: 'Search items' })).toHaveValue('Widget');
    expect(screen.getByRole('combobox', { name: 'Reason' })).toHaveValue('ORDER_RESERVED');
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(screen.getByLabelText('Movement date')).toBeEnabled());
    fireEvent.change(screen.getByLabelText('Movement date'), { target: { value: '2026-09-05' } });
    await waitFor(() => expect(requests.at(-1)?.get('date')).toBe('2026-09-05'));
    expect(requests.at(-1)?.get('page')).toBeNull();
    expect(requests.at(-1)?.get('q')).toBe('Widget');
    expect(requests.at(-1)?.get('reason')).toBe('ORDER_RESERVED');
    expect(requests.at(-1)?.get('itemId')).toBe('7');
    await waitFor(() => expect(screen.getByLabelText('Movement date')).toBeEnabled());
    const dateQuery = requests.at(-1)?.toString();
    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    await waitFor(() => expect(screen.getByLabelText('Movement date')).toBeEnabled());
    expect(requests.at(-1)?.toString()).toBe(dateQuery);
    expect(screen.getByRole('link', { name: 'Widget' }).getAttribute('href')).toContain('date=2026-09-05');
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(screen.getByLabelText('Movement date')).toBeEnabled());
    fireEvent.change(screen.getByLabelText('Movement date'), { target: { value: '' } });
    await waitFor(() => expect(requests.at(-1)?.get('date')).toBeNull());
    expect(screen.getByLabelText('Movement date')).toHaveValue('');
    expect(requests.at(-1)?.get('page')).toBeNull();
    expect(requests.at(-1)?.get('q')).toBe('Widget');
    expect(requests.at(-1)?.get('reason')).toBe('ORDER_RESERVED');
    expect(requests.at(-1)?.get('itemId')).toBe('7');
    await waitFor(() => expect(screen.getByLabelText('Movement date')).toBeEnabled());
    expect(screen.getByRole('link', { name: 'Widget' }).getAttribute('href')).not.toContain('date=');
    const allDatesQuery = requests.at(-1)?.toString();
    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    await waitFor(() => expect(screen.getByLabelText('Movement date')).toBeEnabled());
    expect(requests.at(-1)?.toString()).toBe(allDatesQuery);
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(requests.at(-1)?.get('page')).toBe('1'));
    expect(requests.at(-1)?.get('date')).toBeNull();
    await user.click(await screen.findByRole('button', { name: /Clear item filter/ }));
    await waitFor(() => expect(requests.at(-1)?.get('itemId')).toBeNull());
    expect(requests.at(-1)?.get('date')).toBeNull();
  });

  it('restores stock movement dates through URL history and shows empty days', async () => {
    const user = userEvent.setup();
    const requests: string[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(input => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) return Promise.resolve(new Response('{"token":"csrf"}'));
      if (path.endsWith('/auth/session/refresh')) return Promise.resolve(new Response(JSON.stringify(authenticated)));
      if (path.includes('/stock-movements?')) {
        requests.push(new URL(path, 'http://localhost').searchParams.get('date') || '');
        return Promise.resolve(new Response(JSON.stringify({
          items: [], page: 0, size: 50, totalItems: 0, totalPages: 0
        })));
      }
      return Promise.resolve(new Response('[]'));
    });
    function HistoryControls() {
      const navigate = useNavigate();
      return <><button onClick={() => navigate(-1)}>Browser back</button>
        <button onClick={() => navigate(1)}>Browser forward</button></>;
    }
    render(<MemoryRouter initialEntries={['/stock-history?date=2026-09-01']}>
      <I18nProvider><AuthProvider><HistoryControls /><App /></AuthProvider></I18nProvider>
    </MemoryRouter>);
    await waitFor(() => expect(screen.getByLabelText('Movement date')).toBeEnabled());
    expect(screen.getByLabelText('Movement date')).toHaveValue('2026-09-01');
    expect(requests).toEqual(['2026-09-01']);
    fireEvent.change(screen.getByLabelText('Movement date'), { target: { value: '2026-09-02' } });
    await waitFor(() => expect(requests.at(-1)).toBe('2026-09-02'));
    await user.click(screen.getByRole('button', { name: 'Browser back' }));
    await waitFor(() => expect(requests.at(-1)).toBe('2026-09-01'));
    expect(screen.getByLabelText('Movement date')).toHaveValue('2026-09-01');
    await user.click(screen.getByRole('button', { name: 'Browser forward' }));
    await waitFor(() => expect(requests.at(-1)).toBe('2026-09-02'));
    expect(screen.getByLabelText('Movement date')).toHaveValue('2026-09-02');
    await waitFor(() => expect(screen.getByLabelText('Movement date')).toBeEnabled());
    fireEvent.change(screen.getByLabelText('Movement date'), { target: { value: '' } });
    await waitFor(() => expect(requests.at(-1)).toBe(''));
    await user.click(screen.getByRole('button', { name: 'Browser back' }));
    await waitFor(() => expect(requests.at(-1)).toBe('2026-09-02'));
    expect(screen.getByLabelText('Movement date')).toHaveValue('2026-09-02');
    await user.click(screen.getByRole('button', { name: 'Browser forward' }));
    await waitFor(() => expect(requests.at(-1)).toBe(''));
    expect(screen.getByLabelText('Movement date')).toHaveValue('');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Next' })).not.toBeInTheDocument();
  });

  it('discards an invalid stored order date and defaults to Shanghai today', async () => {
    sessionStorage.setItem(ORDER_DATE_KEY, '2026-02-30');
    vi.spyOn(globalThis, 'fetch').mockImplementation(input => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) return Promise.resolve(new Response('{"token":"csrf"}', { status: 200 }));
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(authenticated), { status: 200 }));
      }
      if (path.endsWith('/items') || path.endsWith('/receipt-templates')) {
        return Promise.resolve(new Response('[]', { status: 200 }));
      }
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/sell');

    expect(await screen.findByLabelText('Order Date')).toHaveValue(shanghaiToday());
    expect(sessionStorage.getItem(ORDER_DATE_KEY)).toBeNull();
  });

  it('redirects protected screens to login when refresh is unavailable', async () => {
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response('{"token":"csrf"}', { status: 200 }))
      .mockResolvedValueOnce(new Response('{"error":"invalid"}', { status: 401 }));
    renderApp('/items');
    expect(await screen.findByRole('heading', { name: 'Login' })).toBeInTheDocument();
  });

  it('restores a browser session through the refresh cookie', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(input => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) return Promise.resolve(new Response('{"token":"csrf"}', { status: 200 }));
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(authenticated), { status: 200 }));
      }
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/items');

    expect(await screen.findByRole('heading', { name: 'Inventory Items' })).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalled();
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
  });

  it('selects a dashboard date and refreshes that date', async () => {
    let dashboardLoads = 0;
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(input => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) {
        return Promise.resolve(new Response('{"token":"csrf"}', { status: 200 }));
      }
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(authenticated), { status: 200 }));
      }
      if (path.includes('/dashboard/daily-out-items')) {
        dashboardLoads += 1;
        const selected = path.includes('?date=2026-07-25');
        return Promise.resolve(new Response(JSON.stringify({
          date: selected ? '2026-07-25' : '2026-07-26',
          time_zone: 'Asia/Shanghai',
          totals: {
            gross_quantity: selected ? 3 : 5,
            restored_quantity: 1,
            net_quantity: selected ? 2 : 4,
            net_amount: selected ? 24 : 48,
            net_cost: selected ? 20 : 40,
            net_profit: selected ? 4 : 8
          },
          items: [{
            item_id: selected ? 2 : 1,
            title: selected ? 'Cable' : 'Widget',
            barcode: selected ? 'CAB' : 'ABC',
            gross_quantity: selected ? 3 : 5,
            restored_quantity: 1,
            net_quantity: selected ? 2 : 4,
            net_amount: selected ? 24 : 48,
            net_cost: selected ? 20 : 40,
            net_profit: selected ? 4 : 8
          }]
        }), { status: 200 }));
      }
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/dashboard');
    const user = userEvent.setup();

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Daily Out Items' })).toBeInTheDocument();
    expect(await screen.findByText(/assigned to the selected order date/i)).toBeInTheDocument();
    expect(await screen.findByText('2026-07-26 · Asia/Shanghai')).toBeInTheDocument();
    const total = screen.getByRole('region', { name: 'Total' });
    await user.click(within(total).getByRole('button', { name: 'Total' }));
    expect(within(total).getByRole('row', { name: /Widget 5 1 4/ })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Dashboard' })).toBeInTheDocument();

    const dateInput = screen.getByLabelText('Dashboard date');
    expect(dateInput).toHaveValue('2026-07-26');
    fireEvent.change(dateInput, { target: { value: '2026-07-25' } });

    expect(await screen.findByRole('row', { name: /Cable 3 1 2/ })).toBeInTheDocument();
    expect(dateInput).toHaveValue('2026-07-25');
    expect(fetchMock).toHaveBeenLastCalledWith(
      '/api/dashboard/daily-out-items?date=2026-07-25',
      expect.any(Object)
    );

    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    await waitFor(() => expect(dashboardLoads).toBe(3));
    expect(fetchMock).toHaveBeenLastCalledWith(
      '/api/dashboard/daily-out-items?date=2026-07-25',
      expect.any(Object)
    );
  });

  it('shows a collapsed total and drills into all contributing orders', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(input => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) {
        return Promise.resolve(new Response('{"token":"csrf"}', { status: 200 }));
      }
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(authenticated), { status: 200 }));
      }
      if (path.includes('/dashboard/daily-out-items/1/orders')) {
        return Promise.resolve(new Response(JSON.stringify({
          date: '2026-07-25',
          time_zone: 'Asia/Shanghai',
          item_id: 1,
          orders: [
            { id: 42, text: 'Second sale', status: 'CONFIRMED', archived_at: null,
              gross_quantity: 3, restored_quantity: 1, net_quantity: 2 },
            { id: 41, text: 'First sale', status: 'CONFIRMED', archived_at: null,
              gross_quantity: 2, restored_quantity: 0, net_quantity: 2 }
          ]
        }), { status: 200 }));
      }
      if (path.includes('/dashboard/daily-out-items')) {
        return Promise.resolve(new Response(JSON.stringify({
          date: '2026-07-25',
          time_zone: 'Asia/Shanghai',
          totals: {
            gross_quantity: 5, restored_quantity: 1, net_quantity: 4,
            net_amount: 48, net_cost: 40, net_profit: 8
          },
          items: [
            { item_id: 1, title: 'Widget', barcode: 'ABC',
              gross_quantity: 5, restored_quantity: 1, net_quantity: 4,
              net_amount: 48, net_cost: 40, net_profit: 8 }
          ]
        }), { status: 200 }));
      }
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/dashboard');
    const user = userEvent.setup();
    const total = await screen.findByRole('region', { name: 'Total' });
    const totalToggle = within(total).getByRole('button', { name: 'Total' });

    expect(totalToggle).toHaveAttribute('aria-expanded', 'false');
    expect(within(total).queryByRole('table')).not.toBeInTheDocument();
    expect(screen.getAllByRole('region')).toHaveLength(1);

    await user.click(totalToggle);
    expect(totalToggle).toHaveAttribute('aria-expanded', 'true');
    expect(within(total).getByRole('row', { name: /Widget 5 1 4/ })).toBeInTheDocument();

    await user.click(within(total).getByRole('button', { name: 'Widget' }));
    expect(await within(total).findByText('Second sale')).toBeInTheDocument();
    expect(within(total).queryByRole('columnheader', { name: 'Port' })).not.toBeInTheDocument();
    const relatedDetails = within(total).getByRole('heading', { name: 'Related Orders: Widget' }).closest('td');
    const relatedTable = within(relatedDetails!).getByRole('table');
    expect(within(relatedTable).getByRole('row', { name: /42 Second sale Confirmed 3 1 2/ })).toBeInTheDocument();
    expect(within(relatedTable).getByRole('row', { name: /41 First sale Confirmed 2 0 2/ })).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/dashboard/daily-out-items/1/orders?date=2026-07-25',
      expect.any(Object)
    );

    await user.click(totalToggle);
    expect(totalToggle).toHaveAttribute('aria-expanded', 'false');
    expect(within(total).queryByRole('table')).not.toBeInTheDocument();
  });

  it('shows Unknown when dashboard cost and profit are omitted', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(input => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) {
        return Promise.resolve(new Response('{"token":"csrf"}', { status: 200 }));
      }
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(authenticated), { status: 200 }));
      }
      if (path.endsWith('/dashboard/daily-out-items')) {
        return Promise.resolve(new Response(JSON.stringify({
          date: '2026-07-26',
          time_zone: 'Asia/Shanghai',
          totals: {
            gross_quantity: 2,
            restored_quantity: 0,
            net_quantity: 2,
            net_amount: 24
          },
          items: [{
            item_id: 1,
            title: 'Legacy Widget',
            barcode: null,
            gross_quantity: 2,
            restored_quantity: 0,
            net_quantity: 2,
            net_amount: 24
          }]
        }), { status: 200 }));
      }
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/dashboard');

    const total = await screen.findByRole('region', { name: 'Total' });
    await userEvent.click(within(total).getByRole('button', { name: 'Total' }));
    expect(within(total).getByRole('row', { name: /Legacy Widget/ })).toHaveTextContent('Unknown');
    expect(within(total).getAllByText('Unknown').length).toBeGreaterThanOrEqual(4);
  });

  it('expands an item to show its selected-date orders', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(input => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) {
        return Promise.resolve(new Response('{"token":"csrf"}', { status: 200 }));
      }
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(authenticated), { status: 200 }));
      }
      if (path.includes('/dashboard/daily-out-items/1/orders')) {
        return Promise.resolve(new Response(JSON.stringify({
          date: '2026-07-25',
          time_zone: 'Asia/Shanghai',
          item_id: 1,
          orders: [
            { id: 41, text: 'Archived sale', status: 'CONFIRMED',
              archived_at: '2026-07-26T01:00:00Z',
              gross_quantity: 2, restored_quantity: 2, net_quantity: 0 }
          ]
        }), { status: 200 }));
      }
      if (path.includes('/dashboard/daily-out-items')) {
        return Promise.resolve(new Response(JSON.stringify({
          date: '2026-07-25',
          time_zone: 'Asia/Shanghai',
          totals: {
            gross_quantity: 5, restored_quantity: 1, net_quantity: 4,
            net_amount: 48, net_cost: 40, net_profit: 8
          },
          items: [{
            item_id: 1, title: 'Widget', barcode: 'ABC',
            gross_quantity: 5, restored_quantity: 1, net_quantity: 4,
            net_amount: 48, net_cost: 40, net_profit: 8
          }]
        }), { status: 200 }));
      }
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/dashboard');
    const user = userEvent.setup();
    const total = await screen.findByRole('region', { name: 'Total' });
    await user.click(within(total).getByRole('button', { name: 'Total' }));
    const itemButton = within(total).getByRole('button', { name: 'Widget' });

    await user.click(itemButton);

    const relatedHeading = await screen.findByRole('heading', { name: 'Related Orders: Widget' });
    const relatedDetails = relatedHeading.closest('td');
    expect(relatedDetails).not.toBeNull();
    expect(within(relatedDetails!).getByRole('row', { name: /41 Archived sale Archived 2 2 0/ })).toBeInTheDocument();
    expect(within(relatedDetails!).queryByRole('columnheader', { name: 'Port' })).not.toBeInTheDocument();
    expect(within(relatedDetails!).queryByRole('columnheader', { name: 'Date' })).not.toBeInTheDocument();
    expect(within(relatedDetails!).getByRole('link', { name: 'View' })).toHaveAttribute('href', '/orders/41');
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/dashboard/daily-out-items/1/orders?date=2026-07-25',
      expect.any(Object)
    );

    await user.click(itemButton);
    expect(screen.queryByRole('heading', { name: 'Related Orders: Widget' })).not.toBeInTheDocument();
  });

  it('shows one day-level empty state', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(input => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) return Promise.resolve(new Response('{"token":"csrf"}', { status: 200 }));
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(authenticated), { status: 200 }));
      }
      if (path.endsWith('/dashboard/daily-out-items')) {
        return Promise.resolve(new Response(JSON.stringify({
          date: '2026-07-26',
          time_zone: 'Asia/Shanghai',
          totals: {
            gross_quantity: 0, restored_quantity: 0, net_quantity: 0,
            net_amount: 0, net_cost: 0, net_profit: 0
          },
          items: []
        }), { status: 200 }));
      }
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/dashboard');

    const total = await screen.findByRole('region', { name: 'Total' });
    await userEvent.click(within(total).getByRole('button', { name: 'Total' }));
    expect(within(total).getByText('No order items were recorded on the selected date.')).toBeInTheDocument();
    expect(within(total).getAllByText('HK$0.00')).toHaveLength(2);
  });

  it('displays all dashboard items in the order returned by the API', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(input => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) return Promise.resolve(new Response('{"token":"csrf"}', { status: 200 }));
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(authenticated), { status: 200 }));
      }
      if (path.endsWith('/dashboard/daily-out-items')) {
        return Promise.resolve(new Response(JSON.stringify({
          date: '2026-07-26',
          time_zone: 'Asia/Shanghai',
          totals: {
            gross_quantity: 14, restored_quantity: 2, net_quantity: 12,
            net_amount: 144, net_cost: 120, net_profit: 24
          },
          items: [
            { item_id: 1, title: 'Widget', barcode: 'W',
              gross_quantity: 5, restored_quantity: 1, net_quantity: 4,
              net_amount: 48, net_cost: 40, net_profit: 8 },
            { item_id: 2, title: 'Cable', barcode: 'C',
              gross_quantity: 3, restored_quantity: 1, net_quantity: 2,
              net_amount: 24, net_cost: 20, net_profit: 4 },
            { item_id: 3, title: 'Adapter', barcode: 'A',
              gross_quantity: 4, restored_quantity: 0, net_quantity: 4,
              net_amount: 48, net_cost: 40, net_profit: 8 },
            { item_id: 4, title: 'Legacy', barcode: null,
              gross_quantity: 2, restored_quantity: 0, net_quantity: 2,
              net_amount: 24, net_cost: 20, net_profit: 4 }
          ]
        }), { status: 200 }));
      }
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/dashboard');

    const total = await screen.findByRole('region', { name: 'Total' });
    await userEvent.click(within(total).getByRole('button', { name: 'Total' }));
    expect(screen.queryByRole('columnheader', { name: 'Barcode' })).not.toBeInTheDocument();
    expect(screen.queryByText('Current Net Value')).not.toBeInTheDocument();
    expect(screen.queryByText('HK$144.00')).not.toBeInTheDocument();
    expect(within(total).getByRole('columnheader', { name: 'Restocked' })).toBeInTheDocument();
    const rows = within(total).getAllByRole('row').slice(1);
    expect(rows.map(row => within(row).getByRole('button').textContent))
      .toEqual(['▸Widget', '▸Cable', '▸Adapter', '▸Legacy']);
  });

  it('redirects cashiers away from the dashboard without loading its data', async () => {
    let dashboardLoads = 0;
    vi.spyOn(globalThis, 'fetch').mockImplementation(input => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) {
        return Promise.resolve(new Response('{"token":"csrf"}', { status: 200 }));
      }
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(cashierAuthenticated), { status: 200 }));
      }
      if (path.endsWith('/dashboard/daily-out-items')) dashboardLoads += 1;
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/dashboard');

    expect(await screen.findByRole('heading', { name: 'Inventory Items' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Dashboard' })).not.toBeInTheDocument();
    expect(dashboardLoads).toBe(0);
  });

  it('lets a cashier add and edit items without delete or stock history', async () => {
    const user = userEvent.setup();
    const item = {
      id: 1, title: 'Widget', description: '', quantity: 3,
      cost: 10, profit: 2, price: 12, barcode: null, version: 4
    };
    let items: Array<typeof item> = [];
    const mutations: Array<{ path: string; method: string; body: unknown }> = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) return Promise.resolve(new Response('{"token":"csrf"}', { status: 200 }));
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(cashierAuthenticated), { status: 200 }));
      }
      if (init?.method && ['POST', 'PUT', 'DELETE'].includes(init.method)) {
        mutations.push({ path, method: init.method, body: init.body ? JSON.parse(String(init.body)) : undefined });
        if (path === '/api/items' && init.method === 'POST') {
          items = [item];
          return Promise.resolve(new Response(JSON.stringify(item), { status: 201 }));
        }
        if (path === '/api/items/1' && init.method === 'PUT') {
          items = [{ ...item, title: 'Updated widget', quantity: 5, version: 5 }];
          return Promise.resolve(new Response(JSON.stringify(items[0])));
        }
      }
      if (path === '/api/items') return Promise.resolve(new Response(JSON.stringify(items)));
      throw new Error(`Unexpected request: ${init?.method || 'GET'} ${path}`);
    });

    renderApp('/items');

    await user.click(await screen.findByRole('button', { name: 'Add Item' }));
    await user.type(screen.getByRole('textbox', { name: 'Title' }), 'Widget');
    await user.type(screen.getByRole('spinbutton', { name: 'Cost' }), '10');
    await user.type(screen.getByRole('spinbutton', { name: 'Profit' }), '2');
    await user.type(screen.getByRole('spinbutton', { name: 'Quantity' }), '3');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('row', { name: /Widget/ })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'History' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Create Order' })).toHaveAttribute('href', '/sell');

    await user.click(screen.getByRole('button', { name: 'Edit' }));
    const title = screen.getByRole('textbox', { name: 'Title' });
    await user.clear(title);
    await user.type(title, 'Updated widget');
    const quantity = screen.getByRole('spinbutton', { name: 'Quantity' });
    await user.clear(quantity);
    await user.type(quantity, '5');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    const updatedRow = await screen.findByRole('row', { name: /Updated widget/ });
    expect(within(updatedRow).getByRole('cell', { name: '5' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();
    const body = { title: 'Widget', description: '', quantity: 3, cost: 10, profit: 2, barcode: null };
    expect(mutations).toEqual([
      { path: '/api/items', method: 'POST', body },
      { path: '/api/items/1', method: 'PUT', body: { ...body, title: 'Updated widget', quantity: 5, version: 4 } }
    ]);
  });

  it.each([
    { status: 'DRAFT', action: 'Save Draft' },
    { status: 'CONFIRMED', action: 'Confirm' },
    { status: 'CONFIRMED', action: 'Confirm & Export' }
  ])('lets a cashier create an order with $action', async ({ status, action }) => {
    const user = userEvent.setup();
    const order = { ...draftOrder, status, created_by: 'cashier' };
    const createdBodies: unknown[] = [];
    const receipt = new Blob(['receipt']);
    vi.spyOn(window, 'alert').mockImplementation(() => undefined);
    if (action === 'Confirm & Export') {
      vi.mocked(exportOrderToExcel).mockResolvedValueOnce({ blob: receipt, capacity: 1, itemCount: 1 });
    }
    vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) return Promise.resolve(new Response('{"token":"csrf"}', { status: 200 }));
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(cashierAuthenticated), { status: 200 }));
      }
      if (path.endsWith('/items')) return Promise.resolve(new Response(JSON.stringify([
        { id: 3, title: 'Widget', quantity: 5, cost: 10, profit: 2, price: 12, barcode: 'ABC' }
      ])));
      if (path.endsWith('/receipt-templates')) {
        return Promise.resolve(new Response('[{"id":"simple","name":"Simple"}]'));
      }
      if (path.endsWith('/orders') && init?.method === 'POST') {
        createdBodies.push(JSON.parse(String(init.body)));
        return Promise.resolve(new Response(JSON.stringify(order), { status: 201 }));
      }
      if (path.endsWith('/orders/99')) return Promise.resolve(new Response(JSON.stringify(order)));
      if (path.includes('/orders/page')) {
        return Promise.resolve(new Response(JSON.stringify({
          items: [order], page: 0, size: 50, totalItems: 1, totalPages: 1
        })));
      }
      throw new Error(`Unexpected request: ${init?.method || 'GET'} ${path}`);
    });

    renderApp('/sell');

    expect(await screen.findByRole('heading', { name: 'Create Order' })).toBeInTheDocument();
    await user.click(screen.getByRole('combobox', { name: 'Select an item…' }));
    await user.click(await screen.findByRole('option', { name: 'Widget — ABC (5)' }));
    await user.click(screen.getByRole('button', { name: 'Add to Cart' }));
    await user.type(screen.getByRole('textbox', { name: 'Order note or reference' }), 'Counter sale');
    fireEvent.change(screen.getByLabelText('Order Date'), { target: { value: '2026-01-03' } });
    await user.click(screen.getByRole('button', { name: action }));

    expect(await screen.findByRole('heading', { name: status === 'DRAFT' ? 'Orders #99' : 'Orders' }))
      .toBeInTheDocument();
    expect(createdBodies).toEqual([{
      text: 'Counter sale', items: [{ item_id: 3, quantity: 1 }],
      status, order_date: '2026-01-03'
    }]);
    if (action === 'Confirm & Export') {
      expect(exportOrderToExcel).toHaveBeenCalledWith(order, 'simple', expect.any(Function), 'Order pricing is unavailable.');
      expect(downloadBlob).toHaveBeenCalledWith(receipt, 'receipt-99.xlsx');
    } else {
      expect(exportOrderToExcel).not.toHaveBeenCalled();
    }
    if (status === 'DRAFT') {
      expect(screen.queryByRole('button', { name: 'Confirm' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Cancel Draft' })).not.toBeInTheDocument();
      expect(screen.queryByLabelText('Text')).not.toBeInTheDocument();
    }
  });

  it('lets cashiers add and remove draft items without confirm, cancel, detail, or return controls', async () => {
    const user = userEvent.setup();
    const gadgetLine = {
      ...draftOrder.items[0], id: 8, item_id: 4, title: 'Gadget', barcode: 'GAD', created_by: 'cashier'
    };
    const withGadget = { ...draftOrder, items: [...draftOrder.items, gadgetLine] };
    const afterRemoval = {
      ...withGadget, items: [gadgetLine],
      removed_items: [{ ...draftOrder.items[0], removed_at: '2026-01-01T11:00:00Z', removed_by: 'cashier' }]
    };
    const mutations: Array<{ path: string; method: string; body: unknown }> = [];
    vi.spyOn(window, 'alert').mockImplementation(() => undefined);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) return Promise.resolve(new Response('{"token":"csrf"}', { status: 200 }));
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(cashierAuthenticated), { status: 200 }));
      }
      if (init?.method && ['POST', 'PUT', 'DELETE'].includes(init.method)) {
        mutations.push({ path, method: init.method, body: init.body ? JSON.parse(String(init.body)) : undefined });
        if (path === '/api/orders/99/items' && init.method === 'POST') {
          return Promise.resolve(new Response(JSON.stringify(withGadget), { status: 201 }));
        }
        if (path === '/api/orders/99/items/7' && init.method === 'DELETE') {
          return Promise.resolve(new Response(JSON.stringify(afterRemoval)));
        }
      }
      if (path === '/api/items') return Promise.resolve(new Response(JSON.stringify([
        { id: 4, title: 'Gadget', description: '', quantity: 5, cost: 10, profit: 2, price: 12, barcode: 'GAD' }
      ])));
      if (path.endsWith('/orders/99')) return Promise.resolve(new Response(JSON.stringify(draftOrder)));
      if (path.endsWith('/receipt-templates')) {
        return Promise.resolve(new Response(JSON.stringify([{ id: 'standard', name: 'Standard' }]), { status: 200 }));
      }
      throw new Error(`Unexpected request: ${init?.method || 'GET'} ${path}`);
    });

    renderApp('/orders/99');

    expect(await screen.findByRole('heading', { name: 'Add Items' })).toBeInTheDocument();
    expect(screen.getByText('Draft sale')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Confirm' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel Draft' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Mark picked up' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Return selected' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Return all remaining' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Text')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove' })).toBeDisabled();
    expect(screen.getByText('A draft must keep at least one item. Cancel the draft to discard it.')).toBeInTheDocument();

    await user.click(screen.getByRole('combobox', { name: 'Select an item…' }));
    await user.click(await screen.findByRole('option', { name: 'Gadget — GAD (5)' }));
    await user.click(screen.getByRole('button', { name: 'Add to Cart' }));
    await user.click(screen.getByRole('button', { name: 'Add Items' }));
    await waitFor(() => expect(window.alert).toHaveBeenCalledWith('Items added.'));

    const widgetRow = await screen.findByRole('row', { name: /Widget/ });
    await user.click(within(widgetRow).getByRole('button', { name: 'Remove' }));

    expect(await screen.findByRole('heading', { name: 'Removed Items' })).toBeInTheDocument();
    expect(mutations).toEqual([
      { path: '/api/orders/99/items', method: 'POST', body: { items: [{ item_id: 4, quantity: 1 }] } },
      { path: '/api/orders/99/items/7', method: 'DELETE', body: undefined }
    ]);
  });

  it('hides return controls from cashiers on a confirmed order', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(input => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) return Promise.resolve(new Response('{"token":"csrf"}', { status: 200 }));
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(cashierAuthenticated), { status: 200 }));
      }
      if (path.endsWith('/orders/99')) {
        return Promise.resolve(new Response(JSON.stringify({ ...draftOrder, status: 'CONFIRMED' })));
      }
      if (path.endsWith('/receipt-templates')) {
        return Promise.resolve(new Response(JSON.stringify([{ id: 'standard', name: 'Standard' }]), { status: 200 }));
      }
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/orders/99');

    expect(await screen.findByRole('button', { name: 'Export receipt' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Return selected' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Return all remaining' })).not.toBeInTheDocument();
    expect(screen.queryByRole('spinbutton', { name: /Return quantity/ })).not.toBeInTheDocument();
  });


  it.each([false, true])('serializes draft edits and confirmation after save failure=%s', async fail => {
    let finishSave!: (response: Response) => void;
    const pendingSave = new Promise<Response>(resolve => { finishSave = resolve; });
    vi.spyOn(window, 'alert').mockImplementation(() => undefined);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) return Promise.resolve(new Response('{"token":"csrf"}'));
      if (path.endsWith('/auth/session/refresh')) return Promise.resolve(new Response(JSON.stringify(authenticated)));
      if (path.endsWith('/orders/99/text')) return pendingSave;
      if (path.endsWith('/orders/99/confirm')) return Promise.resolve(new Response(JSON.stringify({
        ...draftOrder, text: fail ? draftOrder.text : 'Updated draft', status: 'CONFIRMED'
      })));
      if (path.endsWith('/orders/99') && !init?.method) return Promise.resolve(new Response(JSON.stringify(draftOrder)));
      return Promise.resolve(new Response('[]'));
    });
    renderApp('/orders/99');
    const user = userEvent.setup();
    const text = await screen.findByLabelText('Text');
    fireEvent.change(text, { target: { value: 'Updated draft' } });
    await user.click(within(text.closest('.card') as HTMLElement).getByRole('button', { name: 'Save' }));
    expect(text).toBeDisabled();
    expect(screen.getByLabelText('Order Date')).toBeDisabled();
    expect(screen.getByRole('combobox', { name: 'Select an item…' })).toBeDisabled();
    for (const name of ['Confirm', 'Cancel Draft', 'Mark picked up']) {
      expect(screen.getByRole('button', { name })).toBeDisabled();
      await user.click(screen.getByRole('button', { name }));
    }
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(1);
    expect(fetchMock.mock.calls.some(([input]) => String(input).endsWith('/confirm'))).toBe(false);
    finishSave(fail
      ? new Response('{"error":"Save failed"}', { status: 500 })
      : new Response(JSON.stringify({ ...draftOrder, text: 'Updated draft' })));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Confirm' })).toBeEnabled());
    if (fail) expect(window.alert).toHaveBeenCalledWith('Save failed');
    await user.click(screen.getByRole('button', { name: 'Confirm' }));
    expect(await screen.findByText('Confirmed')).toBeInTheDocument();
    expect(screen.queryByLabelText('Text')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Confirm' })).not.toBeInTheDocument();
  });

  it('stops loading failed orders and retries successfully', async () => {
    let attempts = 0;
    vi.spyOn(window, 'alert').mockImplementation(() => undefined);
    vi.spyOn(globalThis, 'fetch').mockImplementation(input => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) return Promise.resolve(new Response('{"token":"csrf"}'));
      if (path.endsWith('/auth/session/refresh')) return Promise.resolve(new Response(JSON.stringify(authenticated)));
      if (path.includes('/orders/page')) {
        attempts++;
        return Promise.resolve(attempts === 1
          ? new Response('{"error":"Unavailable"}', { status: 503 })
          : new Response(JSON.stringify({ items: [draftOrder], page: 0, size: 50, totalItems: 1, totalPages: 1 })));
      }
      return Promise.resolve(new Response('[]'));
    });
    renderApp('/orders');
    expect(await screen.findByRole('alert')).toHaveTextContent('Orders could not be loaded. Please try again.');
    expect(screen.queryByText('Loading…')).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Refresh' }));
    expect(await screen.findByRole('row', { name: /99 Draft sale/ })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(attempts).toBe(2);
  });

  it('debounces order searches and resets pagination', async () => {
    const orderRequests: string[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(input => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) return Promise.resolve(new Response('{"token":"csrf"}', { status: 200 }));
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(authenticated), { status: 200 }));
      }
      if (path.includes('/orders/page')) {
        orderRequests.push(path);
        const searching = path.includes('&q=Alpha');
        return Promise.resolve(new Response(JSON.stringify({
          items: searching ? [] : [{
            id: 42, text: 'Initial order', status: 'CONFIRMED',
            total_quantity: 1, total_amount: 12, returned_quantity: 0, returned_amount: 0,
            net_quantity: 1, net_amount: 12, total_profit: 2, returned_profit: 0, net_profit: 2,
            total_cost: 10, returned_cost: 0, net_cost: 10, return_status: 'NOT_RETURNED',
            picked_up: false, picked_up_at: null, order_date: '2026-01-01',
            created_at: '2026-01-01T10:00:00Z', created_by: 'admin'
          }],
          page: searching ? 0 : path.includes('page=1') ? 1 : 0,
          size: 50, totalItems: searching ? 0 : 51, totalPages: searching ? 0 : 2
        }), { status: 200 }));
      }
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/orders');
    const user = userEvent.setup();
    await screen.findByRole('row', { name: /42 Initial order/ });
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(orderRequests.some(path => path.includes('page=1'))).toBe(true));

    await user.type(screen.getByRole('searchbox', { name: 'Search orders' }), ' Alpha ');
    await waitFor(() => expect(orderRequests.some(
      path => path.includes('/orders/page?page=0&size=50&q=Alpha')
    )).toBe(true));
    expect(await screen.findByText('No matching orders found.')).toBeInTheDocument();
  });

  it('shows Unknown for omitted financials in the order list', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(input => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) {
        return Promise.resolve(new Response('{"token":"csrf"}', { status: 200 }));
      }
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(authenticated), { status: 200 }));
      }
      if (path.includes('/orders/page')) {
        return Promise.resolve(new Response(JSON.stringify({
          items: [{
            id: 42,
            text: 'Legacy sale',
            status: 'CONFIRMED',
            total_quantity: 2,
            total_amount: 24,
            returned_quantity: 0,
            returned_amount: 0,
            net_quantity: 2,
            net_amount: 24,
            return_status: 'NOT_RETURNED',
            created_at: '2026-07-26T03:00:00Z',
            created_by: 'cashier'
          }],
          page: 0,
          size: 50,
          totalItems: 1,
          totalPages: 1
        }), { status: 200 }));
      }
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/orders');

    expect((await screen.findAllByText('Unknown')).length).toBe(6);
    expect(screen.getByRole('row', { name: /42 Legacy sale/ })).toHaveTextContent('Unknown');
  });

  it('shows Unknown for omitted order and line financials in order details', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(input => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) {
        return Promise.resolve(new Response('{"token":"csrf"}', { status: 200 }));
      }
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(authenticated), { status: 200 }));
      }
      if (path.endsWith('/orders/42')) {
        return Promise.resolve(new Response(JSON.stringify({
          id: 42,
          text: 'Legacy sale',
          status: 'CONFIRMED',
          total_quantity: 2,
          total_amount: 24,
          returned_quantity: 0,
          returned_amount: 0,
          net_quantity: 2,
          net_amount: 24,
          return_status: 'NOT_RETURNED',
          created_at: '2026-07-26T03:00:00Z',
          created_by: 'cashier',
          items: [{
            id: 7,
            item_id: 1,
            title: 'Legacy Widget',
            barcode: null,
            quantity: 2,
            returned_quantity: 0,
            remaining_quantity: 2,
            paid_amount: 12,
            subtotal: 24
          }],
          returns: []
        }), { status: 200 }));
      }
      if (path.endsWith('/receipt-templates')) {
        return Promise.resolve(new Response('[]', { status: 200 }));
      }
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/orders/42');

    expect((await screen.findAllByText('Unknown')).length).toBeGreaterThanOrEqual(10);
    expect(screen.getByRole('row', { name: /Legacy Widget/ })).toHaveTextContent('Unknown');
  });

  it('lets an administrator pick up and confirm a draft from the order list', async () => {
    const order = {
      id: 42, text: 'Pickup sale', status: 'DRAFT',
      total_quantity: 1, total_amount: 12, returned_quantity: 0, returned_amount: 0,
      net_quantity: 1, net_amount: 12, total_profit: 2, returned_profit: 0, net_profit: 2,
      total_cost: 10, returned_cost: 0, net_cost: 10, return_status: 'NOT_RETURNED',
      picked_up: false, picked_up_at: null, created_at: '2026-01-01T10:00:00Z', created_by: 'admin',
      modified_at: null, modified_by: null, archived_at: null, archived_by: null
    };
    vi.spyOn(window, 'alert').mockImplementation(() => undefined);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) return Promise.resolve(new Response('{"token":"csrf"}', { status: 200 }));
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(authenticated), { status: 200 }));
      }
      if (path.endsWith('/orders/42/pickup') && init?.method === 'PUT') {
        return Promise.resolve(new Response(JSON.stringify({
          ...order, status: 'CONFIRMED', confirmed_at: '2026-01-02T10:00:00Z', confirmed_by: 'admin',
          picked_up: true, picked_up_at: '2026-01-02T10:00:00Z'
        }), { status: 200 }));
      }
      if (path.includes('/orders/page')) {
        return Promise.resolve(new Response(JSON.stringify({
          items: [order], page: 0, size: 50, totalItems: 1, totalPages: 1
        }), { status: 200 }));
      }
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/orders');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Mark picked up' }));

    expect(window.confirm).toHaveBeenCalledWith(
      'Mark this draft as picked up and confirm it? It can no longer be edited.'
    );

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/orders/42/pickup', expect.objectContaining({
        method: 'PUT', body: JSON.stringify({ picked_up: true })
      })
    ));
    expect(screen.getByRole('row', { name: /42 Pickup sale/ })).toHaveTextContent('Picked up');
    expect(screen.getByRole('row', { name: /42 Pickup sale/ })).toHaveTextContent('Confirmed');
    expect(screen.getByRole('button', { name: 'Mark not picked up' })).toBeInTheDocument();
  });

  it('removes a legacy local-storage token during startup', async () => {
    localStorage.setItem(TOKEN_KEY, 'legacy-token');
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response('{"token":"csrf"}', { status: 200 }))
      .mockResolvedValueOnce(new Response('{"error":"invalid"}', { status: 401 }));
    renderApp('/login');
    expect(await screen.findByRole('heading', { name: 'Login' })).toBeInTheDocument();
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
  });

  it('resets the item edit form when switching between items', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(input => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) return Promise.resolve(new Response('{"token":"csrf"}', { status: 200 }));
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(authenticated), { status: 200 }));
      }
      if (path.endsWith('/items')) {
        return Promise.resolve(new Response(JSON.stringify([
          {
            id: 1, title: 'Item A', description: 'First', quantity: 3,
            cost: 10, profit: 2, price: 12, barcode: 'A'
          },
          {
            id: 2, title: 'Item B', description: 'Second', quantity: 7,
            cost: 20, profit: 5, price: 25, barcode: 'B'
          }
        ]), { status: 200 }));
      }
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/items');
    const user = userEvent.setup();
    await screen.findByRole('heading', { name: 'Inventory Items' });

    const editButtons = await screen.findAllByRole('button', { name: 'Edit' });
    await user.click(editButtons[0]);
    expect(screen.getByRole('textbox', { name: 'Title' })).toHaveValue('Item A');

    await user.click(editButtons[1]);
    expect(screen.getByRole('textbox', { name: 'Title' })).toHaveValue('Item B');
    expect(screen.getByRole('spinbutton', { name: 'Quantity' })).toHaveValue(7);
  });

  it('filters the inventory by title, description, and barcode', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(input => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) return Promise.resolve(new Response('{"token":"csrf"}', { status: 200 }));
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(authenticated), { status: 200 }));
      }
      if (path.endsWith('/items')) {
        return Promise.resolve(new Response(JSON.stringify([
          {
            id: 1, title: 'Red Widget', description: 'Heavy duty tool', quantity: 3,
            cost: 10, profit: 2, price: 12, barcode: 'RW-100'
          },
          {
            id: 2, title: 'Blue Cable', description: 'Flexible connector', quantity: 7,
            cost: 20, profit: 5, price: 25, barcode: 'BC-200'
          }
        ]), { status: 200 }));
      }
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/items');
    const user = userEvent.setup();
    const search = await screen.findByRole('searchbox', { name: 'Search items' });
    expect(search).toHaveAttribute('placeholder', 'Search by item title, description, or barcode…');

    await user.type(search, '  BLUE ');
    expect(screen.getByRole('row', { name: /Blue Cable/ })).toBeInTheDocument();
    expect(screen.queryByRole('row', { name: /Red Widget/ })).not.toBeInTheDocument();

    await user.clear(search);
    await user.type(search, 'heavy DUTY');
    expect(screen.getByRole('row', { name: /Red Widget/ })).toBeInTheDocument();
    expect(screen.queryByRole('row', { name: /Blue Cable/ })).not.toBeInTheDocument();

    await user.clear(search);
    await user.type(search, 'bc-20');
    expect(screen.getByRole('row', { name: /Blue Cable/ })).toBeInTheDocument();

    await user.clear(search);
    await user.type(search, 'missing');
    expect(screen.getByText('No matching items found.')).toBeInTheDocument();

    await user.clear(search);
    expect(screen.getByRole('row', { name: /Red Widget/ })).toBeInTheDocument();
    expect(screen.getByRole('row', { name: /Blue Cable/ })).toBeInTheDocument();
  });

  it('reloads the latest item after an optimistic update conflict', async () => {
    let itemLoads = 0;
    let updateBody: Record<string, unknown> | null = null;
    vi.spyOn(window, 'alert').mockImplementation(() => undefined);
    vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) return Promise.resolve(new Response('{"token":"csrf"}', { status: 200 }));
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(authenticated), { status: 200 }));
      }
      if (path.endsWith('/items/1') && init?.method === 'PUT') {
        updateBody = JSON.parse(String(init.body));
        return Promise.resolve(new Response(JSON.stringify({
          code: 'ITEM_CHANGED', error: 'The item changed while you were editing it.'
        }), { status: 409 }));
      }
      if (path.endsWith('/items')) {
        itemLoads += 1;
        return Promise.resolve(new Response(JSON.stringify([{
          id: 1,
          title: itemLoads === 1 ? 'Old title' : 'Latest title',
          description: '',
          quantity: itemLoads === 1 ? 5 : 4,
          cost: 10,
          profit: 2,
          price: 12,
          barcode: 'A',
          version: itemLoads === 1 ? 0 : 1
        }]), { status: 200 }));
      }
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/items');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Edit' }));
    const title = screen.getByRole('textbox', { name: 'Title' });
    await user.clear(title);
    await user.type(title, 'My change');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(screen.getByRole('textbox', { name: 'Title' }))
      .toHaveValue('Latest title'));
    expect(screen.getByRole('spinbutton', { name: 'Quantity' })).toHaveValue(4);
    expect(updateBody).toMatchObject({ title: 'My change', quantity: 5, version: 0 });
  });

  it('saves an order once and leaves the sell page when receipt export fails', async () => {
    let createdOrders = 0;
    vi.spyOn(window, 'alert').mockImplementation(() => undefined);
    vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) {
        return Promise.resolve(new Response(
          '{"token":"csrf","headerName":"X-XSRF-TOKEN"}',
          { status: 200 }
        ));
      }
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(authenticated), { status: 200 }));
      }
      if (path.endsWith('/items')) {
        return Promise.resolve(new Response(JSON.stringify([
          {
            id: 3, title: 'Widget', description: '', quantity: 5,
            cost: 10, profit: 2, price: 12, barcode: 'ABC'
          }
        ]), { status: 200 }));
      }
      if (path.endsWith('/receipt-templates')) {
        return Promise.resolve(new Response(JSON.stringify([{ id: 'simple', name: 'Simple' }]), { status: 200 }));
      }
      if (path.endsWith('/orders') && init?.method === 'POST') {
        createdOrders += 1;
        return Promise.resolve(new Response(JSON.stringify({
          id: 99, text: 'Counter sale', total_quantity: 1, total_amount: 12,
          returned_quantity: 0, returned_amount: 0, net_quantity: 1, net_amount: 12,
          total_profit: 2, returned_profit: 0, net_profit: 2,
          total_cost: 10, returned_cost: 0, net_cost: 10,
          return_status: 'NOT_RETURNED', created_at: '2026-01-01 10:00:00', created_by: 'admin',
          modified_at: null, modified_by: null, archived_at: null, archived_by: null,
          items: [{
            id: 1, item_id: 3, title: 'Widget', barcode: 'ABC', quantity: 1,
            returned_quantity: 0, remaining_quantity: 1, paid_amount: 12, subtotal: 12,
            cost: 10, cost_subtotal: 10, profit: 2, profit_subtotal: 2
          }],
          returns: []
        }), { status: 201 }));
      }
      if (path.endsWith('/orders')) {
        return Promise.resolve(new Response('[]', { status: 200 }));
      }
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/sell');
    const user = userEvent.setup();
    await screen.findByRole('heading', { name: 'Create Order' });
    await user.click(await screen.findByRole('combobox', { name: 'Select an item…' }));
    await user.click(await screen.findByRole('option', { name: 'Widget — ABC (5)' }));
    await user.click(screen.getByRole('button', { name: 'Add to Cart' }));
    await user.type(screen.getByRole('textbox', { name: 'Order note or reference' }), 'Counter sale');
    await user.click(screen.getByRole('button', { name: 'Confirm & Export' }));

    await waitFor(() => expect(createdOrders).toBe(1));
    expect(await screen.findByRole('heading', { name: 'Orders' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Confirm & Export' })).not.toBeInTheDocument();
    expect(window.alert).toHaveBeenCalledWith(expect.stringContaining('Receipt export failed'));
  });

  it('shows in-stock sell items before zero-stock items without otherwise sorting them', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(input => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) return Promise.resolve(new Response('{"token":"csrf"}', { status: 200 }));
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(authenticated), { status: 200 }));
      }
      if (path.endsWith('/items')) {
        return Promise.resolve(new Response(JSON.stringify([
          { id: 1, title: 'Zero first', description: '', quantity: 0, cost: 1, profit: 1, price: 2, barcode: 'Z1' },
          { id: 2, title: 'Stock second', description: '', quantity: 2, cost: 1, profit: 1, price: 2, barcode: 'S2' },
          { id: 3, title: 'Zero third', description: '', quantity: 0, cost: 1, profit: 1, price: 2, barcode: 'Z3' },
          { id: 4, title: 'Stock fourth', description: '', quantity: 4, cost: 1, profit: 1, price: 2, barcode: 'S4' }
        ]), { status: 200 }));
      }
      if (path.endsWith('/receipt-templates')) return Promise.resolve(new Response('[]', { status: 200 }));
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/sell');

    const itemSelect = await screen.findByRole('combobox', { name: /Select an item/ });
    const user = userEvent.setup();
    await user.click(itemSelect);
    expect(within(screen.getByRole('listbox')).getAllByRole('option').map(option => option.textContent)).toEqual([
      'Stock second — S2 (2)',
      'Stock fourth — S4 (4)',
      'Zero first — Z1 (0)',
      'Zero third — Z3 (0)'
    ]);
    await user.type(itemSelect, 'missing');
    expect(screen.getByText('No matching items found.')).toBeInTheDocument();
    await user.clear(itemSelect);
    await user.type(itemSelect, 's4');
    expect(within(screen.getByRole('listbox')).getAllByRole('option').map(option => option.textContent))
      .toEqual(['Stock fourth — S4 (4)']);
    await user.keyboard('{ArrowDown}{Enter}');
    await user.click(screen.getByRole('button', { name: 'Add to Cart' }));
    expect(screen.getByRole('row', { name: /Stock fourth/ })).toBeInTheDocument();
    expect(itemSelect).toHaveValue('');
  });

  it('shows in-stock draft edit items before zero-stock items without otherwise sorting them', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(input => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) return Promise.resolve(new Response('{"token":"csrf"}', { status: 200 }));
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(authenticated), { status: 200 }));
      }
      if (path.endsWith('/items')) {
        return Promise.resolve(new Response(JSON.stringify([
          { id: 1, title: 'Zero first', description: '', quantity: 0, cost: 1, profit: 1, price: 2, barcode: 'Z1' },
          { id: 2, title: 'Stock second', description: '', quantity: 2, cost: 1, profit: 1, price: 2, barcode: 'S2' },
          { id: 3, title: 'Zero third', description: '', quantity: 0, cost: 1, profit: 1, price: 2, barcode: 'Z3' },
          { id: 4, title: 'Stock fourth', description: '', quantity: 4, cost: 1, profit: 1, price: 2, barcode: 'S4' }
        ]), { status: 200 }));
      }
      if (path.endsWith('/orders/99')) {
        return Promise.resolve(new Response(JSON.stringify({
          id: 99, text: 'Draft sale', status: 'DRAFT', total_quantity: 0, total_amount: 0,
          returned_quantity: 0, returned_amount: 0, net_quantity: 0, net_amount: 0,
          total_profit: 0, returned_profit: 0, net_profit: 0,
          total_cost: 0, returned_cost: 0, net_cost: 0, return_status: 'NOT_RETURNED',
          created_at: '2026-01-01T10:00:00Z', created_by: 'admin', modified_at: null, modified_by: null,
          confirmed_at: null, confirmed_by: null, cancelled_at: null, cancelled_by: null,
          archived_at: null, archived_by: null,
          items: [], removed_items: [], returns: []
        }), { status: 200 }));
      }
      if (path.endsWith('/receipt-templates')) return Promise.resolve(new Response('[]', { status: 200 }));
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/orders/99');

    expect(await screen.findByRole('heading', { name: 'Add Items' })).toBeInTheDocument();
    const itemSelect = await screen.findByRole('combobox', { name: /Select an item/ });
    const user = userEvent.setup();
    await user.click(itemSelect);
    expect(within(screen.getByRole('listbox')).getAllByRole('option').map(option => option.textContent)).toEqual([
      'Stock second — S2 (2)',
      'Stock fourth — S4 (4)',
      'Zero first — Z1 (0)',
      'Zero third — Z3 (0)'
    ]);
    await user.type(itemSelect, 'stock second');
    expect(within(screen.getByRole('listbox')).getAllByRole('option').map(option => option.textContent))
      .toEqual(['Stock second — S2 (2)']);
    await user.click(screen.getByRole('option', { name: 'Stock second — S2 (2)' }));
    await user.click(screen.getByRole('button', { name: 'Add to Cart' }));
    expect(screen.getByRole('row', { name: /Stock second/ })).toBeInTheDocument();
    expect(itemSelect).toHaveValue('');
  });

  it('refreshes sell cart stock and price before creating an order', async () => {
    let itemRequestCount = 0;
    let createdOrders = 0;
    vi.spyOn(window, 'alert').mockImplementation(() => undefined);
    vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) {
        return Promise.resolve(new Response(
          '{"token":"csrf","headerName":"X-XSRF-TOKEN"}',
          { status: 200 }
        ));
      }
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(authenticated), { status: 200 }));
      }
      if (path.endsWith('/items')) {
        itemRequestCount += 1;
        return Promise.resolve(new Response(JSON.stringify([
          {
            id: 3, title: 'Widget', description: '', quantity: itemRequestCount === 1 ? 5 : 1,
            cost: itemRequestCount === 1 ? 10 : 12, profit: 2,
            price: itemRequestCount === 1 ? 12 : 14, barcode: 'ABC'
          }
        ]), { status: 200 }));
      }
      if (path.endsWith('/receipt-templates')) {
        return Promise.resolve(new Response(JSON.stringify([{ id: 'simple', name: 'Simple' }]), { status: 200 }));
      }
      if (path.endsWith('/orders') && init?.method === 'POST') {
        createdOrders += 1;
        return Promise.resolve(new Response('{}', { status: 201 }));
      }
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/sell');
    const user = userEvent.setup();
    await screen.findByRole('heading', { name: 'Create Order' });
    await user.click(await screen.findByRole('combobox', { name: 'Select an item…' }));
    await user.click(await screen.findByRole('option', { name: 'Widget — ABC (5)' }));
    await user.click(screen.getByRole('button', { name: 'Add to Cart' }));
    await user.type(screen.getByRole('textbox', { name: 'Order note or reference' }), 'Counter sale');
    await user.click(screen.getByRole('button', { name: 'Confirm' }));

    await waitFor(() => expect(window.alert).toHaveBeenCalledWith(
      'Stock or pricing changed. Review the updated cart before saving.'
    ));
    expect(createdOrders).toBe(0);
    expect(screen.getAllByText('HK$14.00')).toHaveLength(3);
    expect(screen.getByText('0 remaining after cart')).toBeInTheDocument();
  });

  it('locks every sell input while an order is being submitted', async () => {
    let itemLoads = 0;
    let releaseRefresh!: (response: Response) => void;
    const pendingRefresh = new Promise<Response>(resolve => { releaseRefresh = resolve; });
    vi.spyOn(window, 'alert').mockImplementation(() => undefined);
    vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) {
        return Promise.resolve(new Response('{"token":"csrf","headerName":"X-XSRF-TOKEN"}', { status: 200 }));
      }
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(authenticated), { status: 200 }));
      }
      if (path.endsWith('/items')) {
        itemLoads += 1;
        const response = new Response(JSON.stringify([{
          id: 3, title: 'Widget', description: '', quantity: 5,
          cost: 10, profit: 2, price: 12, barcode: 'ABC', version: 0
        }]), { status: 200 });
        return itemLoads === 1 ? Promise.resolve(response) : pendingRefresh;
      }
      if (path.endsWith('/receipt-templates')) {
        return Promise.resolve(new Response('[{"id":"simple","name":"Simple"}]', { status: 200 }));
      }
      if (path.endsWith('/orders') && init?.method === 'POST') {
        return Promise.resolve(new Response(JSON.stringify({
          id: 99, text: 'Counter sale', total_quantity: 1, total_amount: 12,
          returned_quantity: 0, returned_amount: 0, net_quantity: 1, net_amount: 12,
          total_profit: 2, returned_profit: 0, net_profit: 2,
          total_cost: 10, returned_cost: 0, net_cost: 10,
          return_status: 'NOT_RETURNED', created_at: '2026-01-01T10:00:00Z', created_by: 'admin',
          modified_at: null, modified_by: null, archived_at: null, archived_by: null,
          items: [], returns: []
        }), { status: 201 }));
      }
      if (path.includes('/orders/page')) {
        return Promise.resolve(new Response('{"items":[],"page":0,"size":50,"totalItems":0,"totalPages":0}', { status: 200 }));
      }
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/sell');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('combobox', { name: 'Select an item…' }));
    await user.click(await screen.findByRole('option', { name: 'Widget — ABC (5)' }));
    await user.click(screen.getByRole('button', { name: 'Add to Cart' }));
    await user.type(screen.getByRole('textbox', { name: 'Order note or reference' }), 'Counter sale');
    await user.click(screen.getByRole('button', { name: 'Confirm' }));

    await waitFor(() => expect(screen.getByPlaceholderText('Scan or type a barcode…')).toBeDisabled());
    expect(screen.getByRole('combobox', { name: 'Select an item…' })).toBeDisabled();
    expect(screen.getByRole('textbox', { name: 'Order note or reference' })).toBeDisabled();
    expect(screen.getByRole('combobox', { name: 'Receipt template' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '+' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Remove' })).toBeDisabled();

    releaseRefresh(new Response(JSON.stringify([{
      id: 3, title: 'Widget', description: '', quantity: 5,
      cost: 10, profit: 2, price: 12, barcode: 'ABC', version: 0
    }]), { status: 200 }));
    expect(await screen.findByRole('heading', { name: 'Orders' })).toBeInTheDocument();
  });

  it('creates and edits a draft, then picks it up and confirms it', async () => {
    let createBody = '';
    let textBody = '';
    let dateBody = '';
    let pickupBody = '';
    const draft = draftOrder;
    vi.spyOn(window, 'alert').mockImplementation(() => undefined);
    vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) return Promise.resolve(new Response('{"token":"csrf"}', { status: 200 }));
      if (path.endsWith('/auth/session/refresh')) return Promise.resolve(new Response(JSON.stringify(authenticated), { status: 200 }));
      if (path.endsWith('/items')) return Promise.resolve(new Response(JSON.stringify([{
        id: 3, title: 'Widget', description: '', quantity: 5,
        cost: 10, profit: 2, price: 12, barcode: 'ABC', version: 0
      }]), { status: 200 }));
      if (path.endsWith('/receipt-templates')) return Promise.resolve(new Response('[{"id":"simple","name":"Simple"}]', { status: 200 }));
      if (path.endsWith('/orders') && init?.method === 'POST') {
        createBody = String(init.body);
        return Promise.resolve(new Response(JSON.stringify(draft), { status: 201 }));
      }
      if (path.endsWith('/orders/99/text') && init?.method === 'PUT') {
        textBody = String(init.body);
        return Promise.resolve(new Response(JSON.stringify({
          ...draft, text: 'Updated draft sale'
        }), { status: 200 }));
      }
      if (path.endsWith('/orders/99/date') && init?.method === 'PUT') {
        dateBody = String(init.body);
        return Promise.resolve(new Response(JSON.stringify({
          ...draft, order_date: '2026-01-04'
        }), { status: 200 }));
      }
      if (path.endsWith('/orders/99/pickup') && init?.method === 'PUT') {
        pickupBody = String(init.body);
        return Promise.resolve(new Response(JSON.stringify({
          ...draft, status: 'CONFIRMED', confirmed_at: '2026-01-02T10:00:00Z',
          confirmed_by: 'admin', picked_up: true, picked_up_at: '2026-01-02T10:00:00Z'
        }), { status: 200 }));
      }
      if (path.endsWith('/orders/99')) return Promise.resolve(new Response(JSON.stringify(draft), { status: 200 }));
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/sell');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('combobox', { name: 'Select an item…' }));
    await user.click(await screen.findByRole('option', { name: 'Widget — ABC (5)' }));
    await user.click(screen.getByRole('button', { name: 'Add to Cart' }));
    await user.type(screen.getByRole('textbox', { name: 'Order note or reference' }), 'Draft sale');
    fireEvent.change(screen.getByLabelText('Order Date'), { target: { value: '2026-01-03' } });
    await user.click(screen.getByRole('button', { name: 'Save Draft' }));

    expect(await screen.findByRole('heading', { name: 'Orders #99' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Add Items' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirm' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel Draft' })).toBeInTheDocument();
    expect(JSON.parse(createBody)).toEqual({
      text: 'Draft sale', items: [{ item_id: 3, quantity: 1 }], status: 'DRAFT',
      order_date: '2026-01-03'
    });
    const textInput = screen.getByLabelText('Text');
    fireEvent.change(textInput, { target: { value: '  Updated draft sale  ' } });
    await user.click(within(textInput.closest('.card') as HTMLElement).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(JSON.parse(textBody)).toEqual({ text: 'Updated draft sale' }));
    expect(textInput).toHaveValue('Updated draft sale');
    expect(window.alert).toHaveBeenCalledWith('Order text updated.');
    const dateInput = screen.getByLabelText('Order Date');
    fireEvent.change(dateInput, { target: { value: '2026-01-04' } });
    await user.click(within(dateInput.closest('.card') as HTMLElement).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(JSON.parse(dateBody)).toEqual({ order_date: '2026-01-04' }));
    expect(window.alert).toHaveBeenCalledWith('Order date updated.');
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await user.click(screen.getByRole('button', { name: 'Mark picked up' }));
    await waitFor(() => expect(JSON.parse(pickupBody)).toEqual({ picked_up: true }));
    expect(window.confirm).toHaveBeenCalledWith(
      'Mark this draft as picked up and confirm it? It can no longer be edited.'
    );
    expect(screen.queryByRole('heading', { name: 'Add Items' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Text')).not.toBeInTheDocument();
    expect(screen.getByText('Confirmed')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Mark not picked up' })).toBeInTheDocument();
    expect(window.alert).toHaveBeenCalledWith('Order marked as picked up and confirmed.');
    expect(screen.queryByText('Port', { exact: true })).not.toBeInTheDocument();
    await user.selectOptions(screen.getByRole('combobox', { name: 'Language' }), 'zh-CN');
    expect(screen.queryByText('口岸', { exact: true })).not.toBeInTheDocument();
    expect(screen.getByText('已确认')).toBeInTheDocument();
    await user.selectOptions(screen.getByRole('combobox', { name: 'Language' }), 'en');
    await user.click(screen.getByRole('link', { name: 'Create Order' }));
    expect(await screen.findByLabelText('Order Date')).toHaveValue('2026-01-03');
    expect(sessionStorage.getItem(ORDER_DATE_KEY)).toBe('2026-01-03');
  });

  it('exports a receipt again from order details', async () => {
    const order = {
      id: 42, text: 'Sale', total_quantity: 1, total_amount: 12,
      returned_quantity: 0, returned_amount: 0, net_quantity: 1, net_amount: 12,
      total_profit: 2, returned_profit: 0, net_profit: 2,
      total_cost: 10, returned_cost: 0, net_cost: 10,
      return_status: 'NOT_RETURNED', created_at: '2026-01-01 10:00:00', created_by: 'admin',
      modified_at: null, modified_by: null, archived_at: null, archived_by: null,
      items: [{
        id: 7, item_id: 3, title: 'Widget', barcode: 'ABC', quantity: 1,
        returned_quantity: 0, remaining_quantity: 1, paid_amount: 12, subtotal: 12,
        cost: 10, cost_subtotal: 10, profit: 2, profit_subtotal: 2
      }],
      returns: []
    };
    vi.mocked(exportOrderToExcel).mockResolvedValueOnce({
      blob: new Blob(['receipt']),
      capacity: 10,
      itemCount: 1
    });
    vi.spyOn(window, 'alert').mockImplementation(() => undefined);
    vi.spyOn(globalThis, 'fetch').mockImplementation(input => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) {
        return Promise.resolve(new Response(
          '{"token":"csrf","headerName":"X-XSRF-TOKEN"}',
          { status: 200 }
        ));
      }
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(authenticated), { status: 200 }));
      }
      if (path.endsWith('/receipt-templates')) {
        return Promise.resolve(new Response(JSON.stringify([{ id: 'simple', name: 'Simple' }]), { status: 200 }));
      }
      if (path.endsWith('/orders/42')) {
        return Promise.resolve(new Response(JSON.stringify(order), { status: 200 }));
      }
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/orders/42');
    const user = userEvent.setup();
    await screen.findByRole('heading', { name: 'Orders #42' });
    await screen.findByRole('button', { name: 'Export receipt' });
    await user.click(screen.getByRole('button', { name: 'Export receipt' }));

    expect(exportOrderToExcel).toHaveBeenCalledWith(order, 'simple', expect.any(Function), 'Order pricing is unavailable.');
    expect(downloadBlob).toHaveBeenCalled();
    expect(window.alert).toHaveBeenCalledWith('Receipt downloaded.');
    expect(screen.getByText('Original Cost:')).toBeInTheDocument();
    expect(screen.queryByText('Original Total:')).not.toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Profit' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Profit Subtotal' })).toBeInTheDocument();
  });

  it('archives a fully returned order from the admin orders table', async () => {
    const order = {
      id: 42, text: 'Returned sale', total_quantity: 1, total_amount: 12,
      returned_quantity: 1, returned_amount: 12, net_quantity: 0, net_amount: 0,
      total_profit: 2, returned_profit: 2, net_profit: 0,
      total_cost: 10, returned_cost: 10, net_cost: 0,
      return_status: 'FULLY_RETURNED', created_at: '2026-01-01T10:00:00Z', created_by: 'admin',
      modified_at: null, modified_by: null, archived_at: null, archived_by: null,
      items: [{ id: 7, item_id: 3, title: 'Widget', barcode: 'ABC', quantity: 1,
        returned_quantity: 1, remaining_quantity: 0, paid_amount: 12, subtotal: 12,
        cost: 10, cost_subtotal: 10, profit: 2, profit_subtotal: 2 }],
      returns: []
    };
    vi.spyOn(window, 'alert').mockImplementation(() => undefined);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    let pageLoads = 0;
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) return Promise.resolve(new Response('{"token":"csrf"}', { status: 200 }));
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(authenticated), { status: 200 }));
      }
      if (path.endsWith('/orders/42/archive') && init?.method === 'POST') {
        return Promise.resolve(new Response(JSON.stringify({
          ...order, archived_at: '2026-01-02T10:00:00Z', archived_by: 'admin'
        }), { status: 200 }));
      }
      if (path.includes('/orders/page')) {
        pageLoads += 1;
        return Promise.resolve(new Response(JSON.stringify(pageLoads === 1
          ? { items: [order], page: 0, size: 50, totalItems: 1, totalPages: 1 }
          : { items: [], page: 0, size: 50, totalItems: 0, totalPages: 0 }), { status: 200 }));
      }
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/orders');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Archive' }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/orders/42/archive', expect.objectContaining({ method: 'POST' })
    ));
    expect(window.alert).toHaveBeenCalledWith('Order archived.');
    expect(await screen.findByText('No orders found.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Archive' })).not.toBeInTheDocument();
  });

  it('shows archived orders in the admin archive tab as view-only records', async () => {
    const archivedOrder = {
      id: 42, text: 'Archived sale', total_quantity: 1, total_amount: 12,
      returned_quantity: 1, returned_amount: 12, net_quantity: 0, net_amount: 0,
      total_profit: 2, returned_profit: 2, net_profit: 0,
      total_cost: 10, returned_cost: 10, net_cost: 0,
      return_status: 'FULLY_RETURNED', created_at: '2026-01-01T10:00:00Z', created_by: 'admin',
      modified_at: null, modified_by: null,
      archived_at: '2026-01-02T10:00:00Z', archived_by: 'admin'
    };
    vi.spyOn(globalThis, 'fetch').mockImplementation(input => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) return Promise.resolve(new Response('{"token":"csrf"}', { status: 200 }));
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(authenticated), { status: 200 }));
      }
      if (path.includes('/orders/archived/page')) {
        return Promise.resolve(new Response(JSON.stringify({
          items: [archivedOrder], page: 0, size: 50, totalItems: 1, totalPages: 1
        }), { status: 200 }));
      }
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/orders/archived');

    expect(await screen.findByRole('heading', { name: 'Archived Orders' })).toBeInTheDocument();
    expect(await screen.findByText('Archived sale')).toBeInTheDocument();
    expect(screen.getByText('admin')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'View' })).toHaveAttribute('href', '/orders/42');
    expect(screen.queryByRole('button', { name: 'Archive' })).not.toBeInTheDocument();
  });

  it('starts the cancelled tab on its first page after paging through archived orders', async () => {
    const listOrder = (id: number, text: string) => ({
      id, text, status: 'CONFIRMED', total_quantity: 1, total_amount: 12,
      returned_quantity: 1, returned_amount: 12, net_quantity: 0, net_amount: 0,
      total_profit: 2, returned_profit: 2, net_profit: 0,
      total_cost: 10, returned_cost: 10, net_cost: 0,
      return_status: 'FULLY_RETURNED', order_date: '2026-01-01', created_at: '2026-01-01T10:00:00Z',
      created_by: 'admin', picked_up: false
    });
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(input => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) return Promise.resolve(new Response('{"token":"csrf"}', { status: 200 }));
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(authenticated), { status: 200 }));
      }
      if (path.includes('/orders/archived/page')) {
        const page = Number(new URL(path, 'http://localhost').searchParams.get('page'));
        return Promise.resolve(new Response(JSON.stringify({
          items: [listOrder(40 + page, `Archived sale ${page}`)], page, size: 50, totalItems: 3, totalPages: 3
        }), { status: 200 }));
      }
      if (path.includes('/orders/cancelled/page')) {
        const page = Number(new URL(path, 'http://localhost').searchParams.get('page'));
        return Promise.resolve(new Response(JSON.stringify({
          items: page === 0 ? [{ ...listOrder(50, 'Cancelled sale'), status: 'CANCELLED' }] : [],
          page, size: 50, totalItems: 1, totalPages: 1
        }), { status: 200 }));
      }
      return Promise.resolve(new Response('[]', { status: 200 }));
    });
    const user = userEvent.setup();

    renderApp('/orders/archived');

    expect(await screen.findByText('Archived sale 0')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(await screen.findByText('Archived sale 1')).toBeInTheDocument();

    await user.click(screen.getByRole('link', { name: 'Cancelled Orders' }));

    expect(await screen.findByText('Cancelled sale')).toBeInTheDocument();
    const cancelledRequests = fetchMock.mock.calls
      .map(([input]) => String(input))
      .filter(path => path.includes('/orders/cancelled/page'));
    expect(cancelledRequests).toHaveLength(1);
    expect(cancelledRequests[0]).toContain('page=0');
  });

  it('submits a partial return and displays its audit history', async () => {
    const initialOrder = {
      id: 42, text: 'Sale', total_quantity: 2, total_amount: 25,
      returned_quantity: 0, returned_amount: 0, net_quantity: 2, net_amount: 25,
      total_profit: 5, returned_profit: 0, net_profit: 5,
      total_cost: 20, returned_cost: 0, net_cost: 20,
      return_status: 'NOT_RETURNED', created_at: '2026-01-01 10:00:00', created_by: 'admin',
      modified_at: null, modified_by: null, archived_at: null, archived_by: null,
      items: [{
        id: 7, item_id: 3, title: 'Widget', barcode: 'ABC', quantity: 2,
        returned_quantity: 0, remaining_quantity: 2, paid_amount: 12.5, subtotal: 25,
        cost: 10, cost_subtotal: 20, profit: 2.5, profit_subtotal: 5
      }],
      returns: []
    };
    const returnedOrder = {
      ...initialOrder,
      returned_quantity: 1, returned_amount: 12.5, net_quantity: 1, net_amount: 12.5,
      returned_profit: 2.5, net_profit: 2.5,
      returned_cost: 10, net_cost: 10,
      return_status: 'PARTIALLY_RETURNED',
      items: [{ ...initialOrder.items[0], returned_quantity: 1, remaining_quantity: 1 }],
      returns: [{
        id: 1, reason: 'Damaged', created_at: '2026-01-01 11:00:00', created_by: 'admin',
        total_quantity: 1, total_amount: 12.5,
        items: [{
          order_item_id: 7, item_id: 3, title: 'Widget', barcode: 'ABC',
          quantity: 1, refund_amount: 12.5
        }]
      }]
    };
    let returnBody = '';
    let returnMethod: string | undefined;
    vi.spyOn(window, 'alert').mockImplementation(() => undefined);
    vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) {
        return Promise.resolve(new Response(
          '{"token":"csrf","headerName":"X-XSRF-TOKEN"}',
          { status: 200 }
        ));
      }
      if (path.endsWith('/auth/session/refresh')) {
        return Promise.resolve(new Response(JSON.stringify(authenticated), { status: 200 }));
      }
      if (path.endsWith('/orders/42/returns')) {
        returnBody = String(init?.body);
        returnMethod = init?.method;
        return Promise.resolve(new Response(JSON.stringify(returnedOrder), { status: 201 }));
      }
      if (path.endsWith('/orders/42')) {
        return Promise.resolve(new Response(JSON.stringify(initialOrder), { status: 200 }));
      }
      return Promise.resolve(new Response('[]', { status: 200 }));
    });

    renderApp('/orders/42');
    const user = userEvent.setup();
    await screen.findByRole('heading', { name: 'Orders #42' });
    expect(screen.getByRole('button', { name: 'Return all remaining' })).toBeInTheDocument();
    const returnQuantity = screen.getByRole('spinbutton', { name: 'Return quantity Widget' });
    expect(returnQuantity).toHaveValue(null);
    expect(returnQuantity).toHaveAttribute('placeholder', '0');
    await user.click(screen.getByRole('button', { name: 'Return selected' }));
    expect(returnBody).toBe('');
    for (const value of ['0', '-1', '1.5', '3']) {
      await user.type(returnQuantity, value);
      await user.click(screen.getByRole('button', { name: 'Return selected' }));
      expect(returnBody).toBe('');
      await user.clear(returnQuantity);
      expect(returnQuantity).toHaveValue(null);
    }
    await user.type(returnQuantity, '1');
    expect(returnQuantity).toHaveValue(1);
    await user.type(screen.getByRole('textbox', { name: 'Return reason' }), 'Damaged');
    await user.click(screen.getByRole('button', { name: 'Return selected' }));

    expect(await screen.findByText('Damaged')).toBeInTheDocument();
    expect(returnMethod).toBe('POST');
    expect(JSON.parse(returnBody)).toEqual({
      items: [{ order_item_id: 7, quantity: 1 }],
      reason: 'Damaged'
    });
    expect(returnQuantity).toHaveValue(null);
  });
});
