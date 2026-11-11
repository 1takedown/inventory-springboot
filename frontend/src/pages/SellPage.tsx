import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { ORDER_DATE_KEY, TEMPLATE_KEY, api } from '../api';
import { useI18n } from '../i18n';
import type { CartLine, Item, ReceiptTemplate } from '../types';
import { ItemCombobox } from '../components/ItemCombobox';
import { notifyError } from '../utils/notifyError';
import { isValidCalendarDate, shanghaiToday } from '../utils/businessDate';

function initialOrderDate() {
  const stored = sessionStorage.getItem(ORDER_DATE_KEY);
  if (isValidCalendarDate(stored)) return stored;
  if (stored !== null) sessionStorage.removeItem(ORDER_DATE_KEY);
  return shanghaiToday();
}

export function SellPage() {
  const { t, money, calendarDate } = useI18n();
  const navigate = useNavigate();
  const [items, setItems] = useState<Item[]>([]);
  const [templates, setTemplates] = useState<ReceiptTemplate[]>([]);
  const [template, setTemplate] = useState(() => localStorage.getItem(TEMPLATE_KEY) || '');
  const [selected, setSelected] = useState('');
  const [quantity, setQuantity] = useState('');
  const [barcode, setBarcode] = useState('');
  const [text, setText] = useState('');
  const [orderDate, setOrderDate] = useState(initialOrderDate);
  const [cart, setCart] = useState<CartLine[]>([]);
  const [busy, setBusy] = useState(false);
  const scanner = useRef<HTMLInputElement>(null);
  const quantityInput = useRef<HTMLInputElement>(null);
  const scanInFlight = useRef(false);
  const submitLock = useRef(false);
  const refreshItems = async () => {
    const loadedItems = await api.items();
    setItems(loadedItems);
    return loadedItems;
  };
  useEffect(() => {
    void Promise.all([api.items(), api.templates()]).then(([loadedItems, loadedTemplates]) => {
      setItems(loadedItems);
      setTemplates(loadedTemplates);
      const valid = loadedTemplates.some(entry => entry.id === template);
      if (!valid && loadedTemplates[0]) {
        setTemplate(loadedTemplates[0].id);
        localStorage.setItem(TEMPLATE_KEY, loadedTemplates[0].id);
      }
    }).catch(error => notifyError(error));
  }, []);
  useEffect(() => {
    const refreshOnFocus = () => { void refreshItems().catch(error => notifyError(error)); };
    window.addEventListener('focus', refreshOnFocus);
    return () => window.removeEventListener('focus', refreshOnFocus);
  }, []);
  useEffect(() => { scanner.current?.focus(); }, []);
  const add = (item: Item, amount: number) => {
    if (submitLock.current) return;
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
        ? lines.map(line => line.item_id === item.id ? { ...line, quantity: line.quantity + amount } : line)
        : [...lines, { item_id: item.id, title: item.title, barcode: item.barcode, price, quantity: amount }];
    });
  };
  const addSelected = () => {
    if (submitLock.current) return;
    if (quantityInput.current?.reportValidity() === false) return;
    const item = items.find(entry => entry.id === Number(selected));
    const amount = Number(quantity === '' ? 1 : quantity);
    if (!item || !Number.isInteger(amount) || amount < 1) return alert(t('selectionRequired'));
    add(item, amount);
    setSelected('');
    setQuantity('');
  };
  const scan = async () => {
    if (submitLock.current) return;
    if (!barcode.trim()) return;
    if (scanInFlight.current) return;
    scanInFlight.current = true;
    try {
      add(await api.itemByBarcode(barcode.trim()), 1);
      setBarcode('');
    } catch (error) { notifyError(error); }
    finally { scanInFlight.current = false; }
    scanner.current?.focus();
  };
  const change = (id: number, delta: number) => {
    if (submitLock.current) return;
    const item = items.find(entry => entry.id === id);
    if (!item) return;
    setCart(lines => {
      const line = lines.find(entry => entry.item_id === id);
      if (!line) return lines;
      if (line.quantity + delta < 1) return lines.filter(entry => entry.item_id !== id);
      if (line.quantity + delta > item.quantity) {
        alert(t('stockAvailable', { available: item.quantity }));
        return lines;
      }
      return lines.map(entry => entry.item_id === id ? { ...entry, quantity: entry.quantity + delta } : entry);
    });
  };
  const remove = (id: number) => {
    if (submitLock.current) return;
    setCart(lines => lines.filter(entry => entry.item_id !== id));
  };
  const clear = () => {
    if (submitLock.current) return;
    setCart([]);
  };
  const submit = async (exportReceipt: boolean, status: 'DRAFT' | 'CONFIRMED' = 'CONFIRMED') => {
    if (submitLock.current) return;
    if (scanInFlight.current) return alert(t('scanPending'));
    if (!text.trim()) return alert(t('orderTextRequired'));
    if (!orderDate) return alert(t('orderDateRequired'));
    if (!cart.length) return;
    if (exportReceipt && !template) return alert(t('noTemplate'));
    submitLock.current = true;
    const cartSnapshot = cart.map(line => ({ ...line }));
    const textSnapshot = text.trim();
    const templateSnapshot = template;
    const orderDateSnapshot = orderDate;
    setBusy(true);
    try {
      const latestItems = await refreshItems();
      let changed = false;
      const refreshedCart = cartSnapshot.flatMap(line => {
        const item = latestItems.find(entry => entry.id === line.item_id);
        if (!item) {
          changed = true;
          return [];
        }
        if (item.price === undefined) throw new Error(t('pricingUnavailable'));
        const quantity = Math.min(line.quantity, item.quantity);
        if (quantity !== line.quantity || item.title !== line.title
          || item.barcode !== line.barcode || item.price !== line.price) {
          changed = true;
        }
        return quantity > 0
          ? [{ ...line, title: item.title, barcode: item.barcode, price: item.price, quantity }]
          : [];
      });
      if (changed) {
        setCart(refreshedCart);
        alert(t('cartUpdated'));
        return;
      }
      const order = await api.createOrder(
        textSnapshot,
        refreshedCart.map(({ item_id, quantity }) => ({ item_id, quantity })),
        status,
        orderDateSnapshot
      );
      setCart([]);
      setText('');
      if (exportReceipt) {
        try {
          const { exportOrderToExcel, downloadBlob } = await import('../receipt-excel');
          const result = await exportOrderToExcel(order, templateSnapshot, value => calendarDate(value), t('orderPricingUnavailable'));
          downloadBlob(result.blob, `receipt-${order.id}.xlsx`);
          alert(t('exportSuccess'));
        } catch (exportError) {
          alert(`${t('orderCreated')} ${t('exportFailed', { error: (exportError as Error).message })}`);
        }
      } else alert(t('orderCreated'));
      navigate(status === 'DRAFT' ? `/orders/${order.id}` : '/orders');
    } catch (error) { notifyError(error); } finally {
      submitLock.current = false;
      setBusy(false);
    }
  };
  const total = useMemo(() => cart.reduce((sum, line) => sum + line.price * line.quantity, 0), [cart]);
  const totalQuantity = useMemo(() => cart.reduce((sum, line) => sum + line.quantity, 0), [cart]);
  const itemById = useMemo(() => new Map(items.map(item => [item.id, item])), [items]);
  return (
    <>
      <h1 className="h2 mb-3">{t('sell')}</h1>
      <div className="card card-body mb-3">
        <div className="input-group">
          <input ref={scanner} disabled={busy} className="form-control scanner-input" placeholder={t('scan')} value={barcode}
            onChange={e => setBarcode(e.target.value)} onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); void scan(); } }} />
          <button className="btn btn-primary" disabled={busy} onClick={() => void scan()}>{t('scanAdd')}</button>
        </div>
        <div className="row g-2 mt-2">
          <div className="col-md-8"><ItemCombobox items={items} value={selected} disabled={busy}
            onChange={setSelected} /></div>
          <div className="col-md-2"><input ref={quantityInput} disabled={busy} aria-label={t('quantity')} type="number" min="1" step="1" className="form-control" placeholder="1" value={quantity} onChange={e => setQuantity(e.target.value)} /></div>
          <div className="col-md-2 d-grid"><button disabled={busy} className="btn btn-primary" onClick={addSelected}>{t('addToCart')}</button></div>
        </div>
      </div>
      <h2 className="h4">{t('cart')} ({totalQuantity})</h2>
      {cart.length === 0 ? <p className="text-muted">{t('emptyItems')}</p> : <div className="table-responsive"><table className="table app-table">
        <thead><tr><th>{t('item')}</th><th>{t('price')}</th><th>{t('quantity')}</th><th>{t('subtotal')}</th><th>{t('actions')}</th></tr></thead>
        <tbody>{cart.map(line => {
          const latest = itemById.get(line.item_id);
          return <tr key={line.item_id}><td>{line.title}</td><td>{money(line.price)}</td>
            <td><div className="btn-group"><button disabled={busy} className="btn btn-outline-secondary" onClick={() => change(line.item_id, -1)}>−</button><span className="btn btn-light disabled">{line.quantity}</span><button disabled={busy} className="btn btn-outline-secondary" onClick={() => change(line.item_id, 1)}>+</button></div>
              {latest && <div className="small text-muted">{t('remainingStock', { available: Math.max(0, latest.quantity - line.quantity) })}</div>}</td>
            <td>{money(line.price * line.quantity)}</td><td><button disabled={busy} className="btn btn-outline-danger btn-sm" onClick={() => remove(line.item_id)}>{t('remove')}</button></td>
          </tr>;
        })}</tbody>
        <tfoot><tr><th colSpan={3}>{t('total')} ({totalQuantity})</th><th>{money(total)}</th><th /></tr></tfoot>
      </table></div>}
      <textarea disabled={busy} aria-label={t('orderText')} className="form-control mb-3" placeholder={t('orderText')} value={text} onChange={e => setText(e.target.value)} />
      <label className="form-label" htmlFor="order-date">{t('orderDate')}</label>
      <input id="order-date" disabled={busy} required type="date" className="form-control mb-3"
        value={orderDate} onChange={event => {
          const value = event.target.value;
          setOrderDate(value);
          if (isValidCalendarDate(value)) sessionStorage.setItem(ORDER_DATE_KEY, value);
          else sessionStorage.removeItem(ORDER_DATE_KEY);
        }} />
      <div className="row g-2 align-items-center">
        <div className="col-md-5"><select disabled={busy} aria-label={t('receiptTemplate')} className="form-select" value={template} onChange={e => { setTemplate(e.target.value); localStorage.setItem(TEMPLATE_KEY, e.target.value); }}>
          {templates.map(entry => <option key={entry.id} value={entry.id}>{entry.name}</option>)}
        </select></div>
        <div className="col-md-7 d-flex gap-2 flex-wrap">
          <button className="btn btn-outline-primary" disabled={busy || !cart.length} onClick={() => void submit(false, 'DRAFT')}>{t('saveDraft')}</button>
          <button className="btn btn-success" disabled={busy || !cart.length} onClick={() => void submit(true)}>{t('confirmExport')}</button>
          <button className="btn btn-outline-secondary" disabled={busy || !cart.length} onClick={() => void submit(false)}>{t('confirmOrder')}</button>
          <button className="btn btn-outline-danger" disabled={busy || !cart.length} onClick={clear}>{t('clear')}</button>
        </div>
      </div>
    </>
  );
}
