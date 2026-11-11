import { Fragment, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import { SessionExpiredError, api } from '../api';
import { canViewPrices, useAuth } from '../auth';
import { useI18n } from '../i18n';
import type {
  DashboardItem, DashboardMetrics, DashboardRelatedOrdersResponse, DashboardResponse
} from '../types';
import { Loading } from '../components/Loading';

type DashboardDisplayMetrics = Omit<DashboardMetrics, 'net_amount'>;

function MetricCards({
  metrics,
  financial,
  pricesVisible,
  t
}: {
  metrics: DashboardDisplayMetrics;
  financial: (value: number | null | undefined) => string;
  pricesVisible: boolean;
  t: (key: 'grossOut' | 'restored' | 'netOut' | 'netCost' | 'netProfit') => string;
}) {
  const cards: [string, string | number][] = [
    [t('grossOut'), metrics.gross_quantity],
    [t('restored'), metrics.restored_quantity],
    [t('netOut'), metrics.net_quantity],
    ...(pricesVisible ? [
      [t('netCost'), financial(metrics.net_cost)],
      [t('netProfit'), financial(metrics.net_profit)]
    ] as [string, string][] : [])
  ];
  return (
    <div className="row g-2 g-lg-3">
      {cards.map(([label, value]) => (
        <div className="col-6 col-lg-4" key={label}>
          <div className="card h-100 dashboard-card">
            <div className="card-body py-2">
              <div className="small text-muted">{label}</div>
              <div className="h5 mb-0 numeric">{value}</div>
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}

function DailyItemsPane({
  headingId,
  title,
  items,
  financial,
  pricesVisible,
  expandedKey,
  relatedOrders,
  relatedBusy,
  relatedError,
  onToggle,
  keyPrefix,
  metrics,
  emptyMessage,
  open,
  onPaneToggle,
  t
}: {
  headingId: string;
  title: string;
  items: DashboardItem[];
  financial: (value: number | null | undefined) => string;
  pricesVisible: boolean;
  expandedKey: string | null;
  relatedOrders: DashboardRelatedOrdersResponse | null;
  relatedBusy: boolean;
  relatedError: string | null;
  onToggle: (key: string, item: DashboardItem) => void;
  keyPrefix: string;
  metrics: DashboardDisplayMetrics;
  emptyMessage: string;
  open: boolean;
  onPaneToggle: () => void;
  t: ReturnType<typeof useI18n>['t'];
}) {
  const contentId = `${headingId}-content`;
  return (
    <section className="border rounded p-3 h-100" aria-labelledby={headingId}>
      <h3 className="h5 mb-0" id={headingId}>
        <button
          type="button"
          className="btn btn-link p-0 text-start fw-bold text-decoration-none w-100"
          aria-expanded={open}
          aria-controls={contentId}
          onClick={onPaneToggle}
        ><span aria-hidden="true" className="me-2">{open ? '▾' : '▸'}</span>{title}</button>
      </h3>
      {open && <div id={contentId} className="mt-3">
        {metrics && <div className={items.length > 0 ? 'mb-3' : undefined}>
          <MetricCards metrics={metrics} financial={financial} pricesVisible={pricesVisible} t={t} />
        </div>}
        {items.length === 0 ? <p className={`text-muted ${metrics ? 'mt-3' : ''} mb-0`}>{emptyMessage}</p>
          : <div className="table-responsive">
          <table className="table table-striped align-middle app-table mb-0">
            <thead>
              <tr>
                <th>{t('item')}</th>
                <th>{t('grossOut')}</th>
                <th>{t('restored')}</th>
                <th>{t('netOut')}</th>
                {pricesVisible && <><th>{t('netCost')}</th><th>{t('netProfit')}</th></>}
              </tr>
            </thead>
            <tbody>
              {items.map(item => {
                const key = `${keyPrefix}-${item.item_id}`;
                const expanded = expandedKey === key;
                const detailsId = `dashboard-related-${key}`;
                return <Fragment key={key}>
                  <tr>
                    <td><button
                      type="button"
                      className="btn btn-link p-0 text-start fw-bold"
                      aria-expanded={expanded}
                      aria-controls={detailsId}
                      onClick={() => onToggle(key, item)}
                    ><span aria-hidden="true" className="me-1">{expanded ? '▾' : '▸'}</span>{item.title}</button></td>
                    <td className="numeric">{item.gross_quantity}</td>
                    <td className="numeric">{item.restored_quantity}</td>
                    <td className="numeric">{item.net_quantity}</td>
                    {pricesVisible && <><td className="numeric">{financial(item.net_cost)}</td>
                      <td className="numeric">{financial(item.net_profit)}</td></>}
                  </tr>
                  {expanded && <tr id={detailsId}>
                    <td colSpan={pricesVisible ? 6 : 4} className="p-3 bg-body-tertiary">
                      <h4 className="h6 mb-3">{t('relatedOrders')}: {item.title}</h4>
                      {relatedBusy ? <p className="text-muted mb-0">{t('loading')}</p>
                        : relatedError ? <div className="alert alert-danger mb-0" role="alert">{relatedError}</div>
                          : !relatedOrders || relatedOrders.orders.length === 0
                            ? <p className="text-muted mb-0">{t('emptyRelatedOrders')}</p>
                            : <div className="table-responsive"><table className="table table-sm align-middle mb-0">
                              <thead><tr>
                                <th>{t('orderId')}</th><th>{t('text')}</th><th>{t('status')}</th>
                                <th>{t('grossOut')}</th><th>{t('restored')}</th>
                                <th>{t('netOut')}</th><th>{t('actions')}</th>
                              </tr></thead>
                              <tbody>{relatedOrders.orders.map(order => <tr key={order.id}>
                                <td>{order.id}</td><td>{order.text}</td>
                                <td>{t(order.archived_at ? 'archived' : order.status === 'DRAFT'
                                  ? 'draft' : order.status === 'CANCELLED' ? 'cancelled' : 'confirmed')}</td>
                                <td className="numeric">{order.gross_quantity}</td>
                                <td className="numeric">{order.restored_quantity}</td>
                                <td className="numeric">{order.net_quantity}</td>
                                <td><Link className="btn btn-sm btn-outline-primary" to={`/orders/${order.id}`}>{t('view')}</Link></td>
                              </tr>)}</tbody>
                            </table></div>}
                    </td>
                  </tr>}
                </Fragment>;
              })}
            </tbody>
          </table>
        </div>}
      </div>}
    </section>
  );
}

export function DashboardPage() {
  const { t, money } = useI18n();
  const pricesVisible = canViewPrices(useAuth().user);
  const [dashboard, setDashboard] = useState<DashboardResponse | null>(null);
  const [selectedDate, setSelectedDate] = useState('');
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [expandedKey, setExpandedKey] = useState<string | null>(null);
  const [relatedOrders, setRelatedOrders] = useState<DashboardRelatedOrdersResponse | null>(null);
  const [relatedBusy, setRelatedBusy] = useState(false);
  const [relatedError, setRelatedError] = useState<string | null>(null);
  const [totalOpen, setTotalOpen] = useState(false);
  const relatedRequest = useRef(0);
  const clearRelatedOrders = () => {
    relatedRequest.current += 1;
    setExpandedKey(null);
    setRelatedOrders(null);
    setRelatedBusy(false);
    setRelatedError(null);
  };
  const load = async (date?: string) => {
    clearRelatedOrders();
    setBusy(true);
    setError(null);
    try {
      const response = await api.dailyOutItems(date);
      setDashboard(response);
      setSelectedDate(response.date);
    } catch (loadError) {
      if (!(loadError instanceof SessionExpiredError)) setError((loadError as Error).message);
    } finally {
      setBusy(false);
    }
  };
  useEffect(() => { void load(); }, []);
  const financial = (value: number | null | undefined) =>
    value == null ? t('unknown') : money(value);
  const selectDate = (date: string) => {
    if (!date) return;
    setSelectedDate(date);
    setDashboard(null);
    void load(date);
  };
  const toggleRelatedOrders = async (
    key: string,
    item: DashboardItem
  ) => {
    if (expandedKey === key) {
      clearRelatedOrders();
      return;
    }
    const requestId = ++relatedRequest.current;
    setExpandedKey(key);
    setRelatedOrders(null);
    setRelatedError(null);
    setRelatedBusy(true);
    try {
      const response = await api.dailyOutItemOrders(
        item.item_id,
        selectedDate || undefined
      );
      if (relatedRequest.current === requestId) setRelatedOrders(response);
    } catch (loadError) {
      if (relatedRequest.current === requestId && !(loadError instanceof SessionExpiredError)) {
        setRelatedError((loadError as Error).message);
      }
    } finally {
      if (relatedRequest.current === requestId) setRelatedBusy(false);
    }
  };
  const toggleTotal = () => {
    if (totalOpen && expandedKey?.startsWith('total-')) clearRelatedOrders();
    setTotalOpen(open => !open);
  };
  return (
    <>
      <div className="d-flex flex-column flex-sm-row justify-content-between align-items-sm-start gap-3 mb-3">
        <div>
          <h1 className="h2 mb-1">{t('dashboard')}</h1>
          {dashboard && <p className="text-muted mb-0">{dashboard.date} · {dashboard.time_zone}</p>}
        </div>
        <div className="d-flex align-items-end gap-2">
          <label className="form-label mb-0">
            <span className="d-block small text-muted mb-1">{t('dashboardDate')}</span>
            <input
              className="form-control"
              type="date"
              aria-label={t('dashboardDate')}
              value={selectedDate}
              disabled={busy}
              onChange={event => selectDate(event.target.value)}
            />
          </label>
          <button
            className="btn btn-outline-primary"
            disabled={busy}
            onClick={() => void load(selectedDate || undefined)}
          >
            {busy ? t('loading') : t('refresh')}
          </button>
        </div>
      </div>
      {error && <div className="alert alert-danger" role="alert">{error}</div>}
      {!dashboard ? busy ? <Loading /> : null : <>
        <h2 className="h4 mb-1">{t('dailyOutItems')}</h2>
        <p className="text-muted">{t('dailyOutItemsHelp')}</p>
        <div className="mb-4">
          <DailyItemsPane
            headingId="dashboard-total"
            title={t('total')}
            items={dashboard.items}
            metrics={dashboard.totals}
            emptyMessage={t('emptyDailyOutItems')}
            financial={financial}
            pricesVisible={pricesVisible}
            expandedKey={expandedKey}
            relatedOrders={relatedOrders}
            relatedBusy={relatedBusy}
            relatedError={relatedError}
            onToggle={(key, item) => void toggleRelatedOrders(key, item)}
            keyPrefix="total"
            open={totalOpen}
            onPaneToggle={toggleTotal}
            t={t}
          />
        </div>
      </>}
    </>
  );
}
