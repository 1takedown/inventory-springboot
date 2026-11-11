import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import App from '../App';
import {
  AuthProvider, canCreateOrders, canEditDraftItems, canViewAllOrders, canViewDashboard, canViewPrices,
  canViewStockHistory
} from '../auth';
import { I18nProvider } from '../i18n';

const authenticated = {
  accessToken: 'auditor-token', tokenType: 'Bearer', expiresIn: 600,
  user: { id: 3, username: 'auditor', role: 'AUDITOR' }
};
const item = { id: 3, title: 'Auditor widget', description: 'Blue widget', barcode: 'ABC', quantity: 10, version: 0 };
const order = {
  id: 99, text: 'Auditor sale', status: 'CONFIRMED', order_date: '2026-01-03',
  total_quantity: 3, returned_quantity: 1, net_quantity: 2, return_status: 'PARTIALLY_RETURNED',
  created_at: '2026-01-01T10:00:00Z', created_by: 'admin', picked_up: false,
  items: [{ id: 7, item_id: 3, title: 'Auditor widget', barcode: 'ABC', quantity: 3,
    returned_quantity: 1, remaining_quantity: 2 }],
  removed_items: [{ id: 8, item_id: 4, title: 'Removed widget', barcode: 'REM', quantity: 1,
    removed_at: '2026-01-01T11:00:00Z', removed_by: 'admin' }],
  returns: [{ id: 1, reason: 'Damaged', created_at: '2026-01-01T12:00:00Z', created_by: 'admin',
    total_quantity: 1, items: [{ order_item_id: 7, item_id: 3, title: 'Auditor widget', barcode: 'ABC', quantity: 1 }] }]
};
const archivedOrder = {
  ...order, id: 101, text: 'Archived sale', returned_quantity: 3, net_quantity: 0, return_status: 'FULLY_RETURNED',
  archived_at: '2026-01-02T00:00:00Z', archived_by: 'admin'
};
const cancelledOrder = {
  ...order, id: 102, text: 'Cancelled sale', status: 'CANCELLED', returns: [],
  cancelled_at: '2026-01-02T01:00:00Z', cancelled_by: 'admin'
};
const dashboard = {
  date: '2026-01-03', time_zone: 'Asia/Shanghai',
  totals: { gross_quantity: 3, restored_quantity: 1, net_quantity: 2 },
  items: [{ item_id: 3, title: 'Auditor widget', barcode: 'ABC',
    gross_quantity: 3, restored_quantity: 1, net_quantity: 2 }]
};
const relatedOrders = {
  date: '2026-01-03', time_zone: 'Asia/Shanghai', item_id: 3,
  orders: [{ id: 101, text: 'Archived sale', status: 'CONFIRMED', archived_at: '2026-01-02T00:00:00Z',
    gross_quantity: 3, restored_quantity: 3, net_quantity: 0 }]
};
const movements = {
  items: [{ id: 1, item_id: 3, item_id_snapshot: 3, title_snapshot: 'Auditor widget', barcode_snapshot: 'ABC',
    delta: -3, quantity_before: 13, quantity_after: 10, reason: 'ORDER_RESERVED', order_id: 101,
    order_text: 'Archived sale', created_at: '2026-01-01T10:00:00Z', created_by: 'admin' }],
  page: 0, size: 50, totalItems: 1, totalPages: 1
};
const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body)));
const pageOf = (entry: object) => ({ items: [entry], page: 0, size: 50, totalItems: 1, totalPages: 1 });

function renderApp(path: string) {
  render(<MemoryRouter initialEntries={[path]}>
    <I18nProvider><AuthProvider><App /></AuthProvider></I18nProvider>
  </MemoryRouter>);
}

