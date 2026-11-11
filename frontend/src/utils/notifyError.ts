import { SessionExpiredError } from '../api';

/** Show an error alert unless the session already expired and the user was logged out. */
export function notifyError(error: unknown) {
  if (error instanceof SessionExpiredError) return;
  alert(error instanceof Error ? error.message : String(error));
}
