import { useEffect, useRef, useState } from 'react';
import { Link, Navigate, useParams } from 'react-router';
import { TEMPLATE_KEY, api } from '../api';
import { canEditDraftItems, canViewPrices, useAuth } from '../auth';
import { useI18n } from '../i18n';
import type { Order, ReceiptTemplate } from '../types';
import { DraftItemsEditor } from '../components/DraftItemsEditor';
import { DraftDateEditor } from '../components/DraftDateEditor';
import { DraftTextEditor } from '../components/DraftTextEditor';
import { Loading } from '../components/Loading';
import { notifyError } from '../utils/notifyError';
import { returnStatusKey } from '../utils/returnStatus';

export function OrderDetailsPage() {
  const { id } = useParams();
  const { user } = useAuth();
  const pricesVisible = canViewPrices(user);
  const { t, money, date, calendarDate } = useI18n();
  const profit = (value: number | null | undefined) =>
    value == null ? t('unknown') : money(value);
  const [order, setOrder] = useState<Order | null>();
  const [templates, setTemplates] = useState<ReceiptTemplate[]>([]);
  const [template, setTemplate] = useState(() => localStorage.getItem(TEMPLATE_KEY) || '');
  const [returnQuantities, setReturnQuantities] = useState<Record<number, string>>({});
  const returnInputs = useRef<HTMLTableElement>(null);
  const [reason, setReason] = useState('');
  const [exporting, setExporting] = useState(false);
  const [mutating, setMutating] = useState(false);
  const mutationInFlight = useRef(false);
  const runMutation = async (operation: () => Promise<void>) => {
    if (mutationInFlight.current) return;
    mutationInFlight.current = true;
    setMutating(true);
    try {
      await operation();
    } catch (error) {
      notifyError(error);
    } finally {
      mutationInFlight.current = false;
      setMutating(false);
    }
  };
  const load = async () => {
    try {
      setOrder(await api.order(Number(id)));
    } catch (error) {
      notifyError(error);
      setOrder(null);
    }
  };
  useEffect(() => {
    void load();
    if (pricesVisible) void api.templates().then(loadedTemplates => {
      setTemplates(loadedTemplates);
      const valid = loadedTemplates.some(entry => entry.id === template);
      if (!valid && loadedTemplates[0]) {
        setTemplate(loadedTemplates[0].id);
        localStorage.setItem(TEMPLATE_KEY, loadedTemplates[0].id);
      }
    }).catch(error => notifyError(error));
  }, [id, pricesVisible]);
  if (order === undefined) return <Loading />;
  if (order === null) return <Navigate to="/orders" replace />;
  const orderStatus = order.status || 'CONFIRMED';
  const canEditOrders = user?.role === 'ADMIN';
  const draftItemsEditable = canEditDraftItems(user);

  const returnSelected = async () => {
    if (returnInputs.current?.querySelector<HTMLInputElement>('input:invalid')?.reportValidity() === false) return;
    const items = (order.items || [])
      .map(line => ({ line, quantity: Number(returnQuantities[line.id] || 0) }))
      .filter(entry => entry.quantity !== 0);
    if (!items.length) return alert(t('returnSelectionRequired'));
    if (items.some(({ line, quantity }) =>
      !Number.isInteger(quantity) || quantity < 1 || quantity > line.remaining_quantity)) {
      return alert(t('returnQuantityInvalid'));
    }
    await runMutation(async () => {
      const updated = await api.returnOrder(order.id, {
        items: items.map(({ line, quantity }) => ({ order_item_id: line.id, quantity })),
        reason: reason.trim() || undefined
      });
      setOrder(updated);
      setReturnQuantities({});
      setReason('');
      alert(t('returnSuccess'));
    });
  };

  const returnAll = async () => {
    if (!confirm(t('confirmReturnAll'))) return;
    await runMutation(async () => {
      const updated = await api.returnOrder(order.id, {
        full: true,
        reason: reason.trim() || undefined
      });
      setOrder(updated);
      setReturnQuantities({});
      setReason('');
      alert(t('returnSuccess'));
    });
  };

  const exportReceipt = async (target: Order = order) => {
    if (!template) return alert(t('noTemplate'));
    setExporting(true);
    try {
      const { exportOrderToExcel, downloadBlob } = await import('../receipt-excel');
      const result = await exportOrderToExcel(target, template, value => calendarDate(value), t('orderPricingUnavailable'));
      downloadBlob(result.blob, `receipt-${target.id}.xlsx`);
      alert(t('exportSuccess'));
    } catch (error) {
      alert(t('exportFailed', { error: (error as Error).message }));
    } finally {
      setExporting(false);
    }
  };

  const removeLine = async (lineId: number) => {
    if (!confirm(t('confirmRemoveOrderItem'))) return;
    await runMutation(async () => {
      setOrder(await api.removeOrderItem(order.id, lineId));
    });
  };

  const confirmDraft = async (withExport: boolean) => {
    if (withExport && !template) return alert(t('noTemplate'));
    if (!confirm(t('confirmDraft'))) return;
    await runMutation(async () => {
      const updated = await api.confirmOrder(order.id);
      setOrder(updated);
      if (withExport) await exportReceipt(updated);
      else alert(t('orderConfirmed'));
    });
  };

  const cancelDraft = async () => {
    if (!confirm(t('confirmCancelDraft'))) return;
    await runMutation(async () => {
      setOrder(await api.cancelOrder(order.id));
      alert(t('orderCancelled'));
    });
  };

  const updatePickup = async () => {
    const pickedUp = !order.picked_up;
    const confirmationKey = orderStatus === 'DRAFT'
      ? 'confirmPickUpDraft'
      : pickedUp ? 'confirmMarkPickedUp' : 'confirmMarkNotPickedUp';
    if (!confirm(t(confirmationKey))) return;
    await runMutation(async () => {
      setOrder(await api.updateOrderPickup(order.id, pickedUp));
      alert(t(orderStatus === 'DRAFT' ? 'pickupDraftUpdated' : 'pickupUpdated'));
    });
  };

  return (
    <>
      <Link className="btn btn-outline-secondary mb-3"
        to={order.archived_at ? '/orders/archived' : orderStatus === 'CANCELLED' ? '/orders/cancelled' : '/orders'}>{t('back')}</Link>
      <h1 className="h2">{t('orders')} #{order.id}</h1>
      {order.archived_at && <div className="alert alert-secondary">
        <strong>{t('archived')}</strong> — {t('archivedAt')}: {date(order.archived_at)};{' '}
        {t('archivedBy')}: {order.archived_by || t('none')}
      </div>}
      {(orderStatus !== 'DRAFT' || !canEditOrders) && <p><strong>{t('text')}:</strong> {order.text}</p>}
      <p><strong>{t('status')}:</strong> {t(orderStatus === 'DRAFT' ? 'draft' : orderStatus === 'CANCELLED' ? 'cancelled' : 'confirmed')}</p>
      <p><strong>{t('pickupStatus')}:</strong> {t(order.picked_up ? 'pickedUp' : 'notPickedUp')}
        {order.picked_up_at && <> — {t('pickedUpAt')}: {date(order.picked_up_at)}</>}
      </p>
      {canEditOrders && (orderStatus === 'DRAFT' || orderStatus === 'CONFIRMED') && !order.archived_at &&
        <button className={`btn mb-3 ${order.picked_up ? 'btn-outline-warning' : 'btn-outline-success'}`}
          disabled={mutating} onClick={() => void updatePickup()}>
          {t(order.picked_up ? 'markNotPickedUp' : 'markPickedUp')}
        </button>}
      <p><strong>{t('orderDate')}:</strong> {calendarDate(order.order_date)}</p>
      <p><strong>{t('createdAt')}:</strong> {date(order.created_at)}</p>
      {orderStatus === 'CANCELLED' && <div className="alert alert-secondary">
        <strong>{t('cancelled')}</strong> — {t('cancelledAt')}: {date(order.cancelled_at)};{' '}
        {t('cancelledBy')}: {order.cancelled_by || t('none')}
      </div>}
      {pricesVisible && orderStatus !== 'CANCELLED' && (orderStatus === 'CONFIRMED' || canEditOrders) && <div className="card card-body mb-4">
        <div className="row g-2 align-items-center">
          <div className="col-md-5"><select aria-label={t('receiptTemplate')} className="form-select" value={template} onChange={event => {
            setTemplate(event.target.value);
            localStorage.setItem(TEMPLATE_KEY, event.target.value);
          }}>
            {templates.map(entry => <option key={entry.id} value={entry.id}>{entry.name}</option>)}
          </select></div>
          <div className="col-md-7">
            {orderStatus === 'CONFIRMED' ? <button className="btn btn-success" disabled={exporting || !templates.length}
              onClick={() => void exportReceipt()}>{t('exportReceipt')}</button> : <div className="d-flex gap-2 flex-wrap">
              <button className="btn btn-primary" disabled={mutating} onClick={() => void confirmDraft(false)}>{t('confirmOrder')}</button>
              <button className="btn btn-success" disabled={mutating || !templates.length} onClick={() => void confirmDraft(true)}>{t('confirmExport')}</button>
              <button className="btn btn-outline-danger" disabled={mutating} onClick={() => void cancelDraft()}>{t('cancelDraft')}</button>
            </div>}
          </div>
        </div>
      </div>}
      {canEditOrders && orderStatus === 'DRAFT' && <DraftTextEditor order={order} onUpdate={setOrder} busy={mutating} runMutation={runMutation} />}
      {canEditOrders && orderStatus === 'DRAFT' && <DraftDateEditor order={order} onUpdate={setOrder} busy={mutating} runMutation={runMutation} />}
      {draftItemsEditable && orderStatus === 'DRAFT' && <DraftItemsEditor order={order} onUpdate={setOrder} busy={mutating} runMutation={runMutation} />}
      <div className="row g-2 mb-4">
        {pricesVisible && <><div className="col-md-3"><strong>{t('originalCost')}:</strong> {profit(order.total_cost)}</div>
        <div className="col-md-3"><strong>{t('returnedCost')}:</strong> {profit(order.returned_cost)}</div>
        <div className="col-md-3"><strong>{t('netCost')}:</strong> {profit(order.net_cost)}</div></>}
        <div className="col-md-3"><strong>{t('returnStatus')}:</strong> {t(returnStatusKey(order.return_status))}</div>
      </div>
      {pricesVisible && <div className="row g-2 mb-4">
        <div className="col-md-4"><strong>{t('originalProfit')}:</strong> {profit(order.total_profit)}</div>
        <div className="col-md-4"><strong>{t('returnedProfit')}:</strong> {profit(order.returned_profit)}</div>
        <div className="col-md-4"><strong>{t('netProfit')}:</strong> {profit(order.net_profit)}</div>
      </div>}
      <h2 className="h4 mt-4">{t('orderItems')}</h2>
      <div className="table-responsive"><table ref={returnInputs} className="table app-table">
        <thead><tr><th>{t('item')}</th><th>{t('barcode')}</th><th>{t('soldQuantity')}</th>
          <th>{t('returnedQuantity')}</th><th>{t('remainingQuantity')}</th>
          {pricesVisible && <><th>{t('cost')}</th><th>{t('profit')}</th><th>{t('costSubtotal')}</th>
            <th>{t('profitSubtotal')}</th></>}{canEditOrders && orderStatus === 'CONFIRMED' && <th>{t('returnQuantity')}</th>}
          {draftItemsEditable && orderStatus === 'DRAFT' && <th>{t('actions')}</th>}</tr></thead>
        <tbody>{order.items?.map(line => <tr key={line.id}>
          <td>{line.title}</td><td>{line.barcode || t('none')}</td><td>{line.quantity}</td>
          <td>{line.returned_quantity}</td><td>{line.remaining_quantity}</td>
          {pricesVisible && <><td>{profit(line.cost)}</td><td>{profit(line.profit)}</td>
            <td>{profit(line.cost_subtotal)}</td><td>{profit(line.profit_subtotal)}</td></>}
          {canEditOrders && orderStatus === 'CONFIRMED' && <td><input aria-label={`${t('returnQuantity')} ${line.title}`} type="number"
            className="form-control form-control-sm" min="0" max={line.remaining_quantity} step="1"
            disabled={mutating || line.remaining_quantity === 0}
            placeholder="0" value={returnQuantities[line.id] ?? ''}
            onChange={event => setReturnQuantities(current => ({
              ...current, [line.id]: event.target.value
            }))} /></td>}
          {draftItemsEditable && orderStatus === 'DRAFT' && <td><button className="btn btn-sm btn-outline-danger"
            disabled={mutating || (order.items?.length || 0) <= 1}
            onClick={() => void removeLine(line.id)}>{t('remove')}</button></td>}
        </tr>)}</tbody>
      </table></div>
      {draftItemsEditable && orderStatus === 'DRAFT' && (order.items?.length || 0) <= 1 && <p className="small text-muted">{t('lastDraftItemHint')}</p>}
      {canEditOrders && orderStatus === 'CONFIRMED' && order.return_status !== 'FULLY_RETURNED' && (
        <div className="card card-body mb-4">
          <label className="form-label">{t('returnReason')}
            <input className="form-control mt-1" disabled={mutating} maxLength={500} value={reason}
              placeholder={t('returnReasonPlaceholder')}
              onChange={event => setReason(event.target.value)} />
          </label>
          <div className="d-flex gap-2 flex-wrap">
            <button className="btn btn-warning" disabled={mutating}
              onClick={() => void returnSelected()}>{t('processReturn')}</button>
            <button className="btn btn-outline-danger" disabled={mutating}
              onClick={() => void returnAll()}>{t('returnAll')}</button>
          </div>
        </div>
      )}
      {!!order.removed_items?.length && <>
        <h2 className="h4">{t('removedItems')}</h2>
        <div className="table-responsive"><table className="table table-sm app-table">
          <thead><tr><th>{t('item')}</th><th>{t('barcode')}</th><th>{t('quantity')}</th>{pricesVisible && <th>{t('subtotal')}</th>}
            <th>{t('removedAt')}</th><th>{t('removedBy')}</th></tr></thead>
          <tbody>{order.removed_items.map(line => <tr key={line.id}><td>{line.title}</td>
            <td>{line.barcode || t('none')}</td><td>{line.quantity}</td>{pricesVisible && <td>{profit(line.subtotal)}</td>}
            <td>{date(line.removed_at)}</td><td>{line.removed_by || t('none')}</td></tr>)}</tbody>
        </table></div>
      </>}
      {orderStatus === 'CONFIRMED' && <><h2 className="h4">{t('returnHistory')}</h2>
      {!order.returns?.length ? <p>{t('noReturns')}</p> : order.returns.map(orderReturn => (
        <div className="card card-body mb-3" key={orderReturn.id}>
          <div><strong>{t('date')}:</strong> {date(orderReturn.created_at)}</div>
          <div><strong>{t('returnedBy')}:</strong> {orderReturn.created_by}</div>
          <div><strong>{t('returnReason')}:</strong> {orderReturn.reason || t('none')}</div>
          <div><strong>{t('returnedQuantity')}:</strong> {orderReturn.total_quantity}</div>
          {pricesVisible && <div><strong>{t('refundAmount')}:</strong> {profit(orderReturn.total_amount)}</div>}
          <ul className="mb-0 mt-2">{orderReturn.items.map(line => (
            <li key={line.order_item_id}>{line.title} × {line.quantity}{pricesVisible && <> — {profit(line.refund_amount)}</>}</li>
          ))}</ul>
        </div>
      ))}</>}
    </>
  );
}
