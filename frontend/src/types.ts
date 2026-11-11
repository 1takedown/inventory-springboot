export type Locale = 'en' | 'zh-CN';

export const stockMovementReasons = ['OPENING_STOCK', 'MIGRATION_BASELINE', 'MANUAL_ADJUSTMENT',
  'ORDER_RESERVED', 'ORDER_ITEM_REMOVED', 'ORDER_CANCELLED', 'ORDER_RETURNED', 'ITEM_DELETED'] as const;
export type StockMovementReason = typeof stockMovementReasons[number];
export interface StockMovement {
  id: number;
  item_id?: number | null;
  item_id_snapshot: number;
  title_snapshot: string;
  barcode_snapshot?: string | null;
  delta: number;
  quantity_before: number;
  quantity_after: number;
  reason: StockMovementReason;
  order_id?: number | null;
  order_text: string | null;
  order_item_id?: number | null;
  order_return_id?: number | null;
  created_at: string;
  created_by: string;
}
export interface StockMovementPage {
  items: StockMovement[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface Item {
  id: number;
  title: string;
  description: string | null;
  quantity: number;
  cost?: number;
  profit?: number;
  price?: number;
  barcode: string | null;
  created_at: string;
  created_by: string;
  modified_at: string | null;
  modified_by: string | null;
  version: number;
}

export interface ItemInput {
  title: string;
  description: string;
  quantity: number;
  cost: number;
  profit: number;
  barcode: string | null;
}

export interface OrderLine {
  id: number;
  item_id: number;
  title: string;
  barcode: string | null;
  quantity: number;
  returned_quantity: number;
  remaining_quantity: number;
  paid_amount?: number;
  subtotal?: number;
  cost?: number | null;
  cost_subtotal?: number | null;
  profit?: number | null;
  profit_subtotal?: number | null;
  created_at: string | null;
  created_by: string | null;
  removed_at: string | null;
  removed_by: string | null;
}

export type ReturnStatus = 'NOT_RETURNED' | 'PARTIALLY_RETURNED' | 'FULLY_RETURNED';
export type OrderStatus = 'DRAFT' | 'CONFIRMED' | 'CANCELLED';

export interface OrderReturnLine {
  order_item_id: number;
  item_id: number;
  title: string;
  barcode: string | null;
  quantity: number;
  refund_amount?: number;
}

export interface OrderReturn {
  id: number;
  reason: string | null;
  created_at: string;
  created_by: string;
  total_quantity: number;
  total_amount?: number;
  items: OrderReturnLine[];
}

export interface Order {
  id: number;
  text: string;
  status: OrderStatus;
  order_date: string;
  total_quantity: number;
  total_amount?: number;
  returned_quantity: number;
  returned_amount?: number;
  net_quantity: number;
  net_amount?: number;
  total_profit?: number | null;
  returned_profit?: number | null;
  net_profit?: number | null;
  total_cost?: number | null;
  returned_cost?: number | null;
  net_cost?: number | null;
  return_status: ReturnStatus;
  created_at: string;
  created_by: string;
  modified_at: string | null;
  modified_by: string | null;
  confirmed_at: string | null;
  confirmed_by: string | null;
  cancelled_at: string | null;
  cancelled_by: string | null;
  archived_at: string | null;
  archived_by: string | null;
  picked_up: boolean;
  picked_up_at: string | null;
  items?: OrderLine[];
  removed_items?: OrderLine[];
  returns?: OrderReturn[];
}

export interface OrderPage {
  items: Order[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface DashboardMetrics {
  gross_quantity: number;
  restored_quantity: number;
  net_quantity: number;
  net_amount?: number;
  net_cost?: number | null;
  net_profit?: number | null;
}

export interface DashboardItem extends DashboardMetrics {
  item_id: number;
  title: string;
  barcode: string | null;
}

export interface DashboardResponse {
  date: string;
  time_zone: string;
  totals: DashboardMetrics;
  items: DashboardItem[];
}

export interface DashboardRelatedOrder {
  id: number;
  text: string;
  status: OrderStatus;
  archived_at: string | null;
  gross_quantity: number;
  restored_quantity: number;
  net_quantity: number;
}

export interface DashboardRelatedOrdersResponse {
  date: string;
  time_zone: string;
  item_id: number;
  orders: DashboardRelatedOrder[];
}

export type OrderReturnInput =
  | { full: true; reason?: string }
  | { items: Array<{ order_item_id: number; quantity: number }>; reason?: string };

export interface CartLine {
  item_id: number;
  title: string;
  barcode: string | null;
  price: number;
  quantity: number;
}

export interface ReceiptTemplate {
  id: string;
  name: string;
}

export type UserRole = 'ADMIN' | 'CASHIER' | 'AUDITOR';

export interface AuthUser {
  id: number;
  username: string;
  role: UserRole;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
  user: AuthUser;
}

export interface UserAccount {
  id: number;
  username: string;
  role: UserRole;
  active: number | boolean;
  auth_version: number;
  created_at: string;
  created_by: string;
  modified_at?: string;
  modified_by?: string;
}

export type DraftEditorProps = {
  order: Order;
  onUpdate: (order: Order) => void;
  busy: boolean;
  runMutation: (operation: () => Promise<void>) => Promise<void>;
};
