import type { Item, ItemInput } from '../types';

export const hasMoneyPrecision = (value: number) =>
  Math.abs(value * 100 - Math.round(value * 100)) < 1e-9;

export const blankItem: ItemInput = {
  title: '', description: '', quantity: 0, cost: 0, profit: 0, barcode: null
};

/** In-stock items first, then zero/negative; preserves input order within each group. */
export function itemsForSelection(items: Item[]): Item[] {
  return [
    ...items.filter(item => item.quantity > 0),
    ...items.filter(item => item.quantity <= 0),
  ];
}

export function itemMatchesSearch(item: Item, search: string): boolean {
  const query = search.trim().toLocaleLowerCase();
  if (!query) return true;
  return item.title.toLocaleLowerCase().includes(query)
    || (item.barcode?.toLocaleLowerCase().includes(query) ?? false);
}

export function itemMatchesInventorySearch(item: Item, search: string): boolean {
  const query = search.trim().toLocaleLowerCase();
  if (!query) return true;
  return item.title.toLocaleLowerCase().includes(query)
    || (item.description?.toLocaleLowerCase().includes(query) ?? false)
    || (item.barcode?.toLocaleLowerCase().includes(query) ?? false);
}
