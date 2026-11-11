import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router';
import { api } from '../api';
import { canViewPrices, canViewStockHistory, useAuth } from '../auth';
import { useI18n } from '../i18n';
import type { Item, ItemInput } from '../types';
import { ItemForm } from '../components/ItemForm';
import { Loading } from '../components/Loading';
import { blankItem, itemMatchesInventorySearch } from '../utils/item';
import { notifyError } from '../utils/notifyError';

export function ItemsPage() {
  const { t, money } = useI18n();
  const { user } = useAuth();
  const isAdmin = user?.role === 'ADMIN';
  const canEdit = isAdmin || user?.role === 'CASHIER';
  const pricesVisible = canViewPrices(user);
  const stockHistoryVisible = canViewStockHistory(user);
  const showActions = canEdit || stockHistoryVisible;
  const formatPrice = (value: number | undefined) => value === undefined ? t('unknown') : money(value);
  const [items, setItems] = useState<Item[] | null>(null);
  const [search, setSearch] = useState('');
  const [editing, setEditing] = useState<Item | 'new' | null>(null);
  const filteredItems = useMemo(
    () => items?.filter(item => itemMatchesInventorySearch(item, search)) ?? null,
    [items, search]
  );
  const load = async () => {
    try {
      const loaded = await api.items();
      setItems(loaded);
      return loaded;
    } catch (error) {
      notifyError(error);
      return null;
    }
  };
  useEffect(() => { void load(); }, []);
  const save = async (input: ItemInput) => {
    try {
      if (editing === 'new') await api.createItem(input);
      else if (editing) await api.updateItem(editing.id, input, editing.version);
      setEditing(null);
      await load();
    } catch (error) {
      notifyError(error);
      if ((error as Error & { code?: string }).code === 'ITEM_CHANGED' && editing !== 'new') {
        const loaded = await load();
        const latest = loaded?.find(item => item.id === editing?.id);
        setEditing(latest || null);
      }
    }
  };
  const remove = async (id: number) => {
    if (!confirm(t('confirmItem'))) return;
    try { await api.deleteItem(id); await load(); } catch (error) { notifyError(error); }
  };
  return (
    <>
      <div className="d-flex justify-content-between align-items-center mb-3">
        <h1 className="h2">{t('inventory')}</h1>
        {canEdit && <button className="btn btn-primary" onClick={() => setEditing('new')}>{t('addItem')}</button>}
      </div>
      {canEdit && editing && (editing === 'new' || (editing.cost !== undefined && editing.profit !== undefined)) && <ItemForm
        key={editing === 'new' ? 'new' : `${editing.id}:${editing.version}`}
        initial={editing === 'new' ? blankItem : {
          title: editing.title, description: editing.description || '', quantity: editing.quantity,
          cost: editing.cost!, profit: editing.profit!, barcode: editing.barcode
        }}
        onSave={save}
        onCancel={() => setEditing(null)}
      />}
      <input className="form-control mb-3" type="search" aria-label={t('searchItems')}
        placeholder={t('searchInventoryPlaceholder')} value={search}
        onChange={event => setSearch(event.target.value)} />
      {!items ? <Loading /> : items.length === 0 ? <p>{t('emptyItems')}</p>
        : filteredItems?.length === 0 ? <p>{t('emptyItemSearch')}</p> : (
        <div className="table-responsive">
          <table className="table table-striped align-middle app-table">
            <thead><tr><th>{t('titleField')}</th><th>{t('barcode')}</th>
              {pricesVisible && <><th>{t('cost')}</th><th>{t('profit')}</th><th>{t('price')}</th></>}
              <th>{t('stock')}</th>{showActions && <th>{t('actions')}</th>}</tr></thead>
            <tbody>{filteredItems?.map(item => <tr key={item.id}>
              <td><strong>{item.title}</strong><div className="small text-muted">{item.description}</div></td>
              <td>{item.barcode || t('none')}</td>
              {pricesVisible && <><td>{formatPrice(item.cost)}</td><td>{formatPrice(item.profit)}</td>
                <td>{formatPrice(item.price)}</td></>}
              <td>{item.quantity}</td>
              {showActions && <td className="text-nowrap">
                {stockHistoryVisible && <Link className="btn btn-sm btn-outline-secondary me-2" to={`/stock-history?itemId=${item.id}`}>{t('history')}</Link>}
                {canEdit && <button className="btn btn-sm btn-outline-primary me-2" onClick={() => setEditing(item)}>{t('edit')}</button>}
                {isAdmin && <button className="btn btn-sm btn-danger" onClick={() => void remove(item.id)}>{t('delete')}</button>}
              </td>}
            </tr>)}</tbody>
          </table>
        </div>
      )}
    </>
  );
}
