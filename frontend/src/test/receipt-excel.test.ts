import { readFileSync } from 'node:fs';
import ExcelJS from 'exceljs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '../api';
import { exportOrderToExcel } from '../receipt-excel';
import type { Order } from '../types';

const templates = [
  { id: 'simple', file: 'receipt-template-simple.xlsx', startRow: 6, footerMerge: null },
  { id: 'detailed', file: 'receipt-template-detailed.xlsx', startRow: 11, footerMerge: 'A38:E38' },
  { id: 'with-logo', file: 'receipt-template-with-logo.xlsx', startRow: 14, footerMerge: 'A41:E41' }
];

function namedRange(workbook: ExcelJS.Workbook, name: string) {
  const model = (workbook.definedNames as unknown as {
    model?: Array<{ name: string; ranges?: string[] | string }>
  }).model;
  const ranges = model?.find(entry => entry.name === name)?.ranges;
  return Array.isArray(ranges) ? ranges[0] : ranges;
}

function rowFromRange(range: string | undefined) {
  const match = range?.match(/\$[A-Z]+\$(\d+)/);
  if (!match) throw new Error(`Invalid named range: ${range}`);
  return Number(match[1]);
}

function cellFromRange(range: string | undefined) {
  const match = range?.match(/!\$([A-Z]+)\$(\d+)/);
  if (!match) throw new Error(`Invalid named range: ${range}`);
  return `${match[1]}${match[2]}`;
}

function orderWithLines(count: number): Order {
  const items = Array.from({ length: count }, (_, index) => ({
    id: index + 1,
    item_id: index + 100,
    title: `Item ${index + 1}`,
    barcode: `CODE-${index + 1}`,
    quantity: 1,
    returned_quantity: 0,
    remaining_quantity: 1,
    paid_amount: index + 1,
    subtotal: index + 1,
    cost: index + 0.5,
    cost_subtotal: index + 0.5,
    profit: 0.5,
    profit_subtotal: 0.5,
    created_at: '2026-07-10T15:00:00Z',
    created_by: 'cashier',
    removed_at: null,
    removed_by: null
  }));
  return {
    id: 42,
    text: 'Large order',
    status: 'CONFIRMED',
    total_quantity: count,
    total_amount: items.reduce((sum, item) => sum + item.subtotal, 0),
    returned_quantity: 0,
    returned_amount: 0,
    total_profit: 0,
    returned_profit: 0,
    net_profit: 0,
    total_cost: 0,
    returned_cost: 0,
    net_cost: 0,
    net_quantity: count,
    net_amount: items.reduce((sum, item) => sum + item.subtotal, 0),
    return_status: 'NOT_RETURNED',
    order_date: '2026-07-10',
    created_at: '2026-07-10T15:00:00Z',
    created_by: 'cashier',
    modified_at: null,
    modified_by: null,
    confirmed_at: '2026-07-10T15:00:00Z',
    confirmed_by: 'cashier',
    cancelled_at: null,
    cancelled_by: null,
    archived_at: null,
    archived_by: null,
    picked_up: false,
    picked_up_at: null,
    items,
    returns: []
  };
}

function readBlob(blob: Blob) {
  return new Promise<ArrayBuffer>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as ArrayBuffer);
    reader.onerror = () => reject(reader.error);
    reader.readAsArrayBuffer(blob);
  });
}

describe('receipt workbook export', () => {
  afterEach(() => vi.restoreAllMocks());

  it.each(['Order pricing is unavailable.', '此订单的价格不可用。'])(
    'rejects an unpriced order with the localized message: %s', async message => {
      const template = vi.spyOn(api, 'templateFile');
      const order = orderWithLines(1);
      delete order.total_amount;
      await expect(exportOrderToExcel(order, 'simple', value => value, message)).rejects.toThrow(message);
      order.total_amount = 1;
      delete order.items![0].paid_amount;
      await expect(exportOrderToExcel(order, 'simple', value => value, message)).rejects.toThrow(message);
      expect(template).not.toHaveBeenCalled();
    }
  );

  for (const template of templates) {
    it(`expands the ${template.id} template without losing lines or totals`, async () => {
      const bytes = readFileSync(`../src/main/resources/receipt-templates/${template.file}`);
      vi.spyOn(api, 'templateFile').mockResolvedValue({
        arrayBuffer: async () => new Uint8Array(bytes).buffer
      } as Blob);
      const order = orderWithLines(25);

      const result = await exportOrderToExcel(order, template.id, value => value, 'Order pricing is unavailable.');

      expect(result.capacity).toBe(25);
      const workbook = new ExcelJS.Workbook();
      await workbook.xlsx.load(await readBlob(result.blob));
      const worksheet = workbook.getWorksheet('Receipt');
      expect(worksheet).toBeDefined();
      expect(worksheet!.getCell(cellFromRange(namedRange(workbook, 'Date'))).value).toBe(order.order_date);
      expect(namedRange(workbook, 'Items')).toContain(`$E$${template.startRow + 24}`);
      expect(worksheet!.getCell(`A${template.startRow + 24}`).value).toBe('Item 25');
      expect(worksheet!.getCell(`E${template.startRow + 24}`).value).toBe(25);
      expect(worksheet!.getCell(`B${rowFromRange(namedRange(workbook, 'TotalQuantity'))}`).value)
        .toBe(25);
      expect(worksheet!.getCell(`C${rowFromRange(namedRange(workbook, 'TotalAmount'))}`).value)
        .toBe(325);
      expect(worksheet!.getCell(`A${template.startRow + 20}`).style)
        .toEqual(worksheet!.getCell(`A${template.startRow + 19}`).style);
      if (template.footerMerge) {
        expect((worksheet!.model as unknown as { merges: string[] }).merges)
          .toContain(template.footerMerge);
      }
    });
  }
});
