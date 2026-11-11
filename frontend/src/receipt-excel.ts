import ExcelJS from 'exceljs';
import { api } from './api';
import type { Order } from './types';

type CellRef = { sheet: string | null; start: { col: string; row: number }; end: { col: string; row: number } };
type DefinedNameModel = Array<{ name: string; ranges?: string[] | string }>;

function definedNameModel(workbook: ExcelJS.Workbook) {
  return workbook.definedNames as unknown as { model?: DefinedNameModel };
}

function namedRef(workbook: ExcelJS.Workbook, name: string): string | null {
  const model = definedNameModel(workbook).model;
  const match = model?.find(entry => entry.name === name);
  return match ? (Array.isArray(match.ranges) ? match.ranges[0] : match.ranges) || null : null;
}

function formatRef(ref: CellRef) {
  const sheet = ref.sheet
    ? `'${ref.sheet.replace(/'/g, "''")}'!`
    : '';
  const start = `$${ref.start.col}$${ref.start.row}`;
  const end = `$${ref.end.col}$${ref.end.row}`;
  return `${sheet}${start}${start === end ? '' : `:${end}`}`;
}

function shiftedRef(ref: CellRef, sheetName: string, insertAt: number, count: number) {
  if (ref.sheet && ref.sheet !== sheetName) return ref;
  const shifted = {
    sheet: ref.sheet,
    start: { ...ref.start },
    end: { ...ref.end }
  };
  if (shifted.start.row >= insertAt) shifted.start.row += count;
  if (shifted.end.row >= insertAt) shifted.end.row += count;
  return shifted;
}

