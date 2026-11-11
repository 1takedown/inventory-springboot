import { Link, Outlet, useNavigate } from 'react-router';
import { canCreateOrders, canViewDashboard, canViewStockHistory, useAuth } from '../auth';
import { useI18n } from '../i18n';
import { LanguageSelect } from './LanguageSelect';

export function Layout() {
  const { logout, user } = useAuth();
  const { t } = useI18n();
  const navigate = useNavigate();
  const signOut = async () => {
    await logout();
    navigate('/login');
  };
  return (
    <>
      <nav className="navbar navbar-expand bg-dark navbar-dark mb-4">
        <div className="container gap-2 flex-wrap">
          <Link className="navbar-brand" to="/items">{t('items')}</Link>
          <div className="d-flex gap-2 flex-wrap ms-auto align-items-center">
            {canViewDashboard(user) && <Link className="btn btn-outline-light btn-sm" to="/dashboard">{t('dashboard')}</Link>}
            <Link className="btn btn-outline-light btn-sm" to="/items">{t('items')}</Link>
            {canViewStockHistory(user) && <Link className="btn btn-outline-light btn-sm" to="/stock-history">{t('stockHistory')}</Link>}
            <Link className="btn btn-outline-light btn-sm" to="/orders">{t('orders')}</Link>
            {canCreateOrders(user) &&
              <Link className="btn btn-success btn-sm" to="/sell">{t('sell')}</Link>}
            {user?.role === 'ADMIN' && <Link className="btn btn-outline-light btn-sm" to="/users">{t('users')}</Link>}
            <Link className="btn btn-outline-light btn-sm" to="/account">{t('account')}</Link>
            <LanguageSelect />
            <button className="btn btn-outline-light btn-sm" onClick={() => void signOut()}>{t('logout')}</button>
          </div>
        </div>
      </nav>
      <main className="container pb-5"><Outlet /></main>
    </>
  );
}
