import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { api } from '../api';
import { useI18n } from '../i18n';
import { stockMovementReasons, type StockMovementPage } from '../types';
import { Loading } from '../components/Loading';
import { notifyError } from '../utils/notifyError';
import { BUSINESS_TIME_ZONE, isValidCalendarDate } from '../utils/businessDate';

export function StockHistoryPage() {
  const { t, date, calendarDate } = useI18n();
  const [params, setParams] = useSearchParams();
  const [search, setSearch] = useState(params.get('q') || '');
  const [result, setResult] = useState<StockMovementPage | null>(null);
  const [failed, setFailed] = useState(false);
  const [retry, setRetry] = useState(0);
  const query = params.toString();
  const itemId = params.get('itemId');
  const appliedSearch = params.get('q') || '';
  const rawDate = params.get('date');
  const selectedDate = isValidCalendarDate(rawDate) ? rawDate : '';
  const busy = !result && !failed;

  useEffect(() => {
    if (!params.has('date') || isValidCalendarDate(params.get('date'))) return;
    const next = new URLSearchParams(params);
    next.delete('date');
    next.delete('page');
    setParams(next, { replace: true });
  }, [params, setParams]);

  useEffect(() => { setSearch(appliedSearch); }, [appliedSearch]);

  useEffect(() => {
    let active = true;
    setResult(null);
    setFailed(false);
    const request = new URLSearchParams(query);
    if (request.has('date') && !isValidCalendarDate(request.get('date'))) return;
    request.set('size', '50');
    void api.stockMovements(request).then(value => {
      if (active) setResult(value);
    }).catch(error => {
      if (active) { setFailed(true); notifyError(error); }
    });
    return () => { active = false; };
  }, [query, retry]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      if (search.trim() === (params.get('q') || '')) return;
      const next = new URLSearchParams(params);
      if (search.trim()) next.set('q', search.trim());
      else next.delete('q');
      next.delete('page');
      setParams(next, { replace: true });
    }, 300);
    return () => window.clearTimeout(timer);
  }, [search, params, setParams]);

  function filter(key: string, value: string) {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    if (key !== 'page') next.delete('page');
    setParams(next);
  }

  return <>
    <div className="d-flex flex-column flex-sm-row justify-content-between align-items-sm-start gap-3 mb-3">
      <div>
        <h1 className="h2 mb-1">{t('stockHistory')}</h1>
        <p className="text-muted mb-0">{selectedDate ? calendarDate(selectedDate) : t('allDates')} · {BUSINESS_TIME_ZONE}</p>
      </div>
      <div className="d-flex align-items-end gap-2">
        <label className="form-label mb-0">
          <span className="d-block small text-muted mb-1">{t('stockMovementDate')}</span>
          <input type="date" className="form-control" aria-label={t('stockMovementDate')}
            value={selectedDate} disabled={busy}
            onChange={event => { if (!event.target.value || isValidCalendarDate(event.target.value)) filter('date', event.target.value); }} />
        </label>
        <button className="btn btn-outline-primary" disabled={busy}
          onClick={() => setRetry(value => value + 1)}>{busy ? t('loading') : t('refresh')}</button>
      </div>
    </div>
    <p className="text-muted">{t('stockHistoryHelp')}</p>
    <div className="d-flex gap-2 mb-3 flex-wrap">
      <input type="search" className="form-control" aria-label={t('searchItems')}
        placeholder={t('searchItemsPlaceholder')} value={search} onChange={event => setSearch(event.target.value)} />
      <select className="form-select" aria-label={t('movementReason')} value={params.get('reason') || ''}
        onChange={event => filter('reason', event.target.value)}>
        <option value="">{t('allReasons')}</option>
        {stockMovementReasons.map(reason => <option key={reason} value={reason}>{t(reason)}</option>)}
      </select>
      {itemId && <button className="btn btn-outline-secondary" onClick={() => filter('itemId', '')}>
        {t('item')} #{itemId} — {t('clearItemFilter')}
      </button>}
    </div>
    {failed ? null
      : !result ? <Loading /> : result.items.length === 0 ? <p>{t('emptyStockHistory')}</p>
      : <div className="table-responsive"><table className="table table-striped align-middle app-table">
        <thead><tr><th>{t('item')}</th><th>{t('stockChange')}</th><th>{t('stockBefore')}</th>
          <th>{t('stockAfter')}</th><th>{t('movementReason')}</th><th>{t('stockActor')}</th>
          <th>{t('createdAt')}</th><th>{t('orderText')}</th></tr></thead>
        <tbody>{result.items.map(movement => <tr key={movement.id}>
          <td><Link to={`/stock-history?${new URLSearchParams({ ...Object.fromEntries(params), itemId: String(movement.item_id_snapshot), page: '0' })}`}>{movement.title_snapshot}</Link>
            <div className="small text-muted">#{movement.item_id_snapshot} {movement.barcode_snapshot}
              {movement.item_id == null && <> · {t('deletedStockItem')}</>}</div></td>
          <td className={movement.delta < 0 ? 'text-danger' : movement.delta > 0 ? 'text-success' : ''}>
            {movement.delta > 0 ? '+' : ''}{movement.delta}</td>
          <td>{movement.quantity_before}</td><td>{movement.quantity_after}</td><td>{t(movement.reason)}</td>
          <td>{movement.created_by}</td><td>{date(movement.created_at)}</td>
          <td>{movement.order_id ? <Link to={`/orders/${movement.order_id}`}>{movement.order_text}</Link> : t('none')}</td>
        </tr>)}</tbody>
      </table></div>}
    {result && result.totalPages > 1 && <div className="d-flex justify-content-between align-items-center">
      <button className="btn btn-outline-secondary" disabled={result.page === 0}
        onClick={() => filter('page', String(result.page - 1))}>{t('previous')}</button>
      <span>{t('pageOf', { page: result.page + 1, total: result.totalPages })}</span>
      <button className="btn btn-outline-secondary" disabled={result.page + 1 >= result.totalPages}
        onClick={() => filter('page', String(result.page + 1))}>{t('next')}</button>
    </div>}
  </>;
}
