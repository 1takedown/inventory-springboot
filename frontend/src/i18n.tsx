import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { BUSINESS_TIME_ZONE, isValidCalendarDate } from './utils/businessDate';
import { LOCALE_KEY } from './api';
import type { Locale } from './types';

const messages = {
  en: {
    stockHistory: 'Stock History', stockMovementDate: 'Movement date', history: 'History', movementReason: 'Reason', stockChange: 'Change',
    stockBefore: 'Before', stockAfter: 'After', stockActor: 'Changed by', allReasons: 'All reasons', allDates: 'All dates',
    clearItemFilter: 'Clear item filter', deletedStockItem: 'Deleted item', emptyStockHistory: 'No stock movements found.',
    stockHistoryHelp: 'Opening balances show stock when tracking began. Other entries show when stock changed.',
    OPENING_STOCK: 'Opening stock', MIGRATION_BASELINE: 'Tracking baseline', MANUAL_ADJUSTMENT: 'Manual adjustment',
    ORDER_RESERVED: 'Order allocation', ORDER_ITEM_REMOVED: 'Order item removed', ORDER_CANCELLED: 'Order cancelled',
    ORDER_RETURNED: 'Order returned', ITEM_DELETED: 'Item deleted',
    title: 'Inventory & Order System', language: 'Language', items: 'Items', orders: 'Orders',
    sell: 'Create Order', logout: 'Logout', login: 'Login', username: 'Username', password: 'Password',
    signIn: 'Sign In', inventory: 'Inventory Items', addItem: 'Add Item', editItem: 'Edit Item',
    titleField: 'Title', barcode: 'Barcode', description: 'Description', cost: 'Cost', profit: 'Profit',
    quantity: 'Quantity', save: 'Save', cancel: 'Cancel', edit: 'Edit', delete: 'Delete',
    actions: 'Actions', price: 'Sale Price', stock: 'Stock', emptyItems: 'No items found.',
    orderId: 'Order ID', text: 'Text', textUpdated: 'Order text updated.', total: 'Total', date: 'Date', view: 'View',
    orderDate: 'Order Date', createdAt: 'Created At', orderDateUpdated: 'Order date updated.',
    orderDateRequired: 'Select an order date.',
    emptyOrders: 'No orders found.', back: 'Back to Orders', orderItems: 'Order Items',
    searchOrders: 'Search orders', searchOrdersPlaceholder: 'Search by order ID, text, item, or barcode…',
    emptyOrderSearch: 'No matching orders found.', searchItems: 'Search items',
    searchItemsPlaceholder: 'Search by item title or barcode…', emptyItemSearch: 'No matching items found.',
    searchInventoryPlaceholder: 'Search by item title, description, or barcode…',
    paidAmount: 'Paid Amount', scan: 'Scan or type a barcode…', scanAdd: 'Add',
    pickItem: 'Select an item…', addToCart: 'Add to Cart', cart: 'Cart', item: 'Item',
    subtotal: 'Subtotal', remove: 'Remove', clear: 'Clear Cart', orderText: 'Order note or reference',
    receiptTemplate: 'Receipt template', saveOrder: 'Save', saveExport: 'Save & Export',
    loading: 'Loading…', loginRequired: 'Please enter your username and password.',
    itemRequired: 'Enter a title, cost/profit with at most two decimal places, and a whole-number quantity.',
    selectionRequired: 'Select an item and enter a positive whole-number quantity.',
    stockAvailable: 'Only {available} units are available.', orderTextRequired: 'Enter order text.',
    orderCreated: 'Order created successfully.', confirmItem: 'Delete this item?',
    exportSuccess: 'Receipt downloaded.', exportReceipt: 'Export receipt',
    exportFailed: 'Receipt export failed: {error}',
    cartUpdated: 'Stock or pricing changed. Review the updated cart before saving.',
    remainingStock: '{available} remaining after cart',
    originalTotal: 'Original Total', returnedTotal: 'Returned Total', netTotal: 'Net Total',
    originalProfit: 'Original Profit', returnedProfit: 'Returned Profit', netProfit: 'Net Profit',
    originalCost: 'Original Cost', returnedCost: 'Returned Cost', netCost: 'Net Cost',
    costSubtotal: 'Cost Subtotal', profitSubtotal: 'Profit Subtotal',
    archive: 'Archive', confirmArchive: 'Archive this fully returned order?',
    archiveSuccess: 'Order archived.', archived: 'Archived', archivedAt: 'Archived at', archivedBy: 'Archived by',
    activeOrders: 'Active Orders', archivedOrders: 'Archived Orders',
    emptyArchivedOrders: 'No archived orders found.',
    unknown: 'Unknown',
    returnStatus: 'Return Status', notReturned: 'Not returned', partiallyReturned: 'Partially returned',
    fullyReturned: 'Fully returned', soldQuantity: 'Sold', returnedQuantity: 'Returned',
    remainingQuantity: 'Remaining', returnQuantity: 'Return quantity', returnReason: 'Return reason',
    returnReasonPlaceholder: 'Optional reason', processReturn: 'Return selected',
    returnAll: 'Return all remaining', confirmReturnAll: 'Return all remaining items?',
    returnHistory: 'Return History', returnedBy: 'Returned by', returnSuccess: 'Return completed.',
    returnSelectionRequired: 'Enter a return quantity for at least one item.',
    returnQuantityInvalid: 'Return quantities must be whole numbers within the remaining quantities.',
    noReturns: 'No returns have been processed.', refundAmount: 'Refund Amount',
    noTemplate: 'Choose a receipt template.', none: '-', users: 'Users', account: 'Account',
    role: 'Role', active: 'Active', inactive: 'Inactive', admin: 'Administrator', cashier: 'Cashier', auditor: 'Auditor',
    pricingUnavailable: 'Pricing is unavailable for this item.',
    orderPricingUnavailable: 'Order pricing is unavailable.',
    createUser: 'Create User', resetPassword: 'Reset Password', revokeSessions: 'Revoke Sessions',
    activate: 'Activate', deactivate: 'Deactivate', currentPassword: 'Current Password',
    newPassword: 'New Password', changePassword: 'Change Password', passwordChanged: 'Password changed.',
    userCreated: 'User created.', sessionsRevoked: 'Sessions revoked.', confirmDeactivate: 'Deactivate this user?',
    previous: 'Previous', next: 'Next', pageOf: 'Page {page} of {total}',
    scanPending: 'Wait for the barcode scan to finish before saving.',
    status: 'Status', draft: 'Draft', confirmed: 'Confirmed', cancelled: 'Cancelled',
    pickupStatus: 'Pickup Status', pickedUp: 'Picked up', notPickedUp: 'Not picked up',
    pickedUpAt: 'Picked up at', markPickedUp: 'Mark picked up', markNotPickedUp: 'Mark not picked up',
    confirmMarkPickedUp: 'Mark this order as picked up?',
    confirmMarkNotPickedUp: 'Mark this order as not picked up?', pickupUpdated: 'Pickup state updated.',
    confirmPickUpDraft: 'Mark this draft as picked up and confirm it? It can no longer be edited.',
    pickupDraftUpdated: 'Order marked as picked up and confirmed.',
    cancelledOrders: 'Cancelled Orders', emptyCancelledOrders: 'No cancelled orders found.',
    confirmedAt: 'Confirmed at', confirmedBy: 'Confirmed by', cancelledAt: 'Cancelled at',
    cancelledBy: 'Cancelled by', saveDraft: 'Save Draft', confirmOrder: 'Confirm',
    confirmExport: 'Confirm & Export', confirmDraft: 'Confirm this draft? It can no longer be edited.',
    orderConfirmed: 'Order confirmed.', cancelDraft: 'Cancel Draft',
    lastDraftItemHint: 'A draft must keep at least one item. Cancel the draft to discard it.',
    confirmCancelDraft: 'Cancel this draft and restore its reserved stock?',
    orderCancelled: 'Draft cancelled.', addOrderItems: 'Add Items', addItemsSuccess: 'Items added.',
    confirmRemoveOrderItem: 'Remove this item and restore its stock?', removedItems: 'Removed Items',
    removedAt: 'Removed at', removedBy: 'Removed by', createdBy: 'Added by',
    dashboard: 'Dashboard', dashboardDate: 'Dashboard date', dailyOutItems: 'Daily Out Items', grossOut: 'Reserved',
    restored: 'Restocked', netOut: 'Current Net',
    ordersLoadFailed: 'Orders could not be loaded. Please try again.',
    refresh: 'Refresh', emptyDailyOutItems: 'No order items were recorded on the selected date.',
    dailyOutItemsHelp: 'Order items assigned to the selected order date; returns, removed draft items, and cancellations restock quantities and update these totals.',
    relatedOrders: 'Related Orders', emptyRelatedOrders: 'No related orders found for this item on the selected date.'
  },
  'zh-CN': {
    stockHistory: '库存流水', stockMovementDate: '流水日期', history: '流水', movementReason: '原因', stockChange: '变动',
    stockBefore: '变动前', stockAfter: '变动后', stockActor: '经办人', allReasons: '全部原因', allDates: '全部日期',
    clearItemFilter: '清除商品筛选', deletedStockItem: '已删除商品', emptyStockHistory: '暂无库存流水。',
    stockHistoryHelp: '期初记录表示开始追踪时的库存，其他记录表示库存实际变动的时间。',
    OPENING_STOCK: '期初库存', MIGRATION_BASELINE: '追踪期初库存', MANUAL_ADJUSTMENT: '手动调整',
    ORDER_RESERVED: '订单扣库存', ORDER_ITEM_REMOVED: '移除订单商品', ORDER_CANCELLED: '取消订单',
    ORDER_RETURNED: '订单退货', ITEM_DELETED: '删除商品',
    originalCost: '原始成本', returnedCost: '已退成本', netCost: '净成本', costSubtotal: '成本小计',
    profitSubtotal: '利润小计',
    archive: '归档', confirmArchive: '确定归档此已全部退货的订单吗？', archiveSuccess: '订单已归档。',
    archived: '已归档', archivedAt: '归档时间', archivedBy: '归档人',
    activeOrders: '有效订单', archivedOrders: '已归档订单', emptyArchivedOrders: '暂无已归档订单。',
    originalProfit: '原始利润', returnedProfit: '已退利润', netProfit: '净利润', unknown: '未知',
    originalTotal: '原始总额', returnedTotal: '已退总额', netTotal: '净额',
    returnStatus: '退货状态', notReturned: '未退货', partiallyReturned: '部分退货',
    fullyReturned: '全部退货', soldQuantity: '售出数量', returnedQuantity: '已退数量',
    remainingQuantity: '可退数量', returnQuantity: '退货数量', returnReason: '退货原因',
    returnReasonPlaceholder: '可选原因', processReturn: '退还所选商品',
    returnAll: '退还全部剩余商品', confirmReturnAll: '确定退还全部剩余商品吗？',
    returnHistory: '退货记录', returnedBy: '经办人', returnSuccess: '退货完成。',
    returnSelectionRequired: '请至少为一件商品输入退货数量。',
    returnQuantityInvalid: '退货数量必须是整数且不能超过可退数量。',
    noReturns: '尚无退货记录。', refundAmount: '退款金额',
    title: '库存与订单系统', language: '语言', items: '商品', orders: '订单', sell: '创建订单',
    logout: '登出', login: '登录', username: '用户名', password: '密码', signIn: '登录',
    inventory: '库存商品', addItem: '添加商品', editItem: '编辑商品', titleField: '标题',
    barcode: '条码', description: '描述', cost: '成本', profit: '利润', quantity: '数量',
    save: '保存', cancel: '取消', edit: '编辑', delete: '删除', actions: '操作',
    price: '销售价', stock: '库存', emptyItems: '暂无商品。', orderId: '订单 ID',
    text: '文本', textUpdated: '订单文本已更新。', total: '合计', date: '日期', view: '查看', emptyOrders: '暂无订单。',
    searchOrders: '搜索订单', searchOrdersPlaceholder: '按订单 ID、文本、商品或条码搜索…',
    emptyOrderSearch: '未找到匹配的订单。', searchItems: '搜索商品',
    searchItemsPlaceholder: '按商品标题或条码搜索…', emptyItemSearch: '未找到匹配的商品。',
    searchInventoryPlaceholder: '按商品标题、描述或条码搜索…',
    orderDate: '订单日期', createdAt: '创建时间', orderDateUpdated: '订单日期已更新。',
    orderDateRequired: '请选择订单日期。',
    back: '返回订单', orderItems: '订单商品', paidAmount: '实付金额',
    scan: '扫描或输入条码…', scanAdd: '添加', pickItem: '选择商品…', addToCart: '添加到购物车',
    cart: '购物车', item: '商品', subtotal: '小计', remove: '移除', clear: '清空购物车',
    orderText: '订单备注或参考信息', receiptTemplate: '小票模板', saveOrder: '保存',
    saveExport: '保存并导出', loading: '加载中…', loginRequired: '请输入用户名和密码。',
    itemRequired: '请输入标题、非负成本/利润和整数数量。', selectionRequired: '请选择商品并输入正整数数量。',
    stockAvailable: '库存仅剩 {available} 件。', orderTextRequired: '请输入订单文本。',
    orderCreated: '订单创建成功。', confirmItem: '删除这个商品？',
    exportSuccess: '小票已下载。', exportReceipt: '导出小票',
    exportFailed: '小票导出失败：{error}',
    cartUpdated: '库存或价格已变更。请检查更新后的购物车后再保存。',
    remainingStock: '加入购物车后剩余 {available} 件',
    noTemplate: '请选择小票模板。', none: '-', users: '用户', account: '账户', role: '角色',
    active: '有效', inactive: '停用', admin: '管理员', cashier: '收银员', auditor: '稽核员', createUser: '创建用户',
    pricingUnavailable: '此商品的价格不可用。',
    orderPricingUnavailable: '此订单的价格不可用。',
    resetPassword: '重置密码', revokeSessions: '撤销会话', activate: '启用', deactivate: '停用',
    currentPassword: '当前密码', newPassword: '新密码', changePassword: '修改密码',
    passwordChanged: '密码已修改。', userCreated: '用户已创建。', sessionsRevoked: '会话已撤销。',
    confirmDeactivate: '停用此用户？', previous: '上一页', next: '下一页',
    pageOf: '第 {page} 页，共 {total} 页', scanPending: '请等待条码扫描完成后再保存。',
    status: '状态', draft: '草稿', confirmed: '已确认', cancelled: '已取消',
    pickupStatus: '取货状态', pickedUp: '已取货', notPickedUp: '未取货',
    pickedUpAt: '取货时间', markPickedUp: '标记为已取货', markNotPickedUp: '标记为未取货',
    confirmMarkPickedUp: '将此订单标记为已取货吗？',
    confirmMarkNotPickedUp: '将此订单标记为未取货吗？', pickupUpdated: '取货状态已更新。',
    confirmPickUpDraft: '将此草稿标记为已取货并确认吗？确认后将无法编辑。',
    pickupDraftUpdated: '订单已标记为已取货并确认。',
    cancelledOrders: '已取消订单', emptyCancelledOrders: '暂无已取消订单。',
    confirmedAt: '确认时间', confirmedBy: '确认人', cancelledAt: '取消时间', cancelledBy: '取消人',
    saveDraft: '保存草稿', confirmOrder: '确认', confirmExport: '确认并导出',
    confirmDraft: '确认此草稿吗？确认后将无法编辑。', orderConfirmed: '订单已确认。',
    cancelDraft: '取消草稿',
    lastDraftItemHint: '草稿须至少保留一件商品。如需丢弃请取消草稿。',
    confirmCancelDraft: '取消此草稿并恢复预留库存吗？',
    orderCancelled: '草稿已取消。', addOrderItems: '添加商品', addItemsSuccess: '商品已添加。',
    confirmRemoveOrderItem: '移除此商品并恢复库存吗？', removedItems: '已移除商品',
    removedAt: '移除时间', removedBy: '移除人', createdBy: '添加人',
    dashboard: '仪表板', dashboardDate: '仪表板日期', dailyOutItems: '每日出库商品', grossOut: '预留数量',
    restored: '已回库数量', netOut: '当前净数量',
    ordersLoadFailed: '无法加载订单，请重试。',
    refresh: '刷新', emptyDailyOutItems: '所选日期没有订单商品记录。',
    dailyOutItemsHelp: '显示归属于所选订单日期的订单商品；退货、移除草稿商品或取消订单会使商品回库并更新这些汇总。',
    relatedOrders: '相关订单', emptyRelatedOrders: '所选日期没有此商品的相关订单。'
  }
} as const;

