import { useEffect, useState } from 'react';
import { isValidCalendarDate } from '../utils/businessDate';
import { api } from '../api';
import { useI18n } from '../i18n';
import type { DraftEditorProps } from '../types';

export function DraftDateEditor({ order, onUpdate, busy, runMutation }: DraftEditorProps) {
  const { t } = useI18n();
  const [orderDate, setOrderDate] = useState(order.order_date);

  useEffect(() => { setOrderDate(order.order_date); }, [order.id, order.order_date]);

  const save = async () => {
    if (!isValidCalendarDate(orderDate) || orderDate === order.order_date || busy) return;
    await runMutation(async () => {
      onUpdate(await api.updateOrderDate(order.id, orderDate));
      alert(t('orderDateUpdated'));
    });
  };

  return <div className="card card-body mb-4">
    <label className="form-label" htmlFor="draft-order-date">{t('orderDate')}</label>
    <div className="row g-2">
      <div className="col-md-5">
        <input id="draft-order-date" className="form-control" type="date" required
          disabled={busy} value={orderDate} onChange={event => setOrderDate(event.target.value)} />
      </div>
      <div className="col-md-auto d-grid">
        <button className="btn btn-primary" disabled={busy || !isValidCalendarDate(orderDate) || orderDate === order.order_date}
          onClick={() => void save()}>{t('save')}</button>
      </div>
    </div>
  </div>;
}
