import { useState, type FormEvent } from 'react';
import { Navigate, useNavigate } from 'react-router';
import { useAuth } from '../auth';
import { useI18n } from '../i18n';
import { LanguageSelect } from '../components/LanguageSelect';
import { Loading } from '../components/Loading';
import { notifyError } from '../utils/notifyError';

export function LoginPage() {
  const { token, login, status } = useAuth();
  const { t } = useI18n();
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  if (status === 'restoring') return <main className="container py-5"><Loading /></main>;
  if (token) return <Navigate to="/items" replace />;
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!username.trim() || !password) return alert(t('loginRequired'));
    setBusy(true);
    try {
      await login(username.trim(), password);
      navigate('/items');
    } catch (error) {
      notifyError(error);
    } finally {
      setBusy(false);
    }
  };
  return (
    <main className="container py-5">
      <div className="d-flex justify-content-end mb-3"><LanguageSelect /></div>
      <form className="card shadow-sm mx-auto p-4" style={{ maxWidth: 420 }} onSubmit={submit}>
        <h1 className="h3 mb-4">{t('login')}</h1>
        <label className="form-label">{t('username')}
          <input className="form-control mt-1" value={username} onChange={e => setUsername(e.target.value)} />
        </label>
        <label className="form-label mt-2">{t('password')}
          <input type="password" className="form-control mt-1" value={password} onChange={e => setPassword(e.target.value)} />
        </label>
        <button className="btn btn-success mt-3" disabled={busy}>{busy ? t('loading') : t('signIn')}</button>
      </form>
    </main>
  );
}