function mockAuditor(status = 'CONFIRMED') {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(input => {
    const path = String(input);
    if (path.endsWith('/auth/csrf')) return Promise.resolve(new Response('{"token":"csrf"}'));
    if (path.endsWith('/auth/session/refresh')) return Promise.resolve(new Response(JSON.stringify(authenticated)));
    if (path.endsWith('/items')) return Promise.resolve(new Response(JSON.stringify([item])));
    if (path.includes('/orders/page')) {
      const page = Number(new URL(path, 'http://localhost').searchParams.get('page') || 0);
      return Promise.resolve(new Response(JSON.stringify({
        items: [{ ...order, id: page ? 100 : 99, text: page ? 'Second order' : order.text }],
        page, size: 50, totalItems: 51, totalPages: 2
      })));
    }
    if (path.endsWith('/orders/99')) return Promise.resolve(new Response(JSON.stringify({ ...order, status })));
    if (path.endsWith('/orders/101')) return json(archivedOrder);
    if (path.includes('/orders/archived/page')) return json(pageOf(archivedOrder));
    if (path.includes('/orders/cancelled/page')) return json(pageOf(cancelledOrder));
    if (path.includes('/dashboard/daily-out-items/3/orders')) return json(relatedOrders);
    if (path.includes('/dashboard/daily-out-items')) return json(dashboard);
    if (path.includes('/stock-movements')) return json(movements);
    throw new Error(`Unexpected auditor request: ${path}`);
  });
}