function expandItemsRange(
  workbook: ExcelJS.Workbook,
  worksheet: ExcelJS.Worksheet,
  itemsRef: CellRef,
  extraRows: number
) {
  if (extraRows <= 0) return itemsRef;
  const insertAt = itemsRef.end.row + 1;
  const merges = [...((worksheet.model as unknown as { merges?: string[] }).merges || [])];
  const shiftedMerges = merges.flatMap(range => {
    const parsed = splitRef(range);
    if (!parsed || parsed.end.row < insertAt) return [];
    worksheet.unMergeCells(range);
    return [formatRef(shiftedRef(parsed, worksheet.name, insertAt, extraRows)).replace(/^'[^']+'!/, '')];
  });

  worksheet.spliceRows(insertAt, 0, ...Array.from({ length: extraRows }, () => []));
  const sourceRow = worksheet.getRow(itemsRef.end.row);
  const columnCount = Math.max(worksheet.columnCount, columnNumber(itemsRef.end.col));
  for (let rowNumber = insertAt; rowNumber < insertAt + extraRows; rowNumber++) {
    const targetRow = worksheet.getRow(rowNumber);
    targetRow.height = sourceRow.height;
    for (let column = 1; column <= columnCount; column++) {
      targetRow.getCell(column).style = JSON.parse(JSON.stringify(sourceRow.getCell(column).style));
    }
  }
  shiftedMerges.forEach(range => worksheet.mergeCells(range));

  const names = definedNameModel(workbook);
  names.model = (names.model || []).map(entry => {
    const ranges = Array.isArray(entry.ranges) ? entry.ranges : entry.ranges ? [entry.ranges] : [];
    const adjusted = ranges.map(range => {
      const parsed = splitRef(range);
      if (!parsed || (parsed.sheet && parsed.sheet !== worksheet.name)) return range;
      if (entry.name === 'Items') {
        return formatRef({
          ...parsed,
          end: { ...parsed.end, row: parsed.end.row + extraRows }
        });
      }
      return formatRef(shiftedRef(parsed, worksheet.name, insertAt, extraRows));
    });
    return { ...entry, ranges: Array.isArray(entry.ranges) ? adjusted : adjusted[0] };
  });
  return { ...itemsRef, end: { ...itemsRef.end, row: itemsRef.end.row + extraRows } };
}

function splitRef(value: string): CellRef | null {
  const parts = value.replace(/\$/g, '').split('!');
  const [start, end] = parts.at(-1)!.split(':');
  const parse = (cell: string) => {
    const match = cell.match(/^([A-Z]+)(\d+)$/);
    return match ? { col: match[1], row: Number(match[2]) } : null;
  };
  const first = parse(start);
  const last = parse(end || start);
  if (!first || !last) return null;
  return {
    sheet: parts.length > 1 ? parts[0].replace(/^'|'$/g, '') : null,
    start: first,
    end: last
  };
}

function columnNumber(column: string) {
  return [...column].reduce((value, char) => value * 26 + char.charCodeAt(0) - 64, 0);
}

export async function exportOrderToExcel(
  order: Order,
  templateId: string,
  formatDate: (value: string) => string,
  pricingUnavailableMessage: string
) {
  if (order.total_amount === undefined || order.items?.some(line =>
    line.paid_amount === undefined || line.subtotal === undefined)) {
    throw new Error(pricingUnavailableMessage);
  }
  const template = await api.templateFile(templateId);
  const workbook = new ExcelJS.Workbook();
  await workbook.xlsx.load(await template.arrayBuffer());
  const worksheet = workbook.worksheets[0];
  if (!worksheet) throw new Error('Template has no worksheets');

  const cell = (name: string) => {
    const ref = namedRef(workbook, name);
    const parsed = ref ? splitRef(ref) : null;
    if (!parsed) return null;
    return (parsed.sheet ? workbook.getWorksheet(parsed.sheet) : worksheet)?.getCell(
      `${parsed.start.col}${parsed.start.row}`
    ) || null;
  };
  const assign = (name: string, value: ExcelJS.CellValue) => {
    const target = cell(name);
    if (target) target.value = value;
  };
  assign('OrderId', order.id);
  assign('Date', formatDate(order.order_date));
  assign('Cashier', order.created_by || '');

  const itemsValue = namedRef(workbook, 'Items');
  let itemsRef = itemsValue ? splitRef(itemsValue) : null;
  if (!itemsRef) throw new Error('Template is missing or has an invalid "Items" named range');
  const itemsSheet = itemsRef.sheet ? workbook.getWorksheet(itemsRef.sheet) : worksheet;
  if (!itemsSheet) throw new Error('Template items worksheet is missing');
  const originalCapacity = itemsRef.end.row - itemsRef.start.row + 1;
  const lines = order.items || [];
  itemsRef = expandItemsRange(workbook, itemsSheet, itemsRef, Math.max(0, lines.length - originalCapacity));
  const startColumn = columnNumber(itemsRef.start.col);
  const endColumn = columnNumber(itemsRef.end.col);
  const capacity = itemsRef.end.row - itemsRef.start.row + 1;
  for (let row = itemsRef.start.row; row <= itemsRef.end.row; row++) {
    for (let column = startColumn; column <= endColumn; column++) itemsSheet.getCell(row, column).value = null;
  }
  lines.forEach((line, index) => {
    const row = itemsRef.start.row + index;
    const subtotal = Number(line.subtotal);
    itemsSheet.getCell(row, startColumn).value = line.title || '';
    itemsSheet.getCell(row, startColumn + 1).value = line.barcode || '';
    itemsSheet.getCell(row, startColumn + 2).value = Number(line.quantity);
    itemsSheet.getCell(row, startColumn + 3).value = Number(line.paid_amount);
    itemsSheet.getCell(row, startColumn + 4).value = subtotal;
  });
  assign('TotalQuantity', Number(order.total_quantity));
  assign('TotalAmount', Number(order.total_amount));
  const buffer = await workbook.xlsx.writeBuffer();
  return {
    blob: new Blob([buffer as BlobPart], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    }),
    capacity,
    itemCount: lines.length
  };
}

export function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
