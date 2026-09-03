import express, { Request, Response } from 'express';
import { randomUUID } from 'crypto';
import { trace, metrics, SpanStatusCode } from '@opentelemetry/api';
import { logger } from './logger';

const PORT = Number(process.env.PORT ?? 3003);

const tracer = trace.getTracer('inventory-service');
const meter = metrics.getMeter('inventory-service');

const reservedTotal = meter.createCounter('inventory_items_reserved_total', {
  description: 'Items reserved, by SKU',
});
const stockLookupDuration = meter.createHistogram('inventory_stock_lookup_duration_ms', {
  description: 'Time spent looking up stock for an SKU',
  unit: 'ms',
});

interface ReserveRequest {
  sku?: string;
  qty?: number;
  orderId?: string;
}

const app = express();
app.use(express.json());

app.get('/health', (_req, res) => {
  res.json({ ok: true });
});

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

app.post('/reserve', async (req: Request<unknown, unknown, ReserveRequest>, res: Response) => {
  const { sku, qty, orderId } = req.body ?? {};

  if (!sku || typeof qty !== 'number' || qty <= 0) {
    logger.warn({ orderId, sku, qty }, 'invalid reservation request');
    return res.status(400).json({ error: 'sku and positive qty are required' });
  }

  await tracer.startActiveSpan('inventory.reserve', async (span) => {
    span.setAttribute('inventory.sku', sku);
    span.setAttribute('inventory.qty', qty);
    span.setAttribute('inventory.order_id', orderId ?? 'unknown');

    // child span: stock lookup
    const lookupStart = Date.now();
    await tracer.startActiveSpan('inventory.stock_lookup', async (child) => {
      child.setAttribute('inventory.sku', sku);
      // simulate variable DB latency — occasionally slow
      const delay = Math.random() < 0.15 ? 200 + Math.random() * 300 : 20 + Math.random() * 60;
      await sleep(delay);
      child.addEvent('stock fetched', { rows: 1 });
      child.end();
    });
    stockLookupDuration.record(Date.now() - lookupStart, { sku });

    // simulate out-of-stock for one SKU
    if (sku === 'OUT-OF-STOCK') {
      span.setStatus({ code: SpanStatusCode.ERROR, message: 'out of stock' });
      span.end();
      logger.warn({ orderId, sku, qty }, 'reservation refused: out of stock');
      return res.status(409).json({ error: 'out of stock', sku });
    }

    const reservationId = `rsv_${randomUUID().slice(0, 12)}`;
    span.setAttribute('inventory.reservation_id', reservationId);
    span.setStatus({ code: SpanStatusCode.OK });
    span.end();

    reservedTotal.add(qty, { sku });
    logger.info({ orderId, sku, qty, reservationId }, 'items reserved');
    res.status(201).json({ reservationId, sku, qty, orderId });
  });
});

app.listen(PORT, () => {
  logger.info({ port: PORT }, 'inventory-service listening');
});