type MessageKey = keyof typeof messages.en;
type I18nValue = {
  locale: Locale;
  setLocale: (locale: Locale) => void;
  t: (key: MessageKey, params?: Record<string, string | number>) => string;
  money: (value: number) => string;
  date: (value: string | null | undefined) => string;
  calendarDate: (value: string | null | undefined) => string;
};

const I18nContext = createContext<I18nValue | null>(null);

function initialLocale(): Locale {
  const value = localStorage.getItem(LOCALE_KEY) || navigator.language;
  return value.toLowerCase().startsWith('zh') ? 'zh-CN' : 'en';
}

export function I18nProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState<Locale>(initialLocale);
  const setLocale = (value: Locale) => {
    localStorage.setItem(LOCALE_KEY, value);
    setLocaleState(value);
  };
  useEffect(() => {
    document.documentElement.lang = locale;
    document.title = messages[locale].title;
  }, [locale]);
  const value = useMemo<I18nValue>(() => ({
    locale,
    setLocale,
    t: (key, params = {}) => {
      let text: string = messages[locale][key];
      for (const [name, replacement] of Object.entries(params)) {
        text = text.replace(`{${name}}`, String(replacement));
      }
      return text;
    },
    money: value => new Intl.NumberFormat(locale, { style: 'currency', currency: 'HKD' }).format(value || 0),
    date: value => {
      if (!value) return messages[locale].none;
      const parsed = new Date(value);
      return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString(locale, { timeZone: BUSINESS_TIME_ZONE });
    },
    calendarDate: value => {
      if (!value) return messages[locale].none;
      if (!isValidCalendarDate(value)) return value;
      const parsed = new Date(`${value}T00:00:00Z`);
      return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeZone: 'UTC' }).format(parsed);
    }
  }), [locale]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n() {
  const value = useContext(I18nContext);
  if (!value) throw new Error('useI18n must be used within I18nProvider');
  return value;
}
