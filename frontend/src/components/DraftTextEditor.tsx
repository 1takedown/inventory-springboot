import { useEffect, useState } from 'react';
import { api } from '../api';
import { useI18n } from '../i18n';
import type { DraftEditorProps } from '../types';

export function DraftTextEditor({ order, onUpdate, busy, runMutation }: DraftEditorProps) {
  const { t } = useI18n();
  const [text, setText] = useState(order.text);
  const trimmedText = text.trim();

  useEffect(() => { setText(order.text); }, [order.id, order.text]);

  const save = async () => {
    if (!trimmedText || trimmedText === order.text || busy) return;
    await runMutation(async () => {
      onUpdate(await api.updateOrderText(order.id, trimmedText));
      alert(t('textUpdated'));
    });
  };

  return <div className="card card-body mb-4">
    <label className="form-label" htmlFor="draft-order-text">{t('text')}</label>
    <div className="row g-2">
      <div className="col-md-8">
        <textarea id="draft-order-text" className="form-control" required disabled={busy}
          value={text} onChange={event => setText(event.target.value)} />
      </div>
      <div className="col-md-auto d-grid align-self-start">
        <button className="btn btn-primary" disabled={busy || !trimmedText || trimmedText === order.text}
          onClick={() => void save()}>{t('save')}</button>
      </div>
    </div>
  </div>;
}
