import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import { api } from '../api';
import { canViewAllOrders, canViewPrices, useAuth } from '../auth';
import { useI18n } from '../i18n';
import type { Order } from '../types';
import { Loading } from '../components/Loading';
import { notifyError } from '../utils/notifyError';
import { returnStatusKey } from '../utils/returnStatus';

export function OrdersPage({ archived = false, cancelled = false }: { archived?: boolean; cancelled?: boolean }) {
  const { user } = useAuth();
  const pricesVisible = canViewPrices(user);
  const { t, money, date, calendarDate } = useI18n();
  const profit = (value: number | null | undefined) =>
    value == null ? t('unknown') : money(value);
  const [orders, setOrders] = useState<Order[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [search, setSearch] = useState('');
  const [appliedSearch, setAppliedSearch] = useState('');
  const [archivingOrderId, setArchivingOrderId] = useState<number | null>(null);
  const [pickupOrderId, setPickupOrderId] = useState<number | null>(null);
  const requestVersion = useRef(0);
  const load = async () => {
    const version = ++requestVersion.current;
    setOrders(null);
    setFailed(false);
    try {
      const result = archived
        ? await api.archivedOrdersPage(page, 50, appliedSearch)
        : cancelled
          ? await api.cancelledOrdersPage(page, 50, appliedSearch)
          : await api.ordersPage(page, 50, appliedSearch);
      if (version !== requestVersion.current) return;
      setOrders(result.items);
      setTotalPages(result.totalPages);
    } catch (error) {
      if (version === requestVersion.current) {
        setFailed(true);
        notifyError(error);
      }
    }
  };
  useEffect(() => { void load(); }, [page, archived, cancelled, appliedSearch]);
  useEffect(() => {
    const timer = window.setTimeout(() => {
      setPage(0);
      setAppliedSearch(search.trim());
    }, 300);
    return () => window.clearTimeout(timer);
  }, [search]);
  const archiveOrder = async (orderId: number) => {
    if (!confirm(t('confirmArchive'))) return;
    setArchivingOrderId(orderId);
    try {
      await api.archiveOrder(orderId);
      alert(t('archiveSuccess'));
      if (orders?.length === 1 && page > 0) setPage(current => current - 1);
      else await load();
    } catch (error) {
      notifyError(error);
    } finally {
      setArchivingOrderId(null);
    }
  };
  const updatePickup = async (order: Order) => {
    const pickedUp = !order.picked_up;
    const confirmationKey = order.status === 'DRAFT'
      ? 'confirmPickUpDraft'
      : pickedUp ? 'confirmMarkPickedUp' : 'confirmMarkNotPickedUp';
    if (!confirm(t(confirmationKey))) return;
    setPickupOrderId(order.id);
    try {
      const updated = await api.updateOrderPickup(order.id, pickedUp);
      setOrders(current => current?.map(entry => entry.id === order.id ? updated : entry) || current);
      alert(t(order.status === 'DRAFT' ? 'pickupDraftUpdated' : 'pickupUpdated'));
    } catch (error) {
      notifyError(error);
    } finally {
      setPickupOrderId(null);
    }
  };
  return (
    <>
      <h1 className="h2 mb-3">{archived ? t('archivedOrders') : cancelled ? t('cancelledOrders') : t('orders')}</h1>
      {canViewAllOrders(user) && <div className="nav nav-tabs mb-3">
        <Link className={`nav-link${!archived && !cancelled ? ' active' : ''}`} to="/orders">{t('activeOrders')}</Link>
        <Link className={`nav-link${archived ? ' active' : ''}`} to="/orders/archived">{t('archivedOrders')}</Link>
        <Link className={`nav-link${cancelled ? ' active' : ''}`} to="/orders/cancelled">{t('cancelledOrders')}</Link>
      </div>}
      <input className="form-control mb-3" type="search" aria-label={t('searchOrders')}
        placeholder={t('searchOrdersPlaceholder')} value={search}
        onChange={event => setSearch(event.target.value)} />
      {failed ? <div role="alert" className="alert alert-danger">
        <p>{t('ordersLoadFailed')}</p>
        <button className="btn btn-outline-primary" onClick={() => void load()}>{t('refresh')}</button>
      </div> : !orders ? <Loading /> : orders.length === 0
        ? <p>{t(appliedSearch
          ? 'emptyOrderSearch'
          : archived ? 'emptyArchivedOrders' : cancelled ? 'emptyCancelledOrders' : 'emptyOrders')}</p> : (
        <div className="table-responsive"><table className="table table-striped app-table">
          <thead><tr><th>{t('orderId')}</th><th>{t('text')}</th><th>{t('status')}</th><th>{t('quantity')}</th>
            {pricesVisible && <><th>{t('originalCost')}</th><th>{t('returnedCost')}</th><th>{t('netCost')}</th>
              <th>{t('originalProfit')}</th><th>{t('returnedProfit')}</th><th>{t('netProfit')}</th></>}
            <th>{t('returnStatus')}</th><th>{t('pickupStatus')}</th><th>{t('orderDate')}</th><th>{t('createdAt')}</th>
            {archived && <><th>{t('archivedAt')}</th><th>{t('archivedBy')}</th></>}
            {cancelled && <><th>{t('cancelledAt')}</th><th>{t('cancelledBy')}</th></>}
            <th>{t('actions')}</th></tr></thead>
          <tbody>{orders.map(order => <tr key={order.id}>
            <td>{order.id}</td><td>{order.text}</td>
            <td>{t(order.status === 'DRAFT' ? 'draft' : order.status === 'CANCELLED' ? 'cancelled' : 'confirmed')}</td>
            <td>{order.net_quantity} / {order.total_quantity}</td>
            {pricesVisible && <><td>{profit(order.total_cost)}</td><td>{profit(order.returned_cost)}</td>
            <td>{profit(order.net_cost)}</td>
            <td>{profit(order.total_profit)}</td><td>{profit(order.returned_profit)}</td>
            <td>{profit(order.net_profit)}</td></>}
            <td>{t(returnStatusKey(order.return_status))}</td>
            <td>{order.picked_up ? <>{t('pickedUp')}<br /><small>{date(order.picked_up_at)}</small></> : t('notPickedUp')}</td>
            <td>{calendarDate(order.order_date)}</td><td>{date(order.created_at)}</td>
            {archived && <><td>{order.archived_at ? date(order.archived_at) : t('none')}</td>
              <td>{order.archived_by || t('none')}</td></>}
            {cancelled && <><td>{order.cancelled_at ? date(order.cancelled_at) : t('none')}</td>
              <td>{order.cancelled_by || t('none')}</td></>}
            <td className="text-nowrap">
              <Link className="btn btn-sm btn-outline-primary me-2" to={`/orders/${order.id}`}>{t('view')}</Link>
              {!archived && !cancelled && user?.role === 'ADMIN' &&
                (order.status === 'DRAFT' || order.status === 'CONFIRMED') &&
                <button className={`btn btn-sm me-2 ${order.picked_up ? 'btn-outline-warning' : 'btn-outline-success'}`}
                  disabled={pickupOrderId === order.id}
                  onClick={() => void updatePickup(order)}>
                  {t(order.picked_up ? 'markNotPickedUp' : 'markPickedUp')}
                </button>}
              {!archived && !cancelled && user?.role === 'ADMIN' && (order.status || 'CONFIRMED') === 'CONFIRMED' && order.return_status === 'FULLY_RETURNED' &&
                <button className="btn btn-sm btn-outline-secondary"
                  disabled={archivingOrderId === order.id}
                  onClick={() => void archiveOrder(order.id)}>{t('archive')}</button>}
            </td>
          </tr>)}</tbody>
        </table></div>
      )}
      {orders && totalPages > 1 && <div className="d-flex justify-content-between align-items-center">
        <button className="btn btn-outline-secondary" disabled={page === 0}
          onClick={() => setPage(current => current - 1)}>{t('previous')}</button>
        <span>{t('pageOf', { page: page + 1, total: totalPages })}</span>
        <button className="btn btn-outline-secondary" disabled={page + 1 >= totalPages}
          onClick={() => setPage(current => current + 1)}>{t('next')}</button>
      </div>}
    </>
  );
}
