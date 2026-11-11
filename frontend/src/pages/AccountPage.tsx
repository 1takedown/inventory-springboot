import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router';
import { api } from '../api';
import { useAuth } from '../auth';
import { useI18n } from '../i18n';
import { notifyError } from '../utils/notifyError';

export function AccountPage() {
  const { t } = useI18n();
  const { logout } = useAuth();
  const navigate = useNavigate();
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    try {
      await api.changePassword(currentPassword, newPassword);
      alert(t('passwordChanged'));
      await logout();
      navigate('/login');
    } catch (error) {
      notifyError(error);
    } finally {
      setBusy(false);
    }
  };
  return (
    <form className="card card-body mx-auto" style={{ maxWidth: 520 }} onSubmit={submit}>
      <h1 className="h3">{t('account')}</h1>
      <label className="form-label mt-2">{t('currentPassword')}
        <input type="password" className="form-control mt-1" value={currentPassword}
          onChange={event => setCurrentPassword(event.target.value)} />
      </label>
      <label className="form-label mt-2">{t('newPassword')}
        <input type="password" className="form-control mt-1" value={newPassword}
          onChange={event => setNewPassword(event.target.value)} />
      </label>
      <button className="btn btn-success mt-3" disabled={busy}>{t('changePassword')}</button>
    </form>
  );
}
