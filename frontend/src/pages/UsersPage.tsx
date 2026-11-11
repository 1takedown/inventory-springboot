import { useEffect, useState, type FormEvent } from 'react';
import { api } from '../api';
import { useI18n } from '../i18n';
import type { UserAccount, UserRole } from '../types';
import { Loading } from '../components/Loading';
import { notifyError } from '../utils/notifyError';

export function UsersPage() {
  const { t, date } = useI18n();
  const [users, setUsers] = useState<UserAccount[] | null>(null);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState<UserRole>('CASHIER');
  const load = async () => {
    try { setUsers(await api.users()); } catch (error) { notifyError(error); }
  };
  useEffect(() => { void load(); }, []);
  const create = async (event: FormEvent) => {
    event.preventDefault();
    try {
      await api.createUser(username.trim(), password, role);
      setUsername('');
      setPassword('');
      setRole('CASHIER');
      alert(t('userCreated'));
      await load();
    } catch (error) { notifyError(error); }
  };
  const update = async (id: number, input: { role?: UserRole; active?: boolean }) => {
    try { await api.updateUser(id, input); await load(); } catch (error) { notifyError(error); }
  };
  const resetPassword = async (id: number) => {
    const next = prompt(t('newPassword'));
    if (!next) return;
    try { await api.resetUserPassword(id, next); await load(); } catch (error) { notifyError(error); }
  };
  const revoke = async (id: number) => {
    try {
      await api.revokeUserSessions(id);
      alert(t('sessionsRevoked'));
      await load();
    } catch (error) { notifyError(error); }
  };
  return (
    <>
      <h1 className="h2 mb-3">{t('users')}</h1>
      <form className="card card-body mb-4" onSubmit={create}>
        <h2 className="h5">{t('createUser')}</h2>
        <div className="row g-2">
          <div className="col-md-4"><input aria-label={t('username')} className="form-control"
            placeholder={t('username')} value={username} onChange={event => setUsername(event.target.value)} /></div>
          <div className="col-md-4"><input aria-label={t('password')} type="password" className="form-control"
            placeholder={t('password')} value={password} onChange={event => setPassword(event.target.value)} /></div>
          <div className="col-md-2"><select aria-label={t('role')} className="form-select" value={role}
            onChange={event => setRole(event.target.value as UserRole)}>
            <option value="CASHIER">{t('cashier')}</option><option value="ADMIN">{t('admin')}</option>
            <option value="AUDITOR">{t('auditor')}</option>
          </select></div>
          <div className="col-md-2 d-grid"><button className="btn btn-primary">{t('createUser')}</button></div>
        </div>
      </form>
      {!users ? <Loading /> : <div className="table-responsive"><table className="table table-striped align-middle">
        <thead><tr><th>{t('username')}</th><th>{t('role')}</th><th>{t('active')}</th><th>{t('date')}</th><th>{t('actions')}</th></tr></thead>
        <tbody>{users.map(account => {
          const active = account.active === true || Number(account.active) === 1;
          return <tr key={account.id}>
            <td>{account.username}</td>
            <td><select className="form-select form-select-sm" value={account.role}
              onChange={event => void update(account.id, { role: event.target.value as UserRole })}>
              <option value="ADMIN">{t('admin')}</option><option value="CASHIER">{t('cashier')}</option>
              <option value="AUDITOR">{t('auditor')}</option>
            </select></td>
            <td>{active ? t('active') : t('inactive')}</td>
            <td>{date(account.created_at)}</td>
            <td className="text-nowrap">
              <button className="btn btn-sm btn-outline-secondary me-2"
                onClick={() => void resetPassword(account.id)}>{t('resetPassword')}</button>
              <button className="btn btn-sm btn-outline-warning me-2"
                onClick={() => void revoke(account.id)}>{t('revokeSessions')}</button>
              <button className={`btn btn-sm ${active ? 'btn-outline-danger' : 'btn-outline-success'}`}
                onClick={() => {
                  if (active && !confirm(t('confirmDeactivate'))) return;
                  void update(account.id, { active: !active });
                }}>{active ? t('deactivate') : t('activate')}</button>
            </td>
          </tr>;
        })}</tbody>
      </table></div>}
    </>
  );
}
