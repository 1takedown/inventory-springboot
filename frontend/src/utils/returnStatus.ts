import type { ReturnStatus } from '../types';

export function returnStatusKey(status: ReturnStatus) {
  if (status === 'FULLY_RETURNED') return 'fullyReturned' as const;
  if (status === 'PARTIALLY_RETURNED') return 'partiallyReturned' as const;
  return 'notReturned' as const;
}
