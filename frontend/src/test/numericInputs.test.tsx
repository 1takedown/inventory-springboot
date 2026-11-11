import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router';
import { api, LOCALE_KEY } from '../api';
import { I18nProvider } from '../i18n';
import { ItemForm } from '../components/ItemForm';
import { DraftItemsEditor } from '../components/DraftItemsEditor';
import { SellPage } from '../pages/SellPage';
import { blankItem } from '../utils/item';
import type { Item, Order } from '../types';

describe('numeric inputs', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    localStorage.setItem(LOCALE_KEY, 'en');
    vi.spyOn(window, 'alert').mockImplementation(() => undefined);
  });
  afterEach(() => vi.restoreAllMocks());

  it.each([0, 5])('edits item numbers with initial value %s and saves empty values as zero', async initialValue => {
    const user = userEvent.setup();
    const onSave = vi.fn(async () => undefined);
    render(<I18nProvider><ItemForm
      initial={{ ...blankItem, title: 'Widget', cost: initialValue, profit: initialValue, quantity: initialValue }}
      onSave={onSave} onCancel={vi.fn()} />
    </I18nProvider>);
    const inputs = ['Cost', 'Profit', 'Quantity'].map(name => screen.getByRole('spinbutton', { name }));
    for (const input of inputs) {
      expect(input).toHaveValue(initialValue || null);
      expect(input).toHaveAttribute('placeholder', '0');
      if (initialValue) await user.clear(input);
      await user.type(input, '1');
      expect(input).toHaveValue(1);
    }
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(onSave).toHaveBeenLastCalledWith({ ...blankItem, title: 'Widget', cost: 1, profit: 1, quantity: 1 });
    for (const input of inputs) {
      await user.clear(input);
      expect(input).toHaveValue(null);
    }
    await user.type(inputs[0], '0');
    expect(inputs[0]).toHaveValue(0);
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(onSave).toHaveBeenLastCalledWith({ ...blankItem, title: 'Widget' });
  });

  it('rejects negative numbers, fractional quantities, and excessive money precision', async () => {
    const user = userEvent.setup();
    const onSave = vi.fn(async () => undefined);
    render(<I18nProvider><ItemForm initial={{ ...blankItem, title: 'Widget' }}
      onSave={onSave} onCancel={vi.fn()} /></I18nProvider>);
    for (const [name, value] of [['Cost', '-1'], ['Cost', '1.001'], ['Profit', '-1'], ['Profit', '0.001'],
      ['Quantity', '-1'], ['Quantity', '1.5']]) {
      const input = screen.getByRole('spinbutton', { name });
      await user.type(input, value);
      expect(input).toBeInvalid();
      await user.click(screen.getByRole('button', { name: 'Save' }));
      expect(onSave).not.toHaveBeenCalled();
      await user.clear(input);
    }
    await user.type(screen.getByRole('spinbutton', { name: 'Cost' }), '1.25');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(onSave).toHaveBeenCalledWith({ ...blankItem, title: 'Widget', cost: 1.25 });
  });

  it.each(['sale', 'draft'])('rejects unpriced items for %s additions', async mode => {
    const user = userEvent.setup();
    const item: Item = { ...blankItem, id: 3, title: 'Widget', quantity: 3,
      created_at: '2026-01-01T00:00:00Z', created_by: 'admin', modified_at: null, modified_by: null, version: 0 };
    vi.spyOn(api, 'items').mockResolvedValue([item]);
    vi.spyOn(api, 'templates').mockResolvedValue([]);
    render(<MemoryRouter><I18nProvider>{mode === 'sale' ? <SellPage /> :
      <DraftItemsEditor order={{ id: 99 } as Order} onUpdate={vi.fn()} busy={false}
        runMutation={async operation => operation()} />}
    </I18nProvider></MemoryRouter>);
    await user.click(screen.getByRole('combobox', { name: /Select an item/ }));
    await user.click(await screen.findByRole('option', { name: 'Widget (3)' }));
    await user.click(screen.getByRole('button', { name: 'Add to Cart' }));
    expect(window.alert).toHaveBeenLastCalledWith('Pricing is unavailable for this item.');
    expect(screen.queryByRole('cell', { name: 'Widget' })).not.toBeInTheDocument();
  });

  it.each(['sale', 'draft'])('uses an empty quantity default of one for %s additions', async mode => {
    const user = userEvent.setup();
    const item: Item = { ...blankItem, id: 3, title: 'Widget', quantity: 3, price: 2,
      created_at: '2026-01-01T00:00:00Z', created_by: 'admin', modified_at: null, modified_by: null, version: 0 };
    vi.spyOn(api, 'items').mockResolvedValue([item]);
    vi.spyOn(api, 'templates').mockResolvedValue([]);
    render(<MemoryRouter><I18nProvider>{mode === 'sale' ? <SellPage /> :
      <DraftItemsEditor order={{ id: 99 } as Order} onUpdate={vi.fn()} busy={false}
        runMutation={async operation => operation()} />}
    </I18nProvider></MemoryRouter>);
    const quantity = screen.getByRole('spinbutton', { name: 'Quantity' });
    const select = screen.getByRole('combobox', { name: /Select an item/ });
    const add = screen.getByRole('button', { name: 'Add to Cart' });
    expect(quantity).toHaveValue(null);
    expect(quantity).toHaveAttribute('placeholder', '1');
    await user.click(select);
    await user.click(await screen.findByRole('option', { name: 'Widget (3)' }));
    for (const value of ['0', '-1', '1.5']) {
      await user.type(quantity, value);
      expect(quantity).toBeInvalid();
      await user.click(add);
      expect(screen.queryByRole('row', { name: /Widget/ })).not.toBeInTheDocument();
      await user.clear(quantity);
      expect(quantity).toHaveValue(null);
    }
    await user.type(quantity, '1');
    expect(quantity).toHaveValue(1);
    await user.click(add);
    expect(within(screen.getByRole('row', { name: /Widget/ })).getByText('1')).toBeInTheDocument();
    expect(quantity).toHaveValue(null);
    await user.click(select);
    await user.click(screen.getByRole('option', { name: 'Widget (3)' }));
    await user.click(add);
    expect(within(screen.getByRole('row', { name: /Widget/ })).getByText('2')).toBeInTheDocument();
    await user.click(select);
    await user.click(screen.getByRole('option', { name: 'Widget (3)' }));
    await user.type(quantity, '3');
    await user.click(add);
    expect(window.alert).toHaveBeenLastCalledWith('Only 3 units are available.');
    expect(within(screen.getByRole('row', { name: /Widget/ })).getByText('2')).toBeInTheDocument();
  });

  it('shows the total cart quantity in the sell page Cart heading and Total row', async () => {
    const user = userEvent.setup();
    const stock = (id: number, title: string, quantity: number): Item => ({ ...blankItem, id, title, quantity, price: 2,
      created_at: '2026-01-01T00:00:00Z', created_by: 'admin', modified_at: null, modified_by: null, version: 0 });
    vi.spyOn(api, 'items').mockResolvedValue([stock(3, 'Widget', 5), stock(4, 'Gadget', 4)]);
    vi.spyOn(api, 'templates').mockResolvedValue([]);
    render(<MemoryRouter><I18nProvider><SellPage /></I18nProvider></MemoryRouter>);
    const expectCount = (count: number) => {
      expect(screen.getByRole('heading', { name: `Cart (${count})` })).toBeInTheDocument();
      expect(screen.getByText(`Total (${count})`)).toBeInTheDocument();
    };
    const addToCart = async (option: string, quantity: string) => {
      await user.click(screen.getByRole('combobox', { name: /Select an item/ }));
      await user.click(await screen.findByRole('option', { name: option }));
      await user.type(screen.getByRole('spinbutton', { name: 'Quantity' }), quantity);
      await user.click(screen.getByRole('button', { name: 'Add to Cart' }));
    };

    expect(screen.getByRole('heading', { name: 'Cart (0)' })).toBeInTheDocument();
    expect(screen.queryByText(/^Total/)).not.toBeInTheDocument();
    await addToCart('Widget (5)', '2');
    expectCount(2);
    await addToCart('Gadget (4)', '1');
    expectCount(3);
    await user.click(within(screen.getByRole('row', { name: /Gadget/ })).getByRole('button', { name: '+' }));
    expectCount(4);
    await user.click(within(screen.getByRole('row', { name: /Widget/ })).getByRole('button', { name: 'Remove' }));
    expectCount(2);
    await user.click(screen.getByRole('button', { name: 'Clear Cart' }));
    expect(screen.getByRole('heading', { name: 'Cart (0)' })).toBeInTheDocument();
    expect(screen.queryByText(/^Total/)).not.toBeInTheDocument();
  });
});
