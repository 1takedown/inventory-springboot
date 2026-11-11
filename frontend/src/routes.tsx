import { Navigate, Outlet, Route, Routes } from 'react-router';
import { canCreateOrders, canViewAllOrders, canViewDashboard, canViewStockHistory, useAuth } from './auth';
import { Layout } from './components/Layout';
import { Loading } from './components/Loading';
import { AccountPage } from './pages/AccountPage';
import { DashboardPage } from './pages/DashboardPage';
import { ItemsPage } from './pages/ItemsPage';
import { LoginPage } from './pages/LoginPage';
import { OrderDetailsPage } from './pages/OrderDetailsPage';
import { OrdersPage } from './pages/OrdersPage';
import { SellPage } from './pages/SellPage';
import { UsersPage } from './pages/UsersPage';
import { StockHistoryPage } from './pages/StockHistoryPage';

export function ProtectedRoute() {
  const { token, status } = useAuth();
  if (status === 'restoring') return <main className="container py-5"><Loading /></main>;
  return token ? <Outlet /> : <Navigate to="/login" replace />;
}

export function AdminRoute() {
  const { user } = useAuth();
  return user?.role === 'ADMIN' ? <Outlet /> : <Navigate to="/items" replace />;
}

function PermissionRoute({ allowed }: { allowed: (user: { role: string } | null) => boolean }) {
  const { user } = useAuth();
  return allowed(user) ? <Outlet /> : <Navigate to="/items" replace />;
}

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<ProtectedRoute />}>
        <Route element={<Layout />}>
          <Route path="/items" element={<ItemsPage />} />
          <Route path="/orders" element={<OrdersPage />} />
          <Route path="/orders/:id" element={<OrderDetailsPage />} />
          <Route path="/account" element={<AccountPage />} />
          <Route element={<PermissionRoute allowed={canCreateOrders} />}>
            <Route path="/sell" element={<SellPage />} />
          </Route>
          <Route element={<PermissionRoute allowed={canViewStockHistory} />}>
            <Route path="/stock-history" element={<StockHistoryPage />} />
          </Route>
          <Route element={<PermissionRoute allowed={canViewDashboard} />}>
            <Route path="/dashboard" element={<DashboardPage />} />
          </Route>
          <Route element={<PermissionRoute allowed={canViewAllOrders} />}>
            <Route path="/orders/archived" element={<OrdersPage key="archived" archived />} />
            <Route path="/orders/cancelled" element={<OrdersPage key="cancelled" cancelled />} />
          </Route>
          <Route element={<AdminRoute />}>
            <Route path="/users" element={<UsersPage />} />
          </Route>
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/items" replace />} />
    </Routes>
  );
}