describe('Auditor permissions', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    vi.restoreAllMocks();
  });

  it.each([
    ['ADMIN', true], ['CASHIER', true], ['AUDITOR', false], ['GUEST', false],
    ['admin', false], ['', false], [null, false], [undefined, false]
  ] as const)('allows price access for role %s: %s', (role, expected) => {
    expect(canViewPrices(role == null ? role : { role })).toBe(expected);
  });

  it.each([
    ['ADMIN', true], ['CASHIER', true], ['AUDITOR', false], ['GUEST', false],
    ['admin', false], ['', false], [null, false], [undefined, false]
  ] as const)('allows order creation for role %s: %s', (role, expected) => {
    expect(canCreateOrders(role == null ? role : { role })).toBe(expected);
  });

  it.each([
    ['ADMIN', true], ['CASHIER', true], ['AUDITOR', false], ['GUEST', false],
    ['admin', false], ['', false], [null, false], [undefined, false]
  ] as const)('allows draft item edits for role %s: %s', (role, expected) => {
    expect(canEditDraftItems(role == null ? role : { role })).toBe(expected);
  });

  it.each([
    ['all orders', canViewAllOrders], ['the dashboard', canViewDashboard], ['stock history', canViewStockHistory]
  ] as const)('allows only administrators and auditors to view %s', (_name, allowed) => {
    for (const role of ['ADMIN', 'AUDITOR']) expect(allowed({ role })).toBe(true);
    for (const role of ['CASHIER', 'GUEST', 'auditor', '']) expect(allowed({ role })).toBe(false);
    expect(allowed(null)).toBe(false);
    expect(allowed(undefined)).toBe(false);
  });

  it('browses and searches inventory and pages active orders without prices or write controls', async () => {
    const fetchMock = mockAuditor();
    const user = userEvent.setup();
    renderApp('/items');
    expect(await screen.findByText('Auditor widget')).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '10' })).toBeInTheDocument();
    for (const name of ['Cost', 'Profit', 'Sale Price']) {
      expect(screen.queryByRole('columnheader', { name })).not.toBeInTheDocument();
    }
    expect(screen.getByRole('columnheader', { name: 'Actions' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'History' })).toHaveAttribute('href', '/stock-history?itemId=3');
    for (const name of ['Add Item', 'Edit', 'Delete']) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
    }
    for (const name of ['Create Order', 'Users']) {
      expect(screen.queryByRole('link', { name })).not.toBeInTheDocument();
    }
    for (const name of ['Dashboard', 'Stock History']) {
      expect(screen.getByRole('link', { name })).toBeInTheDocument();
    }
    const search = screen.getByRole('searchbox', { name: 'Search items' });
    await user.type(search, 'missing');
    expect(screen.getByText('No matching items found.')).toBeInTheDocument();
    await user.clear(search);
    expect(screen.getByText('Auditor widget')).toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: 'Orders' }));
    expect(await screen.findByText('Auditor sale')).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '2 / 3' })).toBeInTheDocument();
    for (const name of ['Active Orders', 'Archived Orders', 'Cancelled Orders']) {
      expect(screen.getByRole('link', { name })).toBeInTheDocument();
    }
    expect(screen.queryByRole('columnheader', { name: /Cost|Profit/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Archive|picked up/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(await screen.findByText('Second order')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Previous' }));
    expect(await screen.findByText('Auditor sale')).toBeInTheDocument();
    await user.type(screen.getByRole('searchbox', { name: 'Search orders' }), 'Auditor');
    await waitFor(() => expect(fetchMock.mock.calls.some(([input]) => String(input).includes('q=Auditor'))).toBe(true));
    await user.click(await screen.findByRole('link', { name: 'View' }));
    expect(await screen.findByRole('heading', { name: 'Orders #99' })).toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: 'Account' }));
    expect(await screen.findByRole('button', { name: 'Change Password' })).toBeInTheDocument();
    expect(fetchMock.mock.calls.filter(([input, init]) =>
      !String(input).includes('/auth/') && init?.method && init.method !== 'GET')).toHaveLength(0);
  });

  it.each(['DRAFT', 'CONFIRMED'])('shows a %s order without money, export, or write controls', async status => {
    const fetchMock = mockAuditor(status);
    renderApp('/orders/99');
    expect(await screen.findByRole('heading', { name: 'Orders #99' })).toBeInTheDocument();
    expect(screen.getByText('Auditor sale')).toBeInTheDocument();
    expect(screen.getByText('Removed widget')).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Sold' })).toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: /Cost|Profit|Subtotal/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/Original Cost|Returned Cost|Net Cost|Original Profit|Returned Profit|Net Profit|Refund Amount/))
      .not.toBeInTheDocument();
    expect(screen.queryByText(/HK\$|NaN|Unknown/)).not.toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: 'Receipt template' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Text')).not.toBeInTheDocument();
    for (const name of ['Confirm', 'Confirm & Export', 'Cancel Draft', 'Export receipt', 'Save',
      'Remove', 'Mark picked up', 'Return selected', 'Return all remaining']) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
    }
    if (status === 'CONFIRMED') {
      expect(screen.getByText('Damaged')).toBeInTheDocument();
      expect(screen.getByText('Auditor widget × 1')).toBeInTheDocument();
    }
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('receipt-templates'))).toBe(false);
  });

  it.each(['/sell', '/users'])(
    'redirects an auditor away from %s without requesting its data', async path => {
      const fetchMock = mockAuditor();
      renderApp(path);
      expect(await screen.findByText('Auditor widget')).toBeInTheDocument();
      expect(screen.getByRole('heading', { name: 'Inventory Items' })).toBeInTheDocument();
      expect(fetchMock.mock.calls.map(([input]) => String(input)))
        .toEqual(['/api/auth/csrf', '/api/auth/session/refresh', '/api/items']);
    }
  );

  it('shows dashboard quantities without money and opens a related archived order', async () => {
    const fetchMock = mockAuditor();
    const user = userEvent.setup();
    renderApp('/dashboard');
    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: 'Total' }));
    const itemToggle = await screen.findByRole('button', { name: /Auditor widget/ });
    expect(screen.getAllByText('Current Net').length).toBeGreaterThan(0);
    expect(screen.queryByText(/Net Cost|Net Profit|HK\$|NaN/)).not.toBeInTheDocument();
    await user.click(itemToggle);
    const related = (await screen.findByRole('cell', { name: 'Archived sale' })).closest('tr') as HTMLElement;
    expect(within(related).getByText('Archived')).toBeInTheDocument();
    await user.click(within(related).getByRole('link', { name: 'View' }));
    expect(await screen.findByRole('heading', { name: 'Orders #101' })).toBeInTheDocument();
    expect(screen.queryByText(/Original Cost|Net Profit|Refund Amount|HK\$/)).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.filter(([input, init]) =>
      !String(input).includes('/auth/') && init?.method && init.method !== 'GET')).toHaveLength(0);
  });

  it('shows stock history with links to the related orders', async () => {
    mockAuditor();
    renderApp('/stock-history');
    expect(await screen.findByRole('heading', { name: 'Stock History' })).toBeInTheDocument();
    const row = await screen.findByRole('row', { name: /Archived sale/ });
    expect(within(row).getByRole('link', { name: 'Archived sale' })).toHaveAttribute('href', '/orders/101');
    expect(within(row).getByRole('cell', { name: '-3' })).toBeInTheDocument();
    expect(screen.queryByText(/HK\$/)).not.toBeInTheDocument();
  });

  it.each([
    ['/orders/archived', 'Archived Orders', 'Archived sale', 'Archived at'],
    ['/orders/cancelled', 'Cancelled Orders', 'Cancelled sale', 'Cancelled at']
  ])('lists %s without money or administrator actions', async (path, heading, text, column) => {
    mockAuditor();
    renderApp(path);
    expect(await screen.findByRole('heading', { name: heading })).toBeInTheDocument();
    expect(await screen.findByText(text)).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: column })).toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: /Cost|Profit/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Archive|picked up/ })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Active Orders' })).toBeInTheDocument();
  });

  it('lets administrators create users and assign the Auditor role', async () => {
    const user = userEvent.setup();
    let accounts = [{ id: 2, username: 'existing', role: 'CASHIER', active: true, created_at: '2026-01-01T00:00:00Z' }];
    const mutations: Array<{ method: string; body: unknown }> = [];
    vi.spyOn(window, 'alert').mockImplementation(() => undefined);
    vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      const path = String(input);
      if (path.endsWith('/auth/csrf')) return Promise.resolve(new Response('{"token":"csrf"}'));
      if (path.endsWith('/auth/session/refresh')) return Promise.resolve(new Response(JSON.stringify({
        ...authenticated, user: { id: 1, username: 'admin', role: 'ADMIN' }
      })));
      if (init?.method === 'PATCH') {
        mutations.push({ method: 'PATCH', body: JSON.parse(String(init.body)) });
        accounts = [{ ...accounts[0], role: 'AUDITOR' }];
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      if (path.endsWith('/users') && init?.method === 'POST') {
        const body = JSON.parse(String(init.body));
        mutations.push({ method: 'POST', body });
        accounts.push({ ...accounts[0], id: 3, username: body.username });
        return Promise.resolve(new Response(JSON.stringify(accounts[1]), { status: 201 }));
      }
      if (path.endsWith('/users')) return Promise.resolve(new Response(JSON.stringify(accounts)));
      throw new Error(`Unexpected request: ${path}`);
    });
    renderApp('/users');
    const row = await screen.findByRole('row', { name: /existing/ });
    await user.selectOptions(within(row).getByRole('combobox'), 'AUDITOR');
    await waitFor(() => expect(within(screen.getByRole('row', { name: /existing/ })).getByRole('combobox')).toHaveValue('AUDITOR'));
    await user.type(screen.getByRole('textbox', { name: 'Username' }), 'new-auditor');
    await user.type(screen.getByLabelText('Password'), 'auditor-secret-password');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Role' }), 'AUDITOR');
    await user.click(screen.getByRole('button', { name: 'Create User' }));
    expect(await screen.findByRole('row', { name: /new-auditor/ })).toBeInTheDocument();
    expect(mutations).toEqual([
      { method: 'PATCH', body: { role: 'AUDITOR' } },
      { method: 'POST', body: { username: 'new-auditor', password: 'auditor-secret-password', role: 'AUDITOR' } }
    ]);
  });
});
