import type { ReactNode } from 'react';
import { useI18n } from '../i18n';

export function Loading({ children }: { children?: ReactNode }) {
  const { t } = useI18n();
  return <p className="text-muted">{children || t('loading')}</p>;
}
