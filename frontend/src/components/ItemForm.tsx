import { useState, type FormEvent } from 'react';
import { useI18n } from '../i18n';
import type { ItemInput } from '../types';
import { hasMoneyPrecision } from '../utils/item';

export function ItemForm({ initial, onSave, onCancel }: {
  initial: ItemInput;
  onSave: (input: ItemInput) => Promise<void>;
  onCancel: () => void;
}) {
  const { t } = useI18n();
  const [title, setTitle] = useState(initial.title);
  const [description, setDescription] = useState(initial.description);
  const [barcode, setBarcode] = useState(initial.barcode || '');
  const [cost, setCost] = useState(initial.cost === 0 ? '' : String(initial.cost));
  const [profit, setProfit] = useState(initial.profit === 0 ? '' : String(initial.profit));
  const [quantity, setQuantity] = useState(initial.quantity === 0 ? '' : String(initial.quantity));
  const [busy, setBusy] = useState(false);
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const parsedCost = Number(cost);
    const parsedProfit = Number(profit);
    const parsedQuantity = Number(quantity);
    if (!title.trim()
      || !Number.isFinite(parsedCost) || parsedCost < 0 || !hasMoneyPrecision(parsedCost)
      || !Number.isFinite(parsedProfit) || parsedProfit < 0 || !hasMoneyPrecision(parsedProfit)
      || !Number.isInteger(parsedQuantity) || parsedQuantity < 0) {
      return alert(t('itemRequired'));
    }
    setBusy(true);
    try {
      await onSave({
        title: title.trim(),
        description,
        barcode: barcode || null,
        cost: parsedCost,
        profit: parsedProfit,
        quantity: parsedQuantity
      });
    } finally { setBusy(false); }
  };
  return (
    <form className="card card-body mb-4" onSubmit={submit}>
      <div className="row g-3">
        <div className="col-md-6"><input aria-label={t('titleField')} className="form-control" placeholder={t('titleField')} value={title} onChange={e => setTitle(e.target.value)} /></div>
        <div className="col-md-6"><input aria-label={t('barcode')} className="form-control" placeholder={t('barcode')} value={barcode} onChange={e => setBarcode(e.target.value)} /></div>
        <div className="col-12"><textarea aria-label={t('description')} className="form-control" placeholder={t('description')} value={description} onChange={e => setDescription(e.target.value)} /></div>
        <div className="col-md-4">
          <label className="form-label" htmlFor="item-cost">{t('cost')}</label>
          <input id="item-cost" aria-label={t('cost')} type="number" min="0" step="0.01" className="form-control" placeholder="0" value={cost} onChange={e => setCost(e.target.value)} />
        </div>
        <div className="col-md-4">
          <label className="form-label" htmlFor="item-profit">{t('profit')}</label>
          <input id="item-profit" aria-label={t('profit')} type="number" min="0" step="0.01" className="form-control" placeholder="0" value={profit} onChange={e => setProfit(e.target.value)} />
        </div>
        <div className="col-md-4">
          <label className="form-label" htmlFor="item-quantity">{t('quantity')}</label>
          <input id="item-quantity" aria-label={t('quantity')} type="number" min="0" step="1" className="form-control" placeholder="0" value={quantity} onChange={e => setQuantity(e.target.value)} />
        </div>
      </div>
      <div className="mt-3 d-flex gap-2">
        <button className="btn btn-success" disabled={busy}>{t('save')}</button>
        <button type="button" className="btn btn-secondary" onClick={onCancel}>{t('cancel')}</button>
      </div>
    </form>
  );
}
