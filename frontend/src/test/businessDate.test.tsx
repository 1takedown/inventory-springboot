import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MemoryRouter, useLocation } from 'react-router';
import { I18nProvider, useI18n } from '../i18n';
import { api, LOCALE_KEY } from '../api';
import { StockHistoryPage } from '../pages/StockHistoryPage';
import { DraftDateEditor } from '../components/DraftDateEditor';
import type { Order } from '../types';
import { isValidCalendarDate, shanghaiToday } from '../utils/businessDate';

describe('business dates', () => {
  it('changes today at Shanghai midnight', () => {
    expect(shanghaiToday(new Date('2026-09-08T15:59:59Z'))).toBe('2026-09-08');
    expect(shanghaiToday(new Date('2026-09-08T16:00:00Z'))).toBe('2026-09-09');
  });

  it('validates actual calendar days', () => {
    expect(isValidCalendarDate('2024-02-29')).toBe(true);
    for (const value of [null, '', '2026-02-29', '2026-04-31', '2026-9-09', 'invalid']) {
      expect(isValidCalendarDate(value)).toBe(false);
    }
  });

  it('formats timestamps in Shanghai while preserving calendar dates', () => {
    localStorage.setItem(LOCALE_KEY, 'en');
    function Dates() {
      const { date, calendarDate } = useI18n();
      return <>
        <span>{date('2026-09-08T16:00:00Z')}</span>
        <span>{calendarDate('2026-09-08')}</span>
        <span>{calendarDate('2026-02-29')}</span>
      </>;
    }
    render(<I18nProvider><Dates /></I18nProvider>);
    expect(screen.getByText('9/9/2026, 12:00:00 AM')).toBeInTheDocument();
    expect(screen.getByText('Sep 8, 2026')).toBeInTheDocument();
    expect(screen.getByText('2026-02-29')).toBeInTheDocument();
  });

  it.each(['', 'invalid', '2026-02-29'])('clears invalid movement date %s before fetching', async value => {
    localStorage.setItem(LOCALE_KEY, 'en');
    const request = vi.spyOn(api, 'stockMovements').mockResolvedValue({
      items: [], page: 0, size: 50, totalItems: 0, totalPages: 0
    });
    function Location() {
      return <output data-testid="location">{useLocation().search}</output>;
    }
    try {
      render(<MemoryRouter initialEntries={[`/stock-history?date=${value}&page=3&q=Widget&itemId=7&reason=ORDER_RESERVED`]}>
        <I18nProvider><StockHistoryPage /><Location /></I18nProvider>
      </MemoryRouter>);
      await screen.findByText('No stock movements found.');
      expect(request).toHaveBeenCalledTimes(1);
      const query = request.mock.calls[0][0];
      expect(query.has('date')).toBe(false);
      expect(query.has('page')).toBe(false);
      expect(query.get('q')).toBe('Widget');
      expect(query.get('itemId')).toBe('7');
      expect(query.get('reason')).toBe('ORDER_RESERVED');
      expect(screen.getByTestId('location').textContent).not.toContain('date=');
      expect(screen.getByLabelText('Movement date')).toHaveValue('');
      expect(screen.getByText('All dates · Asia/Shanghai')).toBeInTheDocument();
    } finally {
      request.mockRestore();
    }
  });

  it('disables saving an invalid draft date', () => {
    localStorage.setItem(LOCALE_KEY, 'en');
    render(<I18nProvider><DraftDateEditor
      order={{ id: 1, order_date: '2026-02-28' } as Order}
      onUpdate={vi.fn()} busy={false} runMutation={vi.fn()}
    /></I18nProvider>);
    fireEvent.change(screen.getByLabelText('Order Date'), { target: { value: '2026-03-01' } });
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled();
    fireEvent.change(screen.getByLabelText('Order Date'), { target: { value: '10000-01-01' } });
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
  });
});
