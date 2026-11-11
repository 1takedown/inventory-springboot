import { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api';
import { useI18n } from '../i18n';
import type { CartLine, Item, DraftEditorProps } from '../types';
import { ItemCombobox } from './ItemCombobox';
import { notifyError } from '../utils/notifyError';

export function DraftItemsEditor({ order, onUpdate, busy, runMutation }: DraftEditorProps) {
  const { t, money } = useI18n();
  const [items, setItems] = useState<Item[]>([]);
  const [selected, setSelected] = useState('');
  const [quantity, setQuantity] = useState('');
  const [barcode, setBarcode] = useState('');
  const [cart, setCart] = useState<CartLine[]>([]);
  const scanner = useRef<HTMLInputElement>(null);
  const quantityInput = useRef<HTMLInputElement>(null);
  const scanInFlight = useRef(false);

  const refreshItems = async () => {
    const loaded = await api.items();
    setItems(loaded);
    return loaded;
  };
  useEffect(() => { void refreshItems().catch(error => notifyError(error)); }, [order.id]);

  const add = (item: Item, amount: number) => {
    const price = item.price;
    if (price === undefined) return alert(t('pricingUnavailable'));
    setCart(lines => {
      const existing = lines.find(line => line.item_id === item.id);
      const current = existing?.quantity || 0;
      if (current + amount > item.quantity) {
        alert(t('stockAvailable', { available: item.quantity }));
        return lines;
      }
      return existing
        ? lines.map(line => line.item_id === item.id ? { ...line, quantity: current + amount } : line)
        : [...lines, { item_id: item.id, title: item.title, barcode: item.barcode, price, quantity: amount }];
    });
  };
  const addSelected = () => {
    if (quantityInput.current?.reportValidity() === false) return;
    const item = items.find(entry => entry.id === Number(selected));
    const amount = Number(quantity === '' ? 1 : quantity);
    if (!item || !Number.isInteger(amount) || amount < 1) return alert(t('selectionRequired'));
    add(item, amount);
    setSelected('');
    setQuantity('');
  };
  const scan = async () => {
    if (!barcode.trim() || scanInFlight.current || busy) return;
    scanInFlight.current = true;
    try {
      add(await api.itemByBarcode(barcode.trim()), 1);
      setBarcode('');
    } catch (error) { notifyError(error); }
    finally { scanInFlight.current = false; }
    scanner.current?.focus();
  };
  const change = (id: number, delta: number) => {
    const item = items.find(entry => entry.id === id);
    if (!item) return;
    setCart(lines => {
      const line = lines.find(entry => entry.item_id === id);
      if (!line) return lines;
      const next = line.quantity + delta;
      if (next < 1) return lines.filter(entry => entry.item_id !== id);
      if (next > item.quantity) {
        alert(t('stockAvailable', { available: item.quantity }));
        return lines;
      }
      return lines.map(entry => entry.item_id === id ? { ...entry, quantity: next } : entry);
    });
  };
  const submit = async () => {
    if (!cart.length || busy) return;
    if (scanInFlight.current) return alert(t('scanPending'));
    await runMutation(async () => {
      const latest = await refreshItems();
      let changed = false;
      const refreshed = cart.flatMap(line => {
        const item = latest.find(entry => entry.id === line.item_id);
        if (!item) { changed = true; return []; }
        if (item.price === undefined) throw new Error(t('pricingUnavailable'));
        const amount = Math.min(line.quantity, item.quantity);
        if (amount !== line.quantity || item.title !== line.title || item.barcode !== line.barcode || item.price !== line.price) changed = true;
        return amount > 0 ? [{ ...line, title: item.title, barcode: item.barcode, price: item.price, quantity: amount }] : [];
      });
      if (changed) {
        setCart(refreshed);
        alert(t('cartUpdated'));
        return;
      }
      onUpdate(await api.addOrderItems(order.id, refreshed.map(({ item_id, quantity: amount }) => ({ item_id, quantity: amount }))));
      setCart([]);
      await refreshItems();
      alert(t('addItemsSuccess'));
    });
  };
  const itemById = useMemo(() => new Map(items.map(item => [item.id, item])), [items]);
  const total = useMemo(() => cart.reduce((sum, line) => sum + line.price * line.quantity, 0), [cart]);
  return <div className="card card-body mb-4">
    <h2 className="h4">{t('addOrderItems')}</h2>
    <div className="input-group mb-2">
      <input ref={scanner} disabled={busy} className="form-control" placeholder={t('scan')} value={barcode}
        onChange={event => setBarcode(event.target.value)} onKeyDown={event => {
          if (event.key === 'Enter') { event.preventDefault(); void scan(); }
        }} />
      <button className="btn btn-primary" disabled={busy} onClick={() => void scan()}>{t('scanAdd')}</button>
    </div>
    <div className="row g-2 mb-3">
      <div className="col-md-8"><ItemCombobox items={items} value={selected} disabled={busy}
        onChange={setSelected} /></div>
      <div className="col-md-2"><input ref={quantityInput} disabled={busy} aria-label={t('quantity')} type="number" min="1" step="1" className="form-control" placeholder="1" value={quantity} onChange={event => setQuantity(event.target.value)} /></div>
      <div className="col-md-2 d-grid"><button disabled={busy} className="btn btn-primary" onClick={addSelected}>{t('addToCart')}</button></div>
    </div>
    {cart.length > 0 && <div className="table-responsive"><table className="table table-sm app-table">
      <thead><tr><th>{t('item')}</th><th>{t('price')}</th><th>{t('quantity')}</th><th>{t('subtotal')}</th><th>{t('actions')}</th></tr></thead>
      <tbody>{cart.map(line => <tr key={line.item_id}><td>{line.title}</td><td>{money(line.price)}</td>
        <td><div className="btn-group"><button disabled={busy} className="btn btn-sm btn-outline-secondary" onClick={() => change(line.item_id, -1)}>−</button>
          <span className="btn btn-sm btn-light disabled">{line.quantity}</span><button disabled={busy} className="btn btn-sm btn-outline-secondary" onClick={() => change(line.item_id, 1)}>+</button></div>
          <div className="small text-muted">{t('remainingStock', { available: Math.max(0, (itemById.get(line.item_id)?.quantity || 0) - line.quantity) })}</div></td>
        <td>{money(line.price * line.quantity)}</td><td><button disabled={busy} className="btn btn-sm btn-outline-danger" onClick={() => setCart(lines => lines.filter(entry => entry.item_id !== line.item_id))}>{t('remove')}</button></td></tr>)}</tbody>
      <tfoot><tr><th colSpan={3}>{t('total')}</th><th>{money(total)}</th><th /></tr></tfoot>
    </table></div>}
    <div><button className="btn btn-primary" disabled={busy || !cart.length} onClick={() => void submit()}>{t('addOrderItems')}</button></div>
  </div>;
}
