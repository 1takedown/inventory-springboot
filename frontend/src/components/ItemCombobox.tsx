import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { useI18n } from '../i18n';
import type { Item } from '../types';
import { itemMatchesSearch, itemsForSelection } from '../utils/item';

type ItemComboboxProps = {
  items: Item[];
  value: string;
  disabled?: boolean;
  onChange: (value: string) => void;
};

function itemLabel(item: Item) {
  return `${item.title}${item.barcode ? ` — ${item.barcode}` : ''} (${item.quantity})`;
}

export function ItemCombobox({ items, value, disabled = false, onChange }: ItemComboboxProps) {
  const { t } = useI18n();
  const listId = useId();
  const inputRef = useRef<HTMLInputElement>(null);
  const previousValue = useRef(value);
  const clearingForEdit = useRef(false);
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);
  const matches = useMemo(
    () => itemsForSelection(items).filter(item => itemMatchesSearch(item, query)),
    [items, query]
  );
  const activeItem = activeIndex >= 0 ? matches[activeIndex] : undefined;

  useEffect(() => {
    const valueChanged = previousValue.current !== value;
    previousValue.current = value;
    if (!value) {
      if (valueChanged && !clearingForEdit.current) {
        setQuery('');
        setActiveIndex(-1);
      }
      clearingForEdit.current = false;
      return;
    }
    const selected = items.find(item => String(item.id) === value);
    setQuery(selected ? itemLabel(selected) : '');
  }, [items, value]);

  const choose = (item: Item) => {
    clearingForEdit.current = false;
    setQuery(itemLabel(item));
    setOpen(false);
    setActiveIndex(-1);
    onChange(String(item.id));
  };

  const moveActive = (delta: number) => {
    if (!matches.length) return;
    setOpen(true);
    setActiveIndex(current => {
      if (current < 0) return delta > 0 ? 0 : matches.length - 1;
      return (current + delta + matches.length) % matches.length;
    });
  };

  return <div className="item-combobox">
    <div className="input-group">
      <input ref={inputRef} type="text" role="combobox" className="form-control"
        disabled={disabled} aria-label={t('pickItem')} aria-autocomplete="list"
        aria-expanded={open} aria-controls={listId}
        aria-activedescendant={open && activeItem ? `${listId}-${activeItem.id}` : undefined}
        placeholder={t('searchItemsPlaceholder')} value={query}
        onFocus={() => setOpen(true)} onBlur={() => setOpen(false)}
        onChange={event => {
          setQuery(event.target.value);
          setOpen(true);
          setActiveIndex(-1);
          clearingForEdit.current = true;
          onChange('');
        }}
        onKeyDown={event => {
          if (event.key === 'ArrowDown') {
            event.preventDefault();
            moveActive(1);
          } else if (event.key === 'ArrowUp') {
            event.preventDefault();
            moveActive(-1);
          } else if (event.key === 'Enter' && open && matches.length) {
            event.preventDefault();
            choose(activeItem ?? matches[0]);
          } else if (event.key === 'Escape') {
            setOpen(false);
            setActiveIndex(-1);
          }
        }} />
      <button type="button" className="btn btn-outline-secondary dropdown-toggle dropdown-toggle-split"
        disabled={disabled} aria-label={t('pickItem')}
        onMouseDown={event => event.preventDefault()} onClick={() => {
          setOpen(current => !current);
          inputRef.current?.focus();
        }} />
    </div>
    {open && <div id={listId} role="listbox" className="dropdown-menu show item-combobox-menu">
      {matches.length ? matches.map((item, index) => <button type="button" role="option"
        id={`${listId}-${item.id}`} key={item.id} aria-selected={String(item.id) === value}
        className={`dropdown-item${index === activeIndex ? ' active' : ''}`}
        onMouseDown={event => event.preventDefault()} onClick={() => choose(item)}>
        {itemLabel(item)}
      </button>) : <div className="dropdown-item-text text-muted">{t('emptyItemSearch')}</div>}
    </div>}
  </div>;
}
